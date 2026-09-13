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
        val single = StreamingPcmConverter(source, target).convert(whole)
        val splitConverter = StreamingPcmConverter(source, target)
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
}
