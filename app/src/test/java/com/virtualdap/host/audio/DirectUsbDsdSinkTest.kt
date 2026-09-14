package com.virtualdap.host.audio

import com.virtualdap.host.audio.usb.DirectUsbDsdSink
import com.virtualdap.host.audio.usb.UsbAudioStreamingProfile
import com.virtualdap.host.audio.usb.UsbHostController
import com.virtualdap.host.audio.usb.UsbOutputStatistics
import com.virtualdap.host.audio.usb.UsbOutputTransport
import com.virtualdap.host.audio.usb.UsbSampleRateRange
import com.virtualdap.host.model.OutputRoute
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUsbDsdSinkTest {
    private val source = DsdFormat(2_822_400, 2)
    private val layout = NativeDsdLayout(4, DsdWordOrder.BIG_ENDIAN, DsdBitOrder.MSB_FIRST)
    private val nativeProfile = profile(88_200, 4, 32).copy(
        pcm = false, rawData = true, nativeDsd = layout, dsdQualification = "fixture rule",
    )
    private val dopProfile = profile(176_400, 3, 24)
    private val route = OutputRoute(-1_000_009, "Test DSD DAC", 11, true, emptyList(), emptyList(), 9)

    private fun profile(rate: Int, slot: Int, bits: Int) = UsbAudioStreamingProfile(
        1, 0, 1, 2, 0, 1, null, 1, 1024, 3, 2, slot, bits,
        pcm = true, floatingPoint = false, rawData = false, clockEntity = null,
        rates = listOf(UsbSampleRateRange(rate, rate)), endpointFrequencyControl = false,
    )

    private inner class Transport(override val profile: UsbAudioStreamingProfile) : UsbOutputTransport {
        val bytes = ByteArrayOutputStream()
        var startedAt = 0
        var paused = false
        var closed = false
        var drains = 0
        var underruns = 0L
        override fun start(sampleRate: Int) { startedAt = sampleRate }
        override fun write(bytes: ByteArray): Int {
            check(!closed)
            val count = minOf(profile.frameBytes * 2, bytes.size)
            this.bytes.write(bytes, 0, count)
            return count
        }
        override fun control(type: Int, request: Int, value: Int, index: Int, bytes: ByteArray) = error("unused")
        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun flush() { check(paused); bytes.reset() }
        override fun drain() { drains++ }
        override fun close() { closed = true }
        override fun statistics() = UsbOutputStatistics(
            bytes.size() / profile.frameBytes.toLong(), bytes.size() / profile.frameBytes.toLong(),
            bytes.size() / profile.frameBytes.toLong(), underruns, 0, 0, 0, 0, 3,
        )
    }

    private fun sink(
        profiles: List<UsbAudioStreamingProfile>,
        selected: (Transport) -> Unit = {},
        released: () -> Unit = {},
    ): DirectUsbDsdSink = DirectUsbDsdSink(9, route) { id, choose ->
        assertEquals(9, id)
        Transport(choose(profiles)).also(selected).let { UsbHostController.DirectConnection(it, released) }
    }

    @Test fun nativeDsdUsesQualifiedLayoutRateAndPreservesAllBytesAcrossPartialWrites() {
        lateinit var transport: Transport
        var releases = 0
        val input = ByteArray(800) { it.toByte() }
        sink(listOf(dopProfile, nativeProfile), { transport = it }, { releases++ }).use { output ->
            val configured = output.configure(source, DsdOutputMode.NATIVE_DSD)
            assertEquals(88_200, configured.transportRate)
            assertEquals(input.size, output.write(input))
            assertArrayEquals(NativeDsdEncoder(source, layout).encode(input), transport.bytes.toByteArray())
            assertTrue(output.sourcePreservedActive())
            assertEquals(route, output.routedOutput())
            output.finish()
            assertTrue(output.sourcePreservedActive())
            assertEquals(route, output.routedOutput())
        }
        assertEquals(88_200, transport.startedAt)
        assertEquals(1, transport.drains)
        assertEquals(1, releases)
    }

    @Test fun dopRequiresExplicitCapabilityAndUsesThe24BitCarrierWithoutPcmProcessing() {
        assertThrows(IllegalStateException::class.java) {
            sink(listOf(dopProfile)).use { it.configure(source, DsdOutputMode.DOP) }
        }
        lateinit var transport: Transport
        sink(listOf(dopProfile), { transport = it }).use { output ->
            output.configure(source, DsdOutputMode.DOP, dopCapabilityConfirmed = true)
            output.write(byteArrayOf(0x12, 0x34, 0x56, 0x78))
            assertEquals(176_400, transport.startedAt)
            assertArrayEquals(byteArrayOf(0x56, 0x12, 5, 0x78, 0x34, 5), transport.bytes.toByteArray())
        }
    }

    @Test fun flushDropsOldEpochFragmentsAndRestartsTheDopMarkerSequence() {
        lateinit var transport: Transport
        sink(listOf(dopProfile), { transport = it }).use { output ->
            output.configure(source, DsdOutputMode.DOP, dopCapabilityConfirmed = true)
            output.write(byteArrayOf(1, 2))
            output.setPlaying(false)
            output.flush()
            output.setPlaying(true)
            output.write(byteArrayOf(3, 4, 5, 6))
            assertArrayEquals(byteArrayOf(5, 3, 5, 6, 4, 5), transport.bytes.toByteArray())
        }
    }

    @Test fun truncatedNativeWordFailsClosedWithoutDrainingOrClaimingPreservation() {
        lateinit var transport: Transport
        var releases = 0
        val output = sink(listOf(nativeProfile), { transport = it }, { releases++ })
        output.configure(source, DsdOutputMode.NATIVE_DSD)
        output.write(byteArrayOf(1, 2))
        assertFalse(output.sourcePreservedActive())
        assertThrows(IllegalStateException::class.java) { output.finish() }
        assertTrue(transport.closed)
        assertEquals(0, transport.drains)
        assertEquals(1, releases)
    }
}
