package com.virtualdap.host.bridge

import com.virtualdap.host.audio.PcmEncoding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeWireProtocolTest {
    @Test
    fun writesSubmissionAcknowledgementInLittleEndian() {
        val output = ByteArrayOutputStream()
        BridgeWireWriter.writeAck(output, 0x0102030405060708L)

        val data = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(BridgeWireProtocol.ACK_MAGIC, data.int)
        assertEquals(BridgeWireProtocol.VERSION, data.short)
        assertEquals(0, data.short.toInt())
        assertEquals(0x0102030405060708L, data.long)
        assertEquals(BridgeWireProtocol.ACK_BYTES, output.size())
    }

    @Test
    fun parsesHandshakeAndAudioPacket() {
        val handshake = ByteBuffer.allocate(BridgeWireProtocol.HANDSHAKE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(BridgeWireProtocol.MAGIC)
            .putShort(BridgeWireProtocol.VERSION)
            .putShort(BridgeWireProtocol.HANDSHAKE_BYTES.toShort())
            .putInt(96_000)
            .putShort(2)
            .putShort(PcmEncoding.PCM_24_PACKED.wireId.toShort())
            .putInt(6)
            .putInt(1)
            .putLong(9)
            .array()
        val pcm = ByteArray(12) { it.toByte() }
        val packet = ByteBuffer.allocate(BridgeWireProtocol.MESSAGE_HEADER_BYTES + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(BridgeWireProtocol.TYPE_AUDIO.toShort())
            .putShort(0)
            .putInt(pcm.size)
            .putLong(12)
            .put(pcm)
            .array()
        val reader = BridgeWireReader(ByteArrayInputStream(handshake + packet))

        val parsedHandshake = reader.readHandshake()
        val parsedMessage = reader.readMessage() as BridgeMessage.Audio

        assertEquals(96_000, parsedHandshake.format.sampleRate)
        assertEquals(PcmEncoding.PCM_24_PACKED, parsedHandshake.format.encoding)
        assertEquals(9, parsedHandshake.streamEpoch)
        assertEquals(12, parsedMessage.sequence)
        assertArrayEquals(pcm, parsedMessage.pcm)
    }

    @Test
    fun rejectsOversizedPayloadBeforeAllocation() {
        val packet = ByteBuffer.allocate(BridgeWireProtocol.MESSAGE_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(BridgeWireProtocol.TYPE_AUDIO.toShort())
            .putShort(0)
            .putInt(BridgeWireProtocol.MAX_PAYLOAD_BYTES + 1)
            .putLong(0)
            .array()

        assertThrows(BridgeProtocolException::class.java) {
            BridgeWireReader(ByteArrayInputStream(packet)).readMessage()
        }
    }

    @Test
    fun distinguishesCleanEndFromTruncatedMessage() {
        assertEquals(null, BridgeWireReader(ByteArrayInputStream(byteArrayOf())).readMessage())
        assertThrows(java.io.EOFException::class.java) {
            BridgeWireReader(ByteArrayInputStream(byteArrayOf(1))).readMessage()
        }
    }
}
