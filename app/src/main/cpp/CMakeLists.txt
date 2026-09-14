cmake_minimum_required(VERSION 3.22)
project(VirtualDapDsp LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(DSD2PCM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dsd2pcm")
set(LIBUSB_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libusb")
add_library(dsd2pcm STATIC "${DSD2PCM_ROOT}/dsd2pcm.c")
target_include_directories(dsd2pcm PUBLIC "${DSD2PCM_ROOT}")
set_target_properties(dsd2pcm PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    add_library(virtualdap_dsp SHARED DsdPcmJni.cpp)
    target_link_libraries(virtualdap_dsp PRIVATE dsd2pcm)
    target_compile_options(virtualdap_dsp PRIVATE -Wall -Wextra -Werror)
    target_link_options(virtualdap_dsp PRIVATE "-Wl,-z,max-page-size=16384")
    add_library(usb-1.0 SHARED
        "${LIBUSB_ROOT}/libusb/core.c" "${LIBUSB_ROOT}/libusb/descriptor.c"
        "${LIBUSB_ROOT}/libusb/hotplug.c" "${LIBUSB_ROOT}/libusb/io.c"
        "${LIBUSB_ROOT}/libusb/sync.c" "${LIBUSB_ROOT}/libusb/strerror.c"
        "${LIBUSB_ROOT}/libusb/os/linux_usbfs.c" "${LIBUSB_ROOT}/libusb/os/events_posix.c"
        "${LIBUSB_ROOT}/libusb/os/threads_posix.c" "${LIBUSB_ROOT}/libusb/os/linux_netlink.c")
    target_include_directories(usb-1.0 PRIVATE "${LIBUSB_ROOT}/android" "${LIBUSB_ROOT}/libusb/os"
        PUBLIC "${LIBUSB_ROOT}/libusb")
    target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread)
    target_link_options(usb-1.0 PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_libraries(usb-1.0 PRIVATE log)
    add_library(virtualdap_usb SHARED usb/UsbIsoOutput.cpp usb/UsbOutputJni.cpp)
    target_link_libraries(virtualdap_usb PRIVATE usb-1.0)
    target_compile_options(virtualdap_usb PRIVATE -Wall -Wextra -Werror)
    target_link_options(virtualdap_usb PRIVATE "-Wl,-z,max-page-size=16384")
else()
    enable_testing()
    add_executable(dsd_filter_test tests/DsdFilterTest.cpp)
    target_link_libraries(dsd_filter_test PRIVATE dsd2pcm)
    target_compile_options(dsd_filter_test PRIVATE -Wall -Wextra -Werror)
    add_test(NAME dsd_filter_test COMMAND dsd_filter_test)
    find_package(Threads REQUIRED)
    add_executable(usb_iso_output_test tests/UsbIsoOutputTest.cpp usb/UsbIsoOutput.cpp)
    target_include_directories(usb_iso_output_test PRIVATE "${LIBUSB_ROOT}/libusb")
    target_link_libraries(usb_iso_output_test PRIVATE Threads::Threads)
    target_compile_options(usb_iso_output_test PRIVATE -Wall -Wextra -Werror)
    add_test(NAME usb_iso_output_test COMMAND usb_iso_output_test)
    set_tests_properties(usb_iso_output_test PROPERTIES TIMEOUT 20)
endif()
