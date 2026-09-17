#!/usr/bin/env python3
"""Regression checks on the exact prepared consumer engine (no device/root required)."""

import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET
from prepare_container_sources import reset_generated_output

ROOT = pathlib.Path(__file__).resolve().parents[1]
PREPARED = ROOT / "container_runtime/core/build/generated/upstream"
JAVA = PREPARED / "java/top/niunaijun/blackbox"
APP_JAVA = ROOT / "app/src/main/java/com/virtualdap/host"


class GeneratedOutputTests(unittest.TestCase):
    def test_regeneration_removes_obsolete_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "source"
            source.mkdir()
            output = root / "build/generated/upstream"
            output.mkdir(parents=True)
            (output / "stale.java").write_text("obsolete override")
            reset_generated_output(output, (source,))
            self.assertEqual([], list(output.iterdir()))
            self.assertTrue(source.is_dir())

    def test_source_overlap_is_rejected_before_removing_anything(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "build/generated/upstream"
            output.mkdir(parents=True)
            marker = output / "source.java"
            marker.write_text("keep")
            with self.assertRaises(ValueError):
                reset_generated_output(output, (output,))
            self.assertEqual("keep", marker.read_text())

    def test_symlink_output_is_rejected_without_touching_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "source"
            source.mkdir()
            (source / "keep").write_text("source")
            output = root / "build/generated/upstream"
            output.parent.mkdir(parents=True)
            output.symlink_to(source, target_is_directory=True)
            with self.assertRaises(ValueError):
                reset_generated_output(output, (source,))
            self.assertEqual("source", (source / "keep").read_text())


class PreparedContainerTests(unittest.TestCase):
    def test_external_service_binding_keeps_real_renderer_and_dispatcher(self):
        activity = (JAVA / "fake/service/IActivityManagerProxy.java").read_text()
        begin = activity.index("    public static Object BindServiceCommon")
        binding = activity[begin:activity.index('    @ProxyMethod("unbindService")', begin)]
        self.assertIn("BlackBoxCore.get().isInstalled(resolved.serviceInfo.packageName, userId)", binding)
        self.assertIn("new Intent((Intent) args[2])", binding)
        self.assertIn("ResolveInfo resolved = hostService ? null :", binding)
        self.assertIn("BlackBoxCore.getHostPkg().equals(intent.getComponent().getPackageName())", binding)
        self.assertIn("ServiceBindingArguments.prepare", binding)
        self.assertNotIn("_set_mConnection", binding)
        self.assertNotIn("args[6] = null", binding)
        self.assertEqual(1, binding.count("method.invoke(who, forwarded)"))
        self.assertIn("throw error.getCause()", binding)

    def test_removed_service_rebind_cannot_dereference_missing_application(self):
        dispatcher = (JAVA / "app/dispatcher/AppServiceDispatcher.java").read_text()
        begin = dispatcher.index("    private Service getOrCreateService")
        dispatch = dispatcher[begin:]
        self.assertIn("serviceInfo.packageName, proxyServiceRecord.mUserId", dispatch)
        self.assertLess(dispatch.index("!BlackBoxCore.get().isInstalled"), dispatch.index("findRecord(intent)"))
        activity = (JAVA / "app/BActivityThread.java").read_text()
        for start, end in (("    public Service createService", "    public JobService createJobService"),
                           ("    public JobService createJobService", "    public void bindApplication")):
            begin = activity.index(start)
            body = activity[begin:activity.index(end, begin)]
            self.assertLess(body.index("if (mBoundApplication == null)"),
                            body.index("BRLoadedApk.get(mBoundApplication.info)"))
            self.assertIn("return null;", body)

    def test_application_failure_is_not_a_fake_success_or_stranded_binder_wait(self):
        activity = (JAVA / "app/BActivityThread.java").read_text()
        begin = activity.index("    public void bindApplication(final String packageName")
        dispatch = activity[begin:activity.index("    private Object createBindApplicationData", begin)]
        self.assertIn("SynchronousDispatch.run", dispatch)
        self.assertIn("if (!BlackBoxCore.get().getHandler().post(command))", dispatch)
        self.assertNotIn("conditionVariable.block()", dispatch)
        self.assertNotIn("makeApplication(true, null)", activity)
        self.assertIn("initializationFailure.record(failure)", activity)
        self.assertIn("public boolean isInit() {\n        initializationFailure.check();", activity)
        begin = activity.index("    private void installProviders(Context context")
        installer = activity[begin:activity.index("    public Object getPackageInfo()", begin)]
        self.assertNotIn("catch (Throwable ignored)", installer)
        self.assertIn('"Unable to initialize declared provider " + providerInfo.name, failure', installer)
        self.assertIn('throw new IllegalStateException("Imported Application could not be created")', activity)

    def test_main_process_providers_are_not_initialized_in_every_package_process(self):
        activity = (JAVA / "app/BActivityThread.java").read_text()
        self.assertNotIn("providerInfo.processName.equals(context.getPackageName())", activity)
        self.assertEqual(2, activity.count("ProviderProcessPolicy.shouldInitialize(\n"
                                         "                            processName, providerInfo.processName, providerInfo.multiprocess)"))

    def test_wifi_returns_real_service_data_and_maps_only_known_caller_field(self):
        proxy = (JAVA / "fake/service/IWifiManagerProxy.java").read_text()
        self.assertIn("WifiCallerAttribution.invoke(getBase(), method, args", proxy)
        for fabricated in ("BRWifiInfo", "BRWifiSsid", "BlackBox_Wifi", "ac:62:5a:82:65:c4"):
            self.assertNotIn(fabricated, proxy)
        helper = (JAVA / "utils/compat/WifiCallerAttribution.java").read_text()
        self.assertIn('"getConnectionInfo".equals(method.getName())', helper)
        self.assertIn("guestPackage.equals(args[0])", helper)
        self.assertIn("forwarded = args.clone()", helper)
        self.assertIn("forwarded[0] = hostPackage", helper)
        self.assertNotIn("forwarded[1] =", helper)
        self.assertIn("throw failure.getCause()", helper)

    def test_imported_google_authorities_do_not_escape_to_host_account_providers(self):
        activity = (JAVA / "fake/service/IActivityManagerProxy.java").read_text()
        for authority in ("com.google.android.gms", "com.google.android.gsf", "com.android.vending"):
            self.assertNotIn('((String) auth).contains("' + authority + '")', activity)
        self.assertNotIn('auth.equals("com.google.android.gms.chimera")', activity)
        self.assertIn("BlackBoxCore.getBPackageManager()", activity)
        self.assertIn(".acquireContentProviderClient(providerInfo)", activity)
        native = (JAVA / "core/NativeCore.java").read_text()
        begin = native.index("    public static int getCallingUid(int origCallingUid)")
        caller = native[begin:native.index("    @Keep", begin)]
        self.assertIn("return origCallingUid;", caller)
        self.assertNotIn("getCallingBUid()", caller)
        self.assertNotIn("return Process.SYSTEM_UID", caller)

    def test_private_broadcast_routing_preserves_os_restrictions(self):
        activity = (JAVA / "fake/service/IActivityManagerProxy.java").read_text()
        begin = activity.index('    @ProxyMethod("broadcastIntent")')
        broadcast = activity[begin:activity.index('    @ProxyMethod("unregisterReceiver")', begin)]
        self.assertIn("ContainerBroadcastRouting.prepare", broadcast)
        self.assertIn("method.invoke(who, forwarded)", broadcast)
        self.assertIn("throw error.getCause()", broadcast)
        self.assertNotIn("instanceof String[]", broadcast)
        self.assertNotIn("args[i] = null", broadcast)
        self.assertNotIn("args[getPermissionIndex()] = null", activity)
        self.assertNotIn("args[permissionIndex] = null", activity)
        routing = (JAVA / "utils/compat/ContainerBroadcastRouting.java").read_text()
        self.assertIn("hostPackage.equals(shadow.getPackage())", routing)
        self.assertIn("shadow.getComponent() != null", routing)
        self.assertIn("requestedUser != guestUser", routing)
        self.assertIn("forwarded[forwarded.length - 1] = hostUser", routing)
        self.assertIn("if (shadow == null) return args", routing)

    def test_provider_adapter_changes_only_a_copy_of_the_actual_caller(self):
        proxy = (JAVA / "fake/service/context/providers/ContentProviderStub.java").read_text()
        self.assertIn("args[0] instanceof AttributionSource", proxy)
        self.assertIn('getDeclaredMethod("withPackageName", String.class)', proxy)
        self.assertIn("COPY_WITH_PACKAGE.invoke(original, BlackBoxCore.getHostPkg())", proxy)
        self.assertNotIn("new AttributionSource.Builder", proxy)
        self.assertIn("_set_uid(BlackBoxCore.getHostUid())", proxy)
        self.assertIn("forwarded = args.clone()", proxy)
        self.assertIn("return method.invoke(base, forwarded)", proxy)
        self.assertIn("throw error.getCause()", proxy)
        for fabricated in ("getSafeDefaultValue", "synthetic", "createSamsungAdult", "instanceof String",
                           "fixAttributionSourceInBundle", "fixAttributionSourceInArgs", "return null"):
            self.assertNotIn(fabricated, proxy)

    def test_connectivity_preserves_real_network_state_and_errors(self):
        proxy = (JAVA / "fake/service/IConnectivityManagerProxy.java").read_text()
        self.assertIn('case "requestNetwork": caller = 8;', proxy)
        self.assertIn('case "listenForNetwork": caller = 4;', proxy)
        self.assertIn("guest.equals(args[caller])", proxy)
        self.assertIn("forwarded = args.clone()", proxy)
        self.assertIn("forwarded[caller] = BlackBoxCore.getHostPkg()", proxy)
        self.assertIn("return method.invoke(getBase(), forwarded)", proxy)
        self.assertIn("throw error.getCause()", proxy)
        for fabricated in ("createNetwork", "8.8.8.8", "setDetailedState", "addCapability",
                           "return true", "createMock", "@ScanClass"):
            self.assertNotIn(fabricated, proxy)

    def test_media_controllers_preserve_real_binder_and_attribute_only_caller_fields(self):
        helper = (JAVA / "utils/compat/MediaControllerAttribution.java").read_text()
        self.assertIn('getDeclaredField("CREATOR")', helper)
        self.assertIn('getDeclaredField("mBinder")', helper)
        self.assertIn('"getController".equals(name)', helper)
        self.assertIn("guest.equals(args[0])", helper)
        self.assertIn("forwarded = args.clone()", helper)
        self.assertIn("method.invoke(target, forwarded)", helper)
        self.assertIn("throw error.getCause()", helper)
        self.assertNotIn("new Binder", helper)
        self.assertNotIn("return true", helper)

    def test_authenticator_discovery_does_not_require_an_existing_account(self):
        service = (JAVA / "core/system/accounts/BAccountManagerService.java").read_text()
        block = service.split("public AuthenticatorDescription[] getAuthenticatorTypes(int userId)", 1)[1]
        block = block.split("@Override", 1)[0]
        self.assertIn("queryAuthenticators(userId)", block)
        self.assertIn("info.desc", block)
        self.assertNotIn("getUserAccounts", block)
        self.assertNotIn("USER_ALL", block)
        self.assertNotIn("new AuthenticatorDescription(", block)

    def test_authenticator_sessions_resolve_the_actual_users_services(self):
        service = (JAVA / "core/system/accounts/BAccountManagerService.java").read_text()
        self.assertNotIn("mAuthenticatorCache", service)
        self.assertNotIn("loadAuthenticatorCache", service)
        self.assertIn("findAuthenticator(account.type, userId)", service)
        self.assertIn("findAuthenticator(authenticatorType, mAccounts.userId)", service)
        self.assertIn("bUserAccounts.userId = userId", service)
        query = service.split("private Map<String, AuthenticatorInfo> queryAuthenticators", 1)[1]
        query = query.split("private void generateServicesMap", 1)[0]
        self.assertIn("PackageManager.GET_META_DATA, userId", query)
        self.assertNotIn("USER_ALL", query)
        parser = service.split("private void generateServicesMap", 1)[1].split("private abstract class Session", 1)[0]
        self.assertIn("finally {\n                    parser.close();", parser)

    def test_authenticator_timeouts_do_not_grant_system_account_permission(self):
        self.assertFalse((JAVA / "core/AuthenticatorServiceBridge.java").exists())
        self.assertFalse((JAVA / "fake/service/IPermissionCheckerProxy.java").exists())
        service = (JAVA / "core/system/accounts/BAccountManagerService.java").read_text()
        self.assertIn("mHandler.postDelayed(mTimeout, 30_000L)", service)
        self.assertIn("mHandler.postDelayed(mTimeout, 10 * 60_000L)", service)
        self.assertIn("mHandler.removeCallbacks(mTimeout)", service)
        self.assertIn("mBound = mContext.bindService", service)
        activity = (JAVA / "fake/service/IActivityManagerProxy.java").read_text()
        block = activity.split("if (permission.equals(Manifest.permission.ACCOUNT_MANAGER))", 1)[1]
        block = block.split("if (permission.equals(Manifest.permission.SEND_SMS))", 1)[0]
        self.assertIn("return method.invoke(who, args)", block)
        self.assertNotIn("PERMISSION_GRANTED", block)

    def test_private_authenticator_adapter_does_not_replace_system_permissions(self):
        adapter = (JAVA / "core/LocalAuthenticatorTransport.java").read_text()
        self.assertIn("Binder.getCallingUid() != BlackBoxCore.getHostUid()", adapter)
        self.assertIn("userId != BActivityThread.getUserId()", adapter)
        self.assertIn("owner.getIBinder() != current", adapter)
        self.assertIn("if (!(root instanceof Binder)) return null", adapter)
        self.assertIn("seen.size() > 32", adapter)
        self.assertIn("found != null && found != owner", adapter)
        self.assertIn("AccountAuthenticatorResponse.CREATOR.createFromParcel(parcel)", adapter)
        self.assertIn("target.addAccount(callback", adapter)
        self.assertIn("if (result != null) response.onResult(result)", adapter)
        for forbidden in ("PERMISSION_GRANTED", "setCallingUid", "getAccountsByType", "getPassword", "setAuthToken"):
            self.assertNotIn(forbidden, adapter)

    def test_each_music_client_declares_its_control_process_dependency(self):
        runtime = (APP_JAVA / "container/ContainerRuntime.kt").read_text()
        self.assertIn("if (core.isBlackProcess) bindControl()", runtime)
        self.assertIn("if (BlackBoxCore.get().isMainProcess) initializeControl()", runtime)
        self.assertIn("ComponentName(BlackBoxCore.getHostPkg(), ContainerControlService::class.java.name)", runtime)
        self.assertIn("controlConnection, Context.BIND_AUTO_CREATE", runtime)

    def test_failed_process_startup_rolls_back_and_releases_waiters(self):
        process = (JAVA / "core/system/BProcessManagerService.java").read_text()
        self.assertIn("if (init == null) return false", process)
        self.assertIn("InitializationAttempt.run", process)
        self.assertIn("records.remove(processName, candidate)", process)
        self.assertIn("mPidsSelfLocked.remove(candidate)", process)
        self.assertIn("candidate.initLock.open()", process)
        self.assertIn("process.remove(record.processName, record)", process)
        self.assertNotIn("app.initLock.block()", process)

    def test_current_android_new_intents_use_activity_record(self):
        thread = (JAVA / "app/BActivityThread.java").read_text()
        block = thread.split("public void handleNewIntent(", 1)[1].split("public void scheduleReceiver(", 1)[0]
        self.assertIn("Build.VERSION.SDK_INT >= 31", block)
        self.assertIn("NewIntentCompat.deliver(", block)
        helper = (JAVA / "utils/compat/NewIntentCompat.java").read_text()
        self.assertIn("activities.get(token)", helper)
        self.assertIn('getDeclaredMethod("handleNewIntent", recordClass, List.class)', helper)
        self.assertIn("method.invoke(thread, record, intents)", helper)
        self.assertIn("throw new IllegalStateException", helper)

    def test_no_fabricated_service_identity_or_authentication(self):
        hooks = (JAVA / "fake/hook/HookManager.java").read_text()
        for name in ("GmsProxy", "GoogleAccountManagerProxy", "AuthenticationProxy"):
            self.assertNotIn(name, hooks)
            self.assertFalse((JAVA / f"fake/service/{name}.java").exists())
        self.assertIn("new IAccountManagerProxy()", hooks)
        proxy = (JAVA / "fake/service/IPackageManagerProxy.java").read_text()
        block = proxy.split('public static class GetPackageInfo extends MethodHook {', 1)[1]
        block = block.split('@ProxyMethod("getPackageUid")', 1)[0]
        self.assertIn("getPackageInfo(\n                    packageName, flags", block)
        self.assertIn("if (installed != null) return installed", block)
        self.assertIn("return method.invoke(who, args)", block)
        self.assertIn("return null", block)
        for forbidden in ("createFake", "attachSigningInfo", "REQUESTED_PERMISSION_GRANTED", "catch"):
            self.assertNotIn(forbidden, block)

    def test_media_routes_use_only_real_caller_attribution(self):
        proxy = (JAVA / "fake/service/IMediaRouterServiceProxy.java").read_text()
        block = proxy.split('public static class GetSystemRoutes extends MethodHook {', 1)[1]
        block = block.split('@ProxyMethod("registerClientAsUser")', 1)[0]
        self.assertIn('args != null && args.length > 0', block)
        self.assertIn('guest.equals(args[0])', block)
        self.assertIn('args[0] = top.niunaijun.blackbox.BlackBoxCore.getHostPkg()', block)
        self.assertNotIn('args[1] =', block)
        self.assertIn('return method.invoke(who, args)', block)
        self.assertNotIn('catch', block)

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

    def test_media_service_queries_use_real_container_records(self):
        proxy = (JAVA / "fake/service/IPackageManagerProxy.java").read_text()
        query = (JAVA / "fake/service/ContainerServiceQueryHook.java").read_text()
        self.assertIn("addMethodHook(new ContainerServiceQueryHook())", proxy)
        self.assertIn(".queryIntentServices(intent, flags, user)", query)
        self.assertIn("MethodParameterUtils.toInt(args[2])", query)
        self.assertIn("if (!containerTarget)", query)
        self.assertIn("method.invoke(who, args)", query)
        self.assertNotIn("new ResolveInfo", query)

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

    def test_reported_signing_identity_comes_from_the_installed_archive(self):
        compat = (JAVA / "core/system/pm/PackageManagerCompat.java").read_text()
        self.assertIn("InstalledPackageSigning.populate(", compat)
        self.assertIn("p.baseCodePath, pi, flags", compat)
        self.assertNotIn("getPackageInfo(p.packageName, flags)", compat)
        self.assertNotIn("PackageParser.SigningDetails.UNKNOWN", compat)
        helper = (JAVA / "core/system/pm/InstalledPackageSigning.java").read_text()
        self.assertIn("getPackageArchiveInfo(archivePath, requested)", helper)
        self.assertIn("target.packageName.equals(archive.packageName)", helper)
        self.assertIn("archive.signatures.clone()", helper)
        self.assertIn("new SigningInfo(archive.signingInfo)", helper)
        self.assertNotIn("catch", helper)

    def test_split_clusters_and_atomic_install_are_used(self):
        manager = (JAVA / "core/system/pm/BPackageManagerService.java").read_text()
        self.assertIn("ApkArchiveType.isBundle(file)", manager)
        self.assertNotIn("apkCount >= 2", manager)
        self.assertIn("ApkBundle.plan(files)", manager)
        self.assertIn("parserApk(parseInput.getAbsolutePath())", manager)
        self.assertNotIn("selectSplitApks(", manager)
        creator = (JAVA / "core/system/pm/installer/CreatePackageExecutor.java").read_text()
        self.assertNotIn("deleteDir", creator)
        copier = (JAVA / "core/system/pm/installer/CopyExecutor.java").read_text()
        self.assertIn("AtomicPackagePublisher.publish", copier)
        self.assertNotIn("catch (Throwable ignored)", copier)

    def test_device_targeted_apks_are_normalized_before_container_install(self):
        runtime = (APP_JAVA / "container/ContainerRuntime.kt").read_text()
        normalizer = (APP_JAVA / "container/ApkArchiveNormalizer.kt").read_text()
        self.assertIn("ApkArchiveNormalizer.normalizeIfArchive", runtime)
        self.assertIn("Build.SUPPORTED_64_BIT_ABIS", runtime)
        self.assertNotIn("private fun validateArchive", runtime)
        self.assertIn('entry.name == "toc.pb"', normalizer)
        self.assertIn("BundletoolToc.select", normalizer)
        self.assertIn("matchesMultiAbi", normalizer)
        self.assertIn("matchesDensity", normalizer)
        self.assertIn("MAX_ARCHIVE_ENTRIES", normalizer)
        self.assertIn("JSON-only bundletool archives are not supported", normalizer)


if __name__ == "__main__":
    unittest.main()
