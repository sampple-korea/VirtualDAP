package com.virtualdap.host.service

import com.virtualdap.host.model.*
import org.junit.Assert.*
import org.junit.Test

class PipelinePresentationTest {
    private val failed = PipelineSnapshot(enabled = true, phase = PipelinePhase.ERROR,
        lastError = "DAC disconnected", guestConnected = true, playingStreams = 1, connectedStreams = 1,
        bitPerfectActive = true, directPlayback = true)

    @Test fun failedOutputIsNotChangedIntoSuccessfulWaiting() {
        val result = PipelinePresentation.withoutStreams(failed, null)
        assertEquals(PipelinePhase.ERROR, result.phase)
        assertEquals("DAC disconnected", result.lastError)
        assertFalse(result.guestConnected)
        assertFalse(result.bitPerfectActive)
        assertFalse(result.sourcePreserved)
        assertNull(result.activeRoute)
        assertEquals(0, result.playingStreams)
    }

    @Test fun newestOutputErrorReplacesOlderFailure() {
        assertEquals("Write failed", PipelinePresentation.withoutStreams(failed, "Write failed").lastError)
    }

    @Test fun serviceDestructionKeepsTheFailureButClearsLiveOutput() {
        val result = PipelinePresentation.stopped(failed, preserveFailure = true)
        assertEquals(PipelinePhase.ERROR, result.phase)
        assertEquals(failed.lastError, result.lastError)
        assertFalse(result.enabled)
        assertFalse(result.directPlayback)
    }

    @Test fun explicitStopAcknowledgesAndClearsTheFailure() {
        val result = PipelinePresentation.stopped(failed, preserveFailure = false)
        assertEquals(PipelinePhase.STOPPED, result.phase)
        assertNull(result.lastError)
        assertFalse(result.enabled)
    }

    @Test fun cleanIdleStateDistinguishesListeningFromStopped() {
        assertEquals(PipelinePhase.WAITING_FOR_GUEST,
            PipelinePresentation.withoutStreams(PipelineSnapshot(enabled = true), null).phase)
        assertEquals(PipelinePhase.STOPPED,
            PipelinePresentation.withoutStreams(PipelineSnapshot(), null).phase)
    }
}
