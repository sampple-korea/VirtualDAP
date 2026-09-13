package com.virtualdap.host.audio

import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.math.roundToInt

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
            .sortedBy { kotlin.math.abs(it.toLong() - source.sampleRate) }
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
}

/**
 * Stateful linear PCM converter for compatibility fallback. It keeps the interpolation phase and
 * final input frame across bridge packets so resampling never resets at packet boundaries.
 */
class StreamingPcmConverter(
    val source: PcmFormat,
    val target: PcmFormat,
) {
    private var pending = FloatArray(0)
    private var sourcePosition = 0.0

    fun convert(input: ByteArray): ByteArray {
        require(input.size % source.frameSizeBytes == 0) { "PCM input contains a partial frame" }
        if (input.isEmpty()) return input
        if (source == target) return input
        val mapped = decodeAndMap(input)
        if (source.sampleRate == target.sampleRate) return encode(mapped)

        val combined = FloatArray(pending.size + mapped.size)
        pending.copyInto(combined)
        mapped.copyInto(combined, pending.size)
        val channels = target.channelCount
        val frames = combined.size / channels
        val step = source.sampleRate.toDouble() / target.sampleRate
        val estimatedFrames = ((frames - sourcePosition) / step).toInt().coerceAtLeast(1)
        val output = ByteArrayOutputStream(estimatedFrames * target.frameSizeBytes)
        while (sourcePosition + 1.0 < frames) {
            val leftFrame = floor(sourcePosition).toInt()
            val fraction = (sourcePosition - leftFrame).toFloat()
            for (channel in 0 until channels) {
                val left = combined[leftFrame * channels + channel]
                val right = combined[(leftFrame + 1) * channels + channel]
                writeSample(output, left + (right - left) * fraction)
            }
            sourcePosition += step
        }
        val discardedFrames = floor(sourcePosition).toInt().coerceAtMost((frames - 1).coerceAtLeast(0))
        pending = combined.copyOfRange(discardedFrames * channels, combined.size)
        sourcePosition -= discardedFrames
        return output.toByteArray()
    }

    private fun decodeAndMap(input: ByteArray): FloatArray {
        val frames = input.size / source.frameSizeBytes
        val decoded = FloatArray(source.channelCount)
        val mapped = FloatArray(frames * target.channelCount)
        var offset = 0
        repeat(frames) { frame ->
            for (channel in 0 until source.channelCount) {
                decoded[channel] = readSample(input, offset)
                offset += source.encoding.bytesPerSample
            }
            mapChannels(decoded, mapped, frame * target.channelCount)
        }
        return mapped
    }

    private fun mapChannels(sourceFrame: FloatArray, targetFrames: FloatArray, offset: Int) {
        when {
            source.channelCount == target.channelCount -> sourceFrame.copyInto(targetFrames, offset)
            source.channelCount == 1 && target.channelCount == 2 -> {
                targetFrames[offset] = sourceFrame[0]
                targetFrames[offset + 1] = sourceFrame[0]
            }
            target.channelCount == 1 -> targetFrames[offset] = sourceFrame.average().toFloat().coerceIn(-1f, 1f)
            target.channelCount == 2 -> {
                var left = sourceFrame[0]
                var right = sourceFrame[1]
                if (sourceFrame.size >= 3) {
                    left += sourceFrame[2] * 0.7071f
                    right += sourceFrame[2] * 0.7071f
                }
                if (sourceFrame.size >= 4) {
                    left += sourceFrame[3] * 0.25f
                    right += sourceFrame[3] * 0.25f
                }
                if (sourceFrame.size >= 6) {
                    left += sourceFrame[4] * 0.5f
                    right += sourceFrame[5] * 0.5f
                }
                if (sourceFrame.size >= 8) {
                    left += sourceFrame[6] * 0.5f
                    right += sourceFrame[7] * 0.5f
                }
                targetFrames[offset] = (left / 2.4571f).coerceIn(-1f, 1f)
                targetFrames[offset + 1] = (right / 2.4571f).coerceIn(-1f, 1f)
            }
            else -> throw IllegalArgumentException(
                "Unsupported channel conversion: ${source.channelCount} to ${target.channelCount}",
            )
        }
    }

    private fun readSample(input: ByteArray, offset: Int): Float = when (source.encoding) {
        PcmEncoding.PCM_16 -> {
            val value = (input[offset].toInt() and 0xff) or (input[offset + 1].toInt() shl 8)
            value.toShort() / 32768f
        }
        PcmEncoding.PCM_24_PACKED -> {
            var value = (input[offset].toInt() and 0xff) or
                ((input[offset + 1].toInt() and 0xff) shl 8) or
                ((input[offset + 2].toInt() and 0xff) shl 16)
            if (value and 0x800000 != 0) value = value or -0x1000000
            value / 8_388_608f
        }
        PcmEncoding.PCM_32 -> {
            val value = (input[offset].toInt() and 0xff) or
                ((input[offset + 1].toInt() and 0xff) shl 8) or
                ((input[offset + 2].toInt() and 0xff) shl 16) or
                (input[offset + 3].toInt() shl 24)
            (value / 2_147_483_648.0).toFloat()
        }
        PcmEncoding.PCM_FLOAT -> {
            val bits = (input[offset].toInt() and 0xff) or
                ((input[offset + 1].toInt() and 0xff) shl 8) or
                ((input[offset + 2].toInt() and 0xff) shl 16) or
                (input[offset + 3].toInt() shl 24)
            Float.fromBits(bits).takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
        }
    }

    private fun encode(samples: FloatArray): ByteArray {
        val output = ByteArrayOutputStream(samples.size * target.encoding.bytesPerSample)
        samples.forEach { writeSample(output, it) }
        return output.toByteArray()
    }

    private fun writeSample(output: ByteArrayOutputStream, unbounded: Float) {
        val sample = unbounded.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
        when (target.encoding) {
            PcmEncoding.PCM_16 -> writeInteger(output, (sample * 32_768f).roundToInt().coerceIn(-32_768, 32_767), 2)
            PcmEncoding.PCM_24_PACKED -> writeInteger(
                output,
                (sample * 8_388_608f).roundToInt().coerceIn(-8_388_608, 8_388_607),
                3,
            )
            PcmEncoding.PCM_32 -> {
                val value = (sample * 2_147_483_648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                writeInteger(output, value.toInt(), 4)
            }
            PcmEncoding.PCM_FLOAT -> writeInteger(output, sample.toRawBits(), 4)
        }
    }

    private fun writeInteger(output: ByteArrayOutputStream, value: Int, bytes: Int) {
        repeat(bytes) { byte -> output.write(value ushr (byte * 8) and 0xff) }
    }
}
