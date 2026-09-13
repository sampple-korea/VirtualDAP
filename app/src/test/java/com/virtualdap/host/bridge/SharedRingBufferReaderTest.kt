package com.virtualdap.host.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedRingBufferReaderTest {
    @Test
    fun readsLinearFramesAndAdvancesConsumer() {
        val buffer = initializedBuffer(capacity = 32, read = 2, write = 10)
        for (index in 2 until 10) buffer.put(RingBufferLayout.HEADER_BYTES + index, index.toByte())

        val result = SharedRingBufferReader(buffer).read(8)

        assertArrayEquals(byteArrayOf(2, 3, 4, 5, 6, 7, 8, 9), result)
        assertEquals(10, buffer.getInt(RingBufferLayout.READ_OFFSET))
    }

    @Test
    fun readsAcrossWrapAndKeepsWholeFrames() {
        val buffer = initializedBuffer(capacity = 16, read = 12, write = 6)
        for (index in 12 until 16) buffer.put(RingBufferLayout.HEADER_BYTES + index, index.toByte())
        for (index in 0 until 6) buffer.put(RingBufferLayout.HEADER_BYTES + index, (16 + index).toByte())

        val result = SharedRingBufferReader(buffer).read(9)

        assertArrayEquals(byteArrayOf(12, 13, 14, 15, 16, 17, 18, 19), result)
        assertEquals(4, buffer.getInt(RingBufferLayout.READ_OFFSET))
    }

    @Test
    fun reportsMetadataAndRejectsCorruptOffsets() {
        val buffer = initializedBuffer(capacity = 16, read = 4, write = 12)
        buffer.putLong(RingBufferLayout.STREAM_EPOCH_OFFSET, 7)
        buffer.putLong(RingBufferLayout.FRAMES_WRITTEN_OFFSET, 1_024)
        buffer.putLong(RingBufferLayout.OVERRUNS_OFFSET, 3)
        val reader = SharedRingBufferReader(buffer)

        val metadata = reader.metadata()
        assertEquals(48_000, metadata.format.sampleRate)
        assertEquals(8, metadata.bufferedBytes)
        assertEquals(7, metadata.streamEpoch)
        assertEquals(1_024, metadata.framesWritten)
        assertEquals(3, metadata.overruns)

        buffer.putInt(RingBufferLayout.WRITE_OFFSET, 16)
        assertThrows(BridgeProtocolException::class.java) { reader.metadata() }
    }

    @Test
    fun rejectsUnknownProtocol() {
        val buffer = initializedBuffer()
        buffer.putInt(RingBufferLayout.MAGIC_OFFSET, 0)

        assertThrows(BridgeProtocolException::class.java) { SharedRingBufferReader(buffer) }
    }

    private fun initializedBuffer(
        capacity: Int = 32,
        read: Int = 0,
        write: Int = 0,
    ): ByteBuffer = ByteBuffer.allocate(RingBufferLayout.HEADER_BYTES + capacity)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            putInt(RingBufferLayout.MAGIC_OFFSET, RingBufferLayout.MAGIC)
            putShort(RingBufferLayout.VERSION_OFFSET, RingBufferLayout.VERSION)
            putShort(RingBufferLayout.HEADER_SIZE_OFFSET, RingBufferLayout.HEADER_BYTES.toShort())
            putInt(RingBufferLayout.CAPACITY_OFFSET, capacity)
            putInt(RingBufferLayout.WRITE_OFFSET, write)
            putInt(RingBufferLayout.READ_OFFSET, read)
            putInt(RingBufferLayout.SAMPLE_RATE_OFFSET, 48_000)
            putShort(RingBufferLayout.CHANNEL_COUNT_OFFSET, 2)
            putShort(RingBufferLayout.ENCODING_OFFSET, 1)
        }
}
