package com.virtualdap.host.model

import com.virtualdap.host.audio.PcmFormat

enum class PipelinePhase {
    STOPPED,
    WAITING_FOR_GUEST,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR,
}

enum class LogLevel { INFO, WARNING, ERROR }

data class PipelineLog(
    val timestampMillis: Long,
    val level: LogLevel,
    val message: String,
)

data class OutputRoute(
    val id: Int,
    val name: String,
    val type: Int,
    val isUsb: Boolean,
    val sampleRates: List<Int>,
    val encodings: List<Int>,
)

data class PipelineSnapshot(
    val enabled: Boolean = false,
    val phase: PipelinePhase = PipelinePhase.STOPPED,
    val guestConnected: Boolean = false,
    val guestPeer: String? = null,
    val sourceFormat: PcmFormat? = null,
    val sinkFormat: PcmFormat? = null,
    val selectedRouteId: Int? = null,
    val activeRoute: OutputRoute? = null,
    val availableRoutes: List<OutputRoute> = emptyList(),
    val directPlayback: Boolean = false,
    val sourcePreserved: Boolean = true,
    val bitPerfectActive: Boolean = false,
    val applicationGainLeft: Float = 1f,
    val applicationGainRight: Float = 1f,
    val connectedStreams: Int = 0,
    val playingStreams: Int = 0,
    val framesReceived: Long = 0,
    val bytesReceived: Long = 0,
    val guestDroppedBytes: Long = 0,
    val reconnectCount: Long = 0,
    val latencyMs: Double? = null,
    val lastError: String? = null,
    val logs: List<PipelineLog> = emptyList(),
)
