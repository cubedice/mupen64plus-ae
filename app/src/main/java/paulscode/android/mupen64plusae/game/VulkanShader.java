package paulscode.android.mupen64plusae.game;

import android.content.Context;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/** GL copies the plugin output into shared memory; every Slang preset pass executes in Vulkan. */
final class VulkanShader extends Shader {
    private final Context context;
    private final ShaderLoader selection;
    private final int slot;
    private long handle;
    private int inputWidth, inputHeight, outputWidth, outputHeight;

    VulkanShader(Context context, ShaderLoader selection, int slot, boolean first) {
        super(ShaderLoader.DEFAULT.getShaderCode().get(0), first, false, first, 0);
        this.context = context;
        this.selection = selection;
        this.slot = slot;
    }

    @Override public void setDimensions(int iw, int ih, int tw, int th, int ow, int oh) {
        inputWidth = tw;
        inputHeight = th;
        outputWidth = ow;
        outputHeight = oh;
        super.setDimensions(iw, ih, tw, th, tw, th);
    }

    @Override protected void initializeFbo() {
        try {
            VulkanSlang.ensureLoaded();
            SlangPresetStore.Selection saved = selection == ShaderLoader.CUSTOM_SLANG ? SlangPresetStore.selection(context, slot) : null;
            if (selection == ShaderLoader.CUSTOM_SLANG && saved == null)
                throw new IOException("Choose a .slangp preset for this pass first");
            Map<String, String> overrides = saved == null ? Collections.emptyMap() : SlangPresetStore.overrides(context, saved);
            try (SlangPresetStore.Prepared preset = saved == null
                    ? SlangPresetStore.prepareAsset(context, selection.getNames().get(0))
                    : SlangPresetStore.prepare(context, saved.tree, saved.path)) {
                // Validate overrides against current declarations; ignore removed parameters after a preset edit.
                java.util.List<String> names = new java.util.ArrayList<>();
                java.util.List<Float> numbers = new java.util.ArrayList<>();
                for (SlangParameter parameter : VulkanSlang.parameters(preset.file.getAbsolutePath())) {
                    if (overrides.containsKey(parameter.id)) {
                        names.add(parameter.id);
                        numbers.add(parameter.editedValue(overrides.get(parameter.id)));
                    }
                }
                float[] values = new float[numbers.size()];
                for (int i = 0; i < values.length; i++) values[i] = numbers.get(i);
                handle = VulkanSlang.create(preset.file.getAbsolutePath(), inputWidth, inputHeight, outputWidth, outputHeight,
                        names.toArray(new String[0]), values);
            }
            mFboId = VulkanSlang.inputFramebuffer(handle);
            mFboTextureId = VulkanSlang.outputTexture(handle);
        } catch (IOException | LinkageError e) { throw new IllegalStateException(e.getMessage(), e); }
    }

    @Override public TexturePassResult getTexturePassResult() {
        return new TexturePassResult(mFboTextureId, outputWidth, outputHeight, inputWidth, inputHeight);
    }

    @Override public void draw() {
        super.draw();
        VulkanSlang.render(handle);
    }

    @Override public void release() {
        // The native owner releases its shared textures and framebuffer exactly once.
        mFboId = mFboTextureId = 0;
        super.release();
        if (handle != 0) { VulkanSlang.destroy(handle); handle = 0; }
    }
}
