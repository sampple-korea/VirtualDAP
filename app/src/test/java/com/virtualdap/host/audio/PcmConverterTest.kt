package com.virtualdap.host.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmConverterTest {
    @Test
    fun plannerKeepsExactFormatFirstAndDeduplicatesFallbacks() {
        val source = PcmFormat(96_000, 2, PcmEncoding.PCM_24_PACKED)
        val candidates = OutputFormatPlanner.candidates(source, listOf(48_000, 44_100), true)

        assertEquals(source, candidates.first())
        assertEquals(candidates.distinct(), candidates)
        assertTrue(PcmFormat(96_000, 2, PcmEncoding.PCM_FLOAT) in candidates)
        assertTrue(PcmFormat(48_000, 2, PcmEncoding.PCM_16) in candidates)
        assertTrue(candidates.indexOfFirst { it.sampleRate == 48_000 } < candidates.indexOfFirst { it.sampleRate == 44_100 })
    }

    @Test
    fun plannerSkipsPackedFormatsOnOldHosts() {
        val source = PcmFormat(192_000, 2, PcmEncoding.PCM_32)
        val candidates = OutputFormatPlanner.candidates(source, emptyList(), false)

        assertEquals(PcmEncoding.PCM_FLOAT, candidates.first().encoding)
        assertTrue(candidates.none { it.encoding == PcmEncoding.PCM_32 })
    }

    @Test
    fun convertsSigned16BitToFloatExactlyAtKeyValues() {
        val source = PcmFormat(48_000, 1, PcmEncoding.PCM_16)
        val target = PcmFormat(48_000, 1, PcmEncoding.PCM_FLOAT)
        val input = byteArrayOf(0x00, 0x80.toByte(), 0x00, 0x00, 0xff.toByte(), 0x7f)

        val output = StreamingPcmConverter(source, target).convert(input)
        val bits = output.toList().chunked(4).map { bytes ->
            (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8) or
                ((bytes[2].toInt() and 0xff) shl 16) or (bytes[3].toInt() shl 24)
        }

        assertEquals(-1f, Float.fromBits(bits[0]))
        assertEquals(0f, Float.fromBits(bits[1]))
        assertEquals(32767 / 32768f, Float.fromBits(bits[2]))
    }

    @Test
    fun sameFormatConversionIsBitExact() {
        val format = PcmFormat(44_100, 2, PcmEncoding.PCM_24_PACKED)
        val input = ByteArray(120) { (it * 17).toByte() }

        assertArrayEquals(input, StreamingPcmConverter(format, format).convert(input))
    }

    @Test
    fun resamplingKeepsPhaseAcrossPacketBoundaries() {
        val source = PcmFormat(48_000, 1, PcmEncoding.PCM_16)
        val target = PcmFormat(96_000, 1, PcmEncoding.PCM_16)
        val whole = byteArrayOf(0, 0, 0x10, 0, 0x20, 0, 0x30, 0, 0x40, 0)
        fun doubling() = object : FloatPcmResampler {
            override fun convert(interleavedPcm: FloatArray) = FloatArray(interleavedPcm.size * 2) {
                interleavedPcm[it / 2]
            }
            override fun finish() = FloatArray(0)
            override fun close() = Unit
        }
        val single = StreamingPcmConverter(source, target, doubling()).convert(whole)
        val splitConverter = StreamingPcmConverter(source, target, doubling())
        val split = splitConverter.convert(whole.copyOfRange(0, 4)) +
            splitConverter.convert(whole.copyOfRange(4, whole.size))

        assertArrayEquals(single, split)
        assertTrue(single.isNotEmpty())
    }

    @Test
    fun rejectsPartialSourceFrame() {
        val converter = StreamingPcmConverter(
            PcmFormat(48_000, 2, PcmEncoding.PCM_16),
            PcmFormat(48_000, 2, PcmEncoding.PCM_16),
        )

        assertTrue(runCatching { converter.convert(ByteArray(3)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun finishingResampledStreamEmitsItsTailExactlyOnce() {
        val source = PcmFormat(48_000, 1, PcmEncoding.PCM_16)
        val target = PcmFormat(96_000, 1, PcmEncoding.PCM_16)
        val rateConverter = object : FloatPcmResampler {
            override fun convert(interleavedPcm: FloatArray) = FloatArray(interleavedPcm.size * 2) {
                interleavedPcm[it / 2]
            }
            override fun finish() = floatArrayOf(0.25f, 0.25f)
            override fun close() = Unit
        }
        val converter = StreamingPcmConverter(source, target, rateConverter)
        val body = converter.convert(byteArrayOf(0, 0, 0x10, 0, 0x20, 0))
        val tail = converter.finish()

        assertEquals(8 * target.frameSizeBytes, body.size + tail.size)
        assertTrue(tail.isNotEmpty())
        assertEquals(0, converter.finish().size)
    }

    @Test
    fun streamingConversionBoundsEachNativeRateConverterInput() {
        val seen = mutableListOf<Int>()
        val rateConverter = object : FloatPcmResampler {
            override fun convert(interleavedPcm: FloatArray): FloatArray {
                seen += interleavedPcm.size
                return interleavedPcm
            }
            override fun finish() = FloatArray(0)
            override fun close() = Unit
        }
        val source = PcmFormat(8_000, 1, PcmEncoding.PCM_16)
        val target = PcmFormat(768_000, 2, PcmEncoding.PCM_16)
        val converter = StreamingPcmConverter(source, target, rateConverter)
        var outputBytes = 0

        converter.convertInto(ByteArray(1024 * 1024)) { outputBytes += it.size }

        assertTrue(seen.size > 100)
        assertTrue(seen.all { it in 1..8 * 1024 && it % target.channelCount == 0 })
        assertEquals(2 * 1024 * 1024, outputBytes)
    }

    @Test
    fun directUsbPlannerKeepsDsdPcmFallbackInItsClockFamily() {
        val source = PcmFormat(6_144_000, 2, PcmEncoding.PCM_FLOAT)
        val profile = com.virtualdap.host.audio.usb.UsbAudioStreamingProfile(
            1, 0, 1, 1, 0, 1, null, 1, 512, 3, 2, 4, 24,
            pcm = true, floatingPoint = false, rawData = false, clockEntity = null,
            rates = listOf(
                com.virtualdap.host.audio.usb.UsbSampleRateRange(352_800, 352_800),
                com.virtualdap.host.audio.usb.UsbSampleRateRange(384_000, 384_000),
            ),
            endpointFrequencyControl = true,
        )

        val candidates = com.virtualdap.host.audio.usb.DirectUsbPcmPlanner.candidates(source, profile, profile.rates)

        assertEquals(384_000, candidates.first().inputFormat.sampleRate)
        assertTrue(candidates.all { it.inputFormat.sampleRate < source.sampleRate })
    }
}
