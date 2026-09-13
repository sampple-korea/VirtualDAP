package com.virtualdap.host.audio

import java.io.Closeable

/**
 * Native, stateful 96-tap DSD low-pass filter with 8:1 decimation.
 * Output is interleaved float PCM at source.sampleRate / 8; subsequent sample-rate conversion,
 * when required by a DAC, is a separate explicit stage.
 */
class DsdPcmDecoder(val source: DsdFormat) : Closeable {
    val outputSampleRate: Int = source.sampleRate / 8
    private var handle = nativeCreate(source.channelCount, source.bitOrder == DsdBitOrder.LSB_FIRST)

    init {
        check(handle != 0L) { "Could not initialize the DSD decoder" }
    }

    @Synchronized
    fun convert(interleavedDsd: ByteArray): FloatArray {
        check(handle != 0L) { "DSD decoder is closed" }
        require(interleavedDsd.size <= 1024 * 1024 &&
            interleavedDsd.size % source.channelCount == 0
        ) { "Invalid DSD packet size" }
        return nativeConvert(handle, interleavedDsd)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    private external fun nativeCreate(channels: Int, lsbFirst: Boolean): Long
    private external fun nativeConvert(handle: Long, input: ByteArray): FloatArray
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("virtualdap_dsp") }
    }
}
