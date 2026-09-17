package com.virtualdap.host

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualdap.host.container.ContainerPhase
import com.virtualdap.host.container.ContainerRuntime
import com.virtualdap.host.service.PipelineStore
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.model.PipelinePhase
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import top.niunaijun.blackbox.core.AtomicPackagePublisher
import org.junit.Test
import org.junit.runner.RunWith

/** Installs an actual, independently packaged APK into the ordinary-UID container. */
@RunWith(AndroidJUnit4::class)
class ContainerInstrumentedTest {
    @Test fun successfulWaitDoesNotRepeatItsAction() {
        var calls = 0
        await("single successful action") { ++calls; true }
        assertEquals("A successful click must not be dispatched a second time", 1, calls)
    }

    @Test fun fixtureInstallsAndStartsInsideMusicSpace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Consumer tests must run as an ordinary app UID", android.os.Process.myUid() >= 10_000)
        verifyAtomicPublication(context.cacheDir)
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n com.virtualdap.host/.MainActivity",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        await("container initialization") { ContainerRuntime.state.value.phase != ContainerPhase.INITIALIZING }
        assertEquals(ContainerRuntime.state.value.toString(), ContainerPhase.READY, ContainerRuntime.state.value.phase)
        val externalName = InstrumentationRegistry.getArguments().getString("externalApk")
        val hostPackage = InstrumentationRegistry.getArguments().getString("hostPackage")
        if (externalName != null || hostPackage != null) {
            val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("externalPackage"))
            if (hostPackage != null) {
                assertTrue("Host app should be discoverable",
                    (ContainerRuntime.state.value.hostApplications + ContainerRuntime.state.value.hostServices)
                        .any { it.packageName == hostPackage })
                ContainerRuntime.importHostApp(hostPackage)
            } else {
                requireNotNull(externalName)
                require(externalName.matches(Regex("[A-Za-z0-9_.-]+"))) { "Use a single private cache filename" }
                val file = File(context.cacheDir, externalName)
                require(file.isFile) { "Copy the APK/APKS into the debug host's private cache first" }
                ContainerRuntime.install(Uri.fromFile(file))
            }
            await("external APK import") { ContainerRuntime.state.value.phase != ContainerPhase.INSTALLING }
            assertEquals(ContainerRuntime.state.value.toString(), null, ContainerRuntime.state.value.lastError)
            assertTrue(ContainerRuntime.state.value.toString(),
                ContainerRuntime.state.value.applications.any { it.packageName == expected })
            val originalArchive = if (hostPackage != null) {
                File(context.packageManager.getApplicationInfo(hostPackage, 0).sourceDir)
            } else File(context.cacheDir, requireNotNull(externalName))
            // APK-set signing is checked during split planning; a standalone base APK also
            // permits a direct comparison with Android's own current/history metadata.
            if (ZipFile(originalArchive).use { it.getEntry("AndroidManifest.xml") != null }) {
                verifyInstalledSigning(originalArchive, expected)
            }
            if (InstrumentationRegistry.getArguments().getString("importOnly") == "true") return
            val inspectionSeconds = InstrumentationRegistry.getArguments()
                .getString("externalInspectionSeconds")?.let {
                    requireNotNull(it.toIntOrNull()) { "Inspection duration must be a whole number of seconds" }
                } ?: 0
            require(inspectionSeconds in 0..180) { "Inspection duration must be between 0 and 180 seconds" }
            val externalWav = InstrumentationRegistry.getArguments().getString("externalWav") == "true"
            val evidence = ExternalWavEvidence()
            CaptureProbe(if (externalWav) evidence::observe else null).use { probe ->
                probe.start()
                ContainerRuntime.launch(expected)
                await("external Application.onCreate (not a playback certification)") {
                    ContainerRuntime.state.value.applications.any { it.packageName == expected && it.lastStartedPid != null }
                }
                if (externalWav) {
                    evidence.verifyPlayback(expected)
                }
                InstrumentationRegistry.getArguments().getString("externalUiText")?.let { expectedText ->
                    require(expectedText.isNotBlank() && expectedText.length <= 200)
                    val ui = instrumentation.uiAutomation
                    var previousDiagnostic: String? = null
                    fun visibleAppScreen(): Boolean {
                        val foreground = ContainerRuntime.state.value.foregroundActivity ?: return false
                        if (foreground.packageName != expected) return false
                        val manager = context.getSystemService(android.app.ActivityManager::class.java)
                        if (manager.runningAppProcesses.orEmpty().none { it.pid == foreground.pid }) return false
                        val root = ui.rootInActiveWindow ?: return false
                        val candidates = root.findAccessibilityNodeInfosByText(expectedText)
                        // Log only the requested label's structural match, never account text or
                        // arbitrary contents of the screen. Container windows may report host IDs.
                        val diagnostic = "rootPackage=${root.packageName}; matches=" + candidates.joinToString {
                            "package=${it.packageName}, visible=${it.isVisibleToUser}, " +
                                "exact=${!it.isPassword && it.text?.toString() == expectedText}"
                        }
                        if (diagnostic != previousDiagnostic) {
                            android.util.Log.i("VirtualDAP-Compat", diagnostic)
                            previousDiagnostic = diagnostic
                        }
                        return candidates.any {
                            it.packageName?.toString() in setOf(expected, context.packageName) && it.isVisibleToUser &&
                                !it.isPassword && it.text?.toString() == expectedText
                        }
                    }
                    await("external app visible screen '$expectedText' (not login or playback)", ::visibleAppScreen)
                    // Initialization may succeed just before a crash or an immediate redirect.
                    // Demand that the requested app screen remains visible for a short interval.
                    val stableUntil = SystemClock.elapsedRealtime() + 3_000
                    while (SystemClock.elapsedRealtime() < stableUntil) {
                        assertTrue("External app screen disappeared after initialization", visibleAppScreen())
                        SystemClock.sleep(100)
                    }
                }
                if (inspectionSeconds > 0) {
                    // Explicit local inspection window only: keeps the real app and paced receiver
                    // alive for manual navigation. Elapsed time does not certify login or playback.
                    val inspectedPid = requireNotNull(ContainerRuntime.state.value.applications
                        .first { it.packageName == expected }.lastStartedPid)
                    android.util.Log.i("VirtualDAP-Compat", "Manual inspection ready: $expected ($inspectionSeconds seconds)")
                    SystemClock.sleep(inspectionSeconds * 1000L)
                    val processes = context.getSystemService(android.app.ActivityManager::class.java)
                        .runningAppProcesses.orEmpty()
                    assertTrue("External app process exited during manual inspection; initialization is not compatibility",
                        processes.any { it.pid == inspectedPid })
                }
            }
            return
        }
        val capture = CaptureProbe()
        val fixture = File(context.cacheDir, "instrumented-music-fixture.apk")
        try {
            instrumentation.context.assets.open("music-fixture.apk").use { input ->
                fixture.outputStream().use { input.copyTo(it) }
            }
            ContainerRuntime.install(Uri.fromFile(fixture))
            await("fixture installation") { ContainerRuntime.state.value.phase != ContainerPhase.INSTALLING }
            assertTrue(ContainerRuntime.state.value.toString(),
                ContainerRuntime.state.value.applications.any { it.packageName == FIXTURE })
            verifyInstalledSigning(fixture, FIXTURE)
            // A real imported activity must open for setup with no selected DAC or bridge.
            assertEquals(null, PipelineStore.state.value.selectedRouteId)
            assertEquals(false, PipelineStore.state.value.enabled)
            ContainerRuntime.launch(FIXTURE)
            await("fixture opens for setup without USB or an audio receiver") {
                ContainerRuntime.state.value.foregroundActivity?.packageName == FIXTURE
            }
            assertEquals(null, ContainerRuntime.state.value.lastError)
            assertEquals(false, PipelineStore.state.value.enabled)
            assertEquals(null, PipelineStore.state.value.activeRoute)
            assertEquals(false, PipelineStore.state.value.bitPerfectActive)
            capture.start()
            ContainerRuntime.launch(FIXTURE)
            await("fixture Application.onCreate") {
                ContainerRuntime.state.value.applications.any {
                    it.packageName == FIXTURE && it.lastStartedPid != null
                }
            }
            await("fixture resumed activity lifecycle") {
                ContainerRuntime.state.value.foregroundActivity?.packageName == FIXTURE
            }
            val firstActivity = requireNotNull(ContainerRuntime.state.value.foregroundActivity)
            val newRequest = java.util.UUID.randomUUID().toString()
            val intent = requireNotNull(top.niunaijun.blackbox.BlackBoxCore.getBPackageManager()
                .getLaunchIntentForPackage(FIXTURE, 0)).apply {
                action = "com.virtualdap.fixture.NEW_INTENT"
                setPackage(FIXTURE)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("request", newRequest)
            }
            top.niunaijun.blackbox.BlackBoxCore.get().startActivity(intent, 0)
            await("new intent delivered to the existing activity") {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("NEW INTENT: $newRequest")
                    ?.any { it.isVisibleToUser && it.text?.toString() == "NEW INTENT: $newRequest" } == true
            }
            assertEquals("A new intent must not replace the existing activity instance", firstActivity,
                ContainerRuntime.state.value.foregroundActivity)
            await("real media commands retain caller attribution and token/payload identity") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val failure = root?.findAccessibilityNodeInfosByText("MEDIA CONTROLLER ERROR:")?.firstOrNull()?.text
                if (failure != null) org.junit.Assert.fail(failure.toString())
                root?.findAccessibilityNodeInfosByText("MEDIA CONTROLLER READY: local / parcel")?.isNotEmpty() == true
            }
            await("real network state and default callback without invented DNS or capabilities") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val failure = root?.findAccessibilityNodeInfosByText("NETWORK STATE ERROR:")?.firstOrNull()?.text
                if (failure != null) org.junit.Assert.fail(failure.toString())
                root?.findAccessibilityNodeInfosByText("NETWORK STATE READY:")?.isNotEmpty() == true
            }
            await("Wi-Fi state uses real host caller identity without inventing connection details") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val failure = root?.findAccessibilityNodeInfosByText("WIFI STATE ERROR:")?.firstOrNull()?.text
                if (failure != null) org.junit.Assert.fail(failure.toString())
                root?.findAccessibilityNodeInfosByText("WIFI STATE READY: system query returned")?.isNotEmpty() == true
            }
            await("imported providers use their actual container process, not authority-substring host routing") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val failure = root?.findAccessibilityNodeInfosByText("PRIVATE PROVIDER ERROR:")?.firstOrNull()?.text
                if (failure != null) org.junit.Assert.fail(failure.toString())
                root?.findAccessibilityNodeInfosByText("PRIVATE PROVIDER READY: local process / real denial")?.isNotEmpty() == true
            }
            await("private USER_ALL broadcast stays within the active music space") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val failure = root?.findAccessibilityNodeInfosByText("PRIVATE BROADCAST ERROR:")?.firstOrNull()?.text
                if (failure != null) org.junit.Assert.fail(failure.toString())
                root?.findAccessibilityNodeInfosByText("PRIVATE BROADCAST READY: music space only")?.isNotEmpty() == true
            }
            await("declared media service and bounded failure of an unauthorized authenticator session") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val failure = root?.findAccessibilityNodeInfosByText("MEDIA SERVICE QUERY ERROR:")
                    ?.firstOrNull()?.text
                if (failure != null) org.junit.Assert.fail(failure.toString())
                root?.findAccessibilityNodeInfosByText("AUTHENTICATOR TIMEOUT REPORTED")?.isNotEmpty() == true
            }
            click("Check unsupported output rejection")
            await("ordinary-UID MediaRouter2 discovery") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val failure = root?.findAccessibilityNodeInfosByText("MEDIA ROUTER ERROR:")?.firstOrNull()?.text
                if (failure != null) org.junit.Assert.fail(failure.toString())
                root?.findAccessibilityNodeInfosByText("MEDIA ROUTER READY:")?.isNotEmpty() == true
            }
            await("unsupported formats rejected inside the container") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val failure = root?.findAccessibilityNodeInfosByText("Unsupported output test failed:")
                    ?.firstOrNull()?.text
                if (failure != null) org.junit.Assert.fail(failure.toString())
                root?.findAccessibilityNodeInfosByText("Unsupported output rejected: AudioTrack, AAudio, OpenSL ES")
                    ?.isNotEmpty() == true
            }
            assertEquals("Unsupported formats must not open a capture/output session", 0,
                PipelineStore.state.value.connectedStreams)
            for ((button, rate, encoding) in listOf(
                Triple("Play 48 kHz / 16-bit", 48_000, PcmEncoding.PCM_16),
                Triple("Play 96 kHz / float", 96_000, PcmEncoding.PCM_FLOAT),
            )) {
                click(button)
                await("captured $rate Hz PCM at the test receiver") {
                    val audio = PipelineStore.state.value
                    audio.guestConnected && audio.sourceFormat?.sampleRate == rate &&
                        audio.sourceFormat.encoding == encoding && audio.framesReceived >= rate
                }
                assertEquals(PipelineStore.state.value.toString(), 0, PipelineStore.state.value.guestDroppedBytes)
                await("fixture reports its producer busy before another start") {
                    instrumentation.uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText(button)
                        ?.any { it.text?.toString()?.equals(button, ignoreCase = true) == true && !it.isEnabled } == true
                }
                click("Mute")
                await("application mute at host") { PipelineStore.state.value.applicationGainLeft == 0f }
                assertEquals(false, PipelineStore.state.value.bitPerfectActive)
                click("Unity gain")
                await("application unity gain at host") { PipelineStore.state.value.applicationGainLeft == 1f }
                click("Pause")
                await("host pause") { PipelineStore.state.value.phase == PipelinePhase.PAUSED }
                val pausedFrames = PipelineStore.state.value.framesReceived
                SystemClock.sleep(150)
                assertEquals("Paused producer must stop submitting PCM", pausedFrames, PipelineStore.state.value.framesReceived)
                click("Resume")
                await("host resume") { PipelineStore.state.value.framesReceived > pausedFrames }
                click("Stop")
                await("captured track release") { !PipelineStore.state.value.guestConnected }
            }
            click("Play 44.1 kHz / static loop")
            await("captured static AudioTrack loop") {
                val audio = PipelineStore.state.value
                audio.guestConnected && audio.sourceFormat?.sampleRate == 44_100 &&
                    audio.sourceFormat.encoding == PcmEncoding.PCM_16 && audio.framesReceived >= 44_100
            }
            assertEquals(PipelineStore.state.value.toString(), 0, PipelineStore.state.value.guestDroppedBytes)
            await("static track release") { !PipelineStore.state.value.guestConnected }
            click("Play 88.2 kHz / AAudio callback")
            await("captured AAudio callback PCM") {
                val audio = PipelineStore.state.value
                audio.guestConnected && audio.sourceFormat?.sampleRate == 88_200 &&
                    audio.sourceFormat.encoding == PcmEncoding.PCM_16 && audio.framesReceived >= 88_200
            }
            assertEquals(PipelineStore.state.value.toString(), 0, PipelineStore.state.value.guestDroppedBytes)
            await("AAudio stream release") { !PipelineStore.state.value.guestConnected }
            click("Play 96 kHz / AAudio write")
            await("captured blocking AAudio PCM") {
                val audio = PipelineStore.state.value
                audio.guestConnected && audio.sourceFormat?.sampleRate == 96_000 &&
                    audio.sourceFormat.encoding == PcmEncoding.PCM_FLOAT && audio.framesReceived >= 96_000
            }
            assertEquals(PipelineStore.state.value.toString(), 0, PipelineStore.state.value.guestDroppedBytes)
            await("blocking AAudio stream release") { !PipelineStore.state.value.guestConnected }
            click("Play 48 kHz / OpenSL ES")
            await("captured OpenSL ES buffer-queue PCM") {
                val audio = PipelineStore.state.value
                audio.guestConnected && audio.sourceFormat?.sampleRate == 48_000 &&
                    audio.sourceFormat.encoding == PcmEncoding.PCM_16 && audio.framesReceived >= 48_000
            }
            assertEquals(PipelineStore.state.value.toString(), 0, PipelineStore.state.value.guestDroppedBytes)
            assertEquals(1f, PipelineStore.state.value.applicationGainLeft)
            await("OpenSL ES native API contract") {
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("OpenSL ES finished: 48000 frames, callbacks=4")
                    ?.isNotEmpty() == true
            }
            await("OpenSL ES player release") { !PipelineStore.state.value.guestConnected }
            click("Overlap two tracks")
            await("two independently playing PCM streams") {
                val audio = PipelineStore.state.value
                audio.playingStreams == 2 && audio.framesReceived >= 96_000
            }
            assertEquals(false, PipelineStore.state.value.bitPerfectActive)
            click("Stop first track")
            await("remaining stream survives first release") {
                PipelineStore.state.value.connectedStreams == 1 && PipelineStore.state.value.playingStreams == 1
            }
            val remainingFrames = PipelineStore.state.value.framesReceived
            await("remaining PCM continues") { PipelineStore.state.value.framesReceived > remainingFrames + 4_800 }
            click("Stop")
            await("both overlapping streams released") { PipelineStore.state.value.connectedStreams == 0 }
            ContainerRuntime.stop(FIXTURE)
            await("stop before split update") {
                ContainerRuntime.state.value.applications.first { it.packageName == FIXTURE }.lastStartedPid == null
            }
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            await("container control process remains bound after hosted app stops") {
                @Suppress("DEPRECATION")
                val service = manager.getRunningServices(Int.MAX_VALUE).firstOrNull {
                    it.service.className == "com.virtualdap.host.container.ContainerControlService"
                }
                service != null && service.pid > 0 && manager.runningAppProcesses.orEmpty().any {
                    it.pid == service.pid &&
                        it.importance < android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
                }
            }
            val splitSet = File(context.cacheDir, "instrumented-music-split-set.apks")
            try {
                instrumentation.context.assets.open("music-fixture-bundletool.apks").use { input ->
                    splitSet.outputStream().use { input.copyTo(it) }
                }
                ContainerRuntime.install(Uri.fromFile(splitSet))
                await("feature split installation") { ContainerRuntime.state.value.phase != ContainerPhase.INSTALLING }
                assertEquals(ContainerRuntime.state.value.toString(), null, ContainerRuntime.state.value.lastError)
                ContainerRuntime.launch(FIXTURE)
                await("feature split class loading") {
                    instrumentation.uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText("FEATURE SPLIT LOADED")?.isNotEmpty() == true
                }
            } finally {
                splitSet.delete()
            }
            val corrupted = File(context.cacheDir, "instrumented-invalid-signature.apk")
            try {
                // Repacking removes the APK signing block; changing classes.dex also invalidates v1.
                ZipFile(fixture).use { zip ->
                    ZipOutputStream(corrupted.outputStream()).use { output ->
                        for (entry in zip.entries().asSequence()) {
                            output.putNextEntry(ZipEntry(entry.name))
                            if (!entry.isDirectory) {
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                if (entry.name == "classes.dex" && bytes.isNotEmpty()) {
                                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                                }
                                output.write(bytes)
                            }
                            output.closeEntry()
                        }
                    }
                }
                ContainerRuntime.install(Uri.fromFile(corrupted))
                await("invalid signature rejection") { ContainerRuntime.state.value.phase != ContainerPhase.INSTALLING }
                assertTrue("A tampered update must not report success", ContainerRuntime.state.value.lastError != null)
                assertTrue("The existing app must survive rejected input",
                    ContainerRuntime.state.value.applications.any { it.packageName == FIXTURE })
            } finally {
                corrupted.delete()
            }
            val otherPackages = ContainerRuntime.state.value.applications.map { it.packageName }.toSet() - FIXTURE
            ContainerRuntime.remove(FIXTURE)
            await("remove only the fixture copy from the music space") {
                ContainerRuntime.state.value.phase != ContainerPhase.REMOVING
            }
            assertEquals(null, ContainerRuntime.state.value.lastError)
            assertEquals(otherPackages, ContainerRuntime.state.value.applications.map { it.packageName }.toSet())
            assertEquals(false, top.niunaijun.blackbox.BlackBoxCore.get().isInstalled(FIXTURE, 0))
            // Android can rebind an old proxy after uninstall. It must return a null binding,
            // without trying to build an Application for the now-absent package or crashing.
            val staleService = android.content.pm.ServiceInfo().apply {
                packageName = FIXTURE
                name = "$FIXTURE.FixtureMediaService"
                processName = FIXTURE
            }
            val stale = android.content.Intent()
            top.niunaijun.blackbox.proxy.record.ProxyServiceRecord.saveStub(
                stale, android.content.Intent().setClassName(FIXTURE, staleService.name),
                staleService, android.os.Binder(), 0, 1,
            )
            instrumentation.runOnMainSync {
                val dispatcher = top.niunaijun.blackbox.app.dispatcher.AppServiceDispatcher.get()
                assertEquals(null, dispatcher.onBind(stale))
                assertEquals(android.app.Service.START_NOT_STICKY, dispatcher.onStartCommand(stale, 0, 1))
            }
        } finally {
            capture.close()
            fixture.delete()
            ContainerRuntime.stop(FIXTURE)
            // CaptureProbe owns its receiver; no production service was started by this test.
            // Starting a STOP-only service here can race the next test's foreground launch.
        }
    }

    private fun click(text: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        await("fixture button '$text'") {
            val candidates = ui.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.filter { it.isClickable && it.isEnabled }.orEmpty()
            val selected = candidates.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
                ?: candidates.singleOrNull()
            selected?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun verifyAtomicPublication(cache: File) {
        val root = java.nio.file.Files.createTempDirectory(cache.toPath(), "atomic-install-test-").toFile()
        try {
            val incoming = File(root, "incoming").apply { mkdir() }
            val installed = File(root, "installed").apply { mkdir() }
            File(incoming, "marker").writeText("new")
            File(installed, "marker").writeText("old")
            AtomicPackagePublisher.publish(incoming, installed)
            assertEquals("new", File(installed, "marker").readText())
            assertEquals("old", File(incoming, "marker").readText())
            assertThrows(java.io.IOException::class.java) {
                AtomicPackagePublisher.publish(File(root, "missing"), installed)
            }
            assertEquals("new", File(installed, "marker").readText())
            val outside = File(root, "outside").apply { mkdir() }
            File(outside, "kept").writeText("preserve")
            java.nio.file.Files.createSymbolicLink(File(incoming, "link").toPath(), outside.toPath())
            AtomicPackagePublisher.deleteStaging(incoming)
            assertEquals("preserve", File(outside, "kept").readText())
        } finally {
            AtomicPackagePublisher.deleteStaging(root)
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyInstalledSigning(originalArchive: File, packageName: String) {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNATURES or
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val original = requireNotNull(manager.getPackageArchiveInfo(originalArchive.path, flags))
        assertEquals(packageName, original.packageName)
        val installed = requireNotNull(top.niunaijun.blackbox.BlackBoxCore.getBPackageManager()
            .getPackageInfo(packageName, flags, 0))
        org.junit.Assert.assertArrayEquals("Legacy signing identity must match the imported APK",
            original.signatures, installed.signatures)
        org.junit.Assert.assertArrayEquals("Current signers must match the imported APK",
            requireNotNull(original.signingInfo).apkContentsSigners,
            requireNotNull(installed.signingInfo).apkContentsSigners)
        org.junit.Assert.assertArrayEquals("Signing history must match the imported APK",
            original.signingInfo!!.signingCertificateHistory, installed.signingInfo!!.signingCertificateHistory)
    }

    private fun await(operation: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        org.junit.Assert.fail("$operation timed out: ${ContainerRuntime.state.value}; audio=${PipelineStore.state.value}")
    }

    companion object { const val FIXTURE = "com.virtualdap.fixture.music" }
}
