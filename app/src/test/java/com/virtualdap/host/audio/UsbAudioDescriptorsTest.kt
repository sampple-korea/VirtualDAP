package com.virtualdap.host.audio

import com.virtualdap.host.audio.usb.UsbAudioDescriptors
import com.virtualdap.host.audio.usb.UsbIsoPacketClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioDescriptorsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun readsUac1DiscreteRatesAndIsochronousFeedback() {
        val descriptors = bytes(
            9, 2, 0, 0, 2, 1, 0, 0x80, 50,
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            9, 4, 1, 1, 2, 1, 2, 0, 0,
            7, 0x24, 1, 1, 0, 1, 0,
            14, 0x24, 2, 1, 2, 3, 24, 2, 0x44, 0xac, 0, 0x80, 0xbb, 0,
            9, 5, 1, 5, 0x26, 1, 1, 0, 0x81,
            7, 0x25, 1, 1, 0, 0, 0,
            7, 5, 0x81, 0x11, 3, 0, 1,
        )
        val profile = UsbAudioDescriptors.parse(descriptors).single()
        assertEquals(24, profile.bitResolution)
        assertEquals(6, profile.frameBytes)
        assertEquals(294, profile.maximumPacketBytes)
        assertEquals(0x81, profile.feedbackEndpointAddress)
        assertTrue(profile.rates.any { it.contains(44_100) })
        assertFalse(profile.rates.any { it.contains(96_000) })
        assertTrue(profile.endpointFrequencyControl)
        assertNull(profile.clockEntity)
    }

    @Test fun uac2RatesRemainUnknownUntilItsClockIsQueried() {
        val descriptors = bytes(
            9, 2, 0, 0, 2, 1, 0, 0x80, 50,
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,
            17, 0x24, 2, 1, 1, 1, 0, 10, 2, 3, 0, 0, 0, 0, 0, 0, 0,
            9, 4, 1, 1, 1, 1, 2, 0x20, 0,
            16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0x80, 2, 3, 0, 0, 0, 0,
            6, 0x24, 2, 1, 4, 32,
            7, 5, 2, 5, 0, 4, 1,
        )
        val profile = UsbAudioDescriptors.parse(descriptors).single()
        assertEquals(10, profile.clockEntity)
        assertEquals(32, profile.bitResolution)
        assertTrue(profile.rates.isEmpty())
        assertTrue(profile.pcm)
        assertTrue(profile.rawData) // Candidate only; not a native-DSD guarantee.
    }

    @Test fun malformedLengthAndClockRangesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UsbAudioDescriptors.parse(bytes(0, 4)) }
        assertThrows(IllegalArgumentException::class.java) { UsbAudioDescriptors.parse(bytes(9, 4, 0)) }
        assertThrows(IllegalArgumentException::class.java) { UsbAudioDescriptors.parseClockRanges(bytes(1, 0)) }
        val range = UsbAudioDescriptors.parseClockRanges(
            bytes(1, 0, 0x44, 0xac, 0, 0, 0x88, 0x58, 1, 0, 0x44, 0xac, 0, 0),
        ).single()
        assertTrue(range.contains(44_100))
        assertTrue(range.contains(88_200))
        assertFalse(range.contains(48_000))
    }

    @Test fun fractionalUsbSchedulingDoesNotLose44100HzFrames() {
        for (intervals in listOf(1000, 8000)) {
            val clock = UsbIsoPacketClock(44_100, intervals)
            assertEquals(44_100, (0 until intervals).sumOf { clock.nextFrames() })
            assertEquals(44_100, (0 until intervals).sumOf { clock.nextFrames() })
        }
    }
}
