cmake_minimum_required(VERSION 3.16)
project(VirtualDAPGuestBridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(virtualdap_bridge STATIC BridgeTransport.cpp)
target_include_directories(virtualdap_bridge PUBLIC include)
target_compile_options(virtualdap_bridge PRIVATE -Wall -Wextra -Werror)

if(NOT ANDROID)
    enable_testing()
    find_package(Threads REQUIRED)
    add_executable(bridge_transport_test tests/BridgeTransportTest.cpp)
    target_link_libraries(bridge_transport_test PRIVATE virtualdap_bridge Threads::Threads)
    target_compile_options(bridge_transport_test PRIVATE -Wall -Wextra -Werror)
    add_test(NAME bridge_transport_test COMMAND bridge_transport_test)
endif()
