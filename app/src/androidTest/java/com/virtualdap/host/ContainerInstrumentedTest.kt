package com.virtualdap.host

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualdap.host.container.ContainerPhase
import com.virtualdap.host.container.ContainerRuntime
import com.virtualdap.host.service.AudioPipelineService
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
        } finally {
            fixture.delete()
        }
    }

    private fun await(operation: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("$operation timed out: ${ContainerRuntime.state.value}", condition())
    }

    companion object { const val FIXTURE = "com.virtualdap.fixture.music" }
}
