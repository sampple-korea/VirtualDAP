package com.virtualdap.host.audio.usb

import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.audio.SinkConfiguration
import com.virtualdap.host.model.OutputRoute
import java.io.Closeable

/** PCM-specific adapter. DoP/DSD must use their typed path, never this volume/packing stage. */
class DirectUsbPcmSink(
    private val deviceId: Int,
    private val route: OutputRoute,
    private val openConnection: (Int, (List<UsbAudioStreamingProfile>) -> UsbAudioStreamingProfile) -> UsbHostController.DirectConnection =
        UsbHostController::openDirect,
) : Closeable {
    private var connection: UsbHostController.DirectConnection? = null
    private var packing: UsbPcmPacking? = null
    private var configuration: SinkConfiguration? = null
    private var playing = true
    private var exclusive = true
    private var left = 1f
    private var right = 1f
    private var suspendedStatistics: UsbOutputStatistics? = null

    fun setPlaying(value: Boolean) {
        playing = value
        connection?.output?.let { if (value) it.resume() else it.pause() }
    }
    fun setVolume(left: Float, right: Float) {
        require(left.isFinite() && right.isFinite() && left in 0f..1f && right in 0f..1f)
        this.left = left
        this.right = right
    }
    fun setExclusiveAllowed(value: Boolean) { exclusive = value }
    fun configure(source: PcmFormat): SinkConfiguration {
        configuration?.takeIf { it.requested == source }?.let { return it }
        finish()
        check(exclusive) { "Direct USB requires one active music stream; use Android output for overlapping tracks" }
        val opened = openConnection(deviceId) { profiles ->
            UsbPcmPacking.candidates(source, profiles).firstOrNull()
                ?: error("No matching direct USB PCM profile for ${source.shortLabel()}")
        }
        try {
            val adapter = UsbPcmPacking(source, opened.output.profile)
            opened.output.start(source.sampleRate)
            if (!playing) opened.output.pause()
            packing = adapter
            connection = opened
            suspendedStatistics = null
            return SinkConfiguration(
                source, adapter.outputFormat, route, directPlayback = true,
                bufferFrames = source.sampleRate / 10, sourcePreserved = adapter.preservesSource,
                bitPerfectRequested = adapter.preservesSource,
            ).also { configuration = it }
        } catch (failure: Exception) {
            opened.close()
            throw failure
        }
    }
    fun write(input: ByteArray): Int {
        val transport = connection?.output ?: error("Direct USB output is not configured")
        val adapter = packing ?: error("Direct USB format is not configured")
        val bytes = adapter.pack(input, left, right)
        var offset = 0
        while (offset < bytes.size) {
            val maximum = 1024 * 1024 - 1024 * 1024 % adapter.profile.frameBytes
            val length = minOf(bytes.size - offset, maximum)
            val accepted = transport.write(bytes.copyOfRange(offset, offset + length))
            check(accepted > 0 && accepted % adapter.profile.frameBytes == 0) { "USB output stopped accepting complete frames" }
            offset += accepted
        }
        return input.size
    }
    fun statistics(): UsbOutputStatistics? = connection?.output?.statistics() ?: suspendedStatistics
    fun queuedDurationMs(): Double? {
        val adapter = packing ?: return null
        val stats = statistics() ?: return null
        return stats.queuedBytes * 1000.0 / adapter.profile.frameBytes / adapter.source.sampleRate
    }
    fun bitPerfectActive(): Boolean {
        val stats = statistics() ?: return false
        val adapter = packing ?: return false
        return connection != null && exclusive && adapter.preservesSource && left == 1f && right == 1f &&
            stats.error == 0 && stats.completedFrames > 0 && stats.underruns == 0L &&
            (adapter.profile.feedbackEndpointAddress == null || stats.feedbackPackets > 0)
    }
    fun routedOutput(): OutputRoute? = route.takeIf { (statistics()?.completedFrames ?: 0) > 0 }
    fun flush() { connection?.output?.flush() }
    fun finish() {
        try {
            connection?.output?.let { it.drain(); suspendedStatistics = it.statistics() }
        } finally { close() }
    }
    override fun close() {
        connection?.close()
        connection = null
        packing = null
        configuration = null
    }
}
