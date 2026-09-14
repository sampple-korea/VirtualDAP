package com.virtualdap.host.audio

import java.io.Closeable

/** Common bounded, sequential source contract for uncompressed DSD containers. */
interface DsdStreamReader : Closeable {
    val format: DsdFormat
    val sampleCountPerChannel: Long
    val samplePosition: Long
    val durationMillis: Long
    fun readInterleaved(maximumBytes: Int = 64 * 1024): ByteArray?
}
