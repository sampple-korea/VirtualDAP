cmake_minimum_required(VERSION 3.10.2)
project(VirtualAudioHAL)

# VirtualDAP Audio HAL
# Compiles to audio.primary.virtualdap.so

set(CMAKE_CXX_STANDARD 17)

# Find Android HAL headers (These locations depend on NDK or sysroot)
# In a real CI with NDK, we might link against stub libraries or headers.
# For this PoC, we will assume headers are available or include local stubs if needed.
include_directories(include)

# Source files
add_library(audio.primary.virtualdap SHARED
    VirtualAudioHAL.cpp
)

# Libraries to link against (log, cutils, hardware etc.)
# Since we are using NDK, these are usually provided by the NDK platform
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(audio.primary.virtualdap
    ${log-lib}
    ${android-lib}
)

# Rename output to match HAL naming convention
set_target_properties(audio.primary.virtualdap PROPERTIES
    PREFIX ""
    SUFFIX ".so"
)
