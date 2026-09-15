package com.virtualdap.host.audio.usb

import com.virtualdap.host.audio.FloatPcmResampler
import com.virtualdap.host.audio.HighQualityPcmResampler
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.audio.SinkConfiguration
import com.virtualdap.host.audio.StreamingPcmConverter
import com.virtualdap.host.model.OutputRoute
import java.io.Closeable

/** PCM-specific adapter. DoP/DSD must use their typed path, never this volume/packing stage. */
class DirectUsbPcmSink(
    private val deviceId: Int,
    private val route: OutputRoute,
    private val createRateConverter: (Int, Int, Int) -> FloatPcmResampler =
        ::HighQualityPcmResampler,
    private val openConnection: (Int, (List<UsbAudioStreamingProfile>) -> UsbAudioStreamingProfile) -> UsbHostController.DirectConnection =
        UsbHostController::openDirect,
) : Closeable {
    private var connection: UsbHostController.DirectConnection? = null
    private var packing: UsbPcmPacking? = null
    private var converter: StreamingPcmConverter? = null
    private var conversionTarget: PcmFormat? = null
    private var configuration: SinkConfiguration? = null
    private var playing = true
    private var exclusive = true
    private var left = 1f
    private var right = 1f
    private var suspendedStatistics: UsbOutputStatistics? = null

    fun setPlaying(value: Boolean) {
        playing = value
        connection?.output?.let { if (value) it.resume() else it.pause() }
    }
    fun setVolume(left: Float, right: Float) {
        require(left.isFinite() && right.isFinite() && left in 0f..1f && right in 0f..1f)
        this.left = left
        this.right = right
    }
    fun setExclusiveAllowed(value: Boolean) { exclusive = value }
    fun configure(source: PcmFormat): SinkConfiguration {
        configuration?.takeIf { it.requested == source }?.let { return it }
        finish()
        check(exclusive) { "USB 출력은 한 번에 한 트랙만 재생할 수 있습니다. 크로스페이드를 꺼 주세요." }
        val failures = mutableListOf<String>()
        val knownCandidates = mutableMapOf<UsbAudioStreamingProfile, List<DirectUsbPcmCandidate>>()
        val unavailableProfiles = mutableSetOf<UsbAudioStreamingProfile>()
        for (tier in 0..2) {
            var profileIndex = 0
            profileLoop@ while (true) {
                var targetIndex = 0
                var supportedRates: List<UsbSampleRateRange>? = null
                var candidates: List<DirectUsbPcmCandidate>? = null
                while (true) {
                    if (candidates?.let { targetIndex >= it.size } == true) break
                    var selectedProfile: UsbAudioStreamingProfile? = null
                    val opened = try {
                        openConnection(deviceId) { profiles ->
                            DirectUsbPcmPlanner.compatibleProfiles(source, profiles).getOrNull(profileIndex)
                                ?.also { profile ->
                                    if (profile in unavailableProfiles) throw SkipProfile()
                                    val known = knownCandidates[profile] ?: if (profile.protocol == 0) {
                                        DirectUsbPcmPlanner.candidates(source, profile, profile.rates)
                                            .also { knownCandidates[profile] = it }
                                    } else null
                                    if (known != null && known.none {
                                        DirectUsbPcmPlanner.conversionTier(source, it) == tier
                                    }) throw SkipProfile()
                                    selectedProfile = profile
                                }
                                ?: throw ProfilesExhausted()
                        }
                    } catch (_: ProfilesExhausted) {
                        break@profileLoop
                    } catch (_: SkipProfile) {
                        break
                    } catch (failure: Throwable) {
                        if (selectedProfile == null) throw failure
                        if (failure !is Exception) throw failure
                        unavailableProfiles += selectedProfile
                        failures += selectedProfile.failureLabel(failure)
                        break
                    }
                    val profile = requireNotNull(selectedProfile)
                    val rates = supportedRates ?: try {
                        UsbAudioClockControl(opened.output::control).supportedRates(profile)
                            .also { supportedRates = it }
                    } catch (failure: Exception) {
                        opened.close()
                        unavailableProfiles += profile
                        failures += profile.failureLabel(failure)
                        break
                    }
                    val available = candidates ?: knownCandidates.getOrPut(profile) {
                        DirectUsbPcmPlanner.candidates(source, profile, rates)
                    }.filter { DirectUsbPcmPlanner.conversionTier(source, it) == tier }
                        .also { candidates = it }
                    val candidate = available.getOrNull(targetIndex)
                    if (candidate == null) {
                        opened.close()
                        break
                    }
                    var streamConverter: StreamingPcmConverter? = null
                    try {
                        val target = candidate.inputFormat
                        val adapter = UsbPcmPacking(target, opened.output.profile)
                        streamConverter = createConverter(source, target)
                        opened.output.start(target.sampleRate)
                        if (!playing) opened.output.pause()
                        val preserved = streamConverter == null && adapter.preservesSource
                        packing = adapter
                        converter = streamConverter
                        conversionTarget = target
                        connection = opened
                        suspendedStatistics = null
                        return SinkConfiguration(
                            source, adapter.outputFormat, route, directPlayback = true,
                            bufferFrames = target.sampleRate / 10, sourcePreserved = preserved,
                            bitPerfectRequested = preserved,
                        ).also { configuration = it }
                    } catch (failure: Throwable) {
                        streamConverter?.close()
                        opened.close()
                        if (failure !is Exception) throw failure
                        failures += "${profile.failureLabel(failure)} at ${candidate.inputFormat.shortLabel()}"
                        targetIndex++
                    }
                }
                profileIndex++
            }
        }
        val detail = failures.distinct().takeLast(4).joinToString("; ")
        throw IllegalStateException(
            buildString {
                append("No direct USB PCM profile accepted ")
                append(source.shortLabel())
                if (detail.isNotEmpty()) append(": ").append(detail)
            },
        )
    }
    fun write(input: ByteArray): Int {
        converter?.let { active ->
            active.convertInto(input, ::writeConverted)
            return input.size
        }
        if (input.isNotEmpty()) writeConverted(input)
        return input.size
    }
    private fun writeConverted(input: ByteArray) {
        val transport = connection?.output ?: error("Direct USB output is not configured")
        val adapter = packing ?: error("Direct USB format is not configured")
        val bytes = adapter.pack(input, left, right)
        var offset = 0
        while (offset < bytes.size) {
            val maximum = 1024 * 1024 - 1024 * 1024 % adapter.profile.frameBytes
            val length = minOf(bytes.size - offset, maximum)
            val accepted = transport.write(bytes.copyOfRange(offset, offset + length))
            check(accepted > 0 && accepted % adapter.profile.frameBytes == 0) { "USB output stopped accepting complete frames" }
            offset += accepted
        }
    }
    fun statistics(): UsbOutputStatistics? = connection?.output?.statistics() ?: suspendedStatistics
    fun queuedDurationMs(): Double? {
        val adapter = packing ?: return null
        val stats = statistics() ?: return null
        return stats.queuedBytes * 1000.0 / adapter.profile.frameBytes / adapter.source.sampleRate
    }
    fun bitPerfectActive(): Boolean {
        val stats = statistics() ?: return false
        val adapter = packing ?: return false
        return connection != null && exclusive && configuration?.sourcePreserved == true &&
            adapter.preservesSource && left == 1f && right == 1f &&
            stats.error == 0 && stats.completedFrames > 0 && stats.underruns == 0L &&
            (adapter.profile.feedbackEndpointAddress == null || stats.feedbackPackets > 0)
    }
    fun routedOutput(): OutputRoute? = route.takeIf { (statistics()?.completedFrames ?: 0) > 0 }
    fun flush() {
        val active = connection?.output ?: return
        active.flush()
        val config = configuration ?: return
        val target = conversionTarget ?: return
        try {
            converter?.close()
            converter = null
            converter = createConverter(config.requested, target)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    fun finish() {
        try {
            connection?.output?.let { output ->
                converter?.finish()?.takeIf { it.isNotEmpty() }?.let(::writeConverted)
                output.drain()
                suspendedStatistics = output.statistics()
            }
        } finally { close() }
    }
    override fun close() {
        val closingConnection = connection
        val closingConverter = converter
        connection = null
        packing = null
        converter = null
        conversionTarget = null
        configuration = null
        try { closingConverter?.close() } finally { closingConnection?.close() }
    }

    private fun createConverter(source: PcmFormat, target: PcmFormat): StreamingPcmConverter? {
        if (source == target) return null
        val rateConverter = if (source.sampleRate != target.sampleRate) {
            createRateConverter(source.sampleRate, target.sampleRate, target.channelCount)
        } else null
        return try {
            StreamingPcmConverter(source, target, rateConverter)
        } catch (failure: Throwable) {
            rateConverter?.close()
            throw failure
        }
    }

    private fun UsbAudioStreamingProfile.failureLabel(failure: Exception): String =
        "USB alt $alternateSetting: ${failure.message ?: failure.javaClass.simpleName}"

    private class ProfilesExhausted : IllegalStateException()
    private class SkipProfile : IllegalStateException()
}
