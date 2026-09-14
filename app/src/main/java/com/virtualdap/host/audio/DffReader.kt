package com.virtualdap.host.audio

import java.io.EOFException
import java.io.InputStream

data class DffInfo(
    val format: DsdFormat,
    val sampleCountPerChannel: Long,
    val channelIds: List<String>,
    val formatVersion: Int,
    val totalFileBytes: Long,
) {
    val durationMillis: Long = sampleCountPerChannel / format.sampleRate * 1_000L +
        sampleCountPerChannel % format.sampleRate * 1_000L / format.sampleRate
}

/** Sequential Philips DSDIFF 1.x reader for uncompressed `DSD ` sound chunks. */
class DffReader private constructor(private val input: InputStream) : DsdStreamReader {
    val info: DffInfo
    private var audioBytesRemaining: Long
    private val audioPadBytes: Int
    @Volatile private var bytesReadPerChannel = 0L
    private var padConsumed = false
    private var closed = false

    init {
        val formHeader = input.readExact(12, "DSDIFF form header")
        require(formHeader.ascii(0) == "FRM8") { "Not a DSDIFF stream" }
        val formDataBytes = formHeader.u64be(4)
        require(formDataBytes >= 4) { "Invalid DSDIFF form size" }
        val totalFileBytes = addExact(12L, formDataBytes)
        val formType = input.readExact(4, "DSDIFF form type").ascii(0)
        require(formType == "DSD ") { "Unsupported DSDIFF form type" }
        var formRemaining = formDataBytes - 4L

        var version: Int? = null
        var properties: Properties? = null
        var foundAudioBytes: Long? = null
        var firstChunk = true
        while (formRemaining > 0 && foundAudioBytes == null) {
            require(formRemaining >= 12) { "Truncated DSDIFF chunk header" }
            val header = input.readExact(12, "DSDIFF chunk header")
            formRemaining -= 12
            val id = header.ascii(0)
            val size = header.u64be(4)
            val pad = (size and 1L).toInt()
            require(size <= formRemaining - pad) { "DSDIFF chunk exceeds the form boundary" }
            if (firstChunk) require(id == "FVER") { "DSDIFF FVER must be the first chunk" }
            firstChunk = false
            when (id) {
                "FVER" -> {
                    require(version == null && size == 4L) { "Invalid or duplicate DSDIFF version chunk" }
                    val raw = input.readExact(4, "DSDIFF version").u32be(0)
                    require(raw ushr 24 == 1) { "Unsupported DSDIFF major version" }
                    version = raw
                }
                "PROP" -> {
                    require(properties == null) { "Duplicate DSDIFF property chunk" }
                    properties = readProperties(size)
                }
                "DSD " -> {
                    val parsed = properties ?: throw IllegalArgumentException("DSDIFF properties must precede audio")
                    require(parsed.compression == "DSD ") { "DSDIFF compression declaration is not uncompressed DSD" }
                    require(size > 0 && size % parsed.channelIds.size == 0L) {
                        "DSDIFF audio does not contain complete clustered frames"
                    }
                    foundAudioBytes = size
                }
                "DST " -> throw IllegalArgumentException("DST-compressed DSDIFF is unsupported")
                else -> input.skipExact(size, "DSDIFF $id chunk")
            }
            if (id != "DSD ") {
                if (pad != 0) require(input.readExact(1, "DSDIFF chunk pad")[0] == 0.toByte()) {
                    "DSDIFF pad byte is not zero"
                }
                formRemaining -= size + pad
            }
        }
        val parsed = properties ?: throw IllegalArgumentException("Missing DSDIFF properties")
        val dataBytes = foundAudioBytes ?: throw IllegalArgumentException("Missing uncompressed DSDIFF audio")
        val sampleRate = parsed.sampleRate
        require(parsed.channelIds.isNotEmpty()) { "Missing DSDIFF channel declaration" }
        require(parsed.compression == "DSD ") { "Only uncompressed DSDIFF is supported" }
        val perChannelBytes = dataBytes / parsed.channelIds.size
        val samples = multiplyExact(perChannelBytes, 8L)
        info = DffInfo(
            DsdFormat(sampleRate, parsed.channelIds.size, DsdBitOrder.MSB_FIRST),
            samples, parsed.channelIds, requireNotNull(version), totalFileBytes,
        )
        audioBytesRemaining = dataBytes
        audioPadBytes = (dataBytes and 1L).toInt()
    }

    override val format: DsdFormat get() = info.format
    override val sampleCountPerChannel: Long get() = info.sampleCountPerChannel
    override val durationMillis: Long get() = info.durationMillis
    override val samplePosition: Long get() = bytesReadPerChannel * 8L

    @Synchronized
    override fun readInterleaved(maximumBytes: Int): ByteArray? {
        check(!closed) { "DSDIFF reader is closed" }
        require(maximumBytes in format.channelCount..MAX_PACKET_BYTES) { "Invalid DSDIFF packet limit" }
        if (audioBytesRemaining == 0L) {
            consumeAudioPad()
            return null
        }
        val alignedMaximum = maximumBytes - maximumBytes % format.channelCount
        val bytes = minOf(audioBytesRemaining, alignedMaximum.toLong()).toInt()
        check(bytes > 0 && bytes % format.channelCount == 0)
        val result = input.readExact(bytes, "DSDIFF audio data")
        audioBytesRemaining -= bytes
        bytesReadPerChannel += bytes / format.channelCount
        if (audioBytesRemaining == 0L) consumeAudioPad()
        return result
    }

