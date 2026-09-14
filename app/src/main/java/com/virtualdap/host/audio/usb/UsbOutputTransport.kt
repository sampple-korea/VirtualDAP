package com.virtualdap.host.audio.usb

import java.io.Closeable

/** Typed seam for testing PCM-to-USB composition without a physical DAC. */
interface UsbOutputTransport : Closeable {
    val profile: UsbAudioStreamingProfile
    fun control(type: Int, request: Int, value: Int, index: Int, bytes: ByteArray): Int
    fun start(sampleRate: Int)
    fun write(bytes: ByteArray): Int
    fun pause()
    fun resume()
    fun flush()
    fun drain()
    fun statistics(): UsbOutputStatistics
}
