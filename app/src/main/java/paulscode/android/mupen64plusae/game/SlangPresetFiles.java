package paulscode.android.mupen64plusae.game;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Materializes a preset's dependency graph, preserving paths for the native preset parser. */
public final class SlangPresetFiles {
    public interface Reader { InputStream open(String path) throws IOException; }
    private static final Pattern DIRECTIVE = Pattern.compile(
            "(?m)^[\\t ]*#(include|reference)[\\t ]+(?:\"([^\"\\r\\n]+)\"|([^\\r\\n]+))");
    private static final Pattern ENTRY = Pattern.compile("(?m)^\\s*([\\w]+)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s#]+))");
    private final Reader reader;
    private final File root;
    private final Set<String> copied = new HashSet<>();
    private final Set<String> active = new HashSet<>();
    private final Map<String, String> resolvedText = new LinkedHashMap<>();
    private long bytes;

    public SlangPresetFiles(File root, Reader reader) { this.root = root; this.reader = reader; }

    public File stage(String path) throws IOException {
        String normalized = normalize(path);
        preset(normalized, 0);
        // Keep originals intact while walking shared dependencies. Only then write the
        // explicitly resolved paths consumed by the native parser.
        for (Map.Entry<String, String> entry : resolvedText.entrySet())
            java.nio.file.Files.write(new File(root, entry.getKey()).toPath(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        return new File(root, normalized);
    }

    static String normalize(String path) throws IOException {
        path = path.replace('\\', '/');
        if (path.startsWith("/") || path.contains(":")) throw new IOException("Shader paths must be relative to the selected folder");
        ArrayDeque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IOException("Select a parent folder containing all shader dependencies");
                parts.removeLast();
            } else parts.add(part);
        }
        if (parts.isEmpty()) throw new IOException("Empty shader path");
        return String.join("/", parts);
    }

    private String relative(String owner, String child) throws IOException {
        child = child.replace('\\', '/');
        if (child.startsWith("/") || child.contains(":")) throw new IOException("Absolute shader dependency: " + child);
        return normalize(owner.substring(0, owner.lastIndexOf('/') + 1) + child);
    }

    private String nativePath(String owner, String child) throws IOException {
        return new File(root, relative(owner, child)).getAbsolutePath().replace('\\', '/');
    }

    private static String directivePath(Matcher matcher) {
        if (matcher.group(2) != null) return matcher.group(2);
        // Unquoted references are valid Slang preset syntax. A trailing comment is
        // not part of the filename; spaces within an unquoted path are preserved.
        return matcher.group(3).split("[\\t ]+(?://|#)", 2)[0].trim();
    }

    private String readText(String path) throws IOException {
        String text = new String(copy(path), StandardCharsets.UTF_8);
        return text.startsWith("\ufeff") ? text.substring(1) : text;
    }

    private String resolveDirectives(String owner, String text) throws IOException {
        Matcher matcher = DIRECTIVE.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    "#" + matcher.group(1) + " \"" + nativePath(owner, directivePath(matcher)) + "\""));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private byte[] copy(String path) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Preset loading cancelled");
        File target = new File(root, path);
        if (copied.contains(path)) return java.nio.file.Files.readAllBytes(target.toPath());
        if (copied.size() >= 2048) throw new IOException("Too many preset dependencies");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (InputStream stream = reader.open(path)) {
            if (stream == null) throw new IOException("Missing shader file: " + path);
            byte[] buffer = new byte[16384];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Preset loading cancelled");
                bytes += length;
                if (bytes > 256L * 1024 * 1024 || data.size() + length > 64 * 1024 * 1024)
                    throw new IOException("Preset dependencies exceed the import size limit");
                data.write(buffer, 0, length);
            }
        }
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create shader cache");
        try (OutputStream output = new FileOutputStream(target)) { data.writeTo(output); }
        copied.add(path);
        return data.toByteArray();
    }

    private void source(String path, int depth) throws IOException {
        if (depth > 64 || !active.add(path)) throw new IOException("Cyclic or deeply nested shader include: " + path);
        String text = readText(path);
        Matcher include = DIRECTIVE.matcher(text);
        while (include.find()) source(relative(path, directivePath(include)), depth + 1);
        resolvedText.put(path, resolveDirectives(path, text));
        active.remove(path);
    }

    private Map<String, String> preset(String path, int depth) throws IOException {
        if (depth > 64 || !active.add(path)) throw new IOException("Cyclic or deeply nested preset reference: " + path);
        String text = readText(path);
        Map<String, String> entries = new LinkedHashMap<>();
        Matcher refs = DIRECTIVE.matcher(text);
        while (refs.find()) entries.putAll(preset(relative(path, directivePath(refs)), depth + 1));
        Matcher entry = ENTRY.matcher(text);
        Map<String, String> local = new LinkedHashMap<>();
        while (entry.find()) local.put(entry.group(1), entry.group(2) == null ? entry.group(3) : entry.group(2));
        entries.putAll(local);
        for (Map.Entry<String, String> value : local.entrySet()) {
            if (value.getKey().matches("shader[0-9]+")) source(relative(path, value.getValue()), 0);
        }
        for (String texture : entries.getOrDefault("textures", "").split(";")) {
            String value = local.get(texture.trim());
            if (value != null) copy(relative(path, value));
        }
        Set<String> textures = new HashSet<>();
        for (String name : entries.getOrDefault("textures", "").split(";")) textures.add(name.trim());
        Matcher fields = ENTRY.matcher(resolveDirectives(path, text));
        StringBuffer resolved = new StringBuffer();
        while (fields.find()) {
            String key = fields.group(1);
            if (key.matches("shader[0-9]+") || textures.contains(key)) {
                String value = fields.group(2) == null ? fields.group(3) : fields.group(2);
                String leading = fields.group().substring(0, fields.start(1) - fields.start());
                fields.appendReplacement(resolved, Matcher.quoteReplacement(leading + key + " = \"" + nativePath(path, value) + "\""));
            } else fields.appendReplacement(resolved, Matcher.quoteReplacement(fields.group()));
        }
        fields.appendTail(resolved);
        resolvedText.put(path, resolved.toString());
        active.remove(path);
        return entries;
    }
}
