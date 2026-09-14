package com.virtualdap.host

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virtualdap.host.audio.HighQualityPcmResampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighQualityResamplerInstrumentedTest {
    @Test fun bestSincOutputIsContinuousAcrossArbitraryJavaPacketBoundaries() {
        val frames = SOURCE_RATE / 10
        val input = FloatArray(frames) { frame ->
            (0.65 * sin(2.0 * PI * 1_000 * frame / SOURCE_RATE) +
                0.15 * sin(2.0 * PI * 30_000 * frame / SOURCE_RATE)).toFloat()
        }
        val expected = HighQualityPcmResampler(SOURCE_RATE, TARGET_RATE, 1).use { converter ->
            converter.convert(input) + converter.finish()
        }
        val actual = HighQualityPcmResampler(SOURCE_RATE, TARGET_RATE, 1).use { converter ->
            converter.convert(input.copyOfRange(0, 12_347)) +
                converter.convert(input.copyOfRange(12_347, 25_003)) +
                converter.convert(input.copyOfRange(25_003, input.size)) +
                converter.finish()
        }

        assertEquals(expected.size, actual.size)
        assertArrayEquals(expected, actual, 0.000_001f)
        assertTrue(abs(actual.size - TARGET_RATE / 10) <= 1)
    }

    @Test fun bestSincPassesMusicBandAndRejectsAliasingAboveTargetNyquist() {
        val passBand = convertedTone(1_000)
        val stopBand = convertedTone(30_000)

        assertTrue("1 kHz pass-band RMS was ${passBand.rms()}", passBand.rms() > 0.5)
        assertTrue("30 kHz stop-band RMS was ${stopBand.rms()}", stopBand.rms() < 0.001)
    }

    private fun convertedTone(frequency: Int): FloatArray {
        val frames = SOURCE_RATE / 5
        val input = FloatArray(frames) { frame ->
            (0.8 * sin(2.0 * PI * frequency * frame / SOURCE_RATE)).toFloat()
        }
        return HighQualityPcmResampler(SOURCE_RATE, TARGET_RATE, 1).use { converter ->
            converter.convert(input) + converter.finish()
        }
    }

    private fun FloatArray.rms(): Double {
        val trim = minOf(400, size / 8)
        val start = trim
        val end = size - trim
        require(end > start)
        var power = 0.0
        for (index in start until end) power += this[index] * this[index]
        return sqrt(power / (end - start))
    }

    private companion object {
        const val SOURCE_RATE = 352_800
        const val TARGET_RATE = 44_100
    }
}
