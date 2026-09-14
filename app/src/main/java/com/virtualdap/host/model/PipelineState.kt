package com.virtualdap.host.model

import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.audio.DsdFormat
import com.virtualdap.host.audio.DsdOutputMode

enum class PipelinePhase {
    STOPPED,
    WAITING_FOR_GUEST,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR,
}

enum class LogLevel { INFO, WARNING, ERROR }

enum class DsdPlaybackPhase {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    STOPPING,
    COMPLETED,
    ERROR,
}

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
    val directUsbDeviceId: Int? = null,
    val officialBitPerfectFormats: List<PcmFormat> = emptyList(),
)

data class DsdPlaybackSnapshot(
    val phase: DsdPlaybackPhase = DsdPlaybackPhase.IDLE,
    val fileName: String? = null,
    val format: DsdFormat? = null,
    val mode: DsdOutputMode = DsdOutputMode.PCM_CONVERSION,
    val samplePosition: Long = 0,
    val sampleCountPerChannel: Long = 0,
    val durationMillis: Long = 0,
    val outputFormat: PcmFormat? = null,
    val outputRoute: OutputRoute? = null,
    val transportRate: Int? = null,
    val qualification: String? = null,
    val sourcePreserved: Boolean = false,
    val outputUnderruns: Long = 0,
    val lastError: String? = null,
) {
    val active: Boolean get() = phase in setOf(
        DsdPlaybackPhase.PREPARING,
        DsdPlaybackPhase.PLAYING,
        DsdPlaybackPhase.PAUSED,
        DsdPlaybackPhase.STOPPING,
    )
    val progress: Float get() = if (sampleCountPerChannel <= 0) 0f else
        (samplePosition.toDouble() / sampleCountPerChannel).coerceIn(0.0, 1.0).toFloat()
}

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
    val outputUnderruns: Long = 0,
    val outputFramesCompleted: Long? = null,
    val framesReceived: Long = 0,
    val bytesReceived: Long = 0,
    val guestDroppedBytes: Long = 0,
    val reconnectCount: Long = 0,
    val latencyMs: Double? = null,
    val lastError: String? = null,
    val dsdPlayback: DsdPlaybackSnapshot = DsdPlaybackSnapshot(),
    val logs: List<PipelineLog> = emptyList(),
)