    private fun readProperties(size: Long): Properties {
        require(size in 4L..MAX_PROPERTY_BYTES.toLong()) { "Invalid DSDIFF property size" }
        val type = input.readExact(4, "DSDIFF property type").ascii(0)
        require(type == "SND ") { "Unsupported DSDIFF property type" }
        var remaining = size - 4L
        var rate: Int? = null
        var channelIds: List<String>? = null
        var compression: String? = null
        while (remaining > 0) {
            require(remaining >= 12) { "Truncated DSDIFF property chunk" }
            val header = input.readExact(12, "DSDIFF property header")
            remaining -= 12
            val id = header.ascii(0)
            val payloadSize = header.u64be(4)
            val pad = (payloadSize and 1L).toInt()
            require(payloadSize <= remaining - pad) { "DSDIFF property exceeds its container" }
            when (id) {
                "FS  " -> {
                    require(rate == null && payloadSize == 4L) { "Invalid or duplicate DSDIFF sample rate" }
                    rate = input.readExact(4, "DSDIFF sample rate").u32be(0)
                }
                "CHNL" -> {
                    require(channelIds == null && payloadSize in 6L..34L) { "Invalid or duplicate DSDIFF channels" }
                    val payload = input.readExact(payloadSize.toInt(), "DSDIFF channels")
                    val count = payload.u16be(0)
                    require(count in 1..8 && payloadSize == 2L + count * 4L) { "Invalid DSDIFF channel list" }
                    channelIds = List(count) { index -> payload.ascii(2 + index * 4) }.also { ids ->
                        require(ids.distinct().size == ids.size && ids.all(::validId)) { "Invalid DSDIFF channel identifiers" }
                    }
                }
                "CMPR" -> {
                    require(compression == null && payloadSize in 5L..260L) { "Invalid or duplicate DSDIFF compression" }
                    val payload = input.readExact(payloadSize.toInt(), "DSDIFF compression")
                    val nameBytes = payload[4].toInt() and 0xff
                    require(payloadSize == 5L + nameBytes) { "Invalid DSDIFF compression name length" }
                    compression = payload.ascii(0)
                }
                else -> input.skipExact(payloadSize, "DSDIFF $id property")
            }
            if (pad != 0) require(input.readExact(1, "DSDIFF property pad")[0] == 0.toByte()) {
                "DSDIFF property pad byte is not zero"
            }
            remaining -= payloadSize + pad
        }
        return Properties(
            rate ?: throw IllegalArgumentException("Missing DSDIFF sample rate"),
            channelIds ?: throw IllegalArgumentException("Missing DSDIFF channels"),
            compression ?: throw IllegalArgumentException("Missing DSDIFF compression"),
        )
    }

    private fun consumeAudioPad() {
        if (padConsumed) return
        if (audioPadBytes != 0) require(input.readExact(1, "DSDIFF audio pad")[0] == 0.toByte()) {
            "DSDIFF audio pad byte is not zero"
        }
        padConsumed = true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }

    private data class Properties(val sampleRate: Int, val channelIds: List<String>, val compression: String)

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

    private fun InputStream.skipExact(size: Long, label: String) {
        var remaining = size
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0) {
            val read = read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Truncated $label")
            if (read == 0) {
                if (read() < 0) throw EOFException("Truncated $label")
                remaining--
            } else remaining -= read
        }
    }

    private fun ByteArray.ascii(offset: Int): String {
        require(offset >= 0 && offset + 4 <= size) { "Truncated DSDIFF identifier" }
        return String(this, offset, 4, Charsets.US_ASCII)
    }

    private fun ByteArray.u16be(offset: Int): Int {
        require(offset >= 0 && offset + 2 <= size)
        return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
    }

    private fun ByteArray.u32be(offset: Int): Int {
        require(offset >= 0 && offset + 4 <= size) { "Truncated DSDIFF integer" }
        var value = 0L
        repeat(4) { byte -> value = (value shl 8) or (this[offset + byte].toLong() and 0xffL) }
        require(value <= Int.MAX_VALUE) { "DSDIFF integer exceeds the supported range" }
        return value.toInt()
    }

    private fun ByteArray.u64be(offset: Int): Long {
        require(offset >= 0 && offset + 8 <= size) { "Truncated DSDIFF integer" }
        require(this[offset].toInt() and 0x80 == 0) { "DSDIFF integer exceeds the supported range" }
        var value = 0L
        repeat(8) { byte -> value = (value shl 8) or (this[offset + byte].toLong() and 0xffL) }
        return value
    }

    private fun validId(id: String): Boolean = id.length == 4 && id.all { it.code in 0x20..0x7e } &&
        !(id[0] == ' ' && id.any { it != ' ' })

    private fun multiplyExact(left: Long, right: Long): Long = try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("DSDIFF size overflows the supported range")
    }

    private fun addExact(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("DSDIFF size overflows the supported range")
    }

    companion object {
        private const val MAX_PROPERTY_BYTES = 1024 * 1024
        private const val MAX_PACKET_BYTES = 1024 * 1024

        fun open(input: InputStream): DffReader = try {
            DffReader(input)
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
