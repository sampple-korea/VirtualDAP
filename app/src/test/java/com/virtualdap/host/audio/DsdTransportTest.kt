package com.virtualdap.host.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DsdTransportTest {
    private val stereo = DsdFormat(2_822_400, 2)

    @Test fun framesUseTheSameAlternatingMarkerAcrossChannels() {
        val output = DopEncoder(stereo).encode(byteArrayOf(0x12, 0x34, 0x56, 0x78, 1, 2, 3, 4))
        assertArrayEquals(
            byteArrayOf(0x56, 0x12, 0x05, 0x78, 0x34, 0x05, 3, 1, 0xfa.toByte(), 4, 2, 0xfa.toByte()),
            output,
        )
        assertEquals(176_400, stereo.dopSampleRate)
    }

    @Test fun packetSplitsDoNotResetMarkerOrSwapChannels() {
        val input = ByteArray(28) { it.toByte() }
        val whole = DopEncoder(stereo).encode(input)
        val split = DopEncoder(stereo)
        val packets = split.encode(input.copyOfRange(0, 2)) +
            split.encode(input.copyOfRange(2, 10)) + split.encode(input.copyOfRange(10, input.size))
        split.finish()
        assertArrayEquals(whole, packets)
    }

    @Test fun dsfLeastSignificantBitOrderIsNormalized() {
        val encoder = DopEncoder(DsdFormat(2_822_400, 1, DsdBitOrder.LSB_FIRST))
        assertArrayEquals(byteArrayOf(0x40, 0x80.toByte(), 0x05), encoder.encode(byteArrayOf(1, 2)))
    }

    @Test fun uacFourByteSubslotsKeepThe24BitsLeftJustified() {
        assertArrayEquals(
            byteArrayOf(0, 0x56, 0x12, 5),
            DopEncoder(DsdFormat(2_822_400, 1), DopContainer.PADDED_32).encode(byteArrayOf(0x12, 0x56)),
        )
    }

    @Test fun unsupportedOrMixedRoutesAlwaysRequirePcmConversion() {
        assertEquals(
            DsdOutputMode.PCM_CONVERSION,
            DsdOutputPlanner.select(stereo, DsdOutputCapabilities(2, setOf(stereo.sampleRate), setOf(176_400), false)),
        )
        assertEquals(
            DsdOutputMode.DOP,
            DsdOutputPlanner.select(stereo, DsdOutputCapabilities(2, dopCarrierRates = setOf(176_400), bitTransparent = true)),
        )
        assertEquals(
            DsdOutputMode.NATIVE_DSD,
            DsdOutputPlanner.select(stereo, DsdOutputCapabilities(2, nativeDsdRates = setOf(stereo.sampleRate), bitTransparent = true)),
        )
    }

    @Test fun truncatedChannelAndDopFramesAreRejected() {
        val encoder = DopEncoder(stereo)
        assertThrows(IllegalArgumentException::class.java) { encoder.encode(byteArrayOf(1)) }
        encoder.encode(byteArrayOf(1, 2))
        assertThrows(IllegalStateException::class.java) { encoder.finish() }
    }
}
