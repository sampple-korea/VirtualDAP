package com.virtualdap.host.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmFormatTest {
    @Test
    fun derivesFrameSizeAndBitrate() {
        val format = PcmFormat(192_000, 2, PcmEncoding.PCM_24_PACKED)

        assertEquals(6, format.frameSizeBytes)
        assertEquals(9_216_000, format.nominalBitrate)
    }

    @Test
    fun rejectsInvalidChannelCount() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmFormat(48_000, 0, PcmEncoding.PCM_16)
        }
    }
}
