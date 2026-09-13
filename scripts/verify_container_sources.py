#!/usr/bin/env python3
"""Regression checks on the exact prepared consumer engine (no device/root required)."""

import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
PREPARED = ROOT / "container_runtime/core/build/generated/upstream"
JAVA = PREPARED / "java/top/niunaijun/blackbox"


class PreparedContainerTests(unittest.TestCase):
    def test_no_external_crash_submission(self):
        sender = (JAVA / "utils/LogSender.java").read_text()
        self.assertNotIn("http", sender)
        self.assertNotIn("URLConnection", sender)
        self.assertIn("External log submission is disabled", sender)
        core = (JAVA / "BlackBoxCore.java").read_text()
        self.assertNotIn("SimpleCrashFix.installSimpleFix()", core)
        self.assertNotIn("setDefaultUncaughtExceptionHandler", core)

    def test_no_forced_microphone_unmute(self):
        proxy = (JAVA / "fake/service/IAudioServiceProxy.java").read_text()
        self.assertNotIn("args[0] = false", proxy)
        self.assertNotIn("args[1] = false", proxy)
        self.assertEqual(2, proxy.count("Preserve the caller's requested microphone mute state"))

    def test_permissions_and_services_are_normal_app_scoped(self):
        manifest = ET.parse(PREPARED / "AndroidManifest.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        permissions = [node.get(android + "name") for node in manifest.findall("uses-permission")]
        self.assertEqual(len(set(permissions)), len(permissions))
        for forbidden in ("MANAGE_EXTERNAL_STORAGE", "RECORD_AUDIO", "QUERY_ALL_PACKAGES",
                          "REQUEST_INSTALL_PACKAGES", "ACCESS_FINE_LOCATION"):
            self.assertNotIn("android.permission." + forbidden, permissions)
        for service in manifest.findall("application/service"):
            name = service.get(android + "name", "")
            self.assertNotIn("Daemon", name)
            self.assertNotIn("Vpn", name)

    def test_all_supported_native_abis_are_selected_from_device(self):
        native = (JAVA / "utils/NativeUtils.java").read_text()
        self.assertIn("SUPPORTED_64_BIT_ABIS", native)
        self.assertIn("SUPPORTED_32_BIT_ABIS", native)
        self.assertNotIn("if (out.length() == entry.getSize())", native)

    def test_exact_private_host_event_endpoint_is_routed(self):
        proxy = (JAVA / "fake/service/IActivityManagerProxy.java").read_text()
        self.assertIn('(BlackBoxCore.getHostPkg() + ".container.events").equals(auth)', proxy)
        self.assertNotIn('contains(".container.events")', proxy)

    def test_native_open_varargs_and_art_bounds(self):
        filesystem = (PREPARED / "cpp/Hook/FileSystemHook.cpp").read_text()
        self.assertNotIn("va_arg(args, mode_t)", filesystem)
        self.assertEqual(2, filesystem.count("(flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE"))
        jni = (PREPARED / "cpp/JniHook/JniHook.cpp").read_text()
        self.assertIn("art_method_size / sizeof(uintptr_t)", jni)
        self.assertIn("art_method_size > 256", jni)
        self.assertIn("GetArtMethod(env, clazz, method, is_static)", jni)

    def test_license_notices_are_bundled(self):
        for name in ("blackbox", "dobby"):
            self.assertGreater((PREPARED / ("assets/notices/" + name + "-LICENSE.txt")).stat().st_size, 500)

    def test_imported_apk_signatures_are_verified(self):
        parser = (JAVA / "utils/compat/PackageParserCompat.java").read_text()
        self.assertIn("collectCertificates(p, false)", parser)
        self.assertNotIn("collectCertificates(p, true)", parser)
        manager = (JAVA / "core/system/pm/BPackageManagerService.java").read_text()
        self.assertIn('result.installError("App import failed: " + t.getMessage())', manager)
        self.assertIn("Update signing certificate differs", manager)
        package_record = (JAVA / "core/system/pm/BPackage.java").read_text()
        self.assertNotIn("this.signatures = signingDetails.pastSigningCertificates", package_record)


if __name__ == "__main__":
    unittest.main()
