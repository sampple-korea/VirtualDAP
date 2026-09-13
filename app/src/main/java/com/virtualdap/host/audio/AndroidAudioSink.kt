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
    val sourcePreserved: Boolean,
)

class AudioSinkException(message: String) : IllegalStateException(message)

/** Blocking AudioTrack sink; backpressure keeps guest buffering bounded and playback paced. */
class AndroidAudioSink(context: Context) : Closeable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var track: AudioTrack? = null
    private var configuration: SinkConfiguration? = null
    private var converter: StreamingPcmConverter? = null
    private var selectedRouteId: Int? = null
    private var submittedFrames = 0L
    private var playbackHeadWraps = 0L
    private var lastPlaybackHead = 0L

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
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val preferred = selectedRouteId?.let { id ->
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        }
        val route = preferred?.toOutputRoute()
        val candidates = OutputFormatPlanner.candidates(
            source = format,
            routeSampleRates = route?.sampleRates.orEmpty(),
            packedHighResolutionSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            try {
                return buildTrack(format, candidate, preferred, attributes)
            } catch (error: Exception) {
                failures += "${candidate.shortLabel()}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        throw AudioSinkException(
            "No compatible output for ${format.shortLabel()}; tried ${failures.size} formats. " +
                failures.take(3).joinToString(" | "),
        )
    }

    private fun buildTrack(
        source: PcmFormat,
        target: PcmFormat,
        preferred: AudioDeviceInfo?,
        attributes: AudioAttributes,
    ): SinkConfiguration {
        val androidFormat = AudioFormat.Builder()
            .setSampleRate(target.sampleRate)
            .setEncoding(target.encoding.toAndroidEncoding())
            .setChannelMask(target.channelCount.toOutputChannelMask())
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            target.sampleRate,
            target.channelCount.toOutputChannelMask(),
            target.encoding.toAndroidEncoding(),
        )
        if (minimum <= 0) throw AudioSinkException("AudioTrack rejected ${target.shortLabel()} ($minimum)")
        val bufferBytes = maxOf(minimum * 2, target.frameSizeBytes * (target.sampleRate / 25))
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
        try {
            if (built.state != AudioTrack.STATE_INITIALIZED) {
                throw AudioSinkException("Could not initialize ${target.shortLabel()}")
            }
            if (preferred != null && !built.setPreferredDevice(preferred)) {
                throw AudioSinkException("Output route ${preferred.productName} rejected the stream")
            }
            built.play()
            val direct = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    AudioManager.getDirectPlaybackSupport(androidFormat, attributes) !=
                        AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    @Suppress("DEPRECATION") AudioTrack.isDirectPlaybackSupported(androidFormat, attributes)
                else -> false
            }
            track = built
            converter = StreamingPcmConverter(source, target).takeIf { source != target }
            submittedFrames = 0
            playbackHeadWraps = 0
            lastPlaybackHead = 0
            val activeRoute = built.routedDevice?.toOutputRoute() ?: preferred?.toOutputRoute()
            return SinkConfiguration(
                requested = source,
                configured = target,
                route = activeRoute,
                directPlayback = direct,
                bufferFrames = bufferBytes / target.frameSizeBytes,
                sourcePreserved = source == target,
            ).also { configuration = it }
        } catch (error: Exception) {
            built.release()
            throw error
        }
    }

    fun write(pcm: ByteArray): Int = synchronized(lock) {
        val output = converter?.convert(pcm) ?: pcm
        if (output.isEmpty()) return@synchronized pcm.size
        var active = track ?: throw AudioSinkException("Audio sink is not configured")
        var offset = 0
        while (offset < output.size) {
            val count = active.write(output, offset, output.size - offset, AudioTrack.WRITE_BLOCKING)
            if (count == AudioTrack.ERROR_DEAD_OBJECT) {
                val deadConfiguration = configuration ?: throw AudioSinkException("AudioTrack died")
                releaseTrack()
                val restarted = configure(deadConfiguration.requested)
                if (restarted.configured != deadConfiguration.configured) {
                    throw AudioSinkException("AudioTrack restart changed the negotiated output format")
                }
                active = track ?: throw AudioSinkException("AudioTrack restart failed")
                continue
            }
            if (count < 0) throw AudioSinkException("AudioTrack write failed: $count")
            if (count == 0) throw AudioSinkException("AudioTrack accepted no PCM data")
            offset += count
            submittedFrames += count / (configuration?.configured?.frameSizeBytes
                ?: throw AudioSinkException("Audio sink configuration disappeared"))
        }
        pcm.size
    }

    fun queuedDurationMs(): Double? = synchronized(lock) {
        val active = track ?: return@synchronized null
        val format = configuration?.configured ?: return@synchronized null
        val raw = active.playbackHeadPosition.toLong() and 0xffff_ffffL
        if (raw < lastPlaybackHead && lastPlaybackHead - raw > 0x8000_0000L) {
            playbackHeadWraps += 0x1_0000_0000L
        }
        lastPlaybackHead = raw
        val playedFrames = playbackHeadWraps + raw
        (submittedFrames - playedFrames).coerceAtLeast(0L) * 1_000.0 / format.sampleRate
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
        converter = null
        submittedFrames = 0
        playbackHeadWraps = 0
        lastPlaybackHead = 0
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
