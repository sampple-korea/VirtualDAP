package com.virtualdap.host.bridge

import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BridgeWireProtocol {
    const val MAGIC = 0x56444150
    const val VERSION: Short = 1
    const val HANDSHAKE_BYTES = 32
    const val MESSAGE_HEADER_BYTES = 16
    const val MAX_PAYLOAD_BYTES = 1024 * 1024

    const val TYPE_AUDIO = 1
    const val TYPE_FORMAT = 2
    const val TYPE_STATS = 3
    const val TYPE_PING = 4
}

data class BridgeHandshake(
    val format: PcmFormat,
    val frameSize: Int,
    val flags: Int,
    val streamEpoch: Long,
)

sealed interface BridgeMessage {
    val sequence: Long

    data class Audio(val pcm: ByteArray, override val sequence: Long) : BridgeMessage
    data class Format(val format: PcmFormat, val streamEpoch: Long, override val sequence: Long) : BridgeMessage
    data class Stats(
        val framesWritten: Long,
        val droppedBytes: Long,
        val reconnects: Long,
        override val sequence: Long,
    ) : BridgeMessage
    data class Ping(override val sequence: Long) : BridgeMessage
}

class BridgeWireReader(private val input: InputStream) {
    fun readHandshake(): BridgeHandshake {
        val bytes = input.readExactly(BridgeWireProtocol.HANDSHAKE_BYTES)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = data.int
        if (magic != BridgeWireProtocol.MAGIC) {
            throw BridgeProtocolException("Invalid socket bridge magic: 0x${magic.toUInt().toString(16)}")
        }
        val version = data.short
        if (version != BridgeWireProtocol.VERSION) {
            throw BridgeProtocolException("Unsupported socket bridge version: $version")
        }
        val headerSize = data.short.toInt() and 0xffff
        if (headerSize != BridgeWireProtocol.HANDSHAKE_BYTES) {
            throw BridgeProtocolException("Invalid handshake size: $headerSize")
        }
        val format = readFormat(data)
        val frameSize = data.int
        val flags = data.int
        val epoch = data.long
        if (frameSize != format.frameSizeBytes) {
            throw BridgeProtocolException("Frame size $frameSize does not match ${format.frameSizeBytes}")
        }
        return BridgeHandshake(format, frameSize, flags, epoch)
    }

    /** Returns null only when the peer closes cleanly between messages. */
    fun readMessage(): BridgeMessage? {
        val headerBytes = input.readExactlyOrNull(BridgeWireProtocol.MESSAGE_HEADER_BYTES) ?: return null
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val type = header.short.toInt() and 0xffff
        header.short // flags reserved for forward compatibility
        val payloadSize = header.int
        val sequence = header.long
        if (payloadSize !in 0..BridgeWireProtocol.MAX_PAYLOAD_BYTES) {
            throw BridgeProtocolException("Invalid bridge payload size: $payloadSize")
        }
        val payload = input.readExactly(payloadSize)
        val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return when (type) {
            BridgeWireProtocol.TYPE_AUDIO -> BridgeMessage.Audio(payload, sequence)
            BridgeWireProtocol.TYPE_FORMAT -> {
                if (payloadSize != 16) throw BridgeProtocolException("Invalid format payload size: $payloadSize")
                BridgeMessage.Format(readFormat(data), data.long, sequence)
            }
            BridgeWireProtocol.TYPE_STATS -> {
                if (payloadSize != 24) throw BridgeProtocolException("Invalid stats payload size: $payloadSize")
                BridgeMessage.Stats(data.long, data.long, data.long, sequence)
            }
            BridgeWireProtocol.TYPE_PING -> {
                if (payloadSize != 0) throw BridgeProtocolException("Ping must have an empty payload")
                BridgeMessage.Ping(sequence)
            }
            else -> throw BridgeProtocolException("Unknown bridge message type: $type")
        }
    }

    private fun readFormat(data: ByteBuffer): PcmFormat {
        if (data.remaining() < 8) throw BridgeProtocolException("Truncated PCM format")
        val sampleRate = data.int
        val channels = data.short.toInt() and 0xffff
        val encoding = data.short.toInt() and 0xffff
        return try {
            PcmFormat(sampleRate, channels, PcmEncoding.fromWireId(encoding))
        } catch (error: IllegalArgumentException) {
            throw BridgeProtocolException(error.message ?: "Invalid PCM format")
        }
    }
}

private fun InputStream.readExactly(size: Int): ByteArray =
    readExactlyOrNull(size) ?: throw EOFException("Bridge closed with $size bytes still expected")

private fun InputStream.readExactlyOrNull(size: Int): ByteArray? {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(result, offset, size - offset)
        if (count < 0) {
            if (offset == 0) return null
            throw EOFException("Bridge closed after $offset of $size bytes")
        }
        if (count == 0) continue
        offset += count
    }
    return result
}
