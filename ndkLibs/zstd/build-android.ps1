param(
    [Parameter(Mandatory=$true)][string]$SourceDir,
    [Parameter(Mandatory=$true)][string]$NdkDir,
    [string]$CMake = 'cmake',
    [string]$Ninja = 'ninja'
)
$ErrorActionPreference = 'Stop'
$sourcePath = (Resolve-Path -LiteralPath $SourceDir).Path
$revision = & git -C $sourcePath rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $revision -ne 'f8745da6ff1ad1e7bab384bd1f9d742439278e99') {
    throw 'Expected an unmodified zstd v1.5.7 checkout.'
}
if (& git -C $sourcePath status --porcelain --untracked-files=no) {
    throw 'The zstd checkout has modified tracked files.'
}
$repoPath = (Resolve-Path "$PSScriptRoot/../..").Path
foreach ($abi in @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')) {
    $buildPath = "$repoPath/build/zstd-prebuilt/$abi"
    $configureArgs = @(
        '-S', "$sourcePath/build/cmake", '-B', $buildPath, '-G', 'Ninja',
        "-DCMAKE_MAKE_PROGRAM=$Ninja", "-DCMAKE_TOOLCHAIN_FILE=$NdkDir/build/cmake/android.toolchain.cmake",
        "-DANDROID_ABI=$abi", '-DANDROID_PLATFORM=android-28', '-DANDROID_ARM_NEON=TRUE',
        '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
        '-DZSTD_BUILD_PROGRAMS=OFF', '-DZSTD_BUILD_TESTS=OFF', '-DZSTD_BUILD_SHARED=OFF',
        '-DZSTD_BUILD_STATIC=ON', '-DZSTD_LEGACY_SUPPORT=OFF'
    )
    & $CMake @configureArgs
    if ($LASTEXITCODE -ne 0) { throw "Configure failed: $abi" }
    & $CMake --build $buildPath --target libzstd_static --parallel 4
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $abi" }
    # Both app variants use the optimized dependency.
    foreach ($variant in @('debug', 'release')) {
        Copy-Item -LiteralPath "$buildPath/lib/libzstd.a" -Destination "$repoPath/ndkLibs/libs/$variant/$abi/libzstd.a"
    }
}
Copy-Item -LiteralPath "$sourcePath/lib/zstd.h" -Destination "$PSScriptRoot/include/zstd.h"
Copy-Item -LiteralPath "$sourcePath/lib/zstd_errors.h" -Destination "$PSScriptRoot/include/zstd_errors.h"
