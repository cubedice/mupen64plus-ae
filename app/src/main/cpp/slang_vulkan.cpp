#include "librashader.h"
#include <jni.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <algorithm>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void check(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) throw std::runtime_error(std::string(operation) + " (Vulkan " + std::to_string(result) + ")");
}
void check(libra_error_t error) {
    if (!error) return;
    char* message = nullptr;
    libra_error_write(error, &message);
    std::string text = message ? message : "Slang preset operation failed";
    libra_error_free_string(&message);
    libra_error_free(&error);
    throw std::runtime_error(text);
}
void discard(libra_error_t error) { if (error) libra_error_free(&error); }
struct Preset {
    libra_shader_preset_t handle = nullptr;
    explicit Preset(const char* path) { check(libra_preset_create(path, &handle)); }
    ~Preset() { if (handle) discard(libra_preset_free(&handle)); }
};
struct Utf {
    JNIEnv* env;
    jstring string;
    const char* value;
    Utf(JNIEnv* e, jstring s) : env(e), string(s), value(e->GetStringUTFChars(s, nullptr)) {
        if (!value) throw std::runtime_error("Unable to read shader path");
    }
    ~Utf() { env->ReleaseStringUTFChars(string, value); }
};
void fail(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

struct SharedImage {
    AHardwareBuffer* buffer = nullptr;
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    GLuint texture = 0, framebuffer = 0;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    uint32_t width = 0, height = 0;
    libra_image_vk_t descriptor() const { return {image, VK_FORMAT_R8G8B8A8_UNORM, width, height}; }
};

// Each instance is confined to the existing GL rendering thread. GL and Vulkan share
// hardware buffers, with explicit ownership barriers and completion waits at both boundaries.
struct Renderer {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer commands[3]{};
    VkFence fence = VK_NULL_HANDLE;
    libra_vk_filter_chain_t chain = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC clientBuffer = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC bindImage = nullptr;
    SharedImage input, output;
    size_t frame = 0;

    ~Renderer() {
        if (display != EGL_NO_DISPLAY) glFinish();
        if (device) vkDeviceWaitIdle(device);
        if (chain) discard(libra_vk_filter_chain_free(&chain));
        freeImage(output);
        freeImage(input);
        if (fence) vkDestroyFence(device, fence, nullptr);
        if (pool) vkDestroyCommandPool(device, pool, nullptr);
        if (device) vkDestroyDevice(device, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
    }

    void freeImage(SharedImage& image) {
        if (image.framebuffer) glDeleteFramebuffers(1, &image.framebuffer);
        if (image.texture) glDeleteTextures(1, &image.texture);
        if (image.eglImage != EGL_NO_IMAGE_KHR && destroyImage) destroyImage(display, image.eglImage);
        if (image.image) vkDestroyImage(device, image.image, nullptr);
        if (image.memory) vkFreeMemory(device, image.memory, nullptr);
        if (image.buffer) AHardwareBuffer_release(image.buffer);
        image = {};
    }

    void initialize(const char* path, int iw, int ih, int ow, int oh) {
        if (iw <= 0 || ih <= 0 || ow <= 0 || oh <= 0) throw std::runtime_error("Invalid shader dimensions");
        display = eglGetCurrentDisplay();
        createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        clientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        bindImage = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        const char* extensions = eglQueryString(display, EGL_EXTENSIONS);
        if (!createImage || !destroyImage || !clientBuffer || !bindImage || !extensions ||
                !strstr(extensions, "EGL_ANDROID_image_native_buffer"))
            throw std::runtime_error("This device cannot share images between OpenGL and Vulkan");

        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Mupen64Plus Slang";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        info.pApplicationInfo = &app;
        check(vkCreateInstance(&info, nullptr, &instance), "Create Vulkan 1.1 instance");
        uint32_t count = 0;
        check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "Enumerate Vulkan devices");
        std::vector<VkPhysicalDevice> devices(count);
        check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "Enumerate Vulkan devices");
        const char* required[] = {VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
                                 VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME};
        for (auto candidate : devices) {
            VkPhysicalDeviceProperties properties;
            vkGetPhysicalDeviceProperties(candidate, &properties);
            if (properties.apiVersion < VK_API_VERSION_1_1) continue;
            uint32_t n = 0;
            check(vkEnumerateDeviceExtensionProperties(candidate, nullptr, &n, nullptr), "Read Vulkan extensions");
            std::vector<VkExtensionProperties> available(n);
            check(vkEnumerateDeviceExtensionProperties(candidate, nullptr, &n, available.data()), "Read Vulkan extensions");
            bool compatible = true;
            for (auto name : required) compatible &= std::any_of(available.begin(), available.end(),
                    [name](const auto& ext) { return strcmp(name, ext.extensionName) == 0; });
            if (!compatible) continue;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &n, nullptr);
            std::vector<VkQueueFamilyProperties> families(n);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &n, families.data());
            for (uint32_t i = 0; i < n; ++i) if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
                physical = candidate;
                family = i;
                break;
            }
            if (physical) break;
        }
        if (!physical) throw std::runtime_error("Slang requires Vulkan 1.1 with Android hardware-buffer sharing");
        float priority = 1;
        VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = family;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = 2;
        deviceInfo.ppEnabledExtensionNames = required;
        check(vkCreateDevice(physical, &deviceInfo, nullptr, &device), "Create Vulkan shader device");
        vkGetDeviceQueue(device, family, 0, &queue);
        VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        poolInfo.queueFamilyIndex = family;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device, &poolInfo, nullptr, &pool), "Create shader command pool");
        VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        allocation.commandPool = pool;
        allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocation.commandBufferCount = 3;
        check(vkAllocateCommandBuffers(device, &allocation, commands), "Allocate shader commands");
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        check(vkCreateFence(device, &fenceInfo, nullptr, &fence), "Create shader fence");
        allocateImage(input, iw, ih, true);
        allocateImage(output, ow, oh, false);
        Preset preset(path);
        filter_chain_vk_opt_t options{};
        options.version = LIBRASHADER_CURRENT_VERSION;
        options.frames_in_flight = 1;
        // The app cache path is managed by Java; do not let the library use the process working directory.
        options.disable_cache = true;
        libra_device_vk_t handles{physical, instance, device, queue, vkGetInstanceProcAddr};
        check(libra_vk_filter_chain_create(&preset.handle, handles, &options, &chain));
    }

    void allocateImage(SharedImage& shared, uint32_t width, uint32_t height, bool source) {
        shared.width = width;
        shared.height = height;
        AHardwareBuffer_Desc description{};
        description.width = width;
        description.height = height;
        description.layers = 1;
        description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        description.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
        if (AHardwareBuffer_allocate(&description, &shared.buffer) != 0)
            throw std::runtime_error("Cannot allocate shared shader image");

        auto getProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
                vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
        if (!getProperties) throw std::runtime_error("Android Vulkan hardware-buffer import is unavailable");
        VkAndroidHardwareBufferFormatPropertiesANDROID format{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        properties.pNext = &format;
        check(getProperties(device, shared.buffer, &properties), "Inspect shared shader image");
        if (format.format != VK_FORMAT_R8G8B8A8_UNORM) throw std::runtime_error("Unsupported shared shader image format");
        VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.pNext = &external;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format.format;
        imageInfo.extent = {width, height, 1};
        imageInfo.mipLevels = imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        check(vkCreateImage(device, &imageInfo, nullptr, &shared.image), "Create shared Vulkan image");
        VkImportAndroidHardwareBufferInfoANDROID import{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
        import.buffer = shared.buffer;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.pNext = &import;
        dedicated.image = shared.image;
        VkMemoryAllocateInfo memoryInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        memoryInfo.pNext = &dedicated;
        memoryInfo.allocationSize = properties.allocationSize;
        if (!properties.memoryTypeBits) throw std::runtime_error("No compatible shared image memory");
        memoryInfo.memoryTypeIndex = static_cast<uint32_t>(__builtin_ctz(properties.memoryTypeBits));
        check(vkAllocateMemory(device, &memoryInfo, nullptr, &shared.memory), "Import shared image memory");
        check(vkBindImageMemory(device, shared.image, shared.memory, 0), "Bind shared image memory");
        const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        shared.eglImage = createImage(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer(shared.buffer), attributes);
        if (shared.eglImage == EGL_NO_IMAGE_KHR) throw std::runtime_error("Cannot import shader image into OpenGL");
        glGenTextures(1, &shared.texture);
        glBindTexture(GL_TEXTURE_2D, shared.texture);
        bindImage(GL_TEXTURE_2D, shared.eglImage);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (source) {
            glGenFramebuffers(1, &shared.framebuffer);
            glBindFramebuffer(GL_FRAMEBUFFER, shared.framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, shared.texture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw std::runtime_error("Shared shader framebuffer is incomplete");
        }
        if (glGetError() != GL_NO_ERROR) throw std::runtime_error("OpenGL hardware-buffer import failed");
    }

    void barrier(VkCommandBuffer command, const SharedImage& image, bool acquire, bool source) {
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        const auto layout = source ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        const VkAccessFlags access = source ? VK_ACCESS_SHADER_READ_BIT : VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        barrier.oldLayout = acquire ? VK_IMAGE_LAYOUT_GENERAL : layout;
        barrier.newLayout = acquire ? layout : VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcAccessMask = acquire ? 0 : access;
        barrier.dstAccessMask = acquire ? access : 0;
        barrier.srcQueueFamilyIndex = acquire ? VK_QUEUE_FAMILY_FOREIGN_EXT : family;
        barrier.dstQueueFamilyIndex = acquire ? family : VK_QUEUE_FAMILY_FOREIGN_EXT;
        // The output has not been used by either API on its first frame.
        if (acquire && !source && frame == 0) {
            barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        }
        barrier.image = image.image;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, acquire ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                acquire ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    void render() {
        // Completes the GL source blit and any sampling of the previous output before ownership transfer.
        glFinish();
        check(vkResetCommandPool(device, pool, 0), "Reset shader commands");
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        for (auto command : commands) check(vkBeginCommandBuffer(command, &begin), "Begin shader commands");
        barrier(commands[0], input, true, true);
        barrier(commands[0], output, true, false);
        check(libra_vk_filter_chain_frame(&chain, commands[1], frame, input.descriptor(), output.descriptor(), nullptr, nullptr, nullptr));
        barrier(commands[2], input, false, true);
        barrier(commands[2], output, false, false);
        for (auto command : commands) check(vkEndCommandBuffer(command), "End shader commands");
        check(vkResetFences(device, 1, &fence), "Reset shader fence");
        VkSubmitInfo submission{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submission.commandBufferCount = 3;
        submission.pCommandBuffers = commands;
        check(vkQueueSubmit(queue, 1, &submission, fence), "Submit Slang frame");
        check(vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX), "Wait for Slang frame");
        ++frame;
    }
};
Renderer* renderer(jlong value) { return reinterpret_cast<Renderer*>(value); }
}

#define JNI_METHOD(name) Java_paulscode_android_mupen64plusae_game_VulkanSlang_##name
extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(inspect)(JNIEnv* env, jclass, jstring path) {
    libra_preset_param_list_t params{};
    try {
        Utf name(env, path);
        Preset preset(name.value);
        check(libra_preset_get_runtime_params(&preset.handle, &params));
        jclass type = env->FindClass("paulscode/android/mupen64plusae/game/SlangParameter");
        jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;Ljava/lang/String;FFFF)V");
        if (!constructor) { discard(libra_preset_free_runtime_params(params)); return nullptr; }
        auto result = env->NewObjectArray(static_cast<jsize>(params.length), type, nullptr);
        for (uint64_t i = 0; i < params.length && !env->ExceptionCheck(); ++i) {
            const auto& p = params.parameters[i];
            float initial = p.initial;
            // get_param includes the preset's own parameter overrides.
            auto error = libra_preset_get_param(&preset.handle, p.name, &initial);
            if (error) { discard(error); initial = p.initial; }
            auto id = env->NewStringUTF(p.name);
            auto label = env->NewStringUTF(p.description);
            auto parameter = env->NewObject(type, constructor, id, label, initial, p.minimum, p.maximum, p.step);
            env->SetObjectArrayElement(result, static_cast<jsize>(i), parameter);
            env->DeleteLocalRef(parameter);
            env->DeleteLocalRef(id);
            env->DeleteLocalRef(label);
        }
        discard(libra_preset_free_runtime_params(params));
        return result;
    } catch (const std::exception& e) {
        if (params.parameters) discard(libra_preset_free_runtime_params(params));
        fail(env, e);
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv* env, jclass, jstring path, jint iw, jint ih, jint ow, jint oh,
                                                       jobjectArray names, jfloatArray values) {
    try {
        Utf name(env, path);
        auto result = std::make_unique<Renderer>();
        result->initialize(name.value, iw, ih, ow, oh);
        jsize length = env->GetArrayLength(names);
        if (length != env->GetArrayLength(values)) throw std::runtime_error("Invalid shader parameters");
        std::vector<float> numbers(length);
        env->GetFloatArrayRegion(values, 0, length, numbers.data());
        for (jsize i = 0; i < length; ++i) {
            auto parameter = static_cast<jstring>(env->GetObjectArrayElement(names, i));
            { Utf key(env, parameter); check(libra_vk_filter_chain_set_param(&result->chain, key.value, numbers[i])); }
            env->DeleteLocalRef(parameter);
        }
        return reinterpret_cast<jlong>(result.release());
    } catch (const std::exception& e) { fail(env, e); return 0; }
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(inputFramebuffer)(JNIEnv*, jclass, jlong handle) { return renderer(handle)->input.framebuffer; }
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(outputTexture)(JNIEnv*, jclass, jlong handle) { return renderer(handle)->output.texture; }
extern "C" JNIEXPORT void JNICALL JNI_METHOD(render)(JNIEnv* env, jclass, jlong handle) {
    try { renderer(handle)->render(); } catch (const std::exception& e) { fail(env, e); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(destroy)(JNIEnv*, jclass, jlong handle) { delete renderer(handle); }
