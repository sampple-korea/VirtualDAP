cmake_minimum_required(VERSION 3.22)
project(VirtualDapMusicFixture LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(music_fixture_audio SHARED NativeAudioFixture.cpp)
target_compile_options(music_fixture_audio PRIVATE -Wall -Wextra -Werror)
target_link_options(music_fixture_audio PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(music_fixture_audio PRIVATE aaudio log)
