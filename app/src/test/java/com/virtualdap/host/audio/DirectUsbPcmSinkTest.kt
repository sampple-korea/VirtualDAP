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
    private inner class Transport(
        override val profile: UsbAudioStreamingProfile = this@DirectUsbPcmSinkTest.profile,
    ) : UsbOutputTransport {
        val bytes = ByteArrayOutputStream()
        var started = false
        var startedRate: Int? = null
        var paused = false
        var closed = false
        var underruns = 0L
        var error = 0
        var drains = 0
        var rejectClock = false
        override fun start(sampleRate: Int) {
            check(profile.rates.any { it.contains(sampleRate) } && !rejectClock) { "USB clock rejected the rate" }
            started = true
            startedRate = sampleRate
        }
        override fun write(bytes: ByteArray): Int {
            check(started && !paused && !closed)
            if (error != 0) return -1
            val count = minOf(profile.frameBytes * 2, bytes.size) // Force many complete-frame writes.
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
            bytes.size().toLong() / profile.frameBytes, bytes.size().toLong() / profile.frameBytes,
            bytes.size().toLong() / profile.frameBytes, underruns, 0, 0, 0, error, 3,
        )
    }
    private fun sink(
        transport: Transport,
        createResampler: (Int, Int, Int) -> FloatPcmResampler = { _, _, _ -> error("Unexpected resampling") },
        released: () -> Unit = {},
    ): DirectUsbPcmSink =
        DirectUsbPcmSink(42, route, createResampler) { id, select ->
            assertEquals(42, id)
            assertEquals(transport.profile, select(listOf(transport.profile)))
            UsbHostController.DirectConnection(transport, released)
        }

    private class PassthroughResampler : FloatPcmResampler {
        var closed = false
        override fun convert(interleavedPcm: FloatArray) = interleavedPcm.copyOf()
        override fun finish() = FloatArray(0)
        override fun close() { closed = true }
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

    @Test fun unsupportedDsdPcmRateUsesStreamingHighQualityFallbackAndResetsOnFlush() {
        val transport = Transport()
        val converters = mutableListOf<PassthroughResampler>()
        val requests = mutableListOf<Triple<Int, Int, Int>>()
        val sink = sink(transport, createResampler = { source, target, channels ->
            requests += Triple(source, target, channels)
            PassthroughResampler().also(converters::add)
        })
        sink.use {
            it.setPlaying(false)
            val source = PcmFormat(352_800, 2, PcmEncoding.PCM_FLOAT)
            val configured = it.configure(source)
            assertEquals(PcmFormat(48_000, 2, PcmEncoding.PCM_24_PACKED), configured.configured)
            assertFalse(configured.sourcePreserved)
            assertEquals(48_000, transport.startedRate)
            assertEquals(listOf(Triple(352_800, 48_000, 2)), requests)

            it.flush()
            assertTrue(converters.first().closed)
            assertEquals(2, converters.size)
            it.setPlaying(true)
            val input = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(0.5f).putFloat(-0.5f).array()
            assertEquals(input.size, it.write(input))
            assertArrayEquals(
                byteArrayOf(0, 0, 0, 64, 0, 0, 0, -64),
                transport.bytes.toByteArray(),
            )
            assertFalse(it.bitPerfectActive())
        }
        assertTrue(converters.all { it.closed })
    }

    @Test fun rejectedExactProfileFallsThroughToAnotherAlternateAndRate() {
        val exact = profile.copy(alternateSetting = 1, rates = listOf(UsbSampleRateRange(96_000, 96_000)))
        val fallback = profile.copy(alternateSetting = 2)
        val selected = mutableListOf<UsbAudioStreamingProfile>()
        val transports = mutableListOf<Transport>()
        var releases = 0
        val sink = DirectUsbPcmSink(42, route, { _, _, _ -> PassthroughResampler() }) { _, choose ->
            val chosen = choose(listOf(fallback, exact))
            selected += chosen
            val transport = Transport(chosen).apply { rejectClock = chosen == exact }
            transports += transport
            UsbHostController.DirectConnection(transport) { releases++ }
        }

        sink.use {
            val configured = it.configure(PcmFormat(96_000, 2, PcmEncoding.PCM_24_PACKED))
            assertEquals(48_000, configured.configured.sampleRate)
            assertFalse(configured.sourcePreserved)
        }
        assertEquals(listOf(exact, fallback), selected)
        assertTrue(transports.all { it.closed })
        assertEquals(2, releases)
    }
}
