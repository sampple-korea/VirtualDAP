package com.virtualdap.host.container

import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicOutputReadinessTest {
    private val route = OutputRoute(7, "Official DAC", 11, true, listOf(48_000), emptyList(),
        officialBitPerfectFormats = listOf(PcmFormat(48_000, 2, PcmEncoding.PCM_16)))
    private val preparing = PipelineSnapshot(selectedRouteId = route.id, availableRoutes = listOf(route))

    @Test fun missingSelectionIsAnOutputFailureWithoutWaiting() = runTest {
        val failure = runCatching { MusicOutputReadiness.await(MutableStateFlow(PipelineSnapshot())) }.exceptionOrNull()
        assertTrue(failure is MusicOutputUnavailable)
        assertTrue(failure!!.message!!.contains("official bit-perfect"))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun unsupportedAndDisconnectedSelectionsCannotLaunch() = runTest {
        for (state in listOf(
            preparing.copy(availableRoutes = listOf(route.copy(officialBitPerfectFormats = emptyList()))),
            preparing.copy(selectedRouteId = 99),
        )) {
            assertTrue(runCatching { MusicOutputReadiness.await(MutableStateFlow(state)) }
                .exceptionOrNull() is MusicOutputUnavailable)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun runningReceiverIsAlreadyReady() = runTest {
        MusicOutputReadiness.await(MutableStateFlow(PipelineSnapshot(enabled = true)))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun localDsdOwnsOutputEvenIfBridgeStateIsStale() = runTest {
        val state = preparing.copy(enabled = true,
            dsdPlayback = DsdPlaybackSnapshot(phase = DsdPlaybackPhase.PLAYING))
        val failure = runCatching { MusicOutputReadiness.await(MutableStateFlow(state)) }.exceptionOrNull()
        assertTrue(failure is MusicOutputUnavailable)
        assertTrue(failure!!.message!!.contains("Stop local DSD"))
    }

    @Test fun retryWaitsForReadyDespitePreviousError() = runTest {
        val state = MutableStateFlow(preparing.copy(phase = PipelinePhase.ERROR, lastError = "Previous failure"))
        val result = async { MusicOutputReadiness.await(state) }
        runCurrent()
        assertFalse(result.isCompleted)
        state.value = preparing.copy(enabled = true, phase = PipelinePhase.WAITING_FOR_GUEST)
        result.await()
    }

    @Test fun disconnectDuringPreparationIsReportedImmediately() = runTest {
        val state = MutableStateFlow(preparing)
        val result = async { runCatching { MusicOutputReadiness.await(state) } }
        runCurrent()
        state.value = preparing.copy(availableRoutes = emptyList())
        assertTrue(result.await().exceptionOrNull() is MusicOutputUnavailable)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun preparationTimeoutPreservesOutputErrorNotAppCompatibilityAdvice() = runTest {
        val state = MutableStateFlow(preparing.copy(lastError = "Audio socket could not start"))
        val failure = runCatching { MusicOutputReadiness.await(state, 250) }.exceptionOrNull()
        assertTrue(failure is MusicOutputUnavailable)
        assertEquals("Audio socket could not start", failure!!.message)
        assertEquals(250L, testScheduler.currentTime)
    }
}
