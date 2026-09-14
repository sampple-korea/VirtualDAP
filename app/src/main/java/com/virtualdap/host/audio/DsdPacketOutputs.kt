package com.virtualdap.host.audio

import android.content.Context
import com.virtualdap.host.audio.usb.DirectUsbDsdSink
import com.virtualdap.host.audio.usb.UsbOutputStatistics
import com.virtualdap.host.model.OutputRoute
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Product adapter for the DSD-only direct USB sink. */
class DirectDsdPacketOutput(
    deviceId: Int,
    route: OutputRoute,
    source: DsdFormat,
    mode: DsdOutputMode,
    dopCapabilityConfirmed: Boolean,
) : DsdPacketOutput {
    private val sink = DirectUsbDsdSink(deviceId, route)
    val configuration: com.virtualdap.host.audio.usb.DirectDsdConfiguration

    init {
        configuration = try {
            sink.configure(source, mode, dopCapabilityConfirmed)
        } catch (failure: Throwable) {
            sink.close()
            throw failure
        }
    }

    override fun write(interleavedDsd: ByteArray) {
        check(sink.write(interleavedDsd) == interleavedDsd.size)
    }

    override fun setPlaying(playing: Boolean) {
        sink.setPlaying(playing)
    }
    override fun finish() = sink.finish()
    override fun close() = sink.close()
    fun statistics(): UsbOutputStatistics? = sink.statistics()
    fun sourcePreservedActive(): Boolean = sink.sourcePreservedActive()
    fun routedOutput(): OutputRoute? = sink.routedOutput()
}

/** Stateful DSD decode followed by the existing explicit PCM route. */
class DsdPcmPacketOutput(
    context: Context,
    routeId: Int?,
    source: DsdFormat,
) : DsdPacketOutput {
    private val decoder = DsdPcmDecoder(source)
    private val sink = RoutedAudioSink(context)
    val outputFormat: PcmFormat
    val configuration: SinkConfiguration
    private var finalRoute: OutputRoute? = null
    private var finalStatistics: UsbOutputStatistics? = null

    init {
        try {
            outputFormat = PcmFormat(decoder.outputSampleRate, source.channelCount, PcmEncoding.PCM_FLOAT)
            sink.selectRoute(routeId)
            sink.setVolume(1f, 1f)
            sink.setExclusiveAllowed(true)
            configuration = sink.configure(outputFormat)
        } catch (failure: Throwable) {
            decoder.close()
            sink.close()
            throw failure
        }
    }

    override fun write(interleavedDsd: ByteArray) {
        val pcm = floatsToLittleEndianBytes(decoder.convert(interleavedDsd))
        check(sink.write(pcm) == pcm.size) { "PCM output did not consume the complete decoded DSD packet" }
    }

    override fun setPlaying(playing: Boolean) {
        sink.setPlaying(playing)
    }
    override fun finish() {
        sink.finish()
        finalRoute = sink.routedOutput()
        finalStatistics = sink.usbStatistics()
    }
    override fun close() {
        try {
            decoder.close()
        } finally {
            sink.close()
        }
    }

    fun routedOutput(): OutputRoute? = sink.routedOutput() ?: finalRoute ?: configuration.route
    fun statistics(): UsbOutputStatistics? = sink.usbStatistics() ?: finalStatistics
}

internal fun floatsToLittleEndianBytes(samples: FloatArray): ByteArray {
    val output = ByteArray(samples.size * Float.SIZE_BYTES)
    ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(samples)
    return output
}
