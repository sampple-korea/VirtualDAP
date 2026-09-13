package com.virtualdap.platformruntime

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Converts an embedded Surface's local input into the fixed guest display/input coordinate space. */
object GuestInputMapper {
    const val DISPLAY_WIDTH = 1080
    const val DISPLAY_HEIGHT = 1920

    fun touch(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int): MotionEvent {
        require(surfaceWidth > 0 && surfaceHeight > 0) { "Guest surface has no dimensions" }
        val properties = Array(event.pointerCount) { index ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) }
        }
        val coordinates = Array(event.pointerCount) { index ->
            MotionEvent.PointerCoords().also {
                event.getPointerCoords(index, it)
                it.x = (it.x * DISPLAY_WIDTH / surfaceWidth).coerceIn(0f, DISPLAY_WIDTH - 1f)
                it.y = (it.y * DISPLAY_HEIGHT / surfaceHeight).coerceIn(0f, DISPLAY_HEIGHT - 1f)
            }
        }
        // Rebuild the event: offsetLocation/transform leave raw screen coordinates attached.
        // AVF's touch injector reads getRawX/Y, so those must describe guest pixels too.
        return MotionEvent.obtain(
            event.downTime, event.eventTime, event.action, event.pointerCount, properties, coordinates,
            event.metaState, event.buttonState, 1f, 1f, event.deviceId, event.edgeFlags,
            InputDevice.SOURCE_TOUCHSCREEN, event.flags,
        )
    }

    fun key(event: KeyEvent): KeyEvent {
        if (event.scanCode != 0) return KeyEvent(event)
        // AVF injects Linux evdev scan codes, not Android KeyEvent.keyCode values.
        val scanCode = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> 158
            KeyEvent.KEYCODE_HOME -> 172 // KEY_HOMEPAGE maps to Android HOME in Generic.kl.
            KeyEvent.KEYCODE_ENTER -> 28
            KeyEvent.KEYCODE_DEL -> 14
            KeyEvent.KEYCODE_TAB -> 15
            KeyEvent.KEYCODE_SPACE -> 57
            KeyEvent.KEYCODE_ESCAPE -> 1
            KeyEvent.KEYCODE_DPAD_UP -> 103
            KeyEvent.KEYCODE_DPAD_DOWN -> 108
            KeyEvent.KEYCODE_DPAD_LEFT -> 105
            KeyEvent.KEYCODE_DPAD_RIGHT -> 106
            KeyEvent.KEYCODE_DPAD_CENTER -> 28
            else -> return KeyEvent(event)
        }
        return KeyEvent(
            event.downTime, event.eventTime, event.action, event.keyCode, event.repeatCount,
            event.metaState, event.deviceId, scanCode, event.flags, InputDevice.SOURCE_KEYBOARD,
        )
    }
}
