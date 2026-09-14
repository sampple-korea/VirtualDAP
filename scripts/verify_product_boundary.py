#!/usr/bin/env python3
"""Verify that the built product APK excludes dormant direct-output and full-OS code."""
import pathlib
import sys
import zipfile

apk = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    forbidden_libs = ("libvirtualdap_usb.so", "libusb-1.0.so")
    assert not any(name.endswith(forbidden_libs) for name in names), "Direct USB library packaged"
    forbidden_types = (
        b"Lcom/virtualdap/host/audio/usb/",
        b"Lcom/virtualdap/host/audio/RoutedAudioSink;",
        b"Lcom/virtualdap/host/audio/NativeDsdEncoder;",
        b"Lcom/virtualdap/host/audio/OutputFormatPlanner;",
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
print("APK boundary: official-output product; direct USB and full-OS code absent")
