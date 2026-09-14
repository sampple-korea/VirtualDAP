package com.virtualdap.host.audio

import com.virtualdap.host.audio.usb.UsbAudioClockControl
import com.virtualdap.host.audio.usb.UsbAudioClockEntity
import com.virtualdap.host.audio.usb.UsbAudioClockTopology
import com.virtualdap.host.audio.usb.UsbAudioControlPipe
import com.virtualdap.host.audio.usb.UsbAudioStreamingProfile
import com.virtualdap.host.audio.usb.UsbSampleRateRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun writableSelectorChoosesTheSourceThatSupportsTheRequestedRateAndWaitsForValidity() {
        var selector = 1
        val sourceRates = mutableMapOf(10 to 44_100, 11 to 48_000)
        var validityReads = 0
        var sleeps = 0
        val pipe = UsbAudioControlPipe { type, request, value, index, data ->
            val entity = index ushr 8
            when {
                type == 0xa1 && request == 2 && value == 0x100 -> {
                    val range = if (entity == 10) rangePayload(44_100) else rangePayload(96_000)
                    range.copyInto(data, endIndex = data.size)
                }
                type == 0xa1 && request == 1 && value == 0x100 && data.size == 1 -> data[0] = selector.toByte()
                type == 0x21 && request == 1 && value == 0x100 && data.size == 1 -> selector = data[0].toInt() and 0xff
                type == 0xa1 && request == 1 && value == 0x100 && data.size == 4 ->
                    writeLittleEndian(data, sourceRates.getValue(entity))
                type == 0x21 && request == 1 && value == 0x100 && data.size == 4 ->
                    sourceRates[entity] = readLittleEndian(data)
                type == 0xa1 && request == 1 && value == 0x200 -> {
                    validityReads++
                    data[0] = if (validityReads >= 2) 1 else 0
                }
                else -> error("Unexpected control transfer")
            }
            data.size
        }
        UsbAudioClockControl(pipe, 3, 1) { sleeps++ }.setAndVerify(selectorProfile(selectorControls = 3), 96_000)
        assertEquals(2, selector)
        assertEquals(96_000, sourceRates[11])
        assertEquals(2, validityReads)
        assertEquals(1, sleeps)
    }

    @Test fun readOnlySelectorUsesItsCurrentSourceWithoutAttemptingAWrite() {
        var writes = 0
        val pipe = UsbAudioControlPipe { type, request, value, index, data ->
            val entity = index ushr 8
            when {
                type == 0xa1 && request == 2 && value == 0x100 -> {
                    val range = rangePayload(if (entity == 10) 44_100 else 96_000)
                    range.copyInto(data, endIndex = data.size)
                }
                type == 0xa1 && request == 1 && value == 0x100 && data.size == 1 -> data[0] = 2
                type == 0xa1 && request == 1 && value == 0x100 && data.size == 4 -> writeLittleEndian(data, 96_000)
                type == 0xa1 && request == 1 && value == 0x200 -> data[0] = 1
                type == 0x21 -> {
                    writes++
                    error("Read-only path must not be written")
                }
                else -> error("Unexpected control transfer for entity $entity")
            }
            data.size
        }
        val control = UsbAudioClockControl(pipe)
        assertEquals(listOf(UsbSampleRateRange(96_000, 96_000)), control.supportedRates(selectorProfile(selectorControls = 1)))
        control.setAndVerify(selectorProfile(selectorControls = 1), 96_000)
        assertEquals(0, writes)
    }

    @Test fun recursiveClockTopologyAndPermanentlyInvalidClockAreRejected() {
        val cycle = UsbAudioClockTopology(
            12,
            mapOf(
                12 to UsbAudioClockEntity.Selector(12, listOf(13), 1),
                13 to UsbAudioClockEntity.Multiplier(13, 12, 0),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            UsbAudioClockControl(UsbAudioControlPipe { _, _, _, _, _ -> error("must not transfer") })
                .supportedRates(uac2Profile(12, cycle))
        }

        var validityReads = 0
        val invalid = UsbAudioClockTopology(10, mapOf(10 to UsbAudioClockEntity.Source(10, 3, 7)))
        val pipe = UsbAudioControlPipe { type, request, value, _, data ->
            when {
                type == 0xa1 && request == 2 -> rangePayload(48_000).copyInto(data, endIndex = data.size)
                type == 0xa1 && request == 1 && value == 0x100 -> writeLittleEndian(data, 48_000)
                type == 0xa1 && request == 1 && value == 0x200 -> validityReads++
                else -> error("Unexpected control transfer")
            }
            data.size
        }
        val failure = assertThrows(IllegalStateException::class.java) {
            UsbAudioClockControl(pipe, 3, 0) {}.setAndVerify(uac2Profile(10, invalid), 48_000)
        }
        assertTrue(failure.message.orEmpty().contains("remained invalid"))
        assertEquals(3, validityReads)
    }

    @Test fun clockMultiplierMapsTerminalRateToItsExactSourceRate() {
        var sourceRate = 44_100
        val topology = UsbAudioClockTopology(
            13,
            mapOf(
                10 to UsbAudioClockEntity.Source(10, 3, 3),
                13 to UsbAudioClockEntity.Multiplier(13, 10, 5),
            ),
        )
        val pipe = UsbAudioControlPipe { type, request, value, index, data ->
            val entity = index ushr 8
            when {
                type == 0xa1 && request == 1 && entity == 13 && value == 0x100 -> writeLittleEndian(data, 2)
                type == 0xa1 && request == 1 && entity == 13 && value == 0x200 -> writeLittleEndian(data, 1)
                type == 0xa1 && request == 2 && entity == 10 -> rangePayload(48_000).copyInto(data, endIndex = data.size)
                type == 0xa1 && request == 1 && entity == 10 -> writeLittleEndian(data, sourceRate)
                type == 0x21 && request == 1 && entity == 10 -> sourceRate = readLittleEndian(data)
                else -> error("Unexpected multiplier control transfer")
            }
            data.size
        }
        val control = UsbAudioClockControl(pipe)
        assertEquals(listOf(UsbSampleRateRange(96_000, 96_000)), control.supportedRates(uac2Profile(13, topology)))
        control.setAndVerify(uac2Profile(13, topology), 96_000)
        assertEquals(48_000, sourceRate)
    }

    private fun profile() = UsbAudioStreamingProfile(
        1, 0, 1, 1, 0, 1, null, 1, 288, 2, 2, 3, 24,
        pcm = true, floatingPoint = false, rawData = false, clockEntity = null,
        rates = listOf(UsbSampleRateRange(48_000, 48_000)), endpointFrequencyControl = true,
    )

    private fun selectorProfile(selectorControls: Int): UsbAudioStreamingProfile {
        val topology = UsbAudioClockTopology(
            12,
            mapOf(
                10 to UsbAudioClockEntity.Source(10, 3, 7),
                11 to UsbAudioClockEntity.Source(11, 3, 7),
                12 to UsbAudioClockEntity.Selector(12, listOf(10, 11), selectorControls),
            ),
        )
        return uac2Profile(12, topology)
    }

    private fun uac2Profile(clock: Int, topology: UsbAudioClockTopology) = profile().copy(
        protocol = 0x20,
        controlInterface = 2,
        clockEntity = clock,
        clockTopology = topology,
        rates = emptyList(),
    )

    private fun rangePayload(rate: Int): ByteArray = ByteArray(14).also { data ->
        data[0] = 1
        writeLittleEndian(data, rate, 2)
        writeLittleEndian(data, rate, 6)
    }

    private fun readLittleEndian(data: ByteArray): Int =
        data.indices.fold(0) { value, index -> value or ((data[index].toInt() and 0xff) shl (index * 8)) }

    private fun writeLittleEndian(data: ByteArray, value: Int, offset: Int = 0) =
        (offset until data.size).take(4).forEachIndexed { byte, index ->
            data[index] = (value ushr (byte * 8)).toByte()
        }
}
