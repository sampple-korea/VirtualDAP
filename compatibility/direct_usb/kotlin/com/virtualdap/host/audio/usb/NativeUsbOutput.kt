package com.virtualdap.host.audio.usb

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

data class UsbOutputStatistics(
    val acceptedFrames: Long, val submittedFrames: Long, val completedFrames: Long,
    val underruns: Long, val feedbackPackets: Long, val invalidFeedbackPackets: Long,
    val queuedBytes: Long, val error: Int, val speed: Int,
)

/** Direct native transport. Construction requires an already granted UsbManager descriptor. */
class NativeUsbOutput(grantedFd: Int, override val profile: UsbAudioStreamingProfile) : UsbOutputTransport {
    private val handle = AtomicLong(openNative(
        grantedFd, profile.configuration, profile.interfaceNumber, profile.alternateSetting,
        profile.endpointAddress, profile.feedbackEndpointAddress ?: 0,
        if (profile.protocol == 0x20) profile.controlInterface else -1,
    ))

    override fun control(type: Int, request: Int, value: Int, index: Int, bytes: ByteArray): Int =
        controlNative(openHandle(), type, request, value, index, bytes)

    override fun start(sampleRate: Int) {
        UsbAudioClockControl(::control).setAndVerify(profile, sampleRate)
        startNative(openHandle(), sampleRate, profile.frameBytes)
    }

    override fun write(bytes: ByteArray): Int = writeNative(openHandle(), bytes)
    override fun pause() = commandNative(openHandle(), 2)
    override fun resume() = commandNative(openHandle(), 1)
    override fun flush() = commandNative(openHandle(), 3)
    override fun drain() = commandNative(openHandle(), 4)
    override fun statistics(): UsbOutputStatistics {
        val value = statisticsNative(openHandle())
        check(value.size == 9)
        return UsbOutputStatistics(value[0], value[1], value[2], value[3], value[4], value[5], value[6], value[7].toInt(), value[8].toInt())
    }
    private fun openHandle(): Long = handle.get().also { check(it != 0L) { "USB output is closed" } }
    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(::closeNative) }

    companion object {
        init { System.loadLibrary("virtualdap_usb") }
        @JvmStatic private external fun openNative(fd: Int, config: Int, interfaceNumber: Int, alternate: Int, endpoint: Int, feedback: Int, controlInterface: Int): Long
        @JvmStatic private external fun controlNative(handle: Long, type: Int, request: Int, value: Int, index: Int, data: ByteArray): Int
        @JvmStatic private external fun startNative(handle: Long, sampleRate: Int, frameBytes: Int)
        @JvmStatic private external fun writeNative(handle: Long, data: ByteArray): Int
        @JvmStatic private external fun commandNative(handle: Long, command: Int)
        @JvmStatic private external fun statisticsNative(handle: Long): LongArray
        @JvmStatic private external fun closeNative(handle: Long)
    }
}
