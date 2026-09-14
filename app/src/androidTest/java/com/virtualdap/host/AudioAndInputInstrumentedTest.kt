package com.virtualdap.host

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualdap.host.audio.AndroidAudioSink
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.audio.DsdFormat
import com.virtualdap.host.audio.DsdPcmDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioAndInputInstrumentedTest {
    @Test fun nativeDsdFilterPreservesChannelsAndChunkContinuity() {
        val source = DsdFormat(2_822_400, 2)
        val dsd = ByteArray(1024) { if (it % 2 == 0) 0xff.toByte() else 0 }
        DsdPcmDecoder(source).use { whole ->
            DsdPcmDecoder(source).use { split ->
                assertEquals(352_800, whole.outputSampleRate)
                val expected = whole.convert(dsd)
                val packets = split.convert(dsd.copyOfRange(0, 38)) + split.convert(dsd.copyOfRange(38, dsd.size))
                assertArrayEquals(expected, packets, 0f)
                assertEquals(1f, expected[expected.size - 2], 0.00001f)
                assertEquals(-1f, expected.last(), 0.00001f)
            }
        }
    }

    @Test fun closedDsdDecoderCannotAccessFreedNativeState() {
        val decoder = DsdPcmDecoder(DsdFormat(2_822_400, 2))
        decoder.close()
        decoder.close()
        assertThrows(IllegalStateException::class.java) { decoder.convert(byteArrayOf(1, 2)) }
    }

    @Test fun noSelectedOfficialRouteRejectsEveryEncodingBeforeOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidAudioSink(context).use { sink ->
            for (encoding in PcmEncoding.entries) {
                assertThrows(com.virtualdap.host.audio.AudioSinkException::class.java) {
                    sink.configure(PcmFormat(48_000, 2, encoding))
                }
                org.junit.Assert.assertNull(sink.routedOutput())
                org.junit.Assert.assertFalse(sink.bitPerfectActive())
            }
        }
    }

    @Test fun realUnsupportedDeviceCannotFallBackToTheMixer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidAudioSink(context).use { sink ->
            val route = sink.routes().firstOrNull { it.officialBitPerfectFormats.isEmpty() }
            org.junit.Assume.assumeNotNull(route)
            sink.selectRoute(route!!.id)
            assertThrows(com.virtualdap.host.audio.AudioSinkException::class.java) {
                sink.configure(PcmFormat(48_000, 2, PcmEncoding.PCM_16))
            }
            org.junit.Assert.assertNull(sink.routedOutput())
        }
    }
}
