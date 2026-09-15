package com.virtualdap.host.audio.usb

import com.virtualdap.host.audio.OutputFormatPlanner
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import kotlin.math.abs

/** A format handed to [UsbPcmPacking] after any required channel/rate conversion. */
data class DirectUsbPcmCandidate(
    val inputFormat: PcmFormat,
    val profile: UsbAudioStreamingProfile,
)

/**
 * Pure direct-USB negotiation policy. Profile selection is separate from clock-range selection so
 * UAC2 clock ranges can be queried through the already-permitted device before playback starts.
 */
object DirectUsbPcmPlanner {
    /** Compare quality across every alternate, not just within the first usable interface. */
    fun conversionTier(source: PcmFormat, candidate: DirectUsbPcmCandidate): Int = when {
        candidate.inputFormat == source && UsbPcmPacking(source, candidate.profile).preservesSource -> 0
        candidate.inputFormat.sampleRate == source.sampleRate &&
            candidate.inputFormat.channelCount == source.channelCount -> 1
        else -> 2
    }

    private val commonRates = listOf(
        8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000,
        44_100, 48_000, 64_000, 88_200, 96_000, 128_000,
        176_400, 192_000, 256_000, 352_800, 384_000,
        705_600, 768_000, 1_411_200, 1_536_000, 2_822_400,
        3_072_000, 5_644_800, 6_144_000,
    )

    fun compatibleProfiles(
        source: PcmFormat,
        profiles: List<UsbAudioStreamingProfile>,
    ): List<UsbAudioStreamingProfile> = profiles.filter { profile ->
        (profile.protocol == 0 || profile.protocol == 0x20) &&
            supportsChannelMapping(source.channelCount, profile.channelCount) &&
            preferredEncoding(profile) != null &&
            runCatching {
                UsbPcmPacking(
                    PcmFormat(source.sampleRate, profile.channelCount, requireNotNull(preferredEncoding(profile))),
                    profile,
                )
            }.isSuccess
    }.sortedWith(
        compareByDescending<UsbAudioStreamingProfile> { it.channelCount == source.channelCount }
            .thenByDescending { profile ->
                profile.protocol == 0x20 || profile.rates.any { it.contains(source.sampleRate) }
            }
            .thenByDescending { profile ->
                profile.channelCount == source.channelCount &&
                    runCatching { UsbPcmPacking(source, profile).preservesSource }.getOrDefault(false)
            }
            .thenBy { abs(it.bitResolution - source.encoding.bytesPerSample * 8) }
            .thenByDescending { it.maximumPacketBytes },
    )

    fun candidates(
        source: PcmFormat,
        profile: UsbAudioStreamingProfile,
        supportedRates: List<UsbSampleRateRange>,
    ): List<DirectUsbPcmCandidate> {
        val fallbackEncoding = preferredEncoding(profile) ?: return emptyList()
        if (!supportsChannelMapping(source.channelCount, profile.channelCount)) return emptyList()
        return candidateRates(source.sampleRate, supportedRates).mapNotNull { rate ->
            val exactShape = rate == source.sampleRate && profile.channelCount == source.channelCount
            val target = if (exactShape) source else PcmFormat(rate, profile.channelCount, fallbackEncoding)
            runCatching { UsbPcmPacking(target, profile) }
                .map { DirectUsbPcmCandidate(target, profile) }
                .getOrNull()
        }.distinctBy { it.inputFormat }
    }

    private fun candidateRates(sourceRate: Int, ranges: List<UsbSampleRateRange>): List<Int> {
        val exact = sourceRate.takeIf { rate -> ranges.any { it.contains(rate) } }
        val possible = linkedSetOf<Int>()
        ranges.forEach { range ->
            commonRates.filterTo(possible, range::contains)
            alignedNearest(sourceRate, range)?.let(possible::add)
            possible += range.minimum
            possible += range.maximum
        }
        val fallback = possible.asSequence()
            .filter { it in 8_000..6_144_000 && it != exact }
            // Android output is capped at 768 kHz. Higher direct-USB rates are useful only when
            // downsampling an even faster DSD-to-PCM intermediate, never for explosive upsampling.
            .filter { it <= 768_000 || it < sourceRate }
            .sortedWith(
                compareBy<Int> { if (OutputFormatPlanner.sameRateFamily(sourceRate, it)) 0 else 1 }
                    .thenBy { abs(it.toLong() - sourceRate) }
                    .thenByDescending { it },
            )
            .toList()
        return listOfNotNull(exact) + fallback
    }

    private fun alignedNearest(sourceRate: Int, range: UsbSampleRateRange): Int? {
        val clamped = sourceRate.coerceIn(range.minimum, range.maximum)
        if (range.resolution == 0) return clamped
        val steps = ((clamped.toLong() - range.minimum + range.resolution / 2L) / range.resolution)
        val aligned = range.minimum.toLong() + steps * range.resolution
        return aligned.coerceIn(range.minimum.toLong(), range.maximum.toLong()).toInt()
            .takeIf(range::contains)
    }

    private fun preferredEncoding(profile: UsbAudioStreamingProfile): PcmEncoding? = when {
        profile.rawData || profile.pcm == profile.floatingPoint -> null
        profile.floatingPoint && profile.bitResolution == 32 && profile.subslotBytes == 4 -> PcmEncoding.PCM_FLOAT
        profile.floatingPoint -> null
        profile.bitResolution !in 16..32 || profile.subslotBytes !in 2..4 -> null
        profile.bitResolution <= 16 -> PcmEncoding.PCM_16
        profile.bitResolution <= 24 -> PcmEncoding.PCM_24_PACKED
        else -> PcmEncoding.PCM_32
    }

    private fun supportsChannelMapping(source: Int, target: Int): Boolean =
        source == target || (source == 1 && target == 2) || target in 1..2
}
