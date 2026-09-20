# Vulkan Slang presets

Shader settings offer Shaderlay CRT, Scanlines, LCD, and a Custom Slang preset. All `.slangp`
choices execute through librashader's Vulkan runtime in the shared post-processing stage,
independently of the selected N64 graphics plugin. Existing `.glsl` effects still use OpenGL
and can appear before or after a Slang preset.

## Selecting a preset and editing parameters

1. Add a shader pass and choose **Custom Slang preset**.
2. Grant access to a folder containing the preset, shaders, includes, and lookup textures.
3. Choose the `.slangp` file. Its dependencies and parameter declarations are checked before assignment.
4. Open **Pass N parameters** beneath the pass to edit controls. **Reset defaults** restores the
   preset's values, including inherited defaults. Changes apply when the game renderer starts again.

Choosing Custom Slang again always opens the picker. Cancelling preserves the previous choice.
The preset list is cached after the first successful folder scan, including empty lists, and survives
app restarts. Search filters this cached list. Only choosing a different library folder invalidates
the index; choosing another preset or reselecting the same folder keeps it.
Each pass stores its own preset and parameters; deleting an earlier pass moves these settings
with the remaining pass. Re-selecting the same preset retains values. The bundled Shaderlay
presets do not declare adjustable controls.

Keep the selected folder accessible. Dependencies are copied into a temporary private directory
when inspecting or loading the preset, preserving relative paths for the native parser. The copy
is removed after loading. Select a common parent folder when dependencies use `../`. Imports
resolve each `#reference`, shader, lookup texture and `#include` against the directory of the
file declaring it, including nested references. The temporary copies contain explicit resolved
paths so the native parser uses the same locations; original files remain unchanged. Both
forward-slash and backslash separators are accepted. Imports
are limited to 2,048 files, 64 MB per file, 256 MB total, and 64 levels of dependency nesting.
Preset references, shader includes, and named lookup textures are followed; absolute filesystem
paths outside the granted folder are rejected. Presets using runtime path substitutions should
be made self-contained with relative paths before import.

## Renderer

The shared `ShaderDrawer` consumes every plugin's existing SurfaceTexture output. `VulkanShader`
blits that image (or an earlier GLSL effect's output) into an Android hardware buffer. Vulkan imports
that buffer, executes the complete Slang filter chain, and writes another shared buffer. OpenGL
then presents the result or feeds it into the next effect. There is no per-frame CPU image readback
or upload. The shared boundary is RGBA8 SDR; internal pass formats, scaling, history/feedback,
lookup textures, mipmaps, aliases and parameter uniforms are managed by librashader.

The bridge requires Vulkan 1.1, `VK_ANDROID_external_memory_android_hardware_buffer`,
`VK_EXT_queue_family_foreign`, and EGL image import support. It uses explicit queue ownership
barriers, `glFinish`, and a Vulkan fence wait. These conservative waits serialize the handoff;
performance needs measurement on devices. HDR output is not exposed by this SDR presentation path.

Initialization or rendering failure releases the chain, displays the cause, and falls back to the
unfiltered game image. Library-loading errors in settings dismiss the progress dialog and report
an error. Native loading is explicit rather than a static class initializer, avoiding a permanently
failed Java class. Surface recreation frees and rebuilds the native resources on the render thread.

The previous Java `SlangCompiler` remains as reference code with its tests, but no production
rendering or settings path calls it. Its Android-incompatible block regex was also corrected.

## Building

The Vulkan C API is pinned to librashader 0.12.0, revision
`87e8a97b50516d997defeaa168173dcd185d4022`. Run on Linux or WSL:

```sh
# Debian prerequisites
sudo apt-get install build-essential cmake ninja-build curl ca-certificates unzip pkg-config libclang-dev
bash scripts/build-vulkan-runtime.sh
# Optionally build one ABI: bash scripts/build-vulkan-runtime.sh arm64-v8a
```

The script downloads Rust and Android NDK r26b under the ignored `build/native-deps` directory,
builds with Cargo.lock and Vulkan-only runtime features, and writes shared libraries to
`app/build/rashader/<abi>`. Run it again after cleaning that output. CMake imports those libraries
and builds the JNI bridge; Gradle packages both along with the NDK shared C++ runtime.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

A Java 17 test toolchain is required. Tests cover dependency copying, references, includes, textures,
cycles, path boundaries, cancellation, picker reselection, per-pass settings, migration and removal.
The old translator tests are retained separately. Android device checks must cover asymmetric
passthrough images (orientation), a multipass preset with LUT/history, parameter edits, mixed
GLSL/Slang chains, pause/resume, rotation, screenshots, and unsupported-device fallback across
GLideN64, Rice, Glide64, glN64, Angrylion, and ParaLLEl.

No physical Android device is attached in this development environment; build and JVM tests do
not verify driver interoperability or gameplay performance.

## Sources and licenses

- [librashader](https://github.com/SnowflakePowered/librashader), MPL-2.0 OR GPL-3.0;
  its C API header is MIT. License texts are packaged in `assets/licenses`.
- Bundled `.slangp` and `.slang` files are unchanged Shaderlay assets, with its MIT license packaged.
- [Vulkan Android hardware-buffer specification](https://docs.vulkan.org/spec/latest/chapters/memory.html)
  describes the external image ownership rules used by the bridge.
