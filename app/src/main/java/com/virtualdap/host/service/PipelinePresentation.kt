package com.virtualdap.host.service

import com.virtualdap.host.model.PipelinePhase
import com.virtualdap.host.model.PipelineSnapshot

/** State reduction only: never infer successful output from socket or service lifetime. */
internal object PipelinePresentation {
    fun withoutStreams(current: PipelineSnapshot, outputError: String?): PipelineSnapshot {
        val failure = outputError ?: current.lastError
        return current.copy(
            guestConnected = false, guestPeer = null, sourceFormat = null, sinkFormat = null,
            activeRoute = null, connectedStreams = 0, playingStreams = 0, bitPerfectActive = false,
            directPlayback = false, sourcePreserved = false, latencyMs = null,
            lastError = failure,
            phase = when {
                failure != null -> PipelinePhase.ERROR
                current.enabled -> PipelinePhase.WAITING_FOR_GUEST
                else -> PipelinePhase.STOPPED
            },
        )
    }

    fun stopped(current: PipelineSnapshot, preserveFailure: Boolean): PipelineSnapshot =
        withoutStreams(current.copy(enabled = false, lastError = current.lastError.takeIf { preserveFailure }), null)
}
