package com.virtualdap.host.audio

import java.io.Closeable
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** PCM encoding/channel adapter with an explicitly supplied stateful rate converter. */
class StreamingPcmConverter(
    val source: PcmFormat,
    val target: PcmFormat,
    private val rateConverter: FloatPcmResampler? = null,
) : Closeable {
    private var finished = false

    init {
        require((source.sampleRate == target.sampleRate) == (rateConverter == null)) {
            "Sample-rate changes require one explicit converter, and matching rates require none"
        }
    }

    fun convert(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        convertInto(input, output::write)
        return output.toByteArray()
    }

    /**
     * Converts bounded source slices and immediately hands each result to the sink. This prevents
     * a legal 1 MiB low-rate bridge packet from becoming one enormous allocation when a DAC only
     * accepts a much higher clock.
     */
    fun convertInto(input: ByteArray, consume: (ByteArray) -> Unit) {
        check(!finished) { "PCM converter has already finished" }
        require(input.size <= MAX_INPUT_BYTES) { "PCM input exceeds the packet limit" }
        require(input.size % source.frameSizeBytes == 0) { "PCM input contains a partial frame" }
        if (input.isEmpty()) return
        if (source == target) {
            consume(input)
            return
        }
        val framesPerChunk = maxOf(1, MAX_MAPPED_SAMPLES_PER_CHUNK / target.channelCount)
        val chunkBytes = framesPerChunk * source.frameSizeBytes
        var offset = 0
        while (offset < input.size) {
            val length = minOf(chunkBytes, input.size - offset)
            convertChunk(input.copyOfRange(offset, offset + length))
                .takeIf { it.isNotEmpty() }
                ?.let(consume)
            offset += length
        }
    }

    private fun convertChunk(input: ByteArray): ByteArray {
        val mapped = decodeAndMap(input)
        if (source.sampleRate == target.sampleRate) return encode(mapped)
        return encode(requireNotNull(rateConverter).convert(mapped))
    }

    /** Emits the native filter tail only at end of stream and exactly once. */
    fun finish(): ByteArray {
        if (finished) return byteArrayOf()
        finished = true
        return rateConverter?.finish()?.let(::encode) ?: byteArrayOf()
    }

    override fun close() {
        rateConverter?.close()
        finished = true
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

    private companion object {
        const val MAX_INPUT_BYTES = 1024 * 1024
        const val MAX_MAPPED_SAMPLES_PER_CHUNK = 8 * 1024
    }
}
