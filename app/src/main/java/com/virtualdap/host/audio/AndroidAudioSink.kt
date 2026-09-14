package com.virtualdap.host.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
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

/**
 * Android 14 official bit-perfect mixer sink.
 *
 * This class deliberately has no mixer, default-route, resampling, channel-remapping or encoding
 * fallback. A stream is accepted only when the selected output advertises the exact PCM format
 * with [AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT].
 */
class AndroidAudioSink(context: Context) : Closeable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var track: AudioTrack? = null
    private var configuration: SinkConfiguration? = null
    private var selectedRouteId: Int? = null
    private var selectedDevice: AudioDeviceInfo? = null
    private var selectedMixer: AudioMixerAttributes? = null
    private var mixerAttributes: AudioAttributes? = null
    private var submittedFrames = 0L
    private var playbackHeadWraps = 0L
    private var lastPlaybackHead = 0L
    private var playing = true
    private var exclusiveAllowed = true

    fun setExclusiveAllowed(value: Boolean) = synchronized(lock) {
        exclusiveAllowed = value
        if (!value) releaseTrack()
    }

    fun setVolume(left: Float, right: Float) = synchronized(lock) {
        require(left.isFinite() && right.isFinite() && left in 0f..1f && right in 0f..1f)
        if (left != 1f || right != 1f) {
            releaseTrack()
            throw AudioSinkException(
                "Official bit-perfect output requires 100% application volume on both channels; " +
                    "adjust volume on the DAC instead",
            )
        }
        track?.setVolume(1f)
    }

    /** Controlled producers send PCM only while playing, so no paused write can hold this lock. */
    fun setPlaying(value: Boolean) = synchronized(lock) {
        playing = value
        track?.let { if (value) it.play() else it.pause() }
        Unit
    }

    fun flush() = synchronized(lock) {
        val active = track ?: return@synchronized
        val resume = playing
        active.pause()
        active.flush()
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
            val query = runCatching { queryBitPerfectMixers(device) }
            device.toOutputRoute(query.getOrDefault(emptyList())).copy(
                officialQueryFailure = query.exceptionOrNull()?.javaClass?.simpleName,
            )
        }
        .sortedWith(
            compareByDescending<OutputRoute> { it.officialBitPerfectFormats.isNotEmpty() }
                .thenByDescending { it.isUsb }
                .thenBy { it.name.lowercase() },
        )

    fun configure(format: PcmFormat): SinkConfiguration = synchronized(lock) {
        if (!exclusiveAllowed) {
            throw AudioSinkException("Official bit-perfect output supports exactly one active PCM stream")
        }
        if (configuration?.requested == format && track?.state == AudioTrack.STATE_INITIALIZED &&
            mixerPreferenceActive()
        ) {
            return configuration!!
        }
        try { finishTrack() } finally { releaseTrack() }

        val routeId = selectedRouteId
            ?: throw AudioSinkException("Select an output with Android official bit-perfect support")
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == routeId }
            ?: throw AudioSinkException("The selected output is no longer connected")
        val mixers = bitPerfectMixers(device)
        val advertised = mixers.mapNotNull { it.format.toPcmFormatOrNull() }
        val exact = OfficialBitPerfectPolicy.exactMatch(format, advertised)
            ?: throw AudioSinkException(
                "${device.displayName()} does not support ${format.shortLabel()} through Android's " +
                    "official bit-perfect path. Advertised formats: ${OfficialBitPerfectPolicy.summary(advertised)}",
            )
        val mixer = mixers.firstOrNull { it.format.matches(exact) }
            ?: throw AudioSinkException("The selected bit-perfect mixer format could not be resolved")
        buildTrack(format, device, mixer)
    }

    private fun buildTrack(
        format: PcmFormat,
        device: AudioDeviceInfo,
        mixer: AudioMixerAttributes,
    ): SinkConfiguration {
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
        val bufferBytes = maxOf(minimum * 2, format.frameSizeBytes * (format.sampleRate / 25))

        if (!audioManager.setPreferredMixerAttributes(attributes, device, mixer)) {
            throw AudioSinkException("Android rejected the official bit-perfect mixer request for ${device.displayName()}")
        }
        selectedDevice = device
        selectedMixer = mixer
        mixerAttributes = attributes

        val built = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(mixer.format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setOffloadedPlayback(false)
                .build()
        } catch (error: Exception) {
            clearMixerPreference()
            throw error
        }
        try {
            if (built.state != AudioTrack.STATE_INITIALIZED) {
                throw AudioSinkException("Could not initialize ${format.shortLabel()}")
            }
            if (!built.setPreferredDevice(device)) {
                throw AudioSinkException("Output route ${device.displayName()} rejected the stream")
            }
            built.setStartThresholdInFrames(1)
            built.setVolume(1f)
            track = built
            submittedFrames = 0
            playbackHeadWraps = 0
            lastPlaybackHead = 0
            val result = SinkConfiguration(
                requested = format,
                configured = format,
                route = device.toOutputRoute(bitPerfectMixers(device)),
                directPlayback = true,
                bufferFrames = bufferBytes / format.frameSizeBytes,
                sourcePreserved = true,
                bitPerfectRequested = true,
            )
            configuration = result
            if (!mixerPreferenceActive()) {
                throw AudioSinkException("Android did not retain the requested bit-perfect mixer configuration")
            }
            if (playing) built.play()
            return result
        } catch (error: Throwable) {
            if (track === built) track = null
            configuration = null
            built.release()
            clearMixerPreference()
            throw error
        }
    }

    /** True only after both the official mixer preference and the actual routed device agree. */
    fun bitPerfectActive(): Boolean = synchronized(lock) {
        mixerPreferenceActive() && track?.routedDevice?.id == selectedDevice?.id
    }

    private fun mixerPreferenceActive(): Boolean {
        if (!exclusiveAllowed) return false
        val device = selectedDevice ?: return false
        val attributes = mixerAttributes ?: return false
        val expected = selectedMixer ?: return false
        val expectedFormat = expected.format.toPcmFormatOrNull() ?: return false
        val current = runCatching { audioManager.getPreferredMixerAttributes(attributes, device) }.getOrNull()
            ?: return false
        return current.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            current.format == expected.format && track?.format?.matches(expectedFormat) == true
    }

    private fun clearMixerPreference() {
        val device = selectedDevice
        val attributes = mixerAttributes
        selectedDevice = null
        selectedMixer = null
        mixerAttributes = null
        if (device != null && attributes != null) {
            runCatching { audioManager.clearPreferredMixerAttributes(attributes, device) }
        }
    }

    fun write(pcm: ByteArray): Int = synchronized(lock) {
        require(pcm.size <= 1024 * 1024) { "PCM packet exceeds the size limit" }
        val format = configuration?.configured ?: throw AudioSinkException("Audio sink is not configured")
        require(pcm.size % format.frameSizeBytes == 0) { "PCM packet contains a partial frame" }
        try {
            if (pcm.isNotEmpty()) writeOutput(pcm)
        } catch (failure: Throwable) {
            releaseTrack()
            throw failure
        }
        pcm.size
    }

    private fun writeOutput(output: ByteArray) {
        var active = track ?: throw AudioSinkException("Audio sink is not configured")
        val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        var restarts = 0
        while (buffer.hasRemaining()) {
            if (!mixerPreferenceActive()) {
                throw AudioSinkException("Android revoked the official bit-perfect mixer configuration")
            }
            awaitExactRoute(active)
            val count = active.write(buffer, buffer.remaining(), AudioTrack.WRITE_BLOCKING)
            if (count == AudioTrack.ERROR_DEAD_OBJECT) {
                if (++restarts > 1) throw AudioSinkException("AudioTrack repeatedly died during one packet")
                val deadConfiguration = configuration ?: throw AudioSinkException("AudioTrack died")
                releaseTrack()
                val restarted = configure(deadConfiguration.requested)
                if (restarted.configured != deadConfiguration.configured) {
                    throw AudioSinkException("AudioTrack restart changed the official output format")
                }
                active = track ?: throw AudioSinkException("AudioTrack restart failed")
                continue
            }
            if (count < 0) throw AudioSinkException("AudioTrack write failed: $count")
            if (count == 0) throw AudioSinkException("AudioTrack accepted no PCM data")
            submittedFrames += count / (configuration?.configured?.frameSizeBytes
                ?: throw AudioSinkException("Audio sink configuration disappeared"))
            awaitExactRoute(active)
        }
    }

    private fun awaitExactRoute(active: AudioTrack) {
        val expected = selectedDevice ?: throw AudioSinkException("Selected output disappeared")
        val deadline = SystemClock.elapsedRealtime() + ROUTE_CONFIRMATION_TIMEOUT_MS
        do {
            val routed = active.routedDevice
            if (routed?.id == expected.id) return
            if (routed != null) {
                throw AudioSinkException(
                    "Android routed audio to ${routed.displayName()} instead of ${expected.displayName()}; playback stopped",
                )
            }
            Thread.sleep(2)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AudioSinkException("Android did not confirm routing to ${expected.displayName()}; playback stopped")
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

    fun routedOutput(): OutputRoute? = synchronized(lock) {
        track?.routedDevice?.let { it.toOutputRoute(bitPerfectMixers(it)) }
    }

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
        track?.play()
        track?.setStartThresholdInFrames(1)
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while ((queuedDurationMs() ?: 0.0) > 0.0) {
            if (!mixerPreferenceActive()) {
                throw AudioSinkException("Android revoked bit-perfect output while draining")
            }
            awaitExactRoute(requireNotNull(track))
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
            runCatching { active.flush() }
            active.release()
        }
        track = null
        clearMixerPreference()
        configuration = null
        submittedFrames = 0
        playbackHeadWraps = 0
        lastPlaybackHead = 0
    }

    private fun queryBitPerfectMixers(device: AudioDeviceInfo): List<AudioMixerAttributes> =
        audioManager.getSupportedMixerAttributes(device).filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }

    private fun bitPerfectMixers(device: AudioDeviceInfo): List<AudioMixerAttributes> = try {
        queryBitPerfectMixers(device)
    } catch (error: Exception) {
        throw AudioSinkException("Could not query official bit-perfect support (${error.javaClass.simpleName})")
    }

    private fun AudioDeviceInfo.toOutputRoute(mixers: List<AudioMixerAttributes>) = OutputRoute(
        id = id,
        name = displayName(),
        type = type,
        isUsb = type in USB_DEVICE_TYPES,
        sampleRates = sampleRates.toList().sorted(),
        encodings = encodings.toList().sorted(),
        officialBitPerfectFormats = mixers.mapNotNull { it.format.toPcmFormatOrNull() }.distinct(),
        officialBitPerfectReported = mixers.isNotEmpty(),
    )

    private fun AudioDeviceInfo.displayName(): String =
        productName?.toString()?.takeIf(String::isNotBlank) ?: typeLabel(type)

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

    @SuppressLint("InlinedApi")
    private fun PcmEncoding.toAndroidEncoding(): Int = when (this) {
        PcmEncoding.PCM_16 -> AudioFormat.ENCODING_PCM_16BIT
        PcmEncoding.PCM_24_PACKED -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        PcmEncoding.PCM_32 -> AudioFormat.ENCODING_PCM_32BIT
        PcmEncoding.PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
    }

    private fun AudioFormat.toPcmFormatOrNull(): PcmFormat? {
        val pcm = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmEncoding.PCM_24_PACKED
            AudioFormat.ENCODING_PCM_32BIT -> PcmEncoding.PCM_32
            AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.PCM_FLOAT
            else -> return null
        }
        return runCatching {
            // The capture protocol declares a canonical channel layout, not an arbitrary index mask.
            require(channelIndexMask == 0 && channelMask == channelCount.toOutputChannelMask())
            PcmFormat(sampleRate, channelCount, pcm)
        }.getOrNull()
    }

    private fun AudioFormat.matches(format: PcmFormat): Boolean =
        sampleRate == format.sampleRate && channelCount == format.channelCount &&
            encoding == format.encoding.toAndroidEncoding()

    private fun Int.toOutputChannelMask(): Int = when (this) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> throw AudioSinkException("Unsupported output channel count: $this")
    }

    companion object {
        private const val ROUTE_CONFIRMATION_TIMEOUT_MS = 500L
        private val USB_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
