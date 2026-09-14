cmake_minimum_required(VERSION 3.22)
project(VirtualDapContainer LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
set(CORE "${ROOT}/container_runtime/core/build/generated/upstream/cpp")
set(DOBBY_SOURCE "${ROOT}/container_runtime/core/build/generated/dobby")
set(DOBBY_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
add_subdirectory("${DOBBY_SOURCE}" "${CMAKE_CURRENT_BINARY_DIR}/dobby" EXCLUDE_FROM_ALL)
get_target_property(dobby_sources dobby_static SOURCES)
list(REMOVE_ITEM dobby_sources "${DOBBY_SOURCE}/source/Backend/UserMode/ExecMemory/code-patch-tool-posix.cc")
list(APPEND dobby_sources "${CMAKE_CURRENT_LIST_DIR}/compat/code_patch_android.cpp")
set_property(TARGET dobby_static PROPERTY SOURCES "${dobby_sources}")

add_library(blackbox SHARED
    "${CMAKE_CURRENT_LIST_DIR}/AAudioCapture.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/AudioTrackCapture.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/AtomicPackagePublisher.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/CapturedPcmStream.cpp"
    "${ROOT}/guest_os/BridgeTransport.cpp"
    "${CORE}/BoxCore.cpp"
    "${CORE}/hidden_api.cpp"
    "${CORE}/IO.cpp"
    "${CORE}/Utils/elf_util.cpp"
    "${CORE}/Hook/DexFileHook.cpp"
    "${CORE}/Hook/FileSystemHook.cpp"
    "${CORE}/Utils/VirtualSpoof.cpp"
    "${CORE}/Utils/HexDump.cpp"
    "${CORE}/Utils/AntiDetection.cpp"
    "${CORE}/Hook/VMClassLoaderHook.cpp"
    "${CORE}/Hook/UnixFileSystemHook.cpp"
    "${CORE}/Hook/BinderHook.cpp"
    "${CORE}/Hook/BaseHook.cpp"
    "${CORE}/JniHook/JniHook.cpp"
    "${CORE}/xdl/xdl.c"
    "${CORE}/xdl/xdl_iterate.c"
    "${CORE}/xdl/xdl_linker.c"
    "${CORE}/xdl/xdl_lzma.c"
    "${CORE}/xdl/xdl_util.c"
)
target_include_directories(blackbox PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/compat" "${CORE}" "${DOBBY_SOURCE}/include" "${ROOT}/guest_os/include")
target_compile_options(blackbox PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(blackbox PRIVATE "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
target_link_libraries(blackbox PRIVATE dobby_static log android z dl)
