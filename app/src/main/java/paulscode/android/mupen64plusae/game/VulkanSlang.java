package paulscode.android.mupen64plusae.game;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Native Slang preset engine. Loading is explicit so a failed library load cannot poison this class. */
public final class VulkanSlang {
    private static boolean loaded;
    private VulkanSlang() {}

    public static synchronized void ensureLoaded() throws IOException {
        if (loaded) return;
        try {
            System.loadLibrary("slangvulkan");
            loaded = true;
        } catch (LinkageError e) {
            throw new IOException("Unable to load the Vulkan shader runtime: " + e.getMessage(), e);
        }
    }

    public static List<SlangParameter> parameters(String path) throws IOException {
        ensureLoaded();
        try { return Arrays.asList(inspect(path)); }
        catch (IllegalStateException e) { throw new IOException(e.getMessage(), e); }
    }

    private static native SlangParameter[] inspect(String path);
    static native long create(String path, int inputWidth, int inputHeight, int outputWidth, int outputHeight,
                              String[] names, float[] values);
    static native int inputFramebuffer(long handle);
    static native int outputTexture(long handle);
    static native void render(long handle);
    static native void destroy(long handle);
}
