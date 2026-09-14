package com.virtualdap.host.audio

/** Ordered fallback formats. Exact PCM always wins; conversion is attempted only after rejection. */
object OutputFormatPlanner {
    fun candidates(
        source: PcmFormat,
        routeSampleRates: List<Int>,
        packedHighResolutionSupported: Boolean,
    ): List<PcmFormat> {
        val encodings = buildList {
            if (source.encoding !in setOf(PcmEncoding.PCM_24_PACKED, PcmEncoding.PCM_32) ||
                packedHighResolutionSupported
            ) {
                add(source.encoding)
            }
            if (source.encoding != PcmEncoding.PCM_FLOAT) add(PcmEncoding.PCM_FLOAT)
            if (source.encoding != PcmEncoding.PCM_16) add(PcmEncoding.PCM_16)
        }.distinct()
        val alternateRates = (routeSampleRates.filter { it in 8_000..768_000 } + 48_000 + 44_100)
            .distinct()
            .filterNot { it == source.sampleRate }
            .sortedWith(
                compareBy<Int> { if (sameRateFamily(source.sampleRate, it)) 0 else 1 }
                    .thenBy { kotlin.math.abs(it.toLong() - source.sampleRate) },
            )
            .take(8)
        val result = mutableListOf<PcmFormat>()

        fun add(rate: Int, channels: Int, encoding: PcmEncoding) {
            result += PcmFormat(rate, channels, encoding)
        }

        encodings.forEach { add(source.sampleRate, source.channelCount, it) }
        alternateRates.forEach { rate -> encodings.forEach { add(rate, source.channelCount, it) } }
        if (source.channelCount > 2) {
            encodings.forEach { add(source.sampleRate, 2, it) }
            alternateRates.forEach { rate -> encodings.forEach { add(rate, 2, it) } }
        }
        return result.distinct()
    }

    internal fun sameRateFamily(source: Int, target: Int): Boolean =
        (source % 44_100 == 0 && target % 44_100 == 0) ||
            (source % 48_000 == 0 && target % 48_000 == 0)
}
