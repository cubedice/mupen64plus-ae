# Loaded by CMAKE_PROJECT_GLideN64_INCLUDE, leaving the upstream subrepo unchanged.
# Build zstd for the current Android ABI instead of expecting an unprovided archive.
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ZSTD_LEGACY_SUPPORT OFF CACHE BOOL "" FORCE)
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../ndkLibs/zstd/upstream/build/cmake"
                 "${CMAKE_BINARY_DIR}/zstd")
set_target_properties(libzstd_static PROPERTIES POSITION_INDEPENDENT_CODE ON)

function(link_android_zstd)
    cmake_policy(SET CMP0079 NEW)
    # GLideNHQ is created after the project() hook, so replace its prebuilt link
    # once all upstream targets exist. Linking the target also supplies zstd.h.
    foreach(target GLideNHQ GLideNHQd)
        if(TARGET ${target})
            foreach(property LINK_LIBRARIES INTERFACE_LINK_LIBRARIES)
                get_target_property(libraries ${target} ${property})
                if(libraries)
                    list(FILTER libraries EXCLUDE REGEX "/libzstd\\.a(>|$)")
                    set_property(TARGET ${target} PROPERTY ${property} "${libraries}")
                endif()
            endforeach()
            target_link_libraries(${target} PRIVATE libzstd_static)
        endif()
    endforeach()
endfunction()
cmake_language(DEFER CALL link_android_zstd)
