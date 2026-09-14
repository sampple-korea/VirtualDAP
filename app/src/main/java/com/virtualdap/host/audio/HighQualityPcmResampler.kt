package com.virtualdap.host.audio

import java.io.Closeable

interface FloatPcmResampler : Closeable {
    fun convert(interleavedPcm: FloatArray): FloatArray
    fun finish(): FloatArray
}

/** Streaming, source-pinned libsamplerate best-sinc conversion for production AudioTrack output. */
class HighQualityPcmResampler(
    val sourceSampleRate: Int,
    val targetSampleRate: Int,
    val channelCount: Int,
) : FloatPcmResampler {
    private var handle: Long
    private var finished = false

    init {
        require(sourceSampleRate in 8_000..6_144_000 && targetSampleRate in 8_000..6_144_000) {
            "Unsupported high-quality sample-rate conversion range"
        }
        require(channelCount in 1..8) { "Unsupported high-quality channel count" }
        handle = nativeCreate(sourceSampleRate, targetSampleRate, channelCount)
        check(handle != 0L) { "Could not initialize the high-quality PCM resampler" }
    }

    @Synchronized
    override fun convert(interleavedPcm: FloatArray): FloatArray {
        check(handle != 0L) { "High-quality PCM resampler is closed" }
        check(!finished) { "High-quality PCM resampler has already finished" }
        require(interleavedPcm.size <= 1024 * 1024 && interleavedPcm.size % channelCount == 0) {
            "Invalid interleaved PCM packet"
        }
        return nativeProcess(handle, interleavedPcm, false)
    }

    @Synchronized
    override fun finish(): FloatArray {
        check(handle != 0L) { "High-quality PCM resampler is closed" }
        if (finished) return FloatArray(0)
        return nativeProcess(handle, FloatArray(0), true).also { finished = true }
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
        finished = true
    }

    private external fun nativeCreate(sourceRate: Int, targetRate: Int, channels: Int): Long
    private external fun nativeProcess(handle: Long, input: FloatArray, endOfInput: Boolean): FloatArray
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("virtualdap_dsp") }
    }
}
