package com.virtualdap.host

import android.app.ActivityManager
import android.net.Credentials
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.bridge.*
import com.virtualdap.host.model.*
import com.virtualdap.host.service.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real service/session state without pretending that a DAC is attached. */
@RunWith(AndroidJUnit4::class)
class PipelineFailureInstrumentedTest {
    @Test fun failedSessionStaysVisibleAcrossIdleConnectionsAndCleanDisconnects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PipelineStore.update { PipelineSnapshot(enabled = true) }
        AudioSessionMixer(context, {}).use { sessions ->
            val first = sessions.createSession()
            val idle = sessions.createSession()
            val format = PcmFormat(48_000, 2, PcmEncoding.PCM_16)
            val handshake = BridgeHandshake(format, 4, 0, 1, BridgeWireProtocol.CONTROLLED_VERSION)
            val peer = Credentials(Process.myPid(), Process.myUid(), Process.myUid())
            first.onGuestConnected(peer, handshake)
            idle.onGuestConnected(peer, handshake)
            val failure = assertThrows(IllegalStateException::class.java) { first.onVolume(0f, 0f) }
            first.onGuestDisconnected(failure.message)
            assertEquals(PipelinePhase.ERROR, PipelineStore.state.value.phase)
            assertEquals(failure.message, PipelineStore.state.value.lastError)
            idle.onGuestDisconnected(null)
            assertEquals(PipelinePhase.ERROR, PipelineStore.state.value.phase)
            assertEquals(failure.message, PipelineStore.state.value.lastError)
            assertEquals(0, PipelineStore.state.value.connectedStreams)
            assertFalse(PipelineStore.state.value.bitPerfectActive)
        }
        PipelineStore.update { PipelineSnapshot() }
    }

    @Suppress("DEPRECATION")
    @Test fun unsupportedStartRemainsAnErrorAfterTheRealServiceStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PipelineStore.update { PipelineSnapshot() } // Deliberately no selected official output.
        AudioPipelineService.command(context, AudioPipelineService.ACTION_START)
        val manager = context.getSystemService(ActivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var stoppedAfterError = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = PipelineStore.state.value
            val running = manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == AudioPipelineService::class.java.name }
            if (state.lastError != null && !running) { stoppedAfterError = true; break }
            SystemClock.sleep(50)
        }
        assertTrue("Service did not report and finish its unsupported-start failure", stoppedAfterError)
        assertEquals(PipelinePhase.ERROR, PipelineStore.state.value.phase)
        assertTrue(PipelineStore.state.value.lastError.orEmpty().contains("official bit-perfect"))
        assertFalse(PipelineStore.state.value.enabled)
    }
}
