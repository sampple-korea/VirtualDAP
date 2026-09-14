package com.virtualdap.host.audio.usb

import com.virtualdap.host.audio.DopContainer
import com.virtualdap.host.audio.DopEncoder
import com.virtualdap.host.audio.DsdFormat
import com.virtualdap.host.audio.DsdOutputMode
import com.virtualdap.host.audio.NativeDsdEncoder
import com.virtualdap.host.model.OutputRoute
import java.io.Closeable

data class DirectDsdConfiguration(
    val source: DsdFormat,
    val mode: DsdOutputMode,
    val transportRate: Int,
    val route: OutputRoute,
    /** Hardware-reference qualification for native DSD, or explicit user confirmation for DoP. */
    val qualification: String,
)

/**
 * Opaque DSD-to-USB path. It never exposes a PCM write method, applies gain, mixes, resamples, or
 * silently inserts samples. DoP needs an explicit capability confirmation because UAC descriptors
 * do not advertise it. Native DSD needs a reference-qualified wire layout on the chosen profile.
 */
class DirectUsbDsdSink(
    private val deviceId: Int,
    private val route: OutputRoute,
    private val openConnection: (Int, (List<UsbAudioStreamingProfile>) -> UsbAudioStreamingProfile) -> UsbHostController.DirectConnection =
        UsbHostController::openDirect,
) : Closeable {
    private var connection: UsbHostController.DirectConnection? = null
    private var nativeEncoder: NativeDsdEncoder? = null
    private var dopEncoder: DopEncoder? = null
    private var configuration: DirectDsdConfiguration? = null
    private var frameBytes = 0
    private var playing = true
    private var suspendedStatistics: UsbOutputStatistics? = null
    private var suspendedFeedbackRequired = false
    private var completedCleanly = false

    fun setPlaying(value: Boolean) {
        playing = value
        connection?.output?.let { if (value) it.resume() else it.pause() }
    }

    fun configure(
        source: DsdFormat,
        mode: DsdOutputMode,
        dopCapabilityConfirmed: Boolean = false,
    ): DirectDsdConfiguration {
        require(mode != DsdOutputMode.PCM_CONVERSION) { "PCM conversion must use the typed DSD-to-PCM path" }
        configuration?.takeIf { it.source == source && it.mode == mode }?.let { return it }
        finish()
        suspendedStatistics = null
        suspendedFeedbackRequired = false
        completedCleanly = false

        var selectedNative: NativeCandidate? = null
        var selectedDop: DopCandidate? = null
        val opened = openConnection(deviceId) { profiles ->
            when (mode) {
                DsdOutputMode.NATIVE_DSD -> nativeCandidates(source, profiles).firstOrNull()
                    ?.also { selectedNative = it }?.profile
                    ?: error("No reference-qualified native DSD profile for ${source.shortLabel()}")
                DsdOutputMode.DOP -> {
                    check(dopCapabilityConfirmed) {
                        "DoP is not advertised by USB descriptors; confirm this DAC's DoP support explicitly"
                    }
                    dopCandidates(source, profiles).firstOrNull()?.also { selectedDop = it }?.profile
                        ?: error("No matching 24-bit DoP carrier profile for ${source.shortLabel()}")
                }
                DsdOutputMode.PCM_CONVERSION -> error("unreachable")
            }
        }
        try {
            val transportRate: Int
            val qualification: String
            when (mode) {
                DsdOutputMode.NATIVE_DSD -> {
                    val candidate = checkNotNull(selectedNative)
                    nativeEncoder = NativeDsdEncoder(source, candidate.layout)
                    frameBytes = candidate.profile.frameBytes
                    transportRate = candidate.transportRate
                    qualification = requireNotNull(candidate.profile.dsdQualification)
                }
                DsdOutputMode.DOP -> {
                    val candidate = checkNotNull(selectedDop)
                    dopEncoder = DopEncoder(source, candidate.container)
                    frameBytes = candidate.profile.frameBytes
                    transportRate = source.dopSampleRate
                    qualification = "User-confirmed DoP 1.1 capability"
                }
                DsdOutputMode.PCM_CONVERSION -> error("unreachable")
            }
            opened.output.start(transportRate)
            if (!playing) opened.output.pause()
            connection = opened
            suspendedStatistics = null
            suspendedFeedbackRequired = false
            completedCleanly = false
            return DirectDsdConfiguration(source, mode, transportRate, route, qualification)
                .also { configuration = it }
        } catch (failure: Exception) {
            opened.close()
            resetState()
            suspendedStatistics = null
            throw failure
        }
    }

    /** Consumes canonical chronological bytes: one DSD byte per channel for each time group. */
    fun write(interleavedDsd: ByteArray): Int {
        val transport = connection?.output ?: error("Direct USB DSD output is not configured")
        val encoded = nativeEncoder?.encode(interleavedDsd)
            ?: dopEncoder?.encode(interleavedDsd)
            ?: error("Direct USB DSD format is not configured")
        writeAll(transport, encoded)
        return interleavedDsd.size
    }

    fun statistics(): UsbOutputStatistics? = connection?.output?.statistics() ?: suspendedStatistics

    fun queuedDurationMs(): Double? {
        val configured = configuration ?: return null
        val stats = statistics() ?: return null
        return stats.queuedBytes * 1000.0 / frameBytes / configured.transportRate
    }

    fun sourcePreservedActive(): Boolean {
        val stats = statistics() ?: return false
        val feedbackRequired = connection?.output?.profile?.feedbackEndpointAddress != null ||
            (connection == null && suspendedFeedbackRequired)
        return (connection != null || completedCleanly) && stats.error == 0 && stats.completedFrames > 0 &&
            stats.underruns == 0L &&
            (!feedbackRequired || stats.feedbackPackets > 0)
    }

    fun routedOutput(): OutputRoute? = route.takeIf { (statistics()?.completedFrames ?: 0) > 0 }

    /** The caller pauses before FLUSH; discard encoder fragments from the old source epoch too. */
    fun flush() {
        connection?.output?.flush()
        nativeEncoder?.reset()
        dopEncoder?.reset()
    }

    fun finish() {
        if (connection == null) {
            resetState()
            return
        }
        try {
            nativeEncoder?.finish()
            dopEncoder?.finish()
            connection?.output?.let {
                it.drain()
                suspendedStatistics = it.statistics()
                suspendedFeedbackRequired = it.profile.feedbackEndpointAddress != null
                completedCleanly = true
            }
        } finally {
            close()
        }
    }

    override fun close() {
        connection?.close()
        resetState()
    }

    private fun resetState() {
        connection = null
        nativeEncoder = null
        dopEncoder = null
        configuration = null
        frameBytes = 0
    }

    private fun writeAll(transport: UsbOutputTransport, bytes: ByteArray) {
        var offset = 0
        val maximum = 1024 * 1024 - 1024 * 1024 % frameBytes
        while (offset < bytes.size) {
            val length = minOf(bytes.size - offset, maximum)
            val accepted = transport.write(bytes.copyOfRange(offset, offset + length))
            check(accepted > 0 && accepted % frameBytes == 0) {
                "USB output stopped accepting complete DSD transport frames"
            }
            offset += accepted
        }
    }

    private data class NativeCandidate(
        val profile: UsbAudioStreamingProfile,
        val layout: com.virtualdap.host.audio.NativeDsdLayout,
        val transportRate: Int,
    )

    private data class DopCandidate(val profile: UsbAudioStreamingProfile, val container: DopContainer)

    companion object {
        private fun nativeCandidates(source: DsdFormat, profiles: List<UsbAudioStreamingProfile>): List<NativeCandidate> =
            profiles.mapNotNull { profile ->
                val layout = profile.nativeDsd ?: return@mapNotNull null
                if (profile.channelCount != source.channelCount || profile.subslotBytes != layout.wordBytes ||
                    profile.dsdQualification == null) return@mapNotNull null
                val rate = source.sampleRate / (8 * layout.wordBytes)
                if (profile.protocol != 0x20 && profile.rates.none { it.contains(rate) }) return@mapNotNull null
                NativeCandidate(profile, layout, rate)
            }.sortedByDescending { it.profile.maximumPacketBytes }

        private fun dopCandidates(source: DsdFormat, profiles: List<UsbAudioStreamingProfile>): List<DopCandidate> =
            profiles.mapNotNull { profile ->
                if (profile.channelCount != source.channelCount || !profile.pcm || profile.floatingPoint ||
                    profile.rawData || profile.bitResolution != 24 || profile.subslotBytes !in setOf(3, 4) ||
                    (profile.protocol != 0x20 && profile.rates.none { it.contains(source.dopSampleRate) })
                ) return@mapNotNull null
                DopCandidate(
                    profile,
                    if (profile.subslotBytes == 3) DopContainer.PACKED_24 else DopContainer.PADDED_32,
                )
            }.sortedByDescending { it.profile.maximumPacketBytes }
    }
}
