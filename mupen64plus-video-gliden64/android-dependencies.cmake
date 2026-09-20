# Upstream links ndkLibs/libs/<variant>/<abi>/libzstd.a directly.
# Supply the matching public header without modifying the upstream subrepo.
include_directories("${CMAKE_CURRENT_LIST_DIR}/../ndkLibs/zstd/include")
