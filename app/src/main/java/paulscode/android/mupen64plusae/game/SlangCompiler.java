/*
 * Adapted from Shaderlay's ExternalShaderManager and ShaderManager.
 * Copyright (c) 2024 Shaderlay Contributors. MIT licensed; see assets/licenses/Shaderlay.txt.
 */
package paulscode.android.mupen64plusae.game;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shaderlay's stage conversion adapted to the GLES 2 post-processing renderer.
 * This is a source translator, not a general SPIR-V compiler. Unsupported features fail explicitly.
 */
public final class SlangCompiler {
    public interface SourceReader { String read(String path) throws IOException; }

    public static final class Pass {
        public final String code;
        public final boolean linear;
        public final String wrap;
        public final String scaleTypeX, scaleTypeY;
        public final float scaleX, scaleY;
        public final List<SlangParameter> parameters;

        private Pass(String code, Map<String, String> preset, int index, List<SlangParameter> parameters) throws IOException {
            this.code = code;
            this.parameters = java.util.Collections.unmodifiableList(parameters);
            linear = bool(preset.getOrDefault("filter_linear" + index, "false"));
            wrap = preset.getOrDefault("wrap_mode" + index, "clamp_to_edge");
            if (!wrap.equals("clamp_to_edge"))
                throw new IOException("GLES 2 presets currently require clamp_to_edge wrapping");
            String type = preset.getOrDefault("scale_type" + index, "source");
            scaleTypeX = preset.getOrDefault("scale_type_x" + index, type);
            scaleTypeY = preset.getOrDefault("scale_type_y" + index, type);
            String scale = preset.getOrDefault("scale" + index, "1.0");
            scaleX = positive(preset.getOrDefault("scale_x" + index, scale));
            scaleY = positive(preset.getOrDefault("scale_y" + index, scale));
            for (String axis : new String[] {scaleTypeX, scaleTypeY})
                if (!axis.equals("source") && !axis.equals("viewport") && !axis.equals("absolute"))
                    throw new IOException("Unsupported scale type: " + axis);
        }

        public int width(int source, int viewport) { return dimension(scaleTypeX, scaleX, source, viewport); }
        public int height(int source, int viewport) { return dimension(scaleTypeY, scaleY, source, viewport); }
        private static int dimension(String type, float scale, int source, int viewport) {
            return Math.max(1, Math.round(scale * (type.equals("absolute") ? 1 : type.equals("viewport") ? viewport : source)));
        }
    }

