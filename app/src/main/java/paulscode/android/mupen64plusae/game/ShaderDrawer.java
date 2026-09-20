package paulscode.android.mupen64plusae.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import paulscode.android.mupen64plusae.util.PixelBuffer;

public class ShaderDrawer {

    private static final String TAG = "ShaderDrawer";
    private SurfaceTexture mGameTexture;
    private final ArrayList<ArrayList<Shader>> mShaderPasses = new ArrayList<>();
    private int mWidth = 0;
    private int mHeight = 0;
    private int mGameTextureId, mSourceWidth, mSourceHeight;
    private final Context mContext;

    public ShaderDrawer(Context context, ArrayList<ShaderLoader> selectedShaders) {
        mContext = context.getApplicationContext();
        ShaderLoader.loadShaders(context);

        for (int index = 0; index < selectedShaders.size(); ++ index) {
            boolean first = index == 0;
            boolean last = index == selectedShaders.size() - 1;

            mShaderPasses.add(new ArrayList<>());
            if (selectedShaders.get(index).isSlang()) {
                mShaderPasses.get(index).add(new VulkanShader(mContext, selectedShaders.get(index), index, first));
                if (last) mShaderPasses.get(index).add(new Shader(ShaderLoader.DEFAULT.getShaderCode().get(0), false, true, false, 1));
                continue;
            }
            for (int shaderCodeIndex = 0; shaderCodeIndex < selectedShaders.get(index).getShaderCode().size(); ++shaderCodeIndex) {

                boolean actualLast = last && shaderCodeIndex == selectedShaders.get(index).getShaderCode().size() - 1;
                mShaderPasses.get(index).add(new Shader(selectedShaders.get(index).getShaderCode().get(shaderCodeIndex), first, actualLast,
                        index == 0, shaderCodeIndex));
                first = false;
            }
        }

        if (mShaderPasses.size() == 0) {
            mShaderPasses.add(new ArrayList<>());
            mShaderPasses.get(0).add(new Shader(ShaderLoader.DEFAULT.getShaderCode().get(0), true, true, true, 0));
        }
    }

    public void onSurfaceTextureAvailable(PixelBuffer.SurfaceTextureWithSize surface, int width, int height) {
        Log.i(TAG, "onSurfaceTextureAvailable");

        if (mGameTexture == null) {
            Log.i(TAG, "Texture available, surface_final=" + width + "x" + height +
                    " orig_render=" + surface.mWidth + "x" + surface.mHeight);
            mWidth = width;
            mHeight = height;

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int texture = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);

            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            mGameTexture = surface.mSurfaceTexture;

            try {
                mGameTexture.attachToGLContext(texture);
            } catch (RuntimeException e) {
                mGameTexture = null;
                return;
            }

            // For some reason this is needed, otherwise frame callbacks stop happening on orientation
            // changes or if the app is put on the background then foreground again
            try {
                mGameTexture.updateTexImage();
            } catch (RuntimeException e) {
                e.printStackTrace();
            }

            Shader.TexturePassResult prevResult = new Shader.TexturePassResult(texture, surface.mWidth, surface.mHeight,
                    surface.mWidth, surface.mHeight);

            mGameTextureId = texture;
            mSourceWidth = surface.mWidth;
            mSourceHeight = surface.mHeight;
            try {
                for (int subPassIndex = 0; subPassIndex < mShaderPasses.size(); ++subPassIndex ) {

                    ArrayList<Shader> shaderSubPasses = mShaderPasses.get(subPassIndex);
                    ArrayList<Shader.TexturePassResult> texturePassResults = new ArrayList<>();

                    for (int shaderIndex = 0; shaderIndex < shaderSubPasses.size(); ++shaderIndex) {
                        Shader shader = shaderSubPasses.get(shaderIndex);
                        shader.setSourceTexture(texture);

                        // Slang presets choose dimensions per pass, then blit to the viewport.
                        if (shader instanceof VulkanShader || shaderIndex > 0 && shaderSubPasses.get(shaderIndex - 1) instanceof VulkanShader) {
                            shader.setDimensions(surface.mWidth, surface.mHeight, prevResult.getWidth(), prevResult.getHeight(), width, height);
                        } else if (subPassIndex == 0) {
                            if (shaderIndex == shaderSubPasses.size() - 1) {
                                Log.d("Shader", "subpass=" + subPassIndex + " shader=" + shaderIndex + " scale=yes");
                                shader.setDimensions(surface.mWidth, surface.mHeight, surface.mWidth, surface.mHeight, width, height);
                            } else {
                                Log.d("Shader", "subpass=" + subPassIndex + " shader=" + shaderIndex + " scale=no");
                                shader.setDimensions(surface.mWidth, surface.mHeight, surface.mWidth, surface.mHeight, surface.mWidth, surface.mHeight);
                            }
                        } else {
                            Log.d("Shader", "subpass=" + subPassIndex + " shader=" + shaderIndex + " scale=already");
                            shader.setDimensions(surface.mWidth, surface.mHeight, width, height, width, height);
                        }

                        texturePassResults.add(0, prevResult);

                        shader.initShader();
                        shader.setShaderSubPasses(new ArrayList<>(texturePassResults));
                        texture = shader.getFboTextureId();
                        prevResult = shader.getTexturePassResult();
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                usePassthrough(e);
            }
        }
    }

    public void onSurfaceTextureDestroyed() {
        Log.i(TAG, "onSurfaceTextureDestroyed");
        for (ArrayList<Shader> group : mShaderPasses) for (Shader shader : group) shader.release();

        if (mGameTexture != null) {
            Log.i(TAG, "Dettaching texture");

            try {
                mGameTexture.detachFromGLContext();
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
            mGameTexture = null;
        }
    }

    public Bitmap getScreenShot() {

        if (mWidth <= 0 || mHeight <= 0) {
            return null;
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(mWidth * mHeight * 4);
        GLES20.glReadPixels(0, 0, mWidth, mHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);

        // Fix alpha to 255 for all pixels, some plugins don't ever set alpha values
        for (int bufferIndex = 3; bufferIndex < buffer.array().length; bufferIndex+=4) {
            buffer.array()[bufferIndex] = -1;
        }

        Bitmap bitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return bitmap;
    }

    public void onDrawFrame() {
        if (mGameTexture != null) {
            try {
                mGameTexture.updateTexImage();
            } catch (RuntimeException e) {
                e.printStackTrace();
            }

            try {
                for (ArrayList<Shader> group : mShaderPasses) for (Shader shader : group) shader.draw();
            } catch (RuntimeException | LinkageError e) {
                usePassthrough(e);
                mShaderPasses.get(0).get(0).draw();
            }
        }
    }
    private void usePassthrough(Throwable error) {
        Log.e(TAG, "Unable to render shader chain; using passthrough", error);
        for (ArrayList<Shader> group : mShaderPasses) for (Shader shader : group) shader.release();
        mShaderPasses.clear();
        Shader fallback = new Shader(ShaderLoader.DEFAULT.getShaderCode().get(0), true, true, true, 0);
        fallback.setSourceTexture(mGameTextureId);
        fallback.setDimensions(mSourceWidth, mSourceHeight, mSourceWidth, mSourceHeight, mWidth, mHeight);
        fallback.setShaderSubPasses(new ArrayList<>());
        fallback.initShader();
        ArrayList<Shader> group = new ArrayList<>();
        group.add(fallback);
        mShaderPasses.add(group);
        String message = mContext.getString(paulscode.android.mupen64plusae.R.string.shadersSlangError, error.getMessage());
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(
                mContext, message, android.widget.Toast.LENGTH_LONG).show());
    }
}