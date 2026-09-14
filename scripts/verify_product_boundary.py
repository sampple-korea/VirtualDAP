#!/usr/bin/env python3
"""Verify both selected output transports and absence of retired full-OS/authentication code."""
import pathlib
import sys
import zipfile

apk = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    assert "assets/notices/libusb-LICENSE.txt" in names, "Missing dynamically linked USB library license"
    for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
        for library in ("libvirtualdap_usb.so", "libusb-1.0.so"):
            assert f"lib/{abi}/{library}" in names, f"Missing USB transport: {abi}/{library}"
    forbidden_types = (
        b"Lcom/virtualdap/host/guest/",
        b"Lcom/virtualdap/host/bridge/SharedRingBufferReader;",
        b"Lcom/virtualdap/host/bridge/RingBufferLayout;",
        b"Lcom/virtualdap/runtime/IGuestRuntime",
        b"Ltop/niunaijun/blackbox/fake/service/GmsProxy",
        b"Ltop/niunaijun/blackbox/fake/service/GoogleAccountManagerProxy",
        b"Ltop/niunaijun/blackbox/fake/service/AuthenticationProxy",
    )
    for name in names:
        if name.endswith(".dex"):
            dex = archive.read(name)
            for token in forbidden_types:
                assert token not in dex, f"Dormant code packaged in {name}: {token!r}"
print("APK boundary: direct USB packaged for all ABIs; retired full-OS/authentication code absent")
