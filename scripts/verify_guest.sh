#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
guest_root="$repo_root/guest_os"
android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
cmake_bin="${CMAKE_BIN:-$android_sdk/cmake/3.22.1/bin/cmake}"
ndk_root="${ANDROID_NDK_ROOT:-$android_sdk/ndk/28.2.13676358}"

if [[ ! -x "$cmake_bin" ]]; then
    cmake_bin="$(command -v cmake)"
fi
if [[ ! -x "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang++" ]]; then
    echo "Android NDK 28.2.13676358 not found under $ndk_root" >&2
    exit 1
fi

"$cmake_bin" -S "$guest_root" -B "$guest_root/build-host" -DCMAKE_BUILD_TYPE=RelWithDebInfo
"$cmake_bin" --build "$guest_root/build-host" --parallel
"${cmake_bin%/cmake}/ctest" --test-dir "$guest_root/build-host" --output-on-failure

for abi in arm64-v8a x86_64; do
    build_dir="$guest_root/build-android-$abi"
    "$cmake_bin" -S "$guest_root" -B "$build_dir" \
        -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-33 \
        -DCMAKE_BUILD_TYPE=Release
    "$cmake_bin" --build "$build_dir" --parallel
done

aosp_headers="${VIRTUALDAP_AOSP_HEADERS:-}"
cleanup_headers=false
if [[ -z "$aosp_headers" ]]; then
    aosp_headers="$(mktemp -d /tmp/virtualdap-aosp13.XXXXXX)"
    cleanup_headers=true
    trap 'if [[ "$cleanup_headers" == true ]]; then rm -rf -- "$aosp_headers"; fi' EXIT
    while read -r project destination; do
        git clone --quiet --depth=1 --branch android-13.0.0_r83 \
            "https://android.googlesource.com/platform/$project" "$aosp_headers/$destination"
    done <<'EOF'
hardware/libhardware libhardware
system/media media
system/core core
system/logging logging
EOF
fi

clang="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang++"
"$clang" -std=c++17 -fsyntax-only -Wall -Wextra -Werror \
    -I"$guest_root" \
    -I"$guest_root/include" \
    -I"$aosp_headers/libhardware/include" \
    -I"$aosp_headers/media/audio/include" \
    -I"$aosp_headers/core/libcutils/include" \
    -I"$aosp_headers/core/libsystem/include" \
    -I"$aosp_headers/logging/liblog/include" \
    "$guest_root/VirtualAudioHAL.cpp"

python3 - "$guest_root/audio_policy_configuration.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
module = root.find("./modules/module")
assert module is not None and module.attrib["halVersion"] == "7.1"
profiles = root.findall(".//profile")
assert any(p.attrib.get("format") == "AUDIO_FORMAT_PCM_24_BIT_PACKED" for p in profiles)
assert not any("COMPRESS_OFFLOAD" in value for node in root.iter() for value in node.attrib.values())
print("Android 13 audio policy: OK")
PY

echo "Guest transport, Android ABI builds, HAL headers, and audio policy: OK"
