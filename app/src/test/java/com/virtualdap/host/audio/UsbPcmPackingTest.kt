package com.virtualdap.host.audio

import com.virtualdap.host.audio.usb.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbPcmPackingTest {
    private fun profile(bits: Int, slot: Int, floating: Boolean = false) = UsbAudioStreamingProfile(
        1, 0, 1, 1, 0, 1, null, 1, 512, 3, 2, slot, bits, !floating, floating, false, null,
        listOf(UsbSampleRateRange(48_000, 48_000)), false,
    )
    @Test fun packed24IsLeftJustifiedInsideFourByteUsbSubslots() {
        val adapter = UsbPcmPacking(PcmFormat(48_000, 2, PcmEncoding.PCM_24_PACKED), profile(24, 4))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 0, -1, -2, -3), adapter.pack(byteArrayOf(1, 2, 3, -1, -2, -3)))
        assertTrue(adapter.preservesSource)
    }
    @Test fun integerWideningDoesNotLoseLowBitsOrSign() {
        val adapter = UsbPcmPacking(PcmFormat(48_000, 2, PcmEncoding.PCM_16), profile(32, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, -128, 0, 0, -1, 127), adapter.pack(byteArrayOf(0, -128, -1, 127)))
        assertTrue(adapter.preservesSource)
        val full = UsbPcmPacking(PcmFormat(48_000, 2, PcmEncoding.PCM_32), profile(32, 4))
        val source = byteArrayOf(1, 2, 3, 4, -1, -2, -3, -4)
        assertArrayEquals(source, full.pack(source))
    }
    @Test fun narrowingIsNeverAdvertisedAsSourcePreserving() {
        val adapter = UsbPcmPacking(PcmFormat(48_000, 2, PcmEncoding.PCM_32), profile(24, 3))
        assertFalse(adapter.preservesSource)
        assertArrayEquals(byteArrayOf(2, 3, 4, -2, -3, -4), adapter.pack(byteArrayOf(1, 2, 3, 4, -1, -2, -3, -4)))
    }
    @Test fun twentyBitUsbSubslotsArePackedAndLeftAlignedWithoutFalsePreservation() {
        val twentyBit = profile(20, 3)
        val adapter = UsbPcmPacking(PcmFormat(48_000, 2, PcmEncoding.PCM_24_PACKED), twentyBit)
        assertArrayEquals(
            byteArrayOf(0x10, 0x32, 0x54, 0, -0x32, -0x55),
            adapter.pack(byteArrayOf(0x1f, 0x32, 0x54, 0x0f, -0x32, -0x55)),
        )
        assertFalse(adapter.preservesSource)
    }
    @Test fun floatConversionClipsSafelyAndMuteIsZero() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).putFloat(-1f).array()
        val adapter = UsbPcmPacking(PcmFormat(48_000, 2, PcmEncoding.PCM_FLOAT), profile(16, 2))
        assertArrayEquals(byteArrayOf(-1, 127, 0, -128), adapter.pack(input))
        assertArrayEquals(ByteArray(4), adapter.pack(input, 0f, 0f))
        assertFalse(adapter.preservesSource)
    }
    @Test fun floatUsbProfilesReceiveFloatsNotIntegerBitPatterns() {
        val adapter = UsbPcmPacking(PcmFormat(48_000, 2, PcmEncoding.PCM_16), profile(32, 4, true))
        val converted = ByteBuffer.wrap(adapter.pack(byteArrayOf(0, 64, 0, -128))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.5f, converted.float, 0f)
        assertEquals(-1f, converted.float, 0f)
    }
    @Test fun ambiguousAndRawAlternativesAreRejectedFromPcmPath() {
        val source = PcmFormat(48_000, 2, PcmEncoding.PCM_32)
        assertThrows(IllegalArgumentException::class.java) { UsbPcmPacking(source, profile(32, 4).copy(rawData = true)) }
        assertThrows(IllegalArgumentException::class.java) { UsbPcmPacking(source, profile(32, 4).copy(floatingPoint = true)) }
    }
}
