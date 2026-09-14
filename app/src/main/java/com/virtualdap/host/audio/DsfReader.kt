package com.virtualdap.host.audio

import java.io.EOFException
import java.io.InputStream

data class DsfInfo(
    val format: DsdFormat,
    val sampleCountPerChannel: Long,
    val blockBytesPerChannel: Int,
    val totalFileBytes: Long,
    val metadataOffset: Long,
) {
    val audioBytesPerChannel: Long = sampleCountPerChannel / 8
    val durationMillis: Long = sampleCountPerChannel / format.sampleRate * 1_000L +
        sampleCountPerChannel % format.sampleRate * 1_000L / format.sampleRate
}

/**
 * Streaming Sony DSF reader. DSF stores one fixed-size planar block per channel; this reader emits
 * canonical chronological bytes interleaved by channel and discards only the format-defined tail
 * padding. The input is never buffered as a complete music file.
 */
class DsfReader private constructor(private val input: InputStream) : DsdStreamReader {
    val info: DsfInfo
    private val blockCount: Long
    private var blocksRead = 0L
    @Volatile private var audioBytesReadPerChannel = 0L
    private var currentBlocks: Array<ByteArray>? = null
    private var currentValidBytes = 0
    private var currentOffset = 0
    @Volatile private var closed = false

    init {
        val dsd = input.readExact(28, "DSD header")
        require(dsd.ascii(0) == "DSD ") { "Not a DSF stream" }
        require(dsd.u64le(4) == 28L) { "Unsupported DSF header size" }
        val totalFileBytes = dsd.u64le(12)
        val metadataOffset = dsd.u64le(20)

        val formatHeader = input.readExact(12, "fmt header")
        require(formatHeader.ascii(0) == "fmt ") { "Missing DSF fmt chunk" }
        val formatChunkBytes = formatHeader.u64le(4)
        require(formatChunkBytes in 52L..MAX_CHUNK_BYTES.toLong()) { "Invalid DSF fmt chunk size" }
        val formatPayload = input.readExact((formatChunkBytes - 12).toInt(), "fmt payload")
        require(formatPayload.u32le(0) == 1) { "Unsupported DSF format version" }
        require(formatPayload.u32le(4) == 0) { "Compressed DSF is unsupported" }
        val channelType = formatPayload.u32le(8)
        val channels = formatPayload.u32le(12)
        val expectedChannels = when (channelType) {
            1 -> 1
            2 -> 2
            3 -> 3
            4, 5 -> 4
            6 -> 5
            7 -> 6
            else -> throw IllegalArgumentException("Unsupported DSF channel type: $channelType")
        }
        require(channels == expectedChannels) { "DSF channel type/count mismatch" }
        val sampleRate = formatPayload.u32le(16)
        val bitOrder = when (val bitsPerSample = formatPayload.u32le(20)) {
            1 -> DsdBitOrder.LSB_FIRST
            8 -> DsdBitOrder.MSB_FIRST
            else -> throw IllegalArgumentException("Unsupported DSF bit-order field: $bitsPerSample")
        }
        val sampleCount = formatPayload.u64le(24)
        require(sampleCount > 0 && sampleCount % 8L == 0L) {
            "DSF sample count must contain complete DSD bytes"
        }
        val blockBytes = formatPayload.u32le(32)
        require(blockBytes in 1..MAX_BLOCK_BYTES) { "Invalid DSF channel block size" }
        require(formatPayload.u32le(36) == 0) { "DSF reserved field is not zero" }
        val format = DsdFormat(sampleRate, channels, bitOrder)

        val dataHeader = input.readExact(12, "data header")
        require(dataHeader.ascii(0) == "data") { "Missing DSF data chunk" }
        val dataChunkBytes = dataHeader.u64le(4)
        require(dataChunkBytes >= 12) { "Invalid DSF data chunk size" }
        val audioBytes = sampleCount / 8L
        blockCount = (audioBytes + blockBytes - 1L) / blockBytes
        val expectedPayload = multiplyExact(multiplyExact(blockCount, blockBytes.toLong()), channels.toLong())
        require(dataChunkBytes - 12L == expectedPayload) {
            "DSF data size does not match its samples, channels and block padding"
        }
        val dataEnd = addExact(addExact(28L, formatChunkBytes), dataChunkBytes)
        require(totalFileBytes >= dataEnd) { "DSF total file size ends inside audio data" }
        require(metadataOffset == 0L || metadataOffset in dataEnd..totalFileBytes) {
            "DSF metadata pointer overlaps audio data"
        }
        info = DsfInfo(format, sampleCount, blockBytes, totalFileBytes, metadataOffset)
    }

