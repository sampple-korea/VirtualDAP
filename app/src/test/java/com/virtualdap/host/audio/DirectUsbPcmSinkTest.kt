package com.virtualdap.host.audio

import com.virtualdap.host.audio.usb.*
import com.virtualdap.host.model.OutputRoute
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class DirectUsbPcmSinkTest {
    private val profile = UsbAudioStreamingProfile(
        1, 0, 1, 1, 0, 1, null, 1, 512, 3, 2, 4, 24, true, false, false, null,
        listOf(UsbSampleRateRange(48_000, 48_000)), false,
    )
    private val route = OutputRoute(-1_000_042, "Test USB", 11, true, listOf(48_000), emptyList(), 42)
    private inner class Transport : UsbOutputTransport {
        override val profile = this@DirectUsbPcmSinkTest.profile
        val bytes = ByteArrayOutputStream()
        var started = false
        var paused = false
        var closed = false
        var underruns = 0L
        var error = 0
        var drains = 0
        var rejectClock = false
        override fun start(sampleRate: Int) {
            check(sampleRate == 48_000 && !rejectClock) { "USB clock rejected the rate" }
            started = true
        }
        override fun write(bytes: ByteArray): Int {
            check(started && !paused && !closed)
            if (error != 0) return -1
            val count = minOf(16, bytes.size) // Force many partial native writes.
            this.bytes.write(bytes, 0, count)
            return count
        }
        override fun control(type: Int, request: Int, value: Int, index: Int, bytes: ByteArray): Int = kotlin.error("unused")
        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun flush() { check(paused); bytes.reset() }
        override fun drain() { drains++ }
        override fun close() { closed = true }
        override fun statistics() = UsbOutputStatistics(
            bytes.size() / 8L, bytes.size() / 8L, bytes.size() / 8L, underruns, 0, 0, 0, error, 3,
        )
    }
    private fun sink(transport: Transport, released: () -> Unit = {}): DirectUsbPcmSink =
        DirectUsbPcmSink(42, route) { id, select ->
            assertEquals(42, id)
            assertEquals(profile, select(listOf(profile)))
            UsbHostController.DirectConnection(transport, released)
        }

    @Test fun partialNativeWritesPreserveEveryPackedSourceByte() {
        val transport = Transport()
        var releases = 0
        val sink = sink(transport) { releases++ }
        val source = PcmFormat(48_000, 2, PcmEncoding.PCM_24_PACKED)
        val data = ByteArray(600) { (it * 13).toByte() }
        assertTrue(sink.configure(source).sourcePreserved)
        assertFalse(sink.bitPerfectActive())
        assertEquals(data.size, sink.write(data))
        assertArrayEquals(UsbPcmPacking(source, profile).pack(data), transport.bytes.toByteArray())
        assertTrue(sink.bitPerfectActive())
        assertEquals(route, sink.routedOutput())
        sink.finish()
        sink.close()
        assertEquals(1, transport.drains)
        assertEquals(1, releases)
    }

    @Test fun volumeOverlapUnderrunAndErrorsRevokeBitPerfectStatus() {
        val transport = Transport()
        sink(transport).use { sink ->
            sink.configure(PcmFormat(48_000, 2, PcmEncoding.PCM_24_PACKED))
            sink.write(ByteArray(60))
            assertTrue(sink.bitPerfectActive())
            sink.setVolume(0f, 0f)
            assertFalse(sink.bitPerfectActive())
            sink.setVolume(1f, 1f)
            sink.setExclusiveAllowed(false)
            assertFalse(sink.bitPerfectActive())
            sink.setExclusiveAllowed(true)
            transport.underruns = 1
            assertFalse(sink.bitPerfectActive())
            transport.underruns = 0
            transport.error = -4
            assertFalse(sink.bitPerfectActive())
            assertThrows(IllegalStateException::class.java) { sink.write(ByteArray(60)) }
        }
    }

    @Test fun pausedConfigurationAndFailedRateNegotiationReleaseResources() {
        val transport = Transport()
        sink(transport).use { sink ->
            sink.setPlaying(false)
            sink.configure(PcmFormat(48_000, 2, PcmEncoding.PCM_24_PACKED))
            assertTrue(transport.paused)
            sink.flush()
            sink.setPlaying(true)
            assertFalse(transport.paused)
        }
        assertTrue(transport.closed)
        val rejecting = Transport().apply { rejectClock = true }
        var releases = 0
        sink(rejecting) { releases++ }.use { sink ->
            assertThrows(IllegalStateException::class.java) {
                sink.configure(PcmFormat(48_000, 2, PcmEncoding.PCM_24_PACKED))
            }
        }
        assertTrue(rejecting.closed)
        assertEquals(1, releases)
    }
}
