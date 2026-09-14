#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
cmake="${CMAKE_BIN:-$sdk/cmake/3.22.1/bin/cmake}"
if [[ ! -x "$cmake" ]]; then cmake="$(command -v cmake)"; fi
if [[ ! -f "$repo_root/third_party/dsd2pcm/dsd2pcm.c" ]]; then
    echo "Initialize DSP source with git submodule update --init third_party/dsd2pcm" >&2
    exit 1
fi
if [[ ! -f "$repo_root/third_party/libsamplerate/src/samplerate.c" ]]; then
    echo "Initialize resampler source with git submodule update --init third_party/libsamplerate" >&2
    exit 1
fi
"$cmake" -S "$repo_root/app/src/main/cpp" -B "$repo_root/build/dsp-host" \
    -DCMAKE_BUILD_TYPE=Release
"$cmake" --build "$repo_root/build/dsp-host" --parallel 2
"${cmake%/cmake}/ctest" --test-dir "$repo_root/build/dsp-host" --output-on-failure
