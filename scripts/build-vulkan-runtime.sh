#!/usr/bin/env bash
# Run on Linux/WSL. Prerequisites: C/C++ compiler, libclang, CMake, curl, unzip.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
deps="$root/build/native-deps"
revision=87e8a97b50516d997defeaa168173dcd185d4022
mkdir -p "$deps"
export CARGO_HOME="$deps/cargo"
export RUSTUP_HOME="$deps/rustup"
export PATH="$CARGO_HOME/bin:$PATH"
if [ ! -x "$CARGO_HOME/bin/cargo" ]; then
    curl --fail --location --retry 3 https://sh.rustup.rs -o "$deps/rustup-init.sh"
    sh "$deps/rustup-init.sh" -y --no-modify-path --profile minimal --default-toolchain stable
fi
ndk="$deps/android-ndk-r26b"
if [ ! -d "$ndk" ]; then
    curl --fail --location --retry 3 https://dl.google.com/android/repository/android-ndk-r26b-linux.zip -o "$deps/android-ndk-r26b-linux.zip"
    unzip -oq "$deps/android-ndk-r26b-linux.zip" -d "$deps"
fi
source="$deps/librashader-source"
if [ ! -f "$source/Cargo.toml" ]; then
    curl --fail --location --retry 3 "https://codeload.github.com/SnowflakePowered/librashader/tar.gz/$revision" -o "$deps/librashader.tar.gz"
    mkdir -p "$source"
    tar -xzf "$deps/librashader.tar.gz" --strip-components=1 -C "$source"
fi
export CARGO_TARGET_DIR="$deps/rashader-target"
export LIBCLANG_PATH=/usr/lib/llvm-19/lib
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-4}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
for pair in "arm64-v8a:aarch64-linux-android:aarch64-linux-android" "armeabi-v7a:armv7-linux-androideabi:armv7a-linux-androideabi" "x86:i686-linux-android:i686-linux-android" "x86_64:x86_64-linux-android:x86_64-linux-android"; do
    IFS=: read -r abi target clang_target <<< "$pair"
    if [ -n "${1:-}" ] && [ "$1" != "$abi" ]; then continue; fi
    rustup target add "$target"
    key="${target//-/_}"
    export "CARGO_TARGET_${key^^}_LINKER=$toolchain/${clang_target}28-clang"
    export "CC_$key=$toolchain/${clang_target}28-clang"
    export "CXX_$key=$toolchain/${clang_target}28-clang++"
    export "AR_$key=$toolchain/llvm-ar"
    export "CARGO_TARGET_${key^^}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384"
    cargo build --manifest-path "$source/Cargo.toml" --locked --release --target "$target" \
        -p librashader-capi --no-default-features --features runtime-vulkan
    mkdir -p "$root/app/build/rashader/$abi"
    cp "$CARGO_TARGET_DIR/$target/release/liblibrashader_capi.so" "$root/app/build/rashader/$abi/"
done
