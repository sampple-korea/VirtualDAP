package com.virtualdap.host.audio

import android.content.Context
import com.virtualdap.host.model.OutputRoute
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DoP framing delivered only through Android's exact official bit-perfect PCM route. */
class DsdDopPacketOutput(
    context: Context,
    route: OutputRoute,
    source: DsdFormat,
    dopCapabilityConfirmed: Boolean,
) : DsdPacketOutput {
    private val encoder = DopEncoder(source, DopContainer.PACKED_24)
    private val sink = AndroidAudioSink(context)
    val outputFormat = PcmFormat(source.dopSampleRate, source.channelCount, PcmEncoding.PCM_24_PACKED)
    val configuration: SinkConfiguration
    private var finalRoute: OutputRoute? = null
    private var completedUnchanged = false

    init {
        require(dopCapabilityConfirmed) {
            "Confirm that the selected DAC accepts DoP 1.1; Android advertises the PCM carrier, not DoP decoding"
        }
        require(outputFormat in route.officialBitPerfectFormats) {
            "${route.name} does not advertise the ${outputFormat.shortLabel()} DoP carrier through Android's official bit-perfect path"
        }
        try {
            sink.selectRoute(route.id)
            sink.setVolume(1f, 1f)
            configuration = sink.configure(outputFormat)
        } catch (failure: Throwable) {
            sink.close()
            throw failure
        }
    }

    override fun write(interleavedDsd: ByteArray) {
        val dop = encoder.encode(interleavedDsd)
        if (dop.isNotEmpty()) check(sink.write(dop) == dop.size)
    }

    override fun setPlaying(playing: Boolean) = sink.setPlaying(playing)

    override fun finish() {
        encoder.finish()
        val verified = sink.bitPerfectActive()
        finalRoute = sink.routedOutput()
        sink.finish()
        completedUnchanged = verified
    }

    override fun close() = sink.close()
    fun routedOutput(): OutputRoute? = sink.routedOutput() ?: finalRoute ?: configuration.route
    fun sourcePreserved(): Boolean = completedUnchanged || sink.bitPerfectActive()
}

/** Stateful DSD decode followed by an exact official bit-perfect PCM route. */
class DsdPcmPacketOutput(
    context: Context,
    route: OutputRoute,
    source: DsdFormat,
) : DsdPacketOutput {
    private val decodedFormat = PcmFormat(source.sampleRate / 8, source.channelCount, PcmEncoding.PCM_FLOAT)
    val outputFormat: PcmFormat = OfficialBitPerfectPolicy.dsdPcmTarget(
        decodedFormat,
        route.officialBitPerfectFormats,
    ) ?: throw AudioSinkException(
        "${route.name} does not advertise ${decodedFormat.sampleRate / 1_000.0} kHz / " +
            "${decodedFormat.channelCount}-channel PCM through Android's official bit-perfect path",
    )
    private val decoder = DsdPcmDecoder(source)
    private val converter = if (decodedFormat == outputFormat) null else
        StreamingPcmConverter(decodedFormat, outputFormat)
    private val sink = AndroidAudioSink(context)
    val configuration: SinkConfiguration
    private var finalRoute: OutputRoute? = null

    init {
        try {
            sink.selectRoute(route.id)
            sink.setVolume(1f, 1f)
            configuration = sink.configure(outputFormat)
        } catch (failure: Throwable) {
            converter?.close()
            decoder.close()
            sink.close()
            throw failure
        }
    }

    override fun write(interleavedDsd: ByteArray) {
        val decoded = floatsToLittleEndianBytes(decoder.convert(interleavedDsd))
        val pcm = converter?.convert(decoded) ?: decoded
        check(sink.write(pcm) == pcm.size) { "PCM output did not consume the complete decoded DSD packet" }
    }

    override fun setPlaying(playing: Boolean) = sink.setPlaying(playing)

    override fun finish() {
        converter?.finish()?.takeIf { it.isNotEmpty() }?.let {
            check(sink.write(it) == it.size)
        }
        finalRoute = sink.routedOutput()
        sink.finish()
    }

    override fun close() {
        try {
            converter?.close()
            decoder.close()
        } finally {
            sink.close()
        }
    }

    fun routedOutput(): OutputRoute? = sink.routedOutput() ?: finalRoute ?: configuration.route
    fun bitPerfectActive(): Boolean = sink.bitPerfectActive()
}

internal fun floatsToLittleEndianBytes(samples: FloatArray): ByteArray {
    val output = ByteArray(samples.size * Float.SIZE_BYTES)
    ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(samples)
    return output
}
