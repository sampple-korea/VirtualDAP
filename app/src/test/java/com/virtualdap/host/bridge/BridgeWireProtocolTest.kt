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
    @Test fun volumeRejectsNanInfinityAndOutOfRangeGains() {
        fun packet(left: Float, right: Float) = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(6).putShort(0).putInt(8).putLong(22).putFloat(left).putFloat(right).array()
        assertEquals(
            BridgeMessage.Volume(0.25f, 1f, 22),
            BridgeWireReader(ByteArrayInputStream(packet(0.25f, 1f))).readMessage(),
        )
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f)) {
            assertThrows(BridgeProtocolException::class.java) {
                BridgeWireReader(ByteArrayInputStream(packet(invalid, 1f))).readMessage()
            }
            assertThrows(BridgeProtocolException::class.java) {
                BridgeWireReader(ByteArrayInputStream(packet(1f, invalid))).readMessage()
            }
        }
    }

    @Test fun controlledAckContainsTheSourcePlaybackObservation() {
        val output = ByteArrayOutputStream()
        BridgeWireWriter.writeAck(output, 19, BridgePosition(441, 123456789L))
        val data = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(BridgeWireProtocol.POSITION_ACK_BYTES, output.size())
        assertEquals(BridgeWireProtocol.ACK_MAGIC, data.int)
        assertEquals(BridgeWireProtocol.CONTROLLED_VERSION, data.short)
        assertEquals(0, data.short.toInt())
        assertEquals(19, data.long)
        assertEquals(441, data.long)
        assertEquals(123456789L, data.long)
    }

    @Test fun controlCommandsAreStrictAndBounded() {
        fun packet(value: Int, size: Int = 4) = ByteBuffer.allocate(16 + size)
            .order(ByteOrder.LITTLE_ENDIAN).putShort(5).putShort(0)
            .putInt(size).putLong(4).apply { if (size >= 4) putInt(value) }.array()
        for (command in BridgeControl.entries) {
            val parsed = BridgeWireReader(ByteArrayInputStream(packet(command.wireId))).readMessage()
            assertEquals(BridgeMessage.Control(command, 4), parsed)
        }
        for (invalid in listOf(packet(0), packet(5), packet(1, 0), packet(1, 8))) {
            assertThrows(BridgeProtocolException::class.java) {
                BridgeWireReader(ByteArrayInputStream(invalid)).readMessage()
            }
        }
    }

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