    private static final Pattern STAGE = Pattern.compile("(?m)^\\s*#pragma\\s+stage\\s+(vertex|fragment)\\s*$");
    private static final Pattern PARAMETER = SlangParameter.PRAGMA;
    // Android uses ICU regex, which rejects an unescaped closing brace (desktop Java accepts it).
    private static final Pattern BLOCK = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+\\w+\\s*\\{([^}]*)\\}\\s*(\\w*)\\s*;", Pattern.DOTALL);

    private SlangCompiler() {}

    public static List<Pass> load(String path, SourceReader reader) throws IOException {
        return load(path, reader, java.util.Collections.emptyMap());
    }

    public static List<Pass> load(String path, SourceReader reader, Map<String, String> overrides) throws IOException {
        path = normalize(path);
        Map<String, String> preset = new LinkedHashMap<>();
        for (String raw : reader.read(path).replace("\ufeff", "").split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#") && !line.startsWith("#reference")) continue;
            if (line.startsWith("#reference")) throw new IOException("Preset inheritance (#reference) is not supported");
            Matcher entry = Pattern.compile("^([\\w]+)\\s*=\\s*(?:\"([^\"]*)\"|([^#]*?))\\s*(?:#.*)?$").matcher(line);
            if (!entry.matches()) throw new IOException("Invalid preset line: " + line);
            preset.put(entry.group(1), entry.group(2) != null ? entry.group(2) : entry.group(3).trim());
        }
        int count;
        try { count = Integer.parseInt(preset.getOrDefault("shaders", "0")); }
        catch (NumberFormatException e) { throw new IOException("Invalid shader count", e); }
        if (count < 1 || count > 32) throw new IOException("Preset must have between 1 and 32 passes");
        if (preset.containsKey("textures") && !preset.get("textures").isEmpty())
            throw new IOException("LUT textures are not supported by this GLES 2 translator");
        List<Pass> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            for (String feature : new String[] {"float_framebuffer", "srgb_framebuffer", "mipmap_input"})
                if (bool(preset.getOrDefault(feature + i, "false"))) throw new IOException("Unsupported preset feature: " + feature + i);
            if (preset.containsKey("feedback_pass")) throw new IOException("Feedback passes are not supported");
            if (preset.containsKey("frame_count_mod" + i)) throw new IOException("Frame count modulo is not supported");
            String shader = preset.get("shader" + i);
            if (shader == null || shader.isEmpty()) throw new IOException("Missing shader" + i);
            String source = expand(resolve(path, shader), reader, new HashSet<>());
            List<SlangParameter> parameters = SlangParameter.parse(source, preset, overrides);
            Map<String, String> effective = new HashMap<>(preset);
            // User values may only override declared controls, never preset paths or pass settings.
            for (SlangParameter parameter : parameters) effective.put(parameter.id, Float.toString(parameter.value));
            result.add(new Pass(translate(source, effective, i), preset, i, parameters));
        }
        return result;
    }

    private static boolean bool(String value) throws IOException {
        if (value.equalsIgnoreCase("true") || value.equals("1")) return true;
        if (value.equalsIgnoreCase("false") || value.equals("0")) return false;
        throw new IOException("Invalid boolean: " + value);
    }

    private static float positive(String value) throws IOException {
        float number = number(value);
        if (number <= 0 || number > 16384) throw new IOException("Invalid scale: " + value);
        return number;
    }

    private static float number(String value) throws IOException {
        try {
            float result = Float.parseFloat(value);
            if (!Float.isFinite(result)) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException e) { throw new IOException("Invalid number: " + value, e); }
    }

    public static String normalize(String path) throws IOException {
        path = path.replace('\\', '/');
        if (path.startsWith("/") || path.contains(":")) throw new IOException("Preset paths must be relative");
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IOException("Path escapes shader folder: " + path);
                parts.remove(parts.size() - 1);
            } else parts.add(part);
        }
        if (parts.isEmpty()) throw new IOException("Empty shader path");
        return String.join("/", parts);
    }

    private static String resolve(String base, String child) throws IOException {
        if (child.replace('\\', '/').startsWith("/") || child.contains(":")) throw new IOException("Absolute shader path: " + child);
        int slash = base.lastIndexOf('/');
        return normalize((slash < 0 ? "" : base.substring(0, slash + 1)) + child);
    }

    private static String expand(String path, SourceReader reader, Set<String> stack) throws IOException {
        if (stack.size() >= 32 || !stack.add(path)) throw new IOException("Recursive shader include: " + path);
        StringBuilder result = new StringBuilder();
        String source = reader.read(path).replace("\ufeff", "");
        // Keep newlines when removing comments, so preprocessor directives stay on their own lines.
        Matcher comments = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL).matcher(source);
        StringBuffer clean = new StringBuffer();
        while (comments.find()) comments.appendReplacement(clean, Matcher.quoteReplacement(comments.group().replaceAll("[^\\n]", " ")));
        comments.appendTail(clean);
        for (String line : clean.toString().split("\\r?\\n")) {
            Matcher include = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*$").matcher(line);
            if (include.matches()) result.append(expand(resolve(path, include.group(1)), reader, stack));
            else result.append(line).append('\n');
        }
        stack.remove(path);
        if (result.length() > 2_000_000) throw new IOException("Shader source exceeds 2 MB");
        return result.toString();
    }

    static String translate(String source, Map<String, String> overrides, int passIndex) throws IOException {
        source = source.replace("\r", "");
        Map<String, String> parameters = new HashMap<>();
        for (SlangParameter parameter : SlangParameter.parse(source, overrides, java.util.Collections.emptyMap()))
            parameters.put(parameter.id, Float.toString(parameter.value));
        source = PARAMETER.matcher(source).replaceAll("").replaceAll("(?m)^\\s*#version[^\\n]*", "");
        Matcher stages = STAGE.matcher(source);
        Map<String, String> sections = new HashMap<>();
        String common = null, current = null;
        int start = 0;
        while (stages.find()) {
            if (current == null) common = source.substring(0, stages.start());
            else sections.put(current, source.substring(start, stages.start()));
            current = stages.group(1);
            if (sections.containsKey(current)) throw new IOException("Duplicate shader stage");
            start = stages.end();
        }
        if (current != null) sections.put(current, source.substring(start));
        if (!sections.containsKey("vertex") || !sections.containsKey("fragment")) throw new IOException("Both vertex and fragment stages are required");
        return "#version 100\n#if defined(VERTEX)\n" + stage(common + sections.get("vertex"), true, parameters, passIndex)
                + "\n#elif defined(FRAGMENT)\n" + stage(common + sections.get("fragment"), false, parameters, passIndex) + "\n#endif\n";
    }

    private static String stage(String source, boolean vertex, Map<String, String> parameters, int passIndex) throws IOException {
        StringBuilder definitions = new StringBuilder();
        Map<String, String> replacements = new LinkedHashMap<>();
        Matcher blocks = BLOCK.matcher(source);
        while (blocks.find()) {
            for (String declaration : blocks.group(1).split(";")) {
                String[] member = declaration.trim().split("\\s+");
                if (member.length == 1 && member[0].isEmpty()) continue;
                if (member.length != 2) throw new IOException("Unsupported uniform block member: " + declaration);
                String name = member[1], value;
                switch (name) {
                    case "MVP": value = "mat4(1.0)"; break;
                    case "SourceSize": value = "SlangSourceSize"; break;
                    case "OriginalSize": value = "SlangOriginalSize"; break;
                    case "OutputSize": value = "SlangOutputSize"; break;
                    case "FrameCount": value = "FrameCount"; break;
                    default:
                        value = parameters.get(name);
                        if (value == null) throw new IOException("Unsupported uniform: " + name);
                }
                String instance = blocks.group(2);
                if (instance.isEmpty()) definitions.append("#define ").append(name).append(' ').append(value).append('\n');
                else replacements.put(instance + "." + name, value);
            }
        }
        source = blocks.replaceAll("");
        for (Map.Entry<String, String> replacement : replacements.entrySet())
            source = source.replaceAll("\\b" + Pattern.quote(replacement.getKey()).replace(".", "\\E\\s*\\.\\s*\\Q") + "\\b", Matcher.quoteReplacement(replacement.getValue()));
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            String name = Pattern.quote(parameter.getKey());
            source = source.replaceAll("\\buniform\\s+float\\s+" + name + "\\s*;",
                    Matcher.quoteReplacement("const float " + parameter.getKey() + " = " + parameter.getValue() + ";"));
            source = source.replaceAll("(?m)^([\\t ]*#define[\\t ]+" + name + ")[\\t ]+[^\\n]*$", "$1 " + parameter.getValue());
        }
        source = source.replaceAll("layout\\s*\\([^)]*\\)\\s*", "");
        if (vertex) {
            source = source.replaceAll("\\bin\\s+(vec[234])\\s+(Position|VertexCoord|TexCoord)\\s*;", "attribute $1 $2;")
                    .replaceAll("\\bout\\s+", "varying ");
            source = token(source, "Position", "VertexCoord");
        } else {
            Matcher output = Pattern.compile("\\bout\\s+vec4\\s+(\\w+)\\s*;").matcher(source);
            if (!output.find()) throw new IOException("Fragment shader needs one vec4 output");
            String name = output.group(1);
            source = output.replaceFirst("");
            source = token(source, name, "gl_FragColor").replaceAll("\\bin\\s+", "varying ");
        }
        Matcher samplers = Pattern.compile("uniform\\s+sampler2D\\s+(\\w+)\\s*;").matcher(source);
        boolean hasOriginal = false;
        while (samplers.find()) {
            if (vertex) throw new IOException("Vertex texture sampling is not supported");
            String name = samplers.group(1);
            if (!name.equals("Source") && !name.equals("Original")) throw new IOException("Unsupported sampler: " + name);
            hasOriginal |= name.equals("Original");
        }
        if (hasOriginal) {
            source = source.replaceAll("\\btexture\\s*\\(\\s*Original\\s*,", "SlangSampleOriginal(");
            // The first group's original image is an external texture, whose Y orientation differs
            // from the intermediate FBOs. Shader selects the flip only for later passes in that group.
            source = source.replaceFirst("uniform\\s+sampler2D\\s+Original\\s*;",
                    Matcher.quoteReplacement("uniform sampler2D Original;\nvec4 SlangSampleOriginal(vec2 uv) {\n"
                            + "#ifdef SLANG_FLIP_ORIGINAL\nuv.y = 1.0 - uv.y;\n#endif\nreturn texture2D(Original, uv);\n}\n"));
        }
        source = token(source, "Source", "Texture");
        source = token(source, "Original", "PassPrev" + (passIndex + 1) + "Texture");
        source = source.replaceAll("\\btexture\\s*\\(", "texture2D(");
        for (int n = 2; n <= 4; n++) source = token(source, "float" + n, "vec" + n);
        source = source.replaceAll("\\blerp\\s*\\(", "mix(").replaceAll("\\bfrac\\s*\\(", "fract(");
        if (Pattern.compile("\\b(uint|uvec[234]|sampler3D|sampler2DArray|texelFetch|textureSize|textureLod|gl_VertexID)\\b|#include|#extension|\\bout\\s+|\\bin\\s+").matcher(source).find())
            throw new IOException("Shader requires features beyond the GLES 2 translator");
        if (!Pattern.compile("\\bvoid\\s+main\\s*\\(").matcher(source).find()) throw new IOException("Missing shader main function");
        return "precision highp float;\nprecision highp int;\nuniform vec4 SlangSourceSize;\nuniform vec4 SlangOriginalSize;\nuniform vec4 SlangOutputSize;\nuniform int FrameCount;\n"
                + definitions + source;
    }

    private static String token(String source, String from, String to) {
        return source.replaceAll("\\b" + Pattern.quote(from) + "\\b", Matcher.quoteReplacement(to));
    }
}
