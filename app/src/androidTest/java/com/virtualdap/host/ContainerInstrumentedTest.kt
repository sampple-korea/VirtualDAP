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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Installs an actual, independently packaged APK into the ordinary-UID container. */
@RunWith(AndroidJUnit4::class)
class ContainerInstrumentedTest {
    @Test fun fixtureInstallsAndStartsInsideMusicSpace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n com.virtualdap.host/.MainActivity",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        await("container initialization") { ContainerRuntime.state.value.phase != ContainerPhase.INITIALIZING }
        assertEquals(ContainerRuntime.state.value.toString(), ContainerPhase.READY, ContainerRuntime.state.value.phase)
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
        } finally {
            fixture.delete()
            ContainerRuntime.stop(FIXTURE)
            AudioPipelineService.command(context, AudioPipelineService.ACTION_STOP)
        }
    }

    private fun click(text: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        await("fixture button '$text'") {
            ui.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.isClickable }
                ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun await(operation: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("$operation timed out: ${ContainerRuntime.state.value}", condition())
    }

    companion object { const val FIXTURE = "com.virtualdap.fixture.music" }
}
