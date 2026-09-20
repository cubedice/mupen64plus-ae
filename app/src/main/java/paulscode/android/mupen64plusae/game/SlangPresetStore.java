package paulscode.android.mupen64plusae.game;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.IOException;
import java.io.InputStream;


import java.util.List;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import android.content.SharedPreferences;

/** Reads a preset and its relative dependencies from a user-selected document tree. */
public final class SlangPresetStore {
    private static final String PREFS = "SlangPreset";
    private SlangPresetStore() {}

    public static final class Prepared implements java.io.Closeable {
        public final java.io.File file;
        private final java.io.File root;
        private Prepared(java.io.File root, java.io.File file) { this.root = root; this.file = file; }
        @Override public void close() { removeCache(root); }
    }

    private static void removeCache(java.io.File file) {
        java.io.File[] children = file.listFiles();
        if (children != null) for (java.io.File child : children) removeCache(child);
        file.delete();
    }

    public static Prepared prepare(Context context, Uri tree, String path) throws IOException {
        DocumentFile root = DocumentFile.fromTreeUri(context, tree);
        if (root == null) throw new IOException("Shader folder is unavailable");
        // Cache directory listings: findFile() repeatedly queries the provider for each path component.
        Map<String, DocumentFile> files = new java.util.HashMap<>();
        files.put("", root);
        return prepare(context, path, name -> {
            String parent = "";
            for (String part : name.split("/")) {
                String child = parent.isEmpty() ? part : parent + "/" + part;
                if (!files.containsKey(child)) {
                    DocumentFile directory = files.get(parent);
                    if (directory == null) throw new IOException("Missing shader folder: " + parent);
                    for (DocumentFile item : directory.listFiles())
                        files.put(parent.isEmpty() ? item.getName() : parent + "/" + item.getName(), item);
                }
                if (!files.containsKey(child)) throw new IOException("Missing shader file: " + name);
                parent = child;
            }
            return context.getContentResolver().openInputStream(files.get(name).getUri());
        });
    }

    public static Prepared prepareAsset(Context context, String path) throws IOException {
        return prepare(context, path, name -> context.getAssets().open("mupen64plus_data/shaders/" + name));
    }

    private static Prepared prepare(Context context, String path, SlangPresetFiles.Reader reader) throws IOException {
        java.io.File root = java.nio.file.Files.createTempDirectory(context.getCacheDir().toPath(), "slang-").toFile();
        try { return new Prepared(root, new SlangPresetFiles(root, reader).stage(path)); }
        catch (IOException | RuntimeException e) { removeCache(root); throw e; }
    }

    public static List<SlangParameter> inspect(Context context, Uri tree, String path) throws IOException {
        try (Prepared preset = prepare(context, tree, path)) { return VulkanSlang.parameters(preset.file.getAbsolutePath()); }
    }

    public static final class Selection {
        public final String id, path;
        public final Uri tree;
        private Selection(String id, String tree, String path) {
            this.id = id;
            this.tree = Uri.parse(tree);
            this.path = path;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Selection selection(Context context, int pass) {
        SharedPreferences preferences = prefs(context);
        String id = preferences.getString("slot." + pass, null);
        // Migrate the previous single global selection without losing access to its files.
        if (id == null && preferences.contains("tree") && preferences.contains("path")) {
            save(context, pass, Uri.parse(preferences.getString("tree", "")), preferences.getString("path", ""));
            id = preferences.getString("slot." + pass, null);
        }
        if (id == null) return null;
        String tree = preferences.getString(id + ".tree", null);
        String path = preferences.getString(id + ".path", null);
        return tree == null || path == null ? null : new Selection(id, tree, path);
    }

    public static void save(Context context, int pass, Uri tree, String path) {
        SharedPreferences preferences = prefs(context);
        String existing = preferences.getString("slot." + pass, null);
        if (existing != null && tree.toString().equals(preferences.getString(existing + ".tree", null))
                && path.equals(preferences.getString(existing + ".path", null))) return;
        String id = UUID.randomUUID().toString();
        preferences.edit().putString("slot." + pass, id)
                .putString(id + ".tree", tree.toString()).putString(id + ".path", path).apply();
    }

    public static Uri lastFolder(Context context) {
        String tree = prefs(context).getString("lastTree", prefs(context).getString("tree", null));
        return tree == null ? null : Uri.parse(tree);
    }

    public static synchronized void rememberFolder(Context context, Uri tree) {
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        if (!tree.equals(lastFolder(context))) {
            for (String key : preferences.getAll().keySet())
                if (key.startsWith("presetIndex.")) editor.remove(key);
            editor.putLong("presetIndexGeneration", presetIndexGeneration(context) + 1);
        }
        editor.putString("lastTree", tree.toString()).apply();
    }

    /** Persist the folder listing across picker openings, activity recreation and app restarts. */
    public static synchronized List<String> cachedPresetPaths(Context context, Uri tree) {
        String json = prefs(context).getString("presetIndex." + tree, null);
        if (json == null) return null;
        try {
            org.json.JSONArray array = new org.json.JSONArray(json);
            List<String> paths = new java.util.ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) paths.add(array.getString(i));
            return paths;
        } catch (org.json.JSONException e) { return null; }
    }

    public static synchronized long presetIndexGeneration(Context context) {
        return prefs(context).getLong("presetIndexGeneration", 0);
    }

    public static synchronized void cachePresetPaths(Context context, Uri tree, List<String> paths, long generation) {
        // A scan from the previous folder must not repopulate the cache after a folder change.
        if (generation != presetIndexGeneration(context)) return;
        prefs(context).edit().putString("presetIndex." + tree, new org.json.JSONArray(paths).toString()).apply();
    }

    public static void removePass(Context context, int removed, int count) {
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = removed; i < count - 1; i++) {
            String next = preferences.getString("slot." + (i + 1), null);
            if (next == null) editor.remove("slot." + i);
            else editor.putString("slot." + i, next);
        }
        editor.remove("slot." + (count - 1)).apply();
    }

    public static Map<String, String> overrides(Context context, Selection selection) {
        Map<String, String> result = new LinkedHashMap<>();
        String prefix = selection.id + ".parameter.";
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet())
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof String)
                result.put(entry.getKey().substring(prefix.length()), (String) entry.getValue());
        return result;
    }

    public static void saveParameters(Context context, Selection selection, Map<String, String> values) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String name : overrides(context, selection).keySet()) editor.remove(selection.id + ".parameter." + name);
        for (Map.Entry<String, String> value : values.entrySet()) editor.putString(selection.id + ".parameter." + value.getKey(), value.getValue());
        editor.apply();
    }

}
