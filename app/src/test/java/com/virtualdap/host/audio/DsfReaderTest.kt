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

class DsfReaderTest {
    @Test fun planarBlocksAreStreamedAsCanonicalInterleavedDsdWithoutTailPadding() {
        val file = dsf(
            left = byteArrayOf(1, 2, 3, 4, 5, 6),
            right = byteArrayOf(11, 12, 13, 14, 15, 16),
            blockBytes = 4,
        )
        val tinyReads = object : FilterInputStream(ByteArrayInputStream(file)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(2, length))
        }
        DsfReader.open(tinyReads).use { reader ->
            assertEquals(DsdFormat(2_822_400, 2, DsdBitOrder.LSB_FIRST), reader.info.format)
            assertEquals(48, reader.info.sampleCountPerChannel)
            assertEquals(0, reader.samplePosition)
            val packets = generateSequence { reader.readInterleaved(6) }.toList()
            assertArrayEquals(
                byteArrayOf(1, 11, 2, 12, 3, 13, 4, 14, 5, 15, 6, 16),
                packets.fold(ByteArray(0)) { all, packet -> all + packet },
            )
            assertEquals(listOf(6, 2, 4), packets.map(ByteArray::size))
            assertEquals(48, reader.samplePosition)
            assertNull(reader.readInterleaved())
        }
    }

    @Test fun msbFlagAndMultichannelTypeArePreserved() {
        val file = dsf(
            channels = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4), byteArrayOf(5, 6)),
            channelType = 3,
            bitsPerSample = 8,
            blockBytes = 2,
        )
        DsfReader.open(ByteArrayInputStream(file)).use { reader ->
            assertEquals(DsdBitOrder.MSB_FIRST, reader.info.format.bitOrder)
            assertEquals(3, reader.info.format.channelCount)
            assertArrayEquals(byteArrayOf(1, 3, 5, 2, 4, 6), reader.readInterleaved())
        }
        DsdContainerReader.open(ByteArrayInputStream(file)).use { assertTrue(it is DsfReader) }
    }

    @Test fun malformedHeadersSizesAndChannelDeclarationsFailBeforePlayback() {
        val valid = dsf(left = byteArrayOf(1, 2), right = byteArrayOf(3, 4), blockBytes = 2)
        var malformedInputClosed = false
        val malformed = object : ByteArrayInputStream(valid.clone().also { it[0] = 'X'.code.toByte() }) {
            override fun close() { malformedInputClosed = true; super.close() }
        }
        assertThrows(IllegalArgumentException::class.java) { DsfReader.open(malformed) }
        assertTrue(malformedInputClosed)
        assertThrows(IllegalArgumentException::class.java) {
            DsfReader.open(ByteArrayInputStream(valid.clone().also { putLe32(it, 48, 3) }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DsfReader.open(ByteArrayInputStream(valid.clone().also { putLe64(it, 84, 15) }))
        }
    }

    @Test fun truncatedAudioIsDetectedAtTheExactBlockRead() {
        val valid = dsf(left = byteArrayOf(1, 2), right = byteArrayOf(3, 4), blockBytes = 2)
        val reader = DsfReader.open(ByteArrayInputStream(valid.copyOf(valid.size - 1)))
        assertThrows(java.io.EOFException::class.java) { reader.readInterleaved() }
        reader.close()
        assertThrows(IllegalStateException::class.java) { reader.readInterleaved() }
    }

    private fun dsf(
        left: ByteArray = byteArrayOf(1, 2),
        right: ByteArray = byteArrayOf(3, 4),
        blockBytes: Int = 2,
        channels: List<ByteArray> = listOf(left, right),
        channelType: Int = if (channels.size == 1) 1 else 2,
        bitsPerSample: Int = 1,
    ): ByteArray {
        require(channels.isNotEmpty() && channels.all { it.size == channels[0].size })
        val blocks = (channels[0].size + blockBytes - 1) / blockBytes
        val dataBytes = blocks * blockBytes * channels.size
        val totalBytes = 28 + 52 + 12 + dataBytes
        return ByteArrayOutputStream().apply {
            write("DSD ".toByteArray())
            writeLe64(28)
            writeLe64(totalBytes.toLong())
            writeLe64(0)
            write("fmt ".toByteArray())
            writeLe64(52)
            writeLe32(1)
            writeLe32(0)
            writeLe32(channelType)
            writeLe32(channels.size)
            writeLe32(2_822_400)
            writeLe32(bitsPerSample)
            writeLe64(channels[0].size * 8L)
            writeLe32(blockBytes)
            writeLe32(0)
            write("data".toByteArray())
            writeLe64((12 + dataBytes).toLong())
            repeat(blocks) { block ->
                channels.forEach { channel ->
                    repeat(blockBytes) { index -> write(channel.getOrElse(block * blockBytes + index) { 0 }.toInt()) }
                }
            }
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) =
        repeat(4) { write(value ushr (it * 8)) }

    private fun ByteArrayOutputStream.writeLe64(value: Long) =
        repeat(8) { write((value ushr (it * 8)).toInt()) }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) =
        repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() }

    private fun putLe64(bytes: ByteArray, offset: Int, value: Long) =
        repeat(8) { bytes[offset + it] = (value ushr (it * 8)).toByte() }
}
