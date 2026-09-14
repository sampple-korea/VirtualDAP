package com.virtualdap.host.audio

import android.content.Context
import com.virtualdap.host.audio.usb.DirectUsbPcmSink
import com.virtualdap.host.audio.usb.UsbHostController
import com.virtualdap.host.model.OutputRoute
import com.virtualdap.host.service.PipelineStore
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
        if (UsbHostController.isDirectRoute(selected)) usb?.setPlaying(value) else android.setPlaying(value)
    }
    fun setVolume(left: Float, right: Float) = synchronized(lock) {
        require(left.isFinite() && right.isFinite() && left in 0f..1f && right in 0f..1f)
        this.left = left
        this.right = right
        if (UsbHostController.isDirectRoute(selected)) updateUsbGain() else android.setVolume(left, right)
    }
    fun setExclusiveAllowed(value: Boolean) = synchronized(lock) {
        exclusive = value
        if (UsbHostController.isDirectRoute(selected)) usb?.setExclusiveAllowed(value) else android.setExclusiveAllowed(value)
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
            it.setExclusiveAllowed(exclusive)
            usb = it
        }
        updateUsbGain()
        direct.configure(format)
    }
    private fun updateUsbGain() {
        val master = PipelineStore.state.value.usbGain.coerceIn(0f, 1f)
        usb?.setVolume(left * master, right * master)
    }
    fun write(bytes: ByteArray): Int = synchronized(lock) {
        if (UsbHostController.isDirectRoute(selected)) {
            updateUsbGain()
            requireNotNull(usb) { "USB 출력이 준비되지 않았습니다" }.write(bytes)
        } else android.write(bytes)
    }
    fun flush() = synchronized(lock) {
        if (UsbHostController.isDirectRoute(selected)) usb?.flush() else android.flush()
    }
    fun finish() = synchronized(lock) {
        if (UsbHostController.isDirectRoute(selected)) usb?.finish() else android.finish()
    }
    fun bitPerfectActive(): Boolean = synchronized(lock) {
        if (UsbHostController.isDirectRoute(selected)) {
            updateUsbGain()
            usb?.bitPerfectActive() == true
        } else android.bitPerfectActive()
    }
    fun queuedDurationMs(): Double? = synchronized(lock) { if (usb != null) usb?.queuedDurationMs() else android.queuedDurationMs() }
    fun routedOutput(): OutputRoute? = synchronized(lock) { if (usb != null) usb?.routedOutput() else android.routedOutput() }
    fun usbStatistics() = synchronized(lock) { usb?.statistics() }
    override fun close() = synchronized(lock) {
        usb?.close()
        usb = null
        android.close()
    }
}
