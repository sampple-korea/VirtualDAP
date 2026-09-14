package com.virtualdap.host.service

import android.content.Context
import android.net.Credentials
import android.os.SystemClock
import com.virtualdap.host.audio.AndroidAudioSink
import com.virtualdap.host.audio.RoutedAudioSink
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.bridge.*
import com.virtualdap.host.model.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Independent host tracks let Android mix overlapping music/crossfade streams. A single active
 * stream can request USB bit-perfect; overlapping streams explicitly revoke that preference.
 */
class AudioSessionMixer(
    private val context: Context,
    private val notify: (String) -> Unit,
    private val onPlaybackActivity: (Boolean) -> Unit = {},
    private val beforeOutputStart: () -> Unit = {},
) : Closeable {
    private val topology = Any()
    private val sessions = linkedMapOf<Long, Session>()
    private val sequence = AtomicLong()
    private val closed = AtomicBoolean(false)
    @Volatile private var selectedRoute = PipelineStore.state.value.selectedRouteId
    @Volatile private var routeRevision = 0L
    private var playbackActive = false

    fun createSession(): BridgeEvents = Session(sequence.incrementAndGet())

    fun selectRoute(route: Int?) {
        selectedRoute = route
        routeRevision++
        PipelineStore.update { it.copy(selectedRouteId = route, bitPerfectActive = false) }
        PipelineStore.log("Output selection changed; each active track will negotiate the new route")
    }

    private fun reconcile() = synchronized(topology) {
        val exclusive = sessions.values.count { it.active } <= 1
        sessions.values.forEach {
            it.sink.setExclusiveAllowed(exclusive && it.active)
            it.invalidateBitPerfect()
        }
        publishLocked()
    }

    private fun publish() = synchronized(topology) { publishLocked() }

    /** Never acquire a sink lock here: PCM writers publish only already-observed state. */
    private fun publishLocked() {
        if (closed.get()) return
        val active = sessions.values.filter { it.active }
        if (playbackActive != active.isNotEmpty()) {
            playbackActive = active.isNotEmpty()
            onPlaybackActivity(playbackActive)
        }
        val foreground = (active.ifEmpty { sessions.values.toList() }).maxByOrNull { it.activation }
        val selected = foreground?.state
        PipelineStore.update { current ->
            if (selected == null) current.copy(
                guestConnected = false, guestPeer = null, sourceFormat = null, sinkFormat = null,
                connectedStreams = 0, playingStreams = 0, bitPerfectActive = false,
                directPlayback = false, sourcePreserved = true, latencyMs = null,
                phase = if (current.enabled) PipelinePhase.WAITING_FOR_GUEST else PipelinePhase.STOPPED,
            ) else selected.copy(
                enabled = current.enabled, availableRoutes = current.availableRoutes,
                selectedRouteId = current.selectedRouteId, logs = current.logs,
                connectedStreams = sessions.size, playingStreams = active.size,
                bitPerfectActive = active.size == 1 && selected.bitPerfectActive,
                guestDroppedBytes = sessions.values.sumOf { it.state.guestDroppedBytes },
                reconnectCount = sessions.values.sumOf { it.state.reconnectCount },
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val all = synchronized(topology) { sessions.values.toList().also { sessions.clear() } }
        all.forEach { it.shutdown() }
        onPlaybackActivity(false)
    }

    private inner class Session(private val id: Long) : BridgeEvents {
        val sink = RoutedAudioSink(context)
        private val operations = Any()
        private val stateRef = AtomicReference(PipelineSnapshot())
        var state: PipelineSnapshot
            get() = stateRef.get()
            private set(value) { stateRef.set(value) }
        @Volatile var active = false
        @Volatile var activation = id
        private var controlled = false
        private var appliedRoute = -1L
        private var configuredSource: PcmFormat? = null
        private var positionBase = 0L
        private var lastMixerCheck = 0L
        private var disposed = false

        private fun update(block: (PipelineSnapshot) -> PipelineSnapshot) {
            stateRef.updateAndGet(block)
            publish()
        }

        fun invalidateBitPerfect() { stateRef.updateAndGet { it.copy(bitPerfectActive = false) } }

        override fun onGuestConnected(peer: Credentials, handshake: BridgeHandshake) = synchronized(operations) {
            check(!closed.get() && !disposed) { "Audio pipeline is closed" }
            controlled = handshake.version == BridgeWireProtocol.CONTROLLED_VERSION
            active = !controlled
            sink.setPlaying(active)
            state = PipelineSnapshot(
                guestConnected = true, guestPeer = "pid ${peer.pid} · stream $id",
                sourceFormat = handshake.format, phase = PipelinePhase.BUFFERING,
            )
            synchronized(topology) {
                check(!closed.get()) { "Audio pipeline is closed" }
                sessions[id] = this
            }
            reconcile()
            beforeOutputStart()
            if (active) configure()
            PipelineStore.log("Stream $id connected: ${handshake.format.shortLabel()}")
            notify("Music space connected")
        }

        private fun configure() {
            val format = state.sourceFormat ?: error("Missing source format")
            if (appliedRoute != routeRevision) {
                sink.selectRoute(selectedRoute)
                appliedRoute = routeRevision
                configuredSource = null
            }
            if (configuredSource == format) return
            val result = sink.configure(format)
            configuredSource = format
            update { it.copy(
                sinkFormat = result.configured, sourcePreserved = result.sourcePreserved,
                directPlayback = result.directPlayback, activeRoute = result.route,
                bitPerfectActive = false, lastError = null,
            ) }
            PipelineStore.log("Stream $id output: ${result.configured.shortLabel()}" +
                if (result.sourcePreserved) " · unchanged PCM" else " · compatibility conversion")
        }

        override fun onFormatChanged(format: PcmFormat, streamEpoch: Long) = synchronized(operations) {
            check(!disposed)
            state = state.copy(sourceFormat = format, phase = PipelinePhase.BUFFERING)
            positionBase = state.framesReceived
            configure()
        }

        override fun onPcm(pcm: ByteArray, sequence: Long) = synchronized(operations) {
            check(!disposed && active) { "PCM submitted by a stopped or paused stream" }
            configure()
            val source = state.sourceFormat ?: error("Missing source format")
            val count = sink.write(pcm)
            val usb = sink.usbStatistics()
            check(usb == null || usb.error == 0) { "Direct USB transfer failed (${usb?.error})" }
            if (usb != null && usb.underruns > state.outputUnderruns) {
                PipelineStore.log("Stream $id USB queue ran dry (${usb.underruns} times); no artificial samples were inserted", LogLevel.WARNING)
            }
            val route = sink.routedOutput()
            val now = SystemClock.elapsedRealtime()
            val bitPerfect = if (now - lastMixerCheck >= 500 || route?.id != state.activeRoute?.id) {
                lastMixerCheck = now
                sink.bitPerfectActive()
            } else state.bitPerfectActive
            update { it.copy(
                phase = PipelinePhase.PLAYING, bytesReceived = it.bytesReceived + count,
                framesReceived = it.framesReceived + count / source.frameSizeBytes,
                latencyMs = sink.queuedDurationMs(), activeRoute = route ?: it.activeRoute,
                bitPerfectActive = bitPerfect,
                outputUnderruns = usb?.underruns ?: 0,
                outputFramesCompleted = usb?.completedFrames,
            ) }
        }

        override fun onGuestStats(framesWritten: Long, droppedBytes: Long, reconnects: Long) =
            synchronized(operations) {
                update { it.copy(guestDroppedBytes = droppedBytes, reconnectCount = reconnects) }
            }

        override fun onPlaybackControl(command: BridgeControl) = synchronized(operations) {
            check(!disposed && controlled)
            when (command) {
                BridgeControl.PLAY -> {
                    active = true
                    activation = sequence.incrementAndGet()
                    reconcile()
                    sink.setPlaying(true)
                    configure()
                    update { it.copy(phase = PipelinePhase.BUFFERING) }
                    notify("Playing ${state.sourceFormat?.shortLabel()}")
                }
                BridgeControl.PAUSE -> {
                    sink.setPlaying(false)
                    active = false
                    state = state.copy(phase = PipelinePhase.PAUSED)
                    reconcile()
                    if (PipelineStore.state.value.playingStreams == 0) notify("Music paused")
                }
                BridgeControl.FLUSH -> {
                    sink.flush()
                    positionBase = state.framesReceived
                }
                BridgeControl.STOP -> {
                    sink.finish()
                    sink.setPlaying(false)
                    active = false
                    configuredSource = null
                    positionBase = state.framesReceived
                    state = state.copy(phase = PipelinePhase.BUFFERING, bitPerfectActive = false)
                    reconcile()
                }
            }
        }

        override fun onVolume(left: Float, right: Float) = synchronized(operations) {
            check(!disposed)
            sink.setVolume(left, right)
            update { it.copy(
                applicationGainLeft = left, applicationGainRight = right,
                bitPerfectActive = sink.bitPerfectActive(),
            ) }
        }

        override fun playbackPosition(): BridgePosition = synchronized(operations) {
            val usb = sink.usbStatistics()
            check(usb == null || usb.error == 0) { "Direct USB transfer failed (${usb?.error})" }
            val rate = state.sourceFormat?.sampleRate ?: 48_000
            val queued = ((sink.queuedDurationMs() ?: 0.0) * rate / 1_000.0).toLong()
            BridgePosition((state.framesReceived - positionBase - queued).coerceAtLeast(0), System.nanoTime())
        }

        override fun onGuestDisconnected(reason: String?) {
            var failureReason = reason
            synchronized(operations) {
                if (disposed) return
                disposed = true
                try {
                    if (reason == null && !closed.get()) sink.finish() else sink.close()
                } catch (error: Exception) {
                    failureReason = "Output completion failed: ${error.message}"
                    PipelineStore.log("Stream $id output completion failed: ${error.message}", LogLevel.ERROR)
                } finally {
                    sink.close()
                    active = false
                }
            }
            synchronized(topology) { sessions.remove(id) }
            reconcile()
            if (!closed.get()) {
                PipelineStore.log(failureReason?.let { "Stream $id disconnected: $it" } ?: "Stream $id completed",
                    if (failureReason == null) LogLevel.INFO else LogLevel.WARNING)
                if (PipelineStore.state.value.connectedStreams == 0) {
                    if (failureReason != null) PipelineStore.update { it.copy(lastError = failureReason, phase = PipelinePhase.ERROR) }
                    notify(if (failureReason == null) "Waiting for music" else "Audio stream needs attention")
                }
            }
        }

        fun shutdown() = synchronized(operations) {
            disposed = true
            active = false
            sink.close()
        }
    }
}
