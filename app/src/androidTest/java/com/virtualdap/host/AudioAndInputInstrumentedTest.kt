package com.virtualdap.host

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualdap.host.audio.AndroidAudioSink
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.platformruntime.GuestInputMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioAndInputInstrumentedTest {
    @Test fun everyPcmEncodingCanBeSubmittedToARealAudioTrack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidAudioSink(context).use { sink ->
            for (encoding in PcmEncoding.entries) {
                val format = PcmFormat(48_000, 2, encoding)
                val configured = sink.configure(format)
                assertEquals(format, configured.requested)
                // Silence exercises the native write contract, including PCM_FLOAT (which the
                // byte[] AudioTrack overload rejects), without disturbing a developer's output.
                val packet = ByteArray(480 * format.frameSizeBytes)
                repeat(12) { assertEquals(packet.size, sink.write(packet)) }
                assertNotNull(sink.queuedDurationMs())
            }
        }
    }

    @Test fun scaledTouchUsesGuestPixelsForLocalAndRawCoordinates() {
        val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i + 2; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply { x = 150f; y = 240f; pressure = 1f },
            MotionEvent.PointerCoords().apply { x = 250f; y = 480f; pressure = 0.5f },
        )
        val original = MotionEvent.obtain(
            10, 20, MotionEvent.ACTION_MOVE, 2, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        // Model the local offset introduced by embedding a Surface in a scrolling host screen.
        original.offsetLocation(-50f, -80f)
        val mapped = GuestInputMapper.touch(original, 360, 640)
        try {
            assertEquals(300f, mapped.x, 0.01f)
            assertEquals(480f, mapped.y, 0.01f)
            assertEquals(mapped.x, mapped.rawX, 0.01f)
            assertEquals(mapped.y, mapped.rawY, 0.01f)
            assertEquals(600f, mapped.getX(1), 0.01f)
            assertEquals(1200f, mapped.getY(1), 0.01f)
            assertEquals(2, mapped.getPointerId(0))
            assertEquals(3, mapped.getPointerId(1))
            assertEquals(original.downTime, mapped.downTime)
            assertEquals(original.eventTime, mapped.eventTime)
            assertEquals(0.5f, mapped.getPressure(1), 0.01f)
        } finally {
            original.recycle()
            mapped.recycle()
        }
    }

    @Test fun navigationButtonsUseEvdevCodesAndPhysicalKeyboardCodesArePreserved() {
        assertEquals(158, GuestInputMapper.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)).scanCode)
        assertEquals(172, GuestInputMapper.key(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)).scanCode)
        val physical = KeyEvent(1, 2, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, 0, 7, 30)
        val mapped = GuestInputMapper.key(physical)
        assertEquals(30, mapped.scanCode)
        assertEquals(physical.deviceId, mapped.deviceId)
        assertEquals(physical.eventTime, mapped.eventTime)
    }
}