    /** Returns at most [maximumBytes], always containing complete interleaved channel frames. */
    override fun readInterleaved(maximumBytes: Int): ByteArray? {
        check(!closed) { "DSF reader is closed" }
        require(maximumBytes in info.format.channelCount..MAX_PACKET_BYTES) { "Invalid DSF packet limit" }
        val maximumFrames = maximumBytes / info.format.channelCount
        require(maximumFrames > 0)
        if (currentBlocks == null && !loadBlockSet()) return null

        val available = currentValidBytes - currentOffset
        val frames = minOf(maximumFrames, available)
        val result = ByteArray(frames * info.format.channelCount)
        val blocks = checkNotNull(currentBlocks)
        var destination = 0
        repeat(frames) { frame ->
            repeat(info.format.channelCount) { channel ->
                result[destination++] = blocks[channel][currentOffset + frame]
            }
        }
        currentOffset += frames
        audioBytesReadPerChannel += frames
        if (currentOffset == currentValidBytes) {
            currentBlocks = null
            currentOffset = 0
            currentValidBytes = 0
        }
        return result
    }

    override val format: DsdFormat get() = info.format
    override val sampleCountPerChannel: Long get() = info.sampleCountPerChannel
    override val durationMillis: Long get() = info.durationMillis
    override val samplePosition: Long get() = audioBytesReadPerChannel * 8L

    private fun loadBlockSet(): Boolean {
        if (blocksRead == blockCount) return false
        val blocks = Array(info.format.channelCount) {
            input.readExact(info.blockBytesPerChannel, "DSF channel block")
        }
        val remaining = info.audioBytesPerChannel - audioBytesReadPerChannel
        currentValidBytes = minOf(info.blockBytesPerChannel.toLong(), remaining).toInt()
        check(currentValidBytes > 0)
        currentOffset = 0
        currentBlocks = blocks
        blocksRead++
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        currentBlocks = null
        input.close()
    }

    private fun InputStream.readExact(size: Int, label: String): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(result, offset, size - offset)
            if (read < 0) throw EOFException("Truncated $label")
            if (read == 0) {
                val one = read()
                if (one < 0) throw EOFException("Truncated $label")
                result[offset++] = one.toByte()
            } else offset += read
        }
        return result
    }

    private fun ByteArray.ascii(offset: Int): String =
        String(this, offset, 4, Charsets.US_ASCII)

    private fun ByteArray.u32le(offset: Int): Int {
        require(offset >= 0 && offset + 4 <= size) { "Truncated DSF integer" }
        var value = 0L
        repeat(4) { byte -> value = value or ((this[offset + byte].toLong() and 0xffL) shl (byte * 8)) }
        require(value <= Int.MAX_VALUE) { "DSF integer exceeds the supported range" }
        return value.toInt()
    }

    private fun ByteArray.u64le(offset: Int): Long {
        require(offset >= 0 && offset + 8 <= size) { "Truncated DSF integer" }
        var value = 0L
        repeat(8) { byte ->
            val part = this[offset + byte].toLong() and 0xffL
            if (byte == 7) require(part and 0x80L == 0L) { "DSF integer exceeds the supported range" }
            value = value or (part shl (byte * 8))
        }
        return value
    }

    private fun multiplyExact(left: Long, right: Long): Long = try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("DSF size overflows the supported range")
    }

    private fun addExact(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("DSF size overflows the supported range")
    }

    companion object {
        private const val MAX_CHUNK_BYTES = 1024 * 1024
        private const val MAX_BLOCK_BYTES = 1024 * 1024
        private const val MAX_PACKET_BYTES = 1024 * 1024
        fun open(input: InputStream): DsfReader = try {
            DsfReader(input)
        } catch (failure: Throwable) {
            try {
                input.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }
}
