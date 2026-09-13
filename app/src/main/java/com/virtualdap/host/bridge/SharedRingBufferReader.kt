package com.virtualdap.host.bridge

import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BridgeProtocolException(message: String) : IllegalStateException(message)

data class RingBufferMetadata(
    val format: PcmFormat,
    val streamEpoch: Long,
    val framesWritten: Long,
    val overruns: Long,
    val bufferedBytes: Int,
    val capacityBytes: Int,
)

/**
 * Single-consumer view over the shared PCM ring. The producer publishes its write offset last;
 * the consumer publishes its read offset after copying, so neither side observes partial frames.
 */
class SharedRingBufferReader(source: ByteBuffer) {
    private val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    init {
        validateHeader()
    }

    fun metadata(): RingBufferMetadata {
        validateHeader()
        val format = readFormat()
        val capacity = capacity()
        val write = checkedOffset(RingBufferLayout.WRITE_OFFSET, capacity, "write")
        val read = checkedOffset(RingBufferLayout.READ_OFFSET, capacity, "read")
        return RingBufferMetadata(
            format = format,
            streamEpoch = buffer.getLong(RingBufferLayout.STREAM_EPOCH_OFFSET),
            framesWritten = buffer.getLong(RingBufferLayout.FRAMES_WRITTEN_OFFSET),
            overruns = buffer.getLong(RingBufferLayout.OVERRUNS_OFFSET),
            bufferedBytes = distance(read, write, capacity),
            capacityBytes = capacity,
        )
    }

    fun read(maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        if (maxBytes == 0) return ByteArray(0)

        val capacity = capacity()
        val write = checkedOffset(RingBufferLayout.WRITE_OFFSET, capacity, "write")
        val read = checkedOffset(RingBufferLayout.READ_OFFSET, capacity, "read")
        val frameSize = readFormat().frameSizeBytes
        val available = distance(read, write, capacity)
        val count = minOf(available, maxBytes).let { it - (it % frameSize) }
        if (count == 0) return ByteArray(0)

        val output = ByteArray(count)
        val first = minOf(count, capacity - read)
        copyFromData(read, output, 0, first)
        if (first < count) copyFromData(0, output, first, count - first)

        // The producer uses an acquire load before reusing this region. Keep this as the last write.
        buffer.putInt(RingBufferLayout.READ_OFFSET, (read + count) % capacity)
        return output
    }

    private fun copyFromData(dataOffset: Int, target: ByteArray, targetOffset: Int, count: Int) {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(RingBufferLayout.HEADER_BYTES + dataOffset)
        view.get(target, targetOffset, count)
    }

    private fun readFormat(): PcmFormat {
        val sampleRate = buffer.getInt(RingBufferLayout.SAMPLE_RATE_OFFSET)
        val channels = buffer.getShort(RingBufferLayout.CHANNEL_COUNT_OFFSET).toInt() and 0xffff
        val encodingId = buffer.getShort(RingBufferLayout.ENCODING_OFFSET).toInt() and 0xffff
        return try {
            PcmFormat(sampleRate, channels, PcmEncoding.fromWireId(encodingId))
        } catch (error: IllegalArgumentException) {
            throw BridgeProtocolException(error.message ?: "Invalid PCM format")
        }
    }

    private fun validateHeader() {
        if (buffer.capacity() < RingBufferLayout.HEADER_BYTES) {
            throw BridgeProtocolException("Shared region is smaller than the protocol header")
        }
        val magic = buffer.getInt(RingBufferLayout.MAGIC_OFFSET)
        if (magic != RingBufferLayout.MAGIC) {
            throw BridgeProtocolException("Invalid bridge magic: 0x${magic.toUInt().toString(16)}")
        }
        val version = buffer.getShort(RingBufferLayout.VERSION_OFFSET)
        if (version != RingBufferLayout.VERSION) {
            throw BridgeProtocolException("Unsupported bridge version: $version")
        }
        val headerBytes = buffer.getShort(RingBufferLayout.HEADER_SIZE_OFFSET).toInt() and 0xffff
        if (headerBytes != RingBufferLayout.HEADER_BYTES) {
            throw BridgeProtocolException("Invalid bridge header size: $headerBytes")
        }
        val capacity = capacity()
        if (capacity < 2 || RingBufferLayout.HEADER_BYTES.toLong() + capacity > buffer.capacity()) {
            throw BridgeProtocolException("Invalid ring capacity: $capacity")
        }
    }

    private fun capacity(): Int = buffer.getInt(RingBufferLayout.CAPACITY_OFFSET)

    private fun checkedOffset(fieldOffset: Int, capacity: Int, name: String): Int {
        val value = buffer.getInt(fieldOffset)
        if (value !in 0 until capacity) throw BridgeProtocolException("Invalid $name offset: $value")
        return value
    }

    private fun distance(from: Int, to: Int, capacity: Int): Int =
        if (to >= from) to - from else capacity - from + to
}
