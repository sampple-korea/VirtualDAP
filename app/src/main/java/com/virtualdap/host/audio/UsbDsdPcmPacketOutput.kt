package com.virtualdap.host.audio

import android.content.Context
import com.virtualdap.host.model.OutputRoute

/** Explicit DSD decode; PCM may be negotiated to the USB DAC's supported format. */
class UsbDsdPcmPacketOutput(context: Context, route: OutputRoute, source: DsdFormat) : DsdPacketOutput {
    private val decoder = DsdPcmDecoder(source)
    private val sink = RoutedAudioSink(context)
    val configuration: SinkConfiguration
    init {
        try {
            require(route.directUsbDeviceId != null)
            sink.selectRoute(route.id)
            configuration = sink.configure(PcmFormat(source.sampleRate / 8, source.channelCount, PcmEncoding.PCM_FLOAT))
        } catch (failure: Throwable) {
            decoder.close()
            sink.close()
            throw failure
        }
    }
    override fun write(interleavedDsd: ByteArray) {
        val pcm = floatsToLittleEndianBytes(decoder.convert(interleavedDsd))
        if (pcm.isNotEmpty()) check(sink.write(pcm) == pcm.size)
    }
    override fun setPlaying(playing: Boolean) { sink.setPlaying(playing) }
    override fun finish() { sink.finish() }
    override fun close() { try { decoder.close() } finally { sink.close() } }
    fun routedOutput(): OutputRoute? = sink.routedOutput()
}
