package com.virtualdap.host

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualdap.host.container.ContainerPhase
import com.virtualdap.host.container.ContainerRuntime
import com.virtualdap.host.service.AudioPipelineService
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
                    ContainerRuntime.state.value.hostApplications.any { it.packageName == hostPackage })
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
            if (InstrumentationRegistry.getArguments().getString("importOnly") == "true") return
            AudioPipelineService.command(context, AudioPipelineService.ACTION_START)
            ContainerRuntime.launch(expected)
            await("external Application.onCreate (not a playback certification)") {
                ContainerRuntime.state.value.applications.any { it.packageName == expected && it.lastStartedPid != null }
            }
            return
        }
        val fixture = File(context.cacheDir, "instrumented-music-fixture.apk")
        try {
            instrumentation.context.assets.open("music-fixture.apk").use { input ->
                fixture.outputStream().use { input.copyTo(it) }
            }
            ContainerRuntime.install(Uri.fromFile(fixture))
            await("fixture installation") { ContainerRuntime.state.value.phase != ContainerPhase.INSTALLING }
            assertTrue(ContainerRuntime.state.value.toString(),
                ContainerRuntime.state.value.applications.any { it.packageName == FIXTURE })
            AudioPipelineService.command(context, AudioPipelineService.ACTION_START)
            ContainerRuntime.launch(FIXTURE)
            await("fixture Application.onCreate") {
                ContainerRuntime.state.value.applications.any {
                    it.packageName == FIXTURE && it.lastStartedPid != null
                }
            }
            for ((button, rate, encoding) in listOf(
                Triple("48 kHz", 48_000, PcmEncoding.PCM_16),
                Triple("96 kHz", 96_000, PcmEncoding.PCM_FLOAT),
            )) {
                click(button)
                await("captured $rate Hz PCM at the host output") {
                    val audio = PipelineStore.state.value
                    audio.guestConnected && audio.sourceFormat?.sampleRate == rate &&
                        audio.sourceFormat.encoding == encoding && audio.framesReceived >= rate
                }
                assertEquals(PipelineStore.state.value.toString(), 0, PipelineStore.state.value.guestDroppedBytes)
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
            val splitSet = File(context.cacheDir, "instrumented-music-split-set.apks")
            try {
                instrumentation.context.assets.open("music-fixture.apks").use { input ->
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
        } finally {
            fixture.delete()
            ContainerRuntime.stop(FIXTURE)
            AudioPipelineService.command(context, AudioPipelineService.ACTION_STOP)
        }
    }

    private fun click(text: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        await("fixture button '$text'") {
            val candidates = ui.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.filter { it.isClickable }.orEmpty()
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

    private fun await(operation: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("$operation timed out: ${ContainerRuntime.state.value}", condition())
    }

    companion object { const val FIXTURE = "com.virtualdap.fixture.music" }
}
