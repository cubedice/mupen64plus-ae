# Android zstd dependency

Prebuilt static zstd 1.5.7 libraries live beside the other JNI dependencies in
ndkLibs/libs/{debug,release}/{armeabi-v7a,arm64-v8a,x86,x86_64}/libzstd.a.
Both variants use the same optimized, position-independent library. GLideN64
links these archives directly; app builds do not compile or download zstd.
include/zstd.h is the matching public header. LICENSE and COPYING are upstream licenses.

Built from https://github.com/facebook/zstd/tree/v1.5.7,
commit f8745da6ff1ad1e7bab384bd1f9d742439278e99, using Android NDK
26.1.10909125, API 28, CMake 3.22.1 and Ninja. Programs, tests, shared libraries
and legacy pre-1.0 formats are disabled. GLideNHQ's zlib compatibility wrapper
remains enabled for both zstd and gzip texture caches.

To regenerate on Windows, clone v1.5.7 into build/native-deps/zstd and run
ndkLibs/zstd/build-android.ps1 with these arguments:
-SourceDir build/native-deps/zstd
-NdkDir <Android SDK>/ndk/26.1.10909125
-CMake <Android SDK>/cmake/3.22.1/bin/cmake.exe
-Ninja <Android SDK>/cmake/3.22.1/bin/ninja.exe

The script rebuilds all four ABIs and copies archives into both variants.
Source and build output stay in ignored build/ directories.

On Linux/WSL, the wrapper compatibility test requires a separate checkout of
the same zstd commit and a system zlib library:

    cmake -S mupen64plus-video-gliden64/tests -B build/gliden64-cache-test -DCMAKE_BUILD_TYPE=Release -DZSTD_SOURCE_DIR="$PWD/build/native-deps/zstd"
    cmake --build build/gliden64-cache-test -j 4
    ctest --test-dir build/gliden64-cache-test --output-on-failure

This host test checks signatures and decoded bytes for gzip and zstd caches;
the Android release build verifies linking the checked-in Android archives.
