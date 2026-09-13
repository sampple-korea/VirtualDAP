package com.virtualdap.host.audio

import com.virtualdap.host.audio.usb.UsbAudioClockControl
import com.virtualdap.host.audio.usb.UsbAudioControlPipe
import com.virtualdap.host.audio.usb.UsbAudioStreamingProfile
import com.virtualdap.host.audio.usb.UsbSampleRateRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbAudioClockControlTest {
    @Test fun uac1FrequencyIsWrittenAndReadBackThroughTheOutputEndpoint() {
        var rate = 0
        val control = UsbAudioClockControl(UsbAudioControlPipe { type, request, value, index, data ->
            assertEquals(0x100, value)
            assertEquals(1, index)
            assertEquals(3, data.size)
            if (type == 0x22 && request == 1) rate = readLittleEndian(data)
            else {
                assertEquals(0xa2, type)
                assertEquals(0x81, request)
                writeLittleEndian(data, rate)
            }
            data.size
        })
        control.setAndVerify(profile(), 48_000)
        assertEquals(48_000, rate)
    }

    @Test fun aDacThatSilentlyChangesTheRateIsRejected() {
        val control = UsbAudioClockControl(UsbAudioControlPipe { type, _, _, _, data ->
            if (type and 0x80 != 0) writeLittleEndian(data, 44_100)
            data.size
        })
        assertThrows(IllegalStateException::class.java) { control.setAndVerify(profile(), 48_000) }
    }

    @Test fun uac2RangeQueryUsesTheClockEntityAndControlInterface() {
        val ranges = byteArrayOf(1, 0, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0x77, 1, 0, 0x80.toByte(), 0xbb.toByte(), 0, 0)
        val control = UsbAudioClockControl(UsbAudioControlPipe { type, request, value, index, data ->
            assertEquals(0xa1, type)
            assertEquals(2, request)
            assertEquals(0x100, value)
            assertEquals(0x0a02, index)
            ranges.copyInto(data, endIndex = data.size)
            data.size
        })
        val result = control.supportedRates(profile().copy(protocol = 0x20, controlInterface = 2, clockEntity = 10))
        assertEquals(listOf(UsbSampleRateRange(48_000, 96_000, 48_000)), result)
    }

    private fun profile() = UsbAudioStreamingProfile(
        1, 0, 1, 1, 0, 1, null, 1, 288, 2, 2, 3, 24,
        pcm = true, floatingPoint = false, rawData = false, clockEntity = null,
        rates = listOf(UsbSampleRateRange(48_000, 48_000)), endpointFrequencyControl = true,
    )

    private fun readLittleEndian(data: ByteArray): Int =
        data.indices.fold(0) { value, index -> value or ((data[index].toInt() and 0xff) shl (index * 8)) }

    private fun writeLittleEndian(data: ByteArray, value: Int) =
        data.indices.forEach { data[it] = (value ushr (it * 8)).toByte() }
}
