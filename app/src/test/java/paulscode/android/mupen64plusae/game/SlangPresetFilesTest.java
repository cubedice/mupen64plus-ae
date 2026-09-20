package paulscode.android.mupen64plusae.game;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class SlangPresetFilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private File stage(Map<String, String> files, String preset) throws IOException {
        File root = folder.newFolder();
        new SlangPresetFiles(root, path -> {
            String value = files.get(path);
            if (value == null) throw new FileNotFoundException(path);
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }).stage(preset);
        return root;
    }

    @Test public void copiesReferencedPresetsIncludesAndLookupTextures() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("custom.slangp", "#reference \"crt/base.slangp\"\nBRIGHTNESS = 0.8\nLUT = \"replacement.png\"");
        files.put("crt/base.slangp", "shaders=1\nshader0=\"shaders/main.slang\"\ntextures=\"LUT\"\nLUT=\"../lut.png\"");
        files.put("crt/shaders/main.slang", "#version 450\n#include \"../../common.inc\"");
        files.put("common.inc", "#pragma parameter BRIGHTNESS \"Brightness\" 1 0 2 0.1");
        files.put("lut.png", "original binary bytes");
        files.put("replacement.png", "replacement bytes");
        File root = stage(files, "custom.slangp");
        for (String path : files.keySet()) assertTrue(new File(root, path).isFile());
        assertEquals(files.get("lut.png"), read(root, "lut.png"));
        assertEquals(files.get("replacement.png"), read(root, "replacement.png"));
        assertEquals(files.get("common.inc"), read(root, "common.inc"));
        assertTrue(read(root, "custom.slangp").contains("#reference \"" + absolute(root, "crt/base.slangp") + "\""));
        assertTrue(read(root, "crt/base.slangp").contains("shader0 = \"" + absolute(root, "crt/shaders/main.slang") + "\""));
        assertTrue(read(root, "crt/shaders/main.slang").contains("#include \"" + absolute(root, "common.inc") + "\""));
    }

    private static String read(File root, String path) throws IOException {
        return new String(Files.readAllBytes(new File(root, path).toPath()), StandardCharsets.UTF_8);
    }

    private static String absolute(File root, String path) {
        return new File(root, path).getAbsolutePath().replace('\\', '/');
    }

    @Test public void nestedReferencesUseEachContainingFilesDirectory() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("presets/user/main.slangp", "#reference \"../../effects/base.slangp\"");
        files.put("effects/base.slangp", "#reference \"nested\\\\pass.slangp\"");
        files.put("effects/nested/pass.slangp", "shader0=\"shaders/main.slang\"\ntextures=\"LUT\"\nLUT=\"images/lut.png\"");
        files.put("effects/nested/shaders/main.slang", "#include \"common/local.inc\"");
        files.put("effects/nested/shaders/common/local.inc", "#include \"../math.inc\"");
        files.put("effects/nested/shaders/math.inc", "// local math");
        files.put("effects/nested/images/lut.png", "local LUT");
        File root = stage(files, "presets/user/main.slangp");
        assertTrue(read(root, "effects/base.slangp").contains(absolute(root, "effects/nested/pass.slangp")));
        assertTrue(read(root, "effects/nested/pass.slangp").contains(absolute(root, "effects/nested/images/lut.png")));
        assertTrue(read(root, "effects/nested/shaders/common/local.inc").contains(absolute(root, "effects/nested/shaders/math.inc")));
        assertFalse(new File(root, "shaders/main.slang").exists());
    }

    @Test public void permitsSharedIncludesWithoutTreatingThemAsCycles() throws Exception {
        Map<String, String> files = Map.of("a.slangp", "shaders=2\nshader0=a.slang\nshader1=b.slang",
                "a.slang", "#include \"shared.inc\"", "b.slang", "#include \"shared.inc\"", "shared.inc", "// common");
        assertTrue(new File(stage(files, "a.slangp"), "shared.inc").isFile());
    }

    @Test public void rejectsReferenceCycles() throws Exception {
        try { stage(Map.of("a.slangp", "#reference \"b.slangp\"", "b.slangp", "#reference \"a.slangp\""), "a.slangp"); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("Cyclic")); }
    }

    @Test public void importsUnquotedReferencesWithSpacesAndBom() throws Exception {
        Map<String, String> files = Map.of(
                "presets/user.slangp", "\ufeff#reference ../base presets/base.slangp // inherited preset\r\nGAIN=1.2",
                "base presets/base.slangp", "#reference nested/final.slangp\r\n",
                "base presets/nested/final.slangp", "shaders=1\nshader0=main.slang",
                "base presets/nested/main.slang", "\ufeff#version 450\n#include common.inc\n",
                "base presets/nested/common.inc", "// shared");
        File root = stage(files, "presets/user.slangp");
        assertEquals("#reference \"" + absolute(root, "base presets/base.slangp") + "\"\r\nGAIN=1.2",
                read(root, "presets/user.slangp"));
        assertTrue(read(root, "base presets/base.slangp").contains(absolute(root, "base presets/nested/final.slangp")));
        assertTrue(read(root, "base presets/nested/main.slang").startsWith("#version 450"));
        assertTrue(new File(root, "base presets/nested/common.inc").isFile());
    }

    @Test public void rejectsIncludeCycles() throws Exception {
        try { stage(Map.of("a.slangp", "shader0=a.slang", "a.slang", "#include \"a.slang\""), "a.slangp"); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("Cyclic")); }
    }

    @Test public void preventsEscapingGrantedFolder() throws Exception {
        for (String path : new String[]{"../../private", "/private", "C:/private", "..\\private"}) {
            try { SlangPresetFiles.normalize(path); fail(path); }
            catch (IOException expected) { }
        }
        assertEquals("shaders/common.inc", SlangPresetFiles.normalize("shaders/crt/../common.inc"));
    }

    @Test public void missingDependenciesReportThePath() throws Exception {
        try { stage(Map.of("a.slangp", "shader0=missing.slang"), "a.slangp"); fail(); }
        catch (FileNotFoundException e) { assertEquals("missing.slang", e.getMessage()); }
    }

    @Test public void cancellationStopsImport() throws Exception {
        Thread.currentThread().interrupt();
        try { stage(Map.of("a.slangp", "shaders=0"), "a.slangp"); fail(); }
        catch (InterruptedIOException expected) { }
        finally { Thread.interrupted(); }
    }
}
