cmake_minimum_required(VERSION 3.16)
project(VirtualDAPGuestBridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(virtualdap_bridge STATIC BridgeTransport.cpp)
target_include_directories(virtualdap_bridge PUBLIC include)
target_compile_options(virtualdap_bridge PRIVATE -Wall -Wextra -Werror)

if(ANDROID)
    # Small validation client used by integrators to exercise the exact guest-to-host
    # transport without booting a complete guest image. It is not installed in the
    # production Android image.
    add_executable(bridge_smoke_client tools/BridgeSmokeClient.cpp)
    target_link_libraries(bridge_smoke_client PRIVATE virtualdap_bridge)
    target_compile_options(bridge_smoke_client PRIVATE -Wall -Wextra -Werror)
else()
    enable_testing()
    find_package(Threads REQUIRED)
    add_executable(bridge_transport_test tests/BridgeTransportTest.cpp)
    target_link_libraries(bridge_transport_test PRIVATE virtualdap_bridge Threads::Threads)
    target_compile_options(bridge_transport_test PRIVATE -Wall -Wextra -Werror)
    add_test(NAME bridge_transport_test COMMAND bridge_transport_test)
    add_executable(output_arbiter_test tests/OutputArbiterTest.cpp)
    target_include_directories(output_arbiter_test PRIVATE include)
    target_compile_options(output_arbiter_test PRIVATE -Wall -Wextra -Werror)
    add_test(NAME output_arbiter_test COMMAND output_arbiter_test)
    set(CAPTURE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../container_runtime")
    add_executable(captured_pcm_stream_test
        "${CAPTURE_ROOT}/tests/CapturedPcmStreamTest.cpp"
        "${CAPTURE_ROOT}/core/src/main/cpp/CapturedPcmStream.cpp")
    target_include_directories(captured_pcm_stream_test PRIVATE "${CAPTURE_ROOT}/core/src/main/cpp")
    target_link_libraries(captured_pcm_stream_test PRIVATE virtualdap_bridge Threads::Threads)
    target_compile_options(captured_pcm_stream_test PRIVATE -Wall -Wextra -Werror)
    add_test(NAME captured_pcm_stream_test COMMAND captured_pcm_stream_test)
    set_tests_properties(captured_pcm_stream_test PROPERTIES TIMEOUT 15)
endif()
