package com.virtualdap.host.audio

import com.virtualdap.host.audio.usb.*
import org.junit.Assert.*
import org.junit.Test

class NativeDsdTransportTest {
    private val source = DsdFormat(2_822_400, 2)
    private fun layout(order: DsdWordOrder, bits: DsdBitOrder = DsdBitOrder.MSB_FIRST) = NativeDsdLayout(4, order, bits)

    @Test fun nativeWordsRetainChannelAndTemporalOrderAcrossPackets() {
        val input = byteArrayOf(1, 11, 2, 12, 3, 13, 4, 14)
        val be = NativeDsdEncoder(source, layout(DsdWordOrder.BIG_ENDIAN))
        assertEquals(88_200, be.transportRate)
        assertEquals(0, be.encode(input.copyOfRange(0, 6)).size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 11, 12, 13, 14), be.encode(input.copyOfRange(6, 8)))
        be.finish()
        val le = NativeDsdEncoder(source, layout(DsdWordOrder.LITTLE_ENDIAN))
        assertArrayEquals(byteArrayOf(4, 3, 2, 1, 14, 13, 12, 11), le.encode(input))
    }

    @Test fun bitOrderChangesOnlyWhenTheNegotiatedLayoutRequiresIt() {
        val encoder = NativeDsdEncoder(source, layout(DsdWordOrder.BIG_ENDIAN, DsdBitOrder.LSB_FIRST))
        assertArrayEquals(ByteArray(8) { 0x80.toByte() }, encoder.encode(ByteArray(8) { 1 }))
        val truncated = NativeDsdEncoder(source, layout(DsdWordOrder.BIG_ENDIAN))
        truncated.encode(ByteArray(2))
        assertThrows(IllegalStateException::class.java) { truncated.finish() }
        assertEquals(3_072_000, DsdFormat(49_152_000, 2).dopSampleRate)
    }

    private fun profile(alt: Int = 2, raw: Boolean = false) = UsbAudioStreamingProfile(
        1, 0, 1, alt, 0x20, 1, null, 1, 512, 3, 2, 4, 32,
        !raw, false, raw, 10, emptyList(), false,
    )

    @Test fun knownNativeAlternateCannotBeUsedByPcmPlanner() {
        val profiles = UsbDsdDeviceRules.qualify(listOf(profile(1), profile(2)), UsbDeviceIdentity(0x2772, 0x0230, 1))
        assertTrue(profiles[0].pcm)
        assertFalse(profiles[1].pcm)
        assertNotNull(profiles[1].nativeDsd)
        val pcm = UsbPcmPacking.candidates(PcmFormat(48_000, 2, PcmEncoding.PCM_32), profiles)
        assertEquals(listOf(1), pcm.map { it.alternateSetting })
    }

    @Test fun rawDescriptorAloneNeverQualifiesNativeDsdAndAmaneroRevisionMatters() {
        assertNull(UsbDsdDeviceRules.qualify(listOf(profile(raw = true)), UsbDeviceIdentity(0x1234, 0x5678, 1))[0].nativeDsd)
        val supported = UsbDsdDeviceRules.qualify(listOf(profile()), UsbDeviceIdentity(0x16d0, 0x071a, 0x0199))[0]
        assertEquals(DsdWordOrder.LITTLE_ENDIAN, supported.nativeDsd?.wordOrder)
        assertNull(UsbDsdDeviceRules.qualify(listOf(profile()), UsbDeviceIdentity(0x16d0, 0x071a, 0x0200))[0].nativeDsd)
        assertFalse(UsbDsdDeviceRules.qualify(listOf(profile()), UsbDeviceIdentity(0x16d0, 0x071a, 0x0200))[0].pcm)
        assertNull(UsbDsdDeviceRules.qualify(listOf(profile(raw = true)), UsbDeviceIdentity(0x2fc6, 0xf06b, 1))[0].nativeDsd)
    }

    @Test fun kernelRawAllowListIncludesVendorAndDeviceRulesButHonorsOverrides() {
        assertNotNull(UsbDsdDeviceRules.qualify(listOf(profile(raw = true)), UsbDeviceIdentity(0x1511, 0x1000, 1))[0].nativeDsd)
        assertNotNull(UsbDsdDeviceRules.qualify(listOf(profile(raw = true)), UsbDeviceIdentity(0x0661, 0x0883, 1))[0].nativeDsd)
        assertNotNull(UsbDsdDeviceRules.qualify(listOf(profile(raw = true)), UsbDeviceIdentity(0x21b4, 0x0232, 1))[0].nativeDsd)
        assertNull(UsbDsdDeviceRules.qualify(listOf(profile(raw = true)), UsbDeviceIdentity(0x152a, 0x880a, 1))[0].nativeDsd)
        assertNull(UsbDsdDeviceRules.qualify(listOf(profile(raw = true)), UsbDeviceIdentity(0x2772, 0x0502, 1))[0].nativeDsd)
    }
}
