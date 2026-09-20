package paulscode.android.mupen64plusae.game;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class SlangCompilerTest {
    private static final Path ASSETS = Path.of("src/main/assets/mupen64plus_data/shaders");

    private String stock() throws IOException {
        return read(ASSETS.resolve("slang/passthrough.slang"));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private SlangCompiler.SourceReader reader(Map<String, String> files) {
        return path -> {
            if (!files.containsKey(path)) throw new IOException("Missing " + path);
            return files.get(path);
        };
    }

    @Test public void bundledPresetsTranslateActualTextureAndUniforms() throws Exception {
        for (String name : new String[] {"passthrough", "crt", "scanlines", "lcd-grid"}) {
            List<SlangCompiler.Pass> passes = SlangCompiler.load("slang/" + name + ".slangp",
                    path -> read(ASSETS.resolve(path)));
            assertEquals(1, passes.size());
            String code = passes.get(0).code;
            assertTrue(code.contains("texture2D(Texture,"));
            assertTrue(code.contains("gl_Position = mat4(1.0) * VertexCoord"));
            assertFalse(code.contains("layout("));
            assertFalse(code.contains("params."));
            if (!name.equals("passthrough")) assertTrue(code.contains("SlangOutputSize."));
        }
    }

    @Test public void multiPassIncludesAndIndependentAxisScaling() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("presets/demo.slangp", "shaders = 2\nshader0 = \"../shaders/stock.slang\" # note\n"
                + "shader1 = ../shaders/stock.slang\nfilter_linear0 = true\nscale_type_x0 = absolute\nscale_x0 = 640\n"
                + "scale_type_y0 = viewport\nscale_y0 = 0.5\nscale1 = 2\n");
        files.put("shaders/stock.slang", "#include \"../include/common.inc\"\n" + stock());
        files.put("include/common.inc", "const float sharedValue = 0.5;\n");
        List<SlangCompiler.Pass> passes = SlangCompiler.load("presets/demo.slangp", reader(files));
        assertEquals(2, passes.size());
        assertEquals(640, passes.get(0).width(320, 1920));
        assertEquals(540, passes.get(0).height(240, 1080));
        assertEquals(1280, passes.get(1).width(640, 1920));
        assertTrue(passes.get(0).linear);
        assertTrue(passes.get(0).code.contains("const float sharedValue"));
    }

    @Test public void originalSamplerUsesPresetInputAtEachPass() throws Exception {
        String source = stock().replace("sampler2D Source", "sampler2D Original").replace("texture(Source,", "texture(Original,");
        String code = SlangCompiler.translate(source, Map.of(), 2);
        assertTrue(code.contains("sampler2D PassPrev3Texture"));
        assertTrue(code.contains("texture2D(PassPrev3Texture,"));
        assertTrue(code.contains("SlangSampleOriginal( vTexCoord)"));
        assertTrue(code.contains("#ifdef SLANG_FLIP_ORIGINAL"));
    }

    @Test public void parameterOverridesAndSharedBlocksSurviveStageExtraction() throws Exception {
        String source = "#pragma parameter GAIN \"Gain\" 1.0 0.0 2.0 0.1\n" + stock()
                .replace("uint FrameCount;", "uint FrameCount; float GAIN;")
                .replace("texture(Source, vTexCoord)", "texture(Source, vTexCoord) * params.GAIN");
        String code = SlangCompiler.translate(source, Map.of("GAIN", "1.5"), 0);
        assertTrue(code.contains("texture2D(Texture, vTexCoord) * 1.5"));
        assertFalse(code.contains("params.GAIN"));
        assertFalse(code.contains("#pragma parameter"));
    }

    @Test public void includeCycleFailsWithoutRecursingForever() {
        Map<String, String> files = Map.of("p.slangp", "shaders=1\nshader0=a.slang", "a.slang", "#include \"b.slang\"", "b.slang", "#include \"a.slang\"");
        assertThrows(IOException.class, () -> SlangCompiler.load("p.slangp", reader(files)));
    }

    private String adjustableShader() throws IOException {
        return "#pragma parameter GAIN \"Brightness\" 1.0 0.0 2.0 0.1\n" + stock()
                .replace("uint FrameCount;", "uint FrameCount; float GAIN;")
                .replace("texture(Source, vTexCoord)", "texture(Source, vTexCoord) * params.GAIN");
    }

    @Test public void editedParametersOverridePresetDefaultsInEveryPass() throws Exception {
        Map<String, String> files = Map.of("p.slangp", "shaders=2\nshader0=a.slang\nshader1=a.slang\nGAIN=1.2", "a.slang", adjustableShader());
        List<SlangCompiler.Pass> passes = SlangCompiler.load("p.slangp", reader(files), Map.of("GAIN", "1.7", "shader0", "ignored.slang"));
        for (SlangCompiler.Pass pass : passes) {
            SlangParameter parameter = pass.parameters.get(0);
            assertEquals("Brightness", parameter.label);
            assertEquals(1.2f, parameter.defaultValue, 0);
            assertEquals(1.7f, parameter.value, 0);
            assertTrue(pass.code.contains("texture2D(Texture, vTexCoord) * 1.7"));
        }
        // Clearing saved values resets to the preset, not to the shader's 1.0 default.
        assertEquals(1.2f, SlangCompiler.load("p.slangp", reader(files)).get(0).parameters.get(0).value, 0);
    }

    @Test public void parameterEditorValidatesRangeAndSnapsToStep() throws Exception {
        SlangParameter parameter = SlangParameter.parse("#pragma parameter X \"Offset\" 0 -1 1 0.25\r\n", Map.of(), Map.of()).get(0);
        assertEquals(-0.5f, parameter.editedValue("-0.61"), 0);
        assertEquals(1f, parameter.editedValue("1"), 0);
        for (String invalid : new String[] {"NaN", "Infinity", "", "1.01", "-1.01", "hello"})
            assertThrows(IOException.class, () -> parameter.editedValue(invalid));
    }

    @Test public void optionalParameterStepAndInvalidDeclarations() throws Exception {
        assertEquals(0.2f, SlangParameter.parse("#pragma parameter X \"X\" 1 0 2\n", Map.of(), Map.of()).get(0).step, 0);
        assertEquals(0f, SlangParameter.parse("#pragma parameter SECTION \"Section\" 0 0 0 0\n", Map.of(), Map.of()).get(0).editedValue("0"), 0);
        for (String declaration : new String[] {"1 0 2 0", "1 2 0 0.1", "NaN 0 2 0.1", "3 0 2 0.1", "1 0"})
            assertThrows(IOException.class, () -> SlangParameter.parse("#pragma parameter X \"X\" " + declaration, Map.of(), Map.of()));
    }

    @Test public void directUniformAndMacroParametersReceiveEditedValues() throws Exception {
        for (String declaration : new String[] {"uniform float GAIN;", "#define GAIN 1.0"}) {
            String source = "#pragma parameter GAIN \"Gain\" 1 0 2 0.1\n" + stock()
                    .replace("#pragma stage fragment", "#pragma stage fragment\n" + declaration)
                    .replace("texture(Source, vTexCoord)", "texture(Source, vTexCoord) * GAIN");
            String code = SlangCompiler.translate(source, Map.of("GAIN", "1.5"), 0);
            assertTrue(code.contains(declaration.startsWith("uniform") ? "const float GAIN = 1.5;" : "#define GAIN 1.5"));
        }
    }

    @Test public void pathsStayInsideSelectedTree() throws Exception {
        assertEquals("shaders/a.slang", SlangCompiler.normalize("presets/../shaders\\a.slang"));
        for (String path : new String[] {"../a.slang", "/a.slang", "C:\\a.slang", "a/../../b"})
            assertThrows(IOException.class, () -> SlangCompiler.normalize(path));
    }

    @Test public void malformedPresetsAndUnsupportedFeaturesFailAtomically() throws Exception {
        for (String line : new String[] {"shaders=0", "shaders=33", "shaders=x", "scale0=NaN", "scale0=-1",
                "scale_type0=unknown", "float_framebuffer0=true", "srgb_framebuffer0=true", "mipmap_input0=true",
                "textures=lut", "feedback_pass=0", "frame_count_mod0=60", "wrap_mode0=repeat", "filter_linear0=maybe",
                "shader0=missing.slang", "#reference \"base.slangp\""}) {
            Map<String, String> files = Map.of("p.slangp", "shaders=1\nshader0=a.slang\n" + line, "a.slang", stock());
            assertThrows(line, IOException.class, () -> SlangCompiler.load("p.slangp", reader(files)));
        }
    }

    @Test public void missingStagesAndUnsupportedSamplersFail() throws Exception {
        assertThrows(IOException.class, () -> SlangCompiler.translate("void main() {}", Map.of(), 0));
        String source = stock().replace("sampler2D Source", "sampler2D History");
        assertThrows(IOException.class, () -> SlangCompiler.translate(source, Map.of(), 0));
    }

    @Test public void actualGlesStagesCanBeValidatedWithGlslang() throws Exception {
        // Optional integration check: -Dslang.validator=/path/to/glslangValidator
        String validator = System.getProperty("slang.validator");
        org.junit.Assume.assumeNotNull(validator);
        for (String name : new String[] {"passthrough", "crt", "scanlines", "lcd-grid", "original", "adjusted"}) {
            String code = name.equals("adjusted") ? SlangCompiler.translate(adjustableShader(), Map.of("GAIN", "1.7"), 0) : name.equals("original")
                    ? SlangCompiler.translate(stock().replace("sampler2D Source", "sampler2D Original")
                        .replace("texture(Source,", "texture(Original,"), Map.of(), 2)
                    : SlangCompiler.load("slang/" + name + ".slangp", path -> read(ASSETS.resolve(path))).get(0).code;
            Path folder = Files.createTempDirectory("slang-validator-");
            for (boolean external : new boolean[] {false, true}) {
                Path vertex = folder.resolve("shader.vert"), fragment = folder.resolve("shader.frag");
                Files.write(vertex, code.replace("#version 100", "#version 100\n#define VERTEX 1").getBytes(StandardCharsets.UTF_8));
                String fragmentCode = code.replace("#version 100", "#version 100\n#define FRAGMENT 1\n#extension GL_OES_EGL_image_external : require");
                if (external) fragmentCode = fragmentCode.replace("sampler2D Texture", "samplerExternalOES Texture")
                        .replace("sampler2D PassPrev3Texture", "samplerExternalOES PassPrev3Texture")
                        .replace("#define FRAGMENT 1", "#define FRAGMENT 1\n#define SLANG_FLIP_ORIGINAL 1");
                Files.write(fragment, fragmentCode.getBytes(StandardCharsets.UTF_8));
                Process process = new ProcessBuilder(validator, "-l", vertex.toString(), fragment.toString()).redirectErrorStream(true).start();
                String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(name + " external=" + external + "\n" + log, 0, process.waitFor());
                Files.delete(vertex);
                Files.delete(fragment);
            }
            Files.delete(folder);
        }
    }
}
