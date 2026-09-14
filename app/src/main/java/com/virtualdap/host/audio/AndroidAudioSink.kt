package com.virtualdap.host.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.virtualdap.host.model.OutputRoute
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SinkConfiguration(
    val requested: PcmFormat,
    val configured: PcmFormat,
    val route: OutputRoute?,
    val directPlayback: Boolean,
    val bufferFrames: Int,
    val sourcePreserved: Boolean,
    val bitPerfectRequested: Boolean = false,
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
    private var mixerDevice: AudioDeviceInfo? = null
    private var mixerAttributes: AudioAttributes? = null
    private var playing = true
    private var leftGain = 1f
    private var rightGain = 1f
    private var exclusiveAllowed = true

    fun setExclusiveAllowed(value: Boolean) = synchronized(lock) {
        exclusiveAllowed = value
        if (!value) clearMixerPreference()
    }

    @Suppress("DEPRECATION")
    fun setVolume(left: Float, right: Float) = synchronized(lock) {
        require(left.isFinite() && right.isFinite() && left in 0f..1f && right in 0f..1f)
        leftGain = left
        rightGain = right
        if (left != 1f || right != 1f) clearMixerPreference()
        track?.setStereoVolume(left, right)
    }

    /** Controlled producers send PCM only while playing, so no paused write can hold this lock. */
    fun setPlaying(value: Boolean) = synchronized(lock) {
        playing = value
        track?.let { if (value) it.play() else it.pause() }
    }

    fun flush() = synchronized(lock) {
        val active = track ?: return@synchronized
        val resume = playing
        active.pause()
        active.flush()
        configuration?.let { config ->
            converter?.close()
            converter = null
            try {
                converter = createConverter(config.requested, config.configured)
            } catch (failure: Throwable) {
                releaseTrack()
                throw failure
            }
        }
        submittedFrames = 0
        playbackHeadWraps = 0
        lastPlaybackHead = 0
        if (resume) active.play()
    }

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
        finishTrack()
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
        val bitPerfect = source == target && leftGain == 1f && rightGain == 1f &&
            requestBitPerfectMixer(preferred, attributes, androidFormat)
        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(androidFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .apply {
                // The low-latency flag is useful for the ordinary 48 kHz/16-bit mixer path,
                // but can make direct/high-resolution routes reject an otherwise exact format.
                if (!bitPerfect && target.sampleRate <= 48_000 && target.channelCount <= 2 &&
                    target.encoding == PcmEncoding.PCM_16
                ) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setOffloadedPlayback(false)
            }
        val built = try {
            trackBuilder.build()
        } catch (error: Exception) {
            clearMixerPreference()
            throw error
        }
        var streamConverter: StreamingPcmConverter? = null
        try {
            if (built.state != AudioTrack.STATE_INITIALIZED) {
                throw AudioSinkException("Could not initialize ${target.shortLabel()}")
            }
            if (preferred != null && !built.setPreferredDevice(preferred)) {
                throw AudioSinkException("Output route ${preferred.productName} rejected the stream")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                built.setStartThresholdInFrames(maxOf(1, target.sampleRate / 100))
            }
            @Suppress("DEPRECATION")
            built.setStereoVolume(leftGain, rightGain)
            if (playing) built.play()
            val direct = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    AudioManager.getDirectPlaybackSupport(androidFormat, attributes) !=
                        AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    @Suppress("DEPRECATION") AudioTrack.isDirectPlaybackSupported(androidFormat, attributes)
                else -> false
            }
            streamConverter = createConverter(source, target)
            track = built
            converter = streamConverter
            submittedFrames = 0
            playbackHeadWraps = 0
            lastPlaybackHead = 0
            val activeRoute = built.routedDevice?.toOutputRoute()
            return SinkConfiguration(
                requested = source,
                configured = target,
                route = activeRoute,
                directPlayback = direct,
                bufferFrames = bufferBytes / target.frameSizeBytes,
                sourcePreserved = source == target,
                bitPerfectRequested = bitPerfect,
            ).also { configuration = it }
        } catch (error: Throwable) {
            streamConverter?.close()
            if (track === built) track = null
            if (converter === streamConverter) converter = null
            built.release()
            clearMixerPreference()
            throw error
        }
    }

    private fun requestBitPerfectMixer(
        device: AudioDeviceInfo?,
        attributes: AudioAttributes,
        format: AudioFormat,
    ): Boolean {
        if (!exclusiveAllowed || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            device == null || device.type !in USB_DEVICE_TYPES
        ) return false
        val supported = audioManager.getSupportedMixerAttributes(device).firstOrNull {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                it.format.sampleRate == format.sampleRate &&
                it.format.encoding == format.encoding &&
                it.format.channelMask == format.channelMask
        } ?: return false
        if (!audioManager.setPreferredMixerAttributes(attributes, device, supported)) return false
        mixerDevice = device
        mixerAttributes = attributes
        return true
    }

    /** Checks both the currently routed device and the OS's current mixer preference. */
    fun bitPerfectActive(): Boolean = synchronized(lock) {
        if (!exclusiveAllowed || leftGain != 1f || rightGain != 1f) return@synchronized false
        val device = mixerDevice ?: return@synchronized false
        val attributes = mixerAttributes ?: return@synchronized false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            track?.routedDevice?.id != device.id
        ) return@synchronized false
        val current = audioManager.getPreferredMixerAttributes(attributes, device)
        current?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            current.format == track?.format
    }

    private fun clearMixerPreference() {
        val device = mixerDevice
        val attributes = mixerAttributes
        mixerDevice = null
        mixerAttributes = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            device != null && attributes != null
        ) runCatching { audioManager.clearPreferredMixerAttributes(attributes, device) }
    }

    fun write(pcm: ByteArray): Int = synchronized(lock) {
        converter?.let { active ->
            active.convertInto(pcm, ::writeOutput)
            return@synchronized pcm.size
        }
        if (pcm.isNotEmpty()) writeOutput(pcm)
        pcm.size
    }

    private fun writeOutput(output: ByteArray) {
        var active = track ?: throw AudioSinkException("Audio sink is not configured")
        // AudioTrack's byte[] overload explicitly rejects PCM float. ByteBuffer is the raw-byte
        // API for every advertised encoding, including packed 24-bit, 32-bit and float PCM.
        val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        var restarts = 0
        while (buffer.hasRemaining()) {
            val count = active.write(buffer, buffer.remaining(), AudioTrack.WRITE_BLOCKING)
            if (count == AudioTrack.ERROR_DEAD_OBJECT) {
                if (++restarts > 1) throw AudioSinkException("AudioTrack repeatedly died during one packet")
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
            submittedFrames += count / (configuration?.configured?.frameSizeBytes
                ?: throw AudioSinkException("Audio sink configuration disappeared"))
        }
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

    fun routedOutput(): OutputRoute? = synchronized(lock) { track?.routedDevice?.toOutputRoute() }

    /** Graceful end: submit the converter tail and let queued frames play before releasing. */
    fun finish() = synchronized(lock) {
        try {
            finishTrack()
        } finally {
            releaseTrack()
        }
    }

    override fun close() = synchronized(lock) { releaseTrack() }

    private fun finishTrack() {
        if (track == null) return
        // A graceful stop drains previously submitted frames even after pause.
        track?.play()
        converter?.finish()?.takeIf { it.isNotEmpty() }?.let(::writeOutput)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // A short clip can end before the initial fill threshold. Permit its remaining
            // frames to start instead of waiting forever for a buffer that will never fill.
            track?.setStartThresholdInFrames(1)
        }
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while ((queuedDurationMs() ?: 0.0) > 0.0) {
            if (Thread.currentThread().isInterrupted) throw AudioSinkException("Audio drain interrupted")
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AudioSinkException("Audio output stopped advancing while draining")
            }
            Thread.sleep(5)
        }
    }

    private fun releaseTrack() {
        track?.let { active ->
            try { active.pause() } catch (_: IllegalStateException) { }
            active.flush()
            active.release()
        }
        track = null
        clearMixerPreference()
        configuration = null
        converter?.close()
        converter = null
        submittedFrames = 0
        playbackHeadWraps = 0
        lastPlaybackHead = 0
    }

    private fun createConverter(source: PcmFormat, target: PcmFormat): StreamingPcmConverter? {
        if (source == target) return null
        val resampler = if (source.sampleRate != target.sampleRate) {
            HighQualityPcmResampler(source.sampleRate, target.sampleRate, target.channelCount)
        } else null
        return try {
            StreamingPcmConverter(source, target, resampler)
        } catch (failure: Throwable) {
            resampler?.close()
            throw failure
        }
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
