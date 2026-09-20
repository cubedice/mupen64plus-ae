#include "zstd/zstd_zlibwrapper.h"
#include <cstdio>
#include <cstring>
#include <vector>

static bool check(bool condition, const char* message) {
    if (!condition) std::fprintf(stderr, "%s\n", message);
    return condition;
}

int main() {
    std::vector<unsigned char> source(65536);
    for (size_t i = 0; i < source.size(); ++i) source[i] = static_cast<unsigned char>(i * 17 + i / 101);
    for (int zstd = 0; zstd <= 1; ++zstd) {
        const char* path = zstd ? "roundtrip-zstd.cache" : "roundtrip-gzip.cache";
        ZWRAP_useZSTDcompression(zstd);
        gzFile writer = gzopen(path, "wb");
        if (!check(writer != nullptr, "Cannot create texture cache")) return 1;
        if (!check(gzwrite(writer, source.data(), static_cast<unsigned>(source.size())) == source.size(), "Cache write failed")) return 1;
        if (!check(gzclose(writer) == Z_OK, "Cache close failed")) return 1;
        FILE* raw = std::fopen(path, "rb");
        unsigned char magic[4]{};
        if (!check(raw != nullptr, "Cannot inspect cache")) return 1;
        size_t count = std::fread(magic, 1, sizeof(magic), raw);
        std::fclose(raw);
        const unsigned char zstdMagic[] = {0x28, 0xb5, 0x2f, 0xfd};
        if (!check(count == 4 && (zstd ? std::memcmp(magic, zstdMagic, 4) == 0 : magic[0] == 0x1f && magic[1] == 0x8b), "Wrong compression format")) return 1;

        // Read either format with zstd compression enabled, as GLideNHQ does.
        ZWRAP_useZSTDcompression(1);
        gzFile reader = gzopen(path, "rb");
        if (!check(reader != nullptr, "Cannot open cache")) return 1;
        std::vector<unsigned char> decoded(source.size());
        if (!check(gzread(reader, decoded.data(), static_cast<unsigned>(decoded.size())) == decoded.size(), "Cache read failed")) return 1;
        if (!check(gzclose(reader) == Z_OK && decoded == source, "Cache data differs")) return 1;
        std::remove(path);
    }
    return 0;
}
