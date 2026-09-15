#!/usr/bin/env python3
"""Prepare pinned BlackBox sources with the narrow VirtualDAP integration changes."""

import argparse
import pathlib
import re
import shutil
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise ValueError("Pinned container source no longer matches a required adaptation")
    return text.replace(before, after, 1)


def prepare(upstream, dobby, overrides, output):
    if output.name != "upstream" or output.parent.name != "generated":
        raise ValueError("Output must be the dedicated build/generated/upstream directory")
    output.mkdir(parents=True, exist_ok=True)
    for name in ("java", "aidl", "res", "assets", "cpp"):
        source = upstream / name
        if source.is_dir():
            shutil.copytree(source, output / name, dirs_exist_ok=True)

    elf_header = output / "cpp/Utils/elf_util.h"
    elf_header.write_text(
        replace_once(elf_header.read_text(encoding="utf-8"), "uint32_t h = 0, g;", "uint32_t h = 0, g = 0;"),
        encoding="utf-8",
    )
    filesystem_hook = output / "cpp/Hook/FileSystemHook.cpp"
    content = filesystem_hook.read_text(encoding="utf-8")
    old_mode = (
        "    va_list args;\n    va_start(args, flags);\n"
        "    mode_t mode = va_arg(args, mode_t);\n    va_end(args);"
    )
    if content.count(old_mode) != 2:
        raise ValueError("Unexpected native open-hook layout")
    content = content.replace(
        old_mode,
        "    mode_t mode = 0;\n"
        "    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {\n"
        "        va_list args;\n        va_start(args, flags);\n"
        "        mode = static_cast<mode_t>(va_arg(args, unsigned int));\n"
        "        va_end(args);\n    }",
    )
    filesystem_hook.write_text(content, encoding="utf-8")
    native_options = output / "cpp/Utils/AntiDetection.cpp"
    content = native_options.read_text(encoding="utf-8")
    content = content.replace("va_arg(args, mode_t)", "static_cast<mode_t>(va_arg(args, unsigned int))")
    content = content.replace("if (flags & O_CREAT) {", "if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {")
    native_options.write_text(content, encoding="utf-8")
    jni_hook = output / "cpp/JniHook/JniHook.cpp"
    content = jni_hook.read_text(encoding="utf-8")
    content = replace_once(
        content, "static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId) {",
        "static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId, bool is_static = true) {",
    )
    content = replace_once(content, "env->ToReflectedMethod(clazz, methodId, true)",
                           "env->ToReflectedMethod(clazz, methodId, is_static)")
    content = replace_once(content, "GetArtMethod(env, clazz, method));",
                           "GetArtMethod(env, clazz, method, is_static));")
    content = replace_once(
        content, "    int i = 0;\n",
        "    if (HookEnv.art_method_size < sizeof(uintptr_t) || HookEnv.art_method_size > 256 ||\n"
        "        HookEnv.art_field_size < sizeof(uint32_t) || HookEnv.art_field_size > 256) {\n"
        '        ALOGE("Unsupported ART member layout");\n'
        "        return;\n"
        "    }\n"
        "    int i = 0;\n",
    )
    content = replace_once(content, "for (i = 0; i < HookEnv.art_method_size; ++i)",
                           "for (i = 0; i < HookEnv.art_method_size / sizeof(uintptr_t); ++i)")
    content = replace_once(
        content, 'if(i == HookEnv.art_method_size){\n        ALOGE("init jni hook error. art_method_native_offset not found!");',
        'if(i == HookEnv.art_method_size / sizeof(uintptr_t)){\n        ALOGE("init jni hook error. art_method_native_offset not found!");',
    )
    content = replace_once(content, "for (i = 1; i < HookEnv.art_method_size; ++i)",
                           "for (i = 1; i < HookEnv.art_method_size / sizeof(uint32_t); ++i)")
    content = replace_once(content, "if(i == HookEnv.art_method_size){",
                           "if(i == HookEnv.art_method_size / sizeof(uint32_t)){")
    content = replace_once(content, "i < HookEnv.art_field_size; ++i", "i < HookEnv.art_field_size / sizeof(uint32_t); ++i")
    content = replace_once(content, "if(i == HookEnv.art_field_size){",
                           "if(i == HookEnv.art_field_size / sizeof(uint32_t)){")
    for diagnostic in (
        "art_method_flags_offset not found!",
        "art_field_flags_offset not found!",
    ):
        content = replace_once(
            content,
            'ALOGE("init jni hook error. %s");\n        return;' % diagnostic,
            'ALOGE("init jni hook error. %s");\n        HookEnv.art_method_native_offset = 0;\n        return;' % diagnostic,
        )
    content = replace_once(
        content,
        'HookEnv.method_utils_class = env->FindClass("top/niunaijun/jnihook/MethodUtils");',
        'HookEnv.method_utils_class = static_cast<jclass>(env->NewGlobalRef(\n'
        '        env->FindClass("top/niunaijun/jnihook/MethodUtils")));',
    )
    content = replace_once(
        content,
        "if (env->RegisterNatives(clazz, gMethods, 1) < 0) {\n",
        "if (env->RegisterNatives(clazz, gMethods, 1) < 0) {\n"
        "        *orig_fun = nullptr;\n"
        "        env->ExceptionClear();\n",
    )
    content = replace_once(
        content,
        "    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId));",
        "    if (HookEnv.art_method_flags_offset == 0) return;\n"
        "    jclass reflected_class = env->GetObjectClass(method);\n"
        '    jmethodID modifiers = env->GetMethodID(reflected_class, "getModifiers", "()I");\n'
        "    bool is_static = (env->CallIntMethod(method, modifiers) & 0x8) != 0;\n"
        "    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId, is_static));",
    )
    content = replace_once(
        content,
        "    char *artField = static_cast<char *>(GetFieldMethod(env, field));",
        "    if (HookEnv.art_field_flags_offset == 0) return;\n"
        "    char *artField = static_cast<char *>(GetFieldMethod(env, field));",
    )
    jni_hook.write_text(content, encoding="utf-8")
    dobby_output = output.parent / "dobby"
    if dobby_output.is_symlink():
        raise ValueError("Generated Dobby directory must not be a symlink")
    if dobby_output.exists():
        shutil.rmtree(dobby_output)
    shutil.copytree(dobby, dobby_output, ignore=shutil.ignore_patterns(".git", "build"))

    package = output / "java/top/niunaijun/blackbox"
    # Synthetic accounts/tokens and fabricated package metadata cannot provide real service
    # compatibility. Keep the ordinary account-service implementation and real package records.
    hooks = package / "fake/hook/HookManager.java"
    content = hooks.read_text(encoding="utf-8")
    for name in ("GmsProxy", "GoogleAccountManagerProxy", "AuthenticationProxy"):
        content = replace_once(content, f"import top.niunaijun.blackbox.fake.service.{name};\n", "")
        content = replace_once(content, f"            addInjector(new {name}());\n", "")
        (package / f"fake/service/{name}.java").unlink()
    hooks.write_text(content, encoding="utf-8")

    package_proxy = package / "fake/service/IPackageManagerProxy.java"
    content = package_proxy.read_text(encoding="utf-8")
    content = replace_once(content,
        "    protected void onBindMethod() {\n        super.onBindMethod();",
        "    protected void onBindMethod() {\n        super.onBindMethod();\n"
        "        addMethodHook(new ContainerServiceQueryHook());")
    start = content.index('    @ProxyMethod("getPackageInfo")')
    end = content.index('    @ProxyMethod("getPackageUid")', start)
    content = content[:start] + '''    @ProxyMethod("getPackageInfo")
    public static class GetPackageInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            PackageInfo installed = BlackBoxCore.getBPackageManager().getPackageInfo(
                    packageName, flags, BlackBoxCore.getUserId());
            if (installed != null) return installed;
            if (AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

''' + content[end:]
    package_proxy.write_text(content, encoding="utf-8")
    configuration = package / "app/configuration/ClientConfiguration.java"
    content = configuration.read_text(encoding="utf-8")
    content, count = re.subn(
        r'(public String getLogSenderChatId\(\)\s*\{\s*return )"[^"]*";',
        r'\1"";',
        content,
    )
    if count != 1:
        raise ValueError("Could not disable the upstream log destination")
    configuration.write_text(content, encoding="utf-8")

    core = package / "BlackBoxCore.java"
    content = core.read_text(encoding="utf-8")
    start = content.index("    static {")
    end = content.index("    private ProcessType", start)
    content = content[:start] + (
        "    // VirtualDAP preserves Android crash reporting; it does not install upstream\n"
        "    // global exception swallowing, stack filtering, or automatic crash submission.\n"
    ) + content[end:]
    content = replace_once(
        content, "SimpleCrashFix.installSimpleFix();",
        "// Global crash handlers are deliberately not installed.",
    )
    content = content.replace('Slog.d(TAG, "System hooks installed successfully");', "")
    core.write_text(content, encoding="utf-8")

    activity_thread = package / "app/BActivityThread.java"
    content = activity_thread.read_text(encoding="utf-8")
    content = replace_once(
        content,
        "            if (BRActivityThread.get(BlackBoxCore.mainThread())._check_performNewIntents(null, null) != null) {",
        "            if (Build.VERSION.SDK_INT >= 31) {\n"
        "                top.niunaijun.blackbox.utils.compat.NewIntentCompat.deliver(\n"
        "                        mainThread, token, Collections.singletonList(newIntent));\n"
        "            } else if (BRActivityThread.get(BlackBoxCore.mainThread())._check_performNewIntents(null, null) != null) {",
    )
    activity_thread.write_text(content, encoding="utf-8")

    activity_proxy = package / "fake/service/IActivityManagerProxy.java"
    content = activity_proxy.read_text(encoding="utf-8")
    content = replace_once(
        content, '                if ("media".equals(auth)) {',
        '                if ((BlackBoxCore.getHostPkg() + ".container.events").equals(auth)) {\n'
        '                    // Exact private host endpoint, never a wildcard provider bypass.\n'
        '                    content = method.invoke(who, args);\n'
        '                    if (content != null) ContentProviderDelegate.update(content, (String) auth);\n'
        '                    return content;\n'
        '                }\n\n'
        '                if ("media".equals(auth)) {',
    )
    activity_proxy.write_text(content, encoding="utf-8")
    provider_delegate = package / "fake/delegate/ContentProviderDelegate.java"
    content = provider_delegate.read_text(encoding="utf-8")
    content = replace_once(
        content, 'if ("settings".equals(auth)) {',
        'if ("settings".equals(auth) || (BlackBoxCore.getHostPkg() + ".container.events").equals(auth)) {',
    )
    provider_delegate.write_text(content, encoding="utf-8")

    parser_compat = package / "utils/compat/PackageParserCompat.java"
    content = parser_compat.read_text(encoding="utf-8")
    content = replace_once(
        content, "BRPackageParserPie.getWithException().collectCertificates(p, true);",
        "// The boolean means skipVerify, not collect-signatures. Imported APKs must be verified.\n"
        "            BRPackageParserPie.getWithException().collectCertificates(p, false);",
    )
    parser_compat.write_text(content, encoding="utf-8")

    manager = package / "core/system/pm/BPackageManagerService.java"
    content = manager.read_text(encoding="utf-8")
    content = replace_once(
        content, "        List<File> selectedSplits = null;",
        "        File parseInput = null;\n        String selectedAbi = null;",
    )
    begin = content.index("            if (isApksBundle(stagedFile)) {")
    end = content.index("            PackageInfo packageArchiveInfo =", begin)
    content = content[:begin] + (
        "            if (isApksBundle(stagedFile)) {\n"
        '                extractedDir = new File(BEnvironment.getCacheDir(), "apks_" + UUID.randomUUID());\n'
        "                BzFileUtils.mkdirs(extractedDir);\n"
        "                List<File> files = extractApksBundle(stagedFile, extractedDir);\n"
        "                top.niunaijun.blackbox.utils.SplitApkPlanner.Plan plan =\n"
        "                    top.niunaijun.blackbox.utils.ApkBundle.plan(files);\n"
        "                parseInput = top.niunaijun.blackbox.utils.ApkBundle.stage(\n"
        '                    plan, new File(extractedDir, "selected"));\n'
        '                apkFile = new File(parseInput, "base.apk");\n'
        "                selectedAbi = plan.abi;\n"
        "            } else {\n"
        "                apkFile = stagedFile;\n"
        "                parseInput = stagedFile;\n"
        "                selectedAbi = top.niunaijun.blackbox.utils.ApkBundle.plan(\n"
        "                    java.util.Collections.singletonList(apkFile)).abi;\n"
        "            }\n\n"
    ) + content[end:]
    content = replace_once(content, "PackageParser.Package aPackage = parserApk(apkFile.getAbsolutePath());",
                           "PackageParser.Package aPackage = parserApk(parseInput.getAbsolutePath());")
    begin = content.index("            if (selectedSplits != null && !selectedSplits.isEmpty()) {")
    end = content.index("            if (option.isFlag(InstallOption.FLAG_SYSTEM)) {", begin)
    content = content[:begin] + (
        "            // Cluster parsing preserves real split names, feature components and dependencies.\n"
        "            aPackage.applicationInfo.splitSourceDirs = aPackage.splitCodePaths;\n"
        "            aPackage.applicationInfo.splitPublicSourceDirs = aPackage.splitCodePaths;\n"
        "            aPackage.applicationInfo.splitNames = aPackage.splitNames;\n"
        "            black.android.content.pm.BRApplicationInfoL.get(aPackage.applicationInfo)\n"
        "                ._set_primaryCpuAbi(selectedAbi);\n\n"
    ) + content[end:]
    # Remove the obsolete filename heuristics entirely, not merely leave a second inactive selector.
    begin = content.index("    private static File pickBaseApk(")
    end = content.index("    private static String stripApkExtension(", begin)
    content = content[:begin] + content[end:]
    content = replace_once(
        content,
        "            BPackageSettings bPackageSettings = mSettings.getPackageLPw(aPackage.packageName, aPackage, option);",
        "            BPackageSettings existing = mPackages.get(aPackage.packageName);\n"
        "            android.content.pm.Signature[] incoming = aPackage.mSigningDetails == null\n"
        "                    ? null : aPackage.mSigningDetails.signatures;\n"
        "            if (incoming == null || incoming.length == 0) {\n"
        '                return result.installError("APK has no verified signing certificate");\n'
        "            }\n"
        "            if (existing != null && (existing.pkg.mSignatures == null ||\n"
        "                    !new java.util.HashSet<>(java.util.Arrays.asList(existing.pkg.mSignatures))\n"
        "                        .equals(new java.util.HashSet<>(java.util.Arrays.asList(incoming))))) {\n"
        '                return result.installError("Update signing certificate differs from the installed app");\n'
        "            }\n"
        "            BPackageSettings bPackageSettings = mSettings.getPackageLPw(aPackage.packageName, aPackage, option);",
    )
    content = replace_once(
        content,
        "        } catch (Throwable t) {\n            t.printStackTrace();\n"
        "        } finally {\n            if (stagedFile != null",
        "        } catch (Throwable t) {\n            t.printStackTrace();\n"
        '            result.installError("App import failed: " + t.getMessage());\n'
        "        } finally {\n            if (stagedFile != null",
    )
    content = replace_once(
        content, "            mComponentResolver.removeAllComponents(bPackageSettings.pkg);",
        "            if (existing != null) mComponentResolver.removeAllComponents(existing.pkg);",
    )
    manager.write_text(content, encoding="utf-8")
    create_user = package / "core/system/pm/installer/CreateUserExecutor.java"
    content = create_user.read_text(encoding="utf-8")
    content = replace_once(
        content, "BzFileUtils.deleteDir(BEnvironment.getDataLibDir(packageName, userId));",
        "// Installation never removes an existing user's data or library directory.",
    )
    create_user.write_text(content, encoding="utf-8")
    pm_compat = package / "core/system/pm/PackageManagerCompat.java"
    content = pm_compat.read_text(encoding="utf-8")
    content = replace_once(
        content, "BRApplicationInfoL.get(ai)._set_primaryCpuAbi(Build.CPU_ABI);",
        "if (BRApplicationInfoL.get(ai).primaryCpuAbi() == null) {\n"
        "                BRApplicationInfoL.get(ai)._set_primaryCpuAbi(Build.SUPPORTED_ABIS[0]);\n"
        "            }",
    )
    pm_compat.write_text(content, encoding="utf-8")
    package_record = package / "core/system/pm/BPackage.java"
    content = package_record.read_text(encoding="utf-8")
    content = replace_once(
        content,
        "            if (signingDetails.pastSigningCertificates == null) {\n"
        "                this.signatures = signingDetails.signatures;\n"
        "            } else {\n"
        "                this.signatures = signingDetails.pastSigningCertificates;\n"
        "            }",
        "            // Current signers must not be replaced by historical rotation certificates.\n"
        "            this.signatures = signingDetails.signatures;",
    )
    package_record.write_text(content, encoding="utf-8")

    # Exclude all upstream network implementation and credentials from compiled source.
    (package / "utils/LogSender.java").write_text(
        """package top.niunaijun.blackbox.utils;
import java.io.File;
/** VirtualDAP keeps diagnostics local. External log submission is not part of the product. */
public final class LogSender {
    private LogSender() {}
    public static String send(String destination, File log, String caption) {
        return "External log submission is disabled in VirtualDAP";
    }
}
""", encoding="utf-8")

    audio_proxy = package / "fake/service/IAudioServiceProxy.java"
    content = audio_proxy.read_text(encoding="utf-8")
    for class_name in ("IsMicrophoneMuted", "IsMicrophoneMutedForUser"):
        pattern = r"(public static class " + class_name + r" extends MethodHook \{.*?\n    \})"
        match = re.search(pattern, content, flags=re.S)
        if match is None:
            raise ValueError("Missing upstream microphone query hook")
        block = replace_once(match.group(0), "return false;", "return method.invoke(who, args);")
        content = content[:match.start()] + block + content[match.end():]
    for argument in (0, 1):
        content = replace_once(content, "args[%d] = false;" % argument,
                               "// Preserve the caller's requested microphone mute state.")
    content = content.replace("forcing unmute", "preserving microphone state")
    content = content.replace("isMicrophoneMuted returning false", "querying microphone state")
    content = content.replace("isMicrophoneMutedForUser returning false", "querying microphone state")
    audio_proxy.write_text(content, encoding="utf-8")

    media_router = package / "fake/service/IMediaRouterServiceProxy.java"
    content = media_router.read_text(encoding="utf-8")
    content = replace_once(content, '    @ProxyMethod("registerClientAsUser")', '''    @ProxyMethod("getSystemRoutes")
    public static class GetSystemRoutes extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Android 16 added caller attribution; Android 14's method has no arguments.
            // Attribute only this process's own call to its real host UID/package. Do not
            // alter proxy-router flags, target packages, permissions or returned routes.
            String guest = top.niunaijun.blackbox.app.BActivityThread.getAppPackageName();
            if (args != null && args.length > 0 && guest != null && guest.equals(args[0])) {
                args[0] = top.niunaijun.blackbox.BlackBoxCore.getHostPkg();
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("registerClientAsUser")''')
    media_router.write_text(content, encoding="utf-8")

    compat = package / "utils/compat/BuildCompat.java"
    content = compat.read_text(encoding="utf-8")
    content = replace_once(
        content,
        "Build.VERSION.SDK_INT >= 33 || (Build.VERSION.SDK_INT >= 32 && Build.VERSION.PREVIEW_SDK_INT == 1)",
        "Build.VERSION.SDK_INT >= 34 || (Build.VERSION.SDK_INT == 33 && Build.VERSION.PREVIEW_SDK_INT > 0)",
    )
    content = replace_once(
        content,
        "Build.VERSION.SDK_INT >= 32 || (Build.VERSION.SDK_INT >= 31 && Build.VERSION.PREVIEW_SDK_INT == 1)",
        "Build.VERSION.SDK_INT >= 33 || (Build.VERSION.SDK_INT == 32 && Build.VERSION.PREVIEW_SDK_INT > 0)",
    )
    compat.write_text(content, encoding="utf-8")
    fingerprint = package / "fake/service/IFingerprintManagerProxy.java"
    fingerprint.write_text(
        fingerprint.read_text(encoding="utf-8").replace("Context.FINGERPRINT_SERVICE", '"fingerprint"'),
        encoding="utf-8",
    )
    shutil.copytree(overrides, output / "java", dirs_exist_ok=True)

    ET.register_namespace("android", ANDROID[1:-1])
    ET.register_namespace("tools", "http://schemas.android.com/tools")
    manifest = ET.parse(upstream / "AndroidManifest.xml")
    root = manifest.getroot()
    allowed = {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.WAKE_LOCK",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.POST_NOTIFICATIONS",
    }
    seen = set()
    for permission in list(root.findall("uses-permission")):
        name = permission.get(ANDROID + "name")
        if name not in allowed or name in seen:
            root.remove(permission)
        else:
            seen.add(name)
    application = root.find("application")
    # The host owns the foreground audio service. No VPN or special-use daemon is required.
    for service in list(application.findall("service")):
        name = service.get(ANDROID + "name", "")
        if "DaemonService" in name or name.endswith("ProxyVpnService"):
            application.remove(service)
    manifest.write(output / "AndroidManifest.xml", encoding="utf-8", xml_declaration=True)
    notices = output / "assets/notices"
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(upstream.parents[2] / "LICENSE", notices / "blackbox-LICENSE.txt")
    shutil.copyfile(dobby / "LICENSE", notices / "dobby-LICENSE.txt")
    print("Prepared container sources: local diagnostics, preserved microphone state, minimal host permissions")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=pathlib.Path, required=True)
    parser.add_argument("--dobby", type=pathlib.Path, required=True)
    parser.add_argument("--overrides", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    prepare(args.upstream.resolve(), args.dobby.resolve(), args.overrides.resolve(), args.output.resolve())


if __name__ == "__main__":
    main()
