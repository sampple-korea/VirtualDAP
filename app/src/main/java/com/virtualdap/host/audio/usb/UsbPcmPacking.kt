package com.virtualdap.host.audio.usb

import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import kotlin.math.roundToLong

/** UAC samples are left-justified within their little-endian subslots. */
class UsbPcmPacking(val source: PcmFormat, val profile: UsbAudioStreamingProfile) {
    init {
        require(source.channelCount == profile.channelCount)
        require(profile.subslotBytes in 2..4 && profile.bitResolution in setOf(16, 24, 32))
        require(profile.bitResolution <= profile.subslotBytes * 8)
        require(profile.pcm.xor(profile.floatingPoint) && !profile.rawData) {
            "Select an unambiguous PCM alternate setting; RAW/DSD and selectable multi-format alternatives need their typed driver"
        }
        require(!profile.floatingPoint || (profile.bitResolution == 32 && profile.subslotBytes == 4))
    }
    private val floatOutput = profile.floatingPoint
    val outputFormat = PcmFormat(source.sampleRate, source.channelCount, when {
        floatOutput -> PcmEncoding.PCM_FLOAT
        profile.bitResolution == 16 -> PcmEncoding.PCM_16
        profile.bitResolution == 24 -> PcmEncoding.PCM_24_PACKED
        else -> PcmEncoding.PCM_32
    })
    val preservesSource: Boolean = if (source.encoding == PcmEncoding.PCM_FLOAT) floatOutput
        else !floatOutput && profile.bitResolution >= source.encoding.bytesPerSample * 8

    fun pack(input: ByteArray, leftGain: Float = 1f, rightGain: Float = 1f): ByteArray {
        require(input.size <= 1024 * 1024 && input.size % source.frameSizeBytes == 0)
        require(leftGain.isFinite() && rightGain.isFinite() && leftGain in 0f..1f && rightGain in 0f..1f)
        val sampleBytes = source.encoding.bytesPerSample
        val samples = input.size / sampleBytes
        val result = ByteArray(samples * profile.subslotBytes)
        repeat(samples) { sample ->
            val channel = sample % source.channelCount
            val gain = when (channel) { 0 -> leftGain; 1 -> rightGain; else -> (leftGain + rightGain) / 2f }
            var raw = 0
            repeat(sampleBytes) { byte -> raw = raw or ((input[sample * sampleBytes + byte].toInt() and 0xff) shl (byte * 8)) }
            val value: Long
            if (source.encoding == PcmEncoding.PCM_FLOAT) {
                val floating = Float.fromBits(raw)
                value = if (floatOutput) {
                    if (gain == 1f) raw.toLong() else ((if (floating.isFinite()) floating else 0f) * gain).toRawBits().toLong()
                } else {
                    val bounded = (if (floating.isFinite()) floating.toDouble() else 0.0).coerceIn(-1.0, 1.0) * gain
                    val scale = 1L shl (profile.bitResolution - 1)
                    bounded.times(scale).roundToLong().coerceIn(-scale, scale - 1)
                        .shl(profile.subslotBytes * 8 - profile.bitResolution)
                }
            } else {
                val sourceBits = sampleBytes * 8
                val signed = (raw shl (32 - sourceBits) shr (32 - sourceBits)).toLong()
                val delta = profile.bitResolution - sourceBits
                val aligned = if (delta >= 0) signed shl delta else signed shr -delta
                value = if (floatOutput) {
                    (signed.toDouble() / (1L shl (sourceBits - 1)) * gain).toFloat().toRawBits().toLong()
                } else {
                    (if (gain == 1f) aligned else (aligned * gain.toDouble()).roundToLong())
                        .shl(profile.subslotBytes * 8 - profile.bitResolution)
                }
            }
            repeat(profile.subslotBytes) { byte -> result[sample * profile.subslotBytes + byte] = (value shr (byte * 8)).toByte() }
        }
        return result
    }

    companion object {
        fun candidates(source: PcmFormat, profiles: List<UsbAudioStreamingProfile>): List<UsbAudioStreamingProfile> =
            profiles.filter { profile ->
                runCatching { UsbPcmPacking(source, profile) }.isSuccess &&
                    (profile.protocol == 0x20 || profile.rates.any { it.contains(source.sampleRate) })
            }.sortedWith(
                compareByDescending<UsbAudioStreamingProfile> { UsbPcmPacking(source, it).preservesSource }
                    .thenBy { kotlin.math.abs(it.bitResolution - source.encoding.bytesPerSample * 8) }
                    .thenByDescending { it.maximumPacketBytes },
            )
    }
}
