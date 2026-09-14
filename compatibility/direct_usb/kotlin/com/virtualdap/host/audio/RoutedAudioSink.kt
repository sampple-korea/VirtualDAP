package com.virtualdap.host.audio

import android.content.Context
import com.virtualdap.host.audio.usb.DirectUsbPcmSink
import com.virtualdap.host.audio.usb.UsbHostController
import com.virtualdap.host.model.OutputRoute
import java.io.Closeable

/** One session, one selected transport. Direct USB is explicit, never an automatic route takeover. */
class RoutedAudioSink(context: Context) : Closeable {
    private val android = AndroidAudioSink(context)
    private val lock = Any()
    private var usb: DirectUsbPcmSink? = null
    private var selected: Int? = null
    private var playing = true
    private var exclusive = true
    private var left = 1f
    private var right = 1f
    fun selectRoute(id: Int?) = synchronized(lock) {
        if (selected != id) {
            usb?.close()
            usb = null
            android.close()
            selected = id
            android.selectRoute(id.takeUnless(UsbHostController::isDirectRoute))
        }
    }
    fun setPlaying(value: Boolean) = synchronized(lock) {
        playing = value
        usb?.setPlaying(value) ?: android.setPlaying(value)
    }
    fun setVolume(left: Float, right: Float) = synchronized(lock) {
        this.left = left
        this.right = right
        usb?.setVolume(left, right) ?: android.setVolume(left, right)
    }
    fun setExclusiveAllowed(value: Boolean) = synchronized(lock) {
        exclusive = value
        usb?.setExclusiveAllowed(value) ?: android.setExclusiveAllowed(value)
    }
    fun configure(format: PcmFormat): SinkConfiguration = synchronized(lock) {
        if (!UsbHostController.isDirectRoute(selected)) {
            android.setVolume(left, right)
            android.setPlaying(playing)
            android.setExclusiveAllowed(exclusive)
            return@synchronized android.configure(format)
        }
        val route = UsbHostController.routes().firstOrNull { it.id == selected }
            ?: error("Selected direct USB device is unavailable; choose an output to resume")
        val deviceId = requireNotNull(route.directUsbDeviceId)
        val direct = usb ?: DirectUsbPcmSink(deviceId, route).also {
            it.setPlaying(playing)
            it.setVolume(left, right)
            it.setExclusiveAllowed(exclusive)
            usb = it
        }
        direct.configure(format)
    }
    fun write(bytes: ByteArray): Int = synchronized(lock) { usb?.write(bytes) ?: android.write(bytes) }
    fun flush() = synchronized(lock) { usb?.flush() ?: android.flush() }
    fun finish() = synchronized(lock) { usb?.finish() ?: android.finish() }
    fun bitPerfectActive(): Boolean = synchronized(lock) { usb?.bitPerfectActive() ?: android.bitPerfectActive() }
    fun queuedDurationMs(): Double? = synchronized(lock) { if (usb != null) usb?.queuedDurationMs() else android.queuedDurationMs() }
    fun routedOutput(): OutputRoute? = synchronized(lock) { if (usb != null) usb?.routedOutput() else android.routedOutput() }
    fun usbStatistics() = synchronized(lock) { usb?.statistics() }
    override fun close() = synchronized(lock) {
        usb?.close()
        usb = null
        android.close()
    }
}
