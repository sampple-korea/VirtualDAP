package com.virtualdap.host.container

import com.virtualdap.host.model.PipelineSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Output preparation is separate from application startup and must report its own failures. */
internal class MusicOutputUnavailable(message: String) : IllegalStateException(message)

internal object MusicOutputReadiness {
    suspend fun await(pipeline: StateFlow<PipelineSnapshot>, timeoutMillis: Long = 5_000) {
        val initial = pipeline.value
        obstruction(initial)?.let { throw MusicOutputUnavailable(it) }
        if (initial.enabled) return

        // A previous output error must not prevent a retry while ACTION_START is being handled.
        val ready = withTimeoutOrNull(timeoutMillis) {
            pipeline.first { it.enabled || obstruction(it) != null }
        } ?: throw MusicOutputUnavailable(
            pipeline.value.lastError
                ?: "Audio output did not become ready. Select a supported output and start the pipeline again.",
        )
        obstruction(ready)?.let { throw MusicOutputUnavailable(it) }
    }

    private fun obstruction(state: PipelineSnapshot): String? = when {
        state.dsdPlayback.active -> "Stop local DSD playback before starting a music app."
        // A running bridge has already validated its output (instrumentation uses a test receiver).
        state.enabled -> null
        state.availableRoutes.none {
            it.id == state.selectedRouteId && it.officialBitPerfectFormats.isNotEmpty()
        } -> "Select an output supported by Android's official bit-perfect path before starting a music app."
        else -> null
    }
}
