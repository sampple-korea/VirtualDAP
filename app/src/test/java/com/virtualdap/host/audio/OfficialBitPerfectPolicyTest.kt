package com.virtualdap.host.audio

import org.junit.Assert.*
import org.junit.Test

class OfficialBitPerfectPolicyTest {
    private val source = PcmFormat(96_000, 2, PcmEncoding.PCM_24_PACKED)

    @Test fun rejectsEveryInexactAlternativeAndEmptyCapabilities() {
        for (alternative in listOf(
            source.copy(sampleRate = 48_000),
            source.copy(channelCount = 1),
            source.copy(encoding = PcmEncoding.PCM_16),
            source.copy(encoding = PcmEncoding.PCM_32),
        )) assertNull(OfficialBitPerfectPolicy.exactMatch(source, listOf(alternative)))
        assertNull(OfficialBitPerfectPolicy.exactMatch(source, emptyList()))
        assertEquals(source, OfficialBitPerfectPolicy.exactMatch(source, listOf(source)))
    }

    @Test fun explicitDsdConversionCannotChangeClockOrChannelLayoutForCompatibility() {
        val decoded = PcmFormat(352_800, 2, PcmEncoding.PCM_FLOAT)
        assertNull(OfficialBitPerfectPolicy.dsdPcmTarget(decoded, listOf(
            decoded.copy(sampleRate = 176_400), decoded.copy(channelCount = 1),
        )))
        val integer = decoded.copy(encoding = PcmEncoding.PCM_24_PACKED)
        assertEquals(integer, OfficialBitPerfectPolicy.dsdPcmTarget(decoded, listOf(integer)))
        assertEquals(decoded, OfficialBitPerfectPolicy.dsdPcmTarget(decoded, listOf(integer, decoded)))
    }
}
