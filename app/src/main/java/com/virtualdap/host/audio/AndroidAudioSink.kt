package com.virtualdap.host.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.virtualdap.host.model.OutputRoute
import java.io.Closeable

data class SinkConfiguration(
    val requested: PcmFormat,
    val configured: PcmFormat,
    val route: OutputRoute?,
    val directPlayback: Boolean,
    val bufferFrames: Int,
)

class AudioSinkException(message: String) : IllegalStateException(message)

/** Blocking AudioTrack sink; backpressure keeps guest buffering bounded and playback paced. */
class AndroidAudioSink(context: Context) : Closeable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var track: AudioTrack? = null
    private var configuration: SinkConfiguration? = null
    private var selectedRouteId: Int? = null

    fun selectRoute(deviceId: Int?) = synchronized(lock) {
        if (selectedRouteId != deviceId) {
            selectedRouteId = deviceId
            releaseTrack()
        }
    }

    fun routes(): List<OutputRoute> = audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filterNot { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        .map { device ->
            OutputRoute(
                id = device.id,
                name = device.productName?.toString()?.takeIf(String::isNotBlank)
                    ?: typeLabel(device.type),
                type = device.type,
                isUsb = device.type in USB_DEVICE_TYPES,
                sampleRates = device.sampleRates.toList().sorted(),
                encodings = device.encodings.toList().sorted(),
            )
        }
        .sortedWith(compareByDescending<OutputRoute> { it.isUsb }.thenBy { it.name.lowercase() })

    fun configure(format: PcmFormat): SinkConfiguration = synchronized(lock) {
        if (configuration?.requested == format && track?.state == AudioTrack.STATE_INITIALIZED) {
            return configuration!!
        }
        releaseTrack()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            (format.encoding == PcmEncoding.PCM_24_PACKED || format.encoding == PcmEncoding.PCM_32)
        ) {
            throw AudioSinkException("24/32-bit packed PCM requires Android 12 or newer on the host")
        }

        val androidFormat = AudioFormat.Builder()
            .setSampleRate(format.sampleRate)
            .setEncoding(format.encoding.toAndroidEncoding())
            .setChannelMask(format.channelCount.toOutputChannelMask())
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            format.sampleRate,
            format.channelCount.toOutputChannelMask(),
            format.encoding.toAndroidEncoding(),
        )
        if (minimum <= 0) throw AudioSinkException("AudioTrack rejected ${format.shortLabel()} ($minimum)")
        val bufferBytes = maxOf(minimum * 2, format.frameSizeBytes * (format.sampleRate / 10))
        val built = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(androidFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setOffloadedPlayback(false)
            }
            .build()
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            built.release()
            throw AudioSinkException("Could not initialize ${format.shortLabel()}")
        }

        val preferred = selectedRouteId?.let { id ->
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        }
        if (preferred != null && !built.setPreferredDevice(preferred)) {
            built.release()
            throw AudioSinkException("Output route ${preferred.productName} rejected the stream")
        }
        built.play()
        track = built
        val direct = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                AudioManager.getDirectPlaybackSupport(androidFormat, attributes) !=
                    AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                @Suppress("DEPRECATION") AudioTrack.isDirectPlaybackSupported(androidFormat, attributes)
            else -> false
        }
        val route = built.routedDevice?.toOutputRoute() ?: preferred?.toOutputRoute()
        return SinkConfiguration(
            requested = format,
            configured = format,
            route = route,
            directPlayback = direct,
            bufferFrames = bufferBytes / format.frameSizeBytes,
        ).also { configuration = it }
    }

    fun write(pcm: ByteArray): Int = synchronized(lock) {
        val active = track ?: throw AudioSinkException("Audio sink is not configured")
        var offset = 0
        while (offset < pcm.size) {
            val count = active.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            if (count == AudioTrack.ERROR_DEAD_OBJECT) {
                val format = configuration?.requested ?: throw AudioSinkException("AudioTrack died")
                releaseTrack()
                configure(format)
                continue
            }
            if (count < 0) throw AudioSinkException("AudioTrack write failed: $count")
            if (count == 0) throw AudioSinkException("AudioTrack accepted no PCM data")
            offset += count
        }
        offset
    }

    override fun close() = synchronized(lock) { releaseTrack() }

    private fun releaseTrack() {
        track?.let { active ->
            try { active.pause() } catch (_: IllegalStateException) { }
            active.flush()
            active.release()
        }
        track = null
        configuration = null
    }

    private fun AudioDeviceInfo.toOutputRoute() = OutputRoute(
        id = id,
        name = productName?.toString()?.takeIf(String::isNotBlank) ?: typeLabel(type),
        type = type,
        isUsb = type in USB_DEVICE_TYPES,
        sampleRates = sampleRates.toList().sorted(),
        encodings = encodings.toList().sorted(),
    )

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI audio"
        else -> "Audio device"
    }

    @SuppressLint("InlinedApi") // Guarded in configure(); constants are inlined and safe to inspect.
    private fun PcmEncoding.toAndroidEncoding(): Int = when (this) {
        PcmEncoding.PCM_16 -> AudioFormat.ENCODING_PCM_16BIT
        PcmEncoding.PCM_24_PACKED -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        PcmEncoding.PCM_32 -> AudioFormat.ENCODING_PCM_32BIT
        PcmEncoding.PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
    }

    private fun Int.toOutputChannelMask(): Int = when (this) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> throw AudioSinkException("Unsupported output channel count: $this")
    }

    companion object {
        private val USB_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
