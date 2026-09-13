#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_root="$repo_root/platform_runtime"
android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
ndk_root="${ANDROID_NDK_ROOT:-$android_sdk/ndk/28.2.13676358}"
toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64"

for aidl in IGuestRuntimeCallback.aidl IGuestRuntimeService.aidl; do
    cmp "$repo_root/app/src/main/aidl/com/virtualdap/runtime/$aidl" \
        "$runtime_root/aidl/com/virtualdap/runtime/$aidl"
done

for target in aarch64-linux-android33 x86_64-linux-android33; do
    compiler="$toolchain/bin/clang++"
    "$compiler" --target="$target" --sysroot="$toolchain/sysroot" -std=c++17 \
        -Wall -Wextra -Werror -fsyntax-only "$runtime_root/jni/BridgeProxy.cpp"
done

python3 - "$runtime_root/AndroidManifest.xml" \
    "$runtime_root/permissions/privapp-permissions-virtualdap-runtime.xml" \
    "$runtime_root/java/com/virtualdap/platformruntime/GuestRuntimeService.kt" \
    "$runtime_root/sepolicy/private/seapp_contexts" <<'PY'
import sys
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
manifest = ET.parse(sys.argv[1]).getroot()
permission = manifest.find("permission")
assert permission is not None
assert permission.attrib[android + "name"] == "com.virtualdap.host.permission.BIND_GUEST_RUNTIME"
assert permission.attrib[android + "protectionLevel"] == "signature"
service = manifest.find("./application/service")
assert service is not None and service.attrib[android + "exported"] == "true"
assert service.attrib[android + "permission"] == permission.attrib[android + "name"]
grants = ET.parse(sys.argv[2]).getroot()
names = {node.attrib["name"] for node in grants.findall(".//permission")}
assert names == {
    "android.permission.MANAGE_VIRTUAL_MACHINE",
    "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
}
source = open(sys.argv[3], encoding="utf-8").read()
for required in (
    ".setProtectedVm(false)",
    ".setUseSpeaker(false)",
    ".setUseMicrophone(false)",
    'addParam("androidboot.virtualdap.bridge_token=',
    'private const val PROTOCOL_VERSION = 3',
):
    assert required in source, required
seapp = open(sys.argv[4], encoding="utf-8").read().strip()
assert "name=com.virtualdap.platformruntime" in seapp
assert "domain=vmlauncher_app" in seapp
print("Platform runtime manifest and permissions: OK")
PY

echo "Platform AIDL parity and authenticated vsock proxy ABI builds: OK"
