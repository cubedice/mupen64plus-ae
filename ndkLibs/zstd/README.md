# Android zstd dependency

`upstream/lib` and `upstream/build/cmake` are copied unchanged from
[facebook/zstd v1.5.7](https://github.com/facebook/zstd/tree/v1.5.7), commit
`f8745da6ff1ad1e7bab384bd1f9d742439278e99`, together with LICENSE and COPYING.

GLideN64's Android CMake project hook builds the static library from source for
each ABI and links it into GLideNHQ. Programs, tests, shared libraries and legacy
pre-1.0 zstd formats are disabled. No separate prebuilt libzstd.a is required.
The upstream GLideN64 zlib compatibility wrapper remains enabled, preserving
zstd texture-cache support and compatibility with zlib-compressed caches.

Build with `gradlew :mupen64plus-video-gliden64:assembleRelease` or the full app
release task. The dependency is included in the repository, so builds do not
download zstd during CMake configuration.

On Linux/WSL, run the wrapper compatibility test with:

```sh
cmake -S mupen64plus-video-gliden64/tests -B build/gliden64-cache-test -DCMAKE_BUILD_TYPE=Release
cmake --build build/gliden64-cache-test -j 4
ctest --test-dir build/gliden64-cache-test --output-on-failure
```

The test checks the file signatures and decoded bytes for both gzip and zstd
caches using GLideNHQ's actual wrapper sources. A system zlib library is required.
