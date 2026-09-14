package com.virtualdap.host.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DffReaderTest {
    @Test fun uncompressedClusteredFramesAreStreamedWithoutRepackingOrWholeFileBuffering() {
        val audio = byteArrayOf(1, 11, 2, 12, 3, 13)
        val stream = object : FilterInputStream(ByteArrayInputStream(dff(audio))) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(3, length))
        }
        DffReader.open(stream).use { reader ->
            assertEquals(DsdFormat(2_822_400, 2, DsdBitOrder.MSB_FIRST), reader.format)
            assertEquals(listOf("SLFT", "SRGT"), reader.info.channelIds)
            assertEquals(24, reader.sampleCountPerChannel)
            assertArrayEquals(byteArrayOf(1, 11, 2, 12), reader.readInterleaved(4))
            assertArrayEquals(byteArrayOf(3, 13), reader.readInterleaved(5))
            assertEquals(24, reader.samplePosition)
            assertNull(reader.readInterleaved())
        }
        DsdContainerReader.open(ByteArrayInputStream(dff(audio))).use { assertTrue(it is DffReader) }
    }

    @Test fun unknownEvenAndOddChunksAreSkippedWithinTheirDeclaredBoundaries() {
        val unknown = chunk("JUNK", byteArrayOf(9, 8, 7))
        DffReader.open(ByteArrayInputStream(dff(byteArrayOf(1, 2), extraBeforeAudio = unknown))).use {
            assertArrayEquals(byteArrayOf(1, 2), it.readInterleaved())
        }
    }

    @Test fun compressedMisalignedAndMissingPropertiesFailBeforeAudioOutput() {
        assertThrows(IllegalArgumentException::class.java) {
            DffReader.open(ByteArrayInputStream(dff(byteArrayOf(1, 2), compression = "DST ")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DffReader.open(ByteArrayInputStream(dff(byteArrayOf(1, 2, 3))))
        }
        val withoutProperties = form(chunk("FVER", byteArrayOf(1, 5, 0, 0)) + chunk("DSD ", byteArrayOf(1, 2)))
        assertThrows(IllegalArgumentException::class.java) { DffReader.open(ByteArrayInputStream(withoutProperties)) }
    }

    @Test fun malformedConstructionClosesInputAndTruncatedAudioFailsAtRead() {
        var closed = false
        val broken = dff(byteArrayOf(1, 2)).also { it[0] = 'X'.code.toByte() }
        val tracked = object : ByteArrayInputStream(broken) {
            override fun close() { closed = true; super.close() }
        }
        assertThrows(IllegalArgumentException::class.java) { DffReader.open(tracked) }
        assertTrue(closed)

        val complete = dff(byteArrayOf(1, 2, 3, 4))
        val reader = DffReader.open(ByteArrayInputStream(complete.copyOf(complete.size - 2)))
        assertThrows(java.io.EOFException::class.java) { reader.readInterleaved() }
        reader.close()
    }

    private fun dff(
        audio: ByteArray,
        compression: String = "DSD ",
        extraBeforeAudio: ByteArray = ByteArray(0),
    ): ByteArray {
        val properties = ByteArrayOutputStream().apply {
            write("SND ".toByteArray())
            write(chunk("FS  ", be32(2_822_400)))
            write(chunk("CHNL", be16(2) + "SLFTSRGT".toByteArray()))
            val name = if (compression == "DSD ") "not compressed" else "DST Encoded"
            write(chunk("CMPR", compression.toByteArray() + byteArrayOf(name.length.toByte()) + name.toByteArray()))
        }.toByteArray()
        return form(
            chunk("FVER", byteArrayOf(1, 5, 0, 0)) +
                chunk("PROP", properties) + extraBeforeAudio + chunk("DSD ", audio),
        )
    }

    private fun form(chunks: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write("FRM8".toByteArray())
        write(be64(4L + chunks.size))
        write("DSD ".toByteArray())
        write(chunks)
    }.toByteArray()

    private fun chunk(id: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray())
        write(be64(payload.size.toLong()))
        write(payload)
        if (payload.size % 2 != 0) write(0)
    }.toByteArray()

    private fun be16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun be32(value: Int) = ByteArray(4) { byte -> (value ushr ((3 - byte) * 8)).toByte() }
    private fun be64(value: Long) = ByteArray(8) { byte -> (value ushr ((7 - byte) * 8)).toByte() }
}
