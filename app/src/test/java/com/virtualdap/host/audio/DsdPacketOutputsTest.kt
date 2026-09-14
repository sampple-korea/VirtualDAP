package com.virtualdap.host.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DsdPacketOutputsTest {
    @Test fun floatPcmIsSerializedInAndroidsLittleEndianWireOrder() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0x80.toByte(), 0x3f, 0, 0, 0, 0xbf.toByte()),
            floatsToLittleEndianBytes(floatArrayOf(0f, 1f, -0.5f)),
        )
    }
}
