package com.virtualdap.host.audio

/** Pure format policy shared by Android mixer discovery, DSD conversion, UI and tests. */
object OfficialBitPerfectPolicy {
    fun exactMatch(requested: PcmFormat, advertised: Collection<PcmFormat>): PcmFormat? =
        advertised.firstOrNull { it == requested }

    /**
     * DSD-to-PCM is an explicit source conversion. Its fixed 8:1 output rate and channel layout
     * are never changed to make a route work; only the PCM sample representation may be selected.
     */
    fun dsdPcmTarget(decoded: PcmFormat, advertised: Collection<PcmFormat>): PcmFormat? {
        val sameClock = advertised.filter {
            it.sampleRate == decoded.sampleRate && it.channelCount == decoded.channelCount
        }
        val preference = listOf(
            PcmEncoding.PCM_FLOAT,
            PcmEncoding.PCM_32,
            PcmEncoding.PCM_24_PACKED,
            PcmEncoding.PCM_16,
        )
        return preference.firstNotNullOfOrNull { encoding ->
            sameClock.firstOrNull { it.encoding == encoding }
        }
    }

    fun summary(advertised: Collection<PcmFormat>): String = advertised
        .distinct()
        .sortedWith(compareBy<PcmFormat> { it.sampleRate }.thenBy { it.channelCount }.thenBy { it.encoding.wireId })
        .joinToString { it.shortLabel() }
        .ifEmpty { "none" }
}
