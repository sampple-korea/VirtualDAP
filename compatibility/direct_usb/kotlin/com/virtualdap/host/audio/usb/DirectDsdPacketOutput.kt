package com.virtualdap.host.audio

import com.virtualdap.host.audio.usb.DirectUsbDsdSink
import com.virtualdap.host.audio.usb.UsbOutputStatistics
import com.virtualdap.host.model.OutputRoute

/** Dormant adapter retained for a future, explicitly enabled compatibility mode. */
class DirectDsdPacketOutput(
    deviceId: Int,
    route: OutputRoute,
    source: DsdFormat,
    mode: DsdOutputMode,
    dopCapabilityConfirmed: Boolean,
) : DsdPacketOutput {
    private val sink = DirectUsbDsdSink(deviceId, route)
    val configuration: com.virtualdap.host.audio.usb.DirectDsdConfiguration

    init {
        configuration = try {
            sink.configure(source, mode, dopCapabilityConfirmed)
        } catch (failure: Throwable) {
            sink.close()
            throw failure
        }
    }

    override fun write(interleavedDsd: ByteArray) {
        check(sink.write(interleavedDsd) == interleavedDsd.size)
    }

    override fun setPlaying(playing: Boolean) = sink.setPlaying(playing)
    override fun finish() = sink.finish()
    override fun close() = sink.close()
    fun statistics(): UsbOutputStatistics? = sink.statistics()
    fun sourcePreservedActive(): Boolean = sink.sourcePreservedActive()
    fun routedOutput(): OutputRoute? = sink.routedOutput()
}
