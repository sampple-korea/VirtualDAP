package com.virtualdap.host

import android.net.Credentials
import android.os.SystemClock
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.bridge.*
import com.virtualdap.host.model.*
import com.virtualdap.host.service.PipelineStore
import java.io.Closeable

/**
 * Test-only paced socket receiver. Observes real container capture and control messages without
 * requiring a DAC. It never creates an AudioTrack or represents receipt as hardware playback.
 */
internal class CaptureProbe(
    private val observePcm: ((Credentials, PcmFormat, ByteArray) -> Unit)? = null,
) : Closeable {
    private val lock = Any()
    private val sessions = linkedSetOf<ProbeSession>()
    private val server = LocalSocketBridgeServer(eventsFactory = { ProbeSession() })
    @Volatile private var running = false
    fun start() {
        server.start()
        running = true
        PipelineStore.update { it.copy(enabled = true) }
    }
    override fun close() {
        running = false
        server.close()
        PipelineStore.update { it.copy(enabled = false) }
    }

    private fun publish(current: ProbeSession?) = synchronized(lock) {
        val selected = current?.takeIf { it in sessions } ?: sessions.lastOrNull()
        PipelineStore.update {
            (selected?.snapshot ?: PipelineSnapshot()).copy(
                enabled = running,
                guestConnected = sessions.isNotEmpty(),
                connectedStreams = sessions.size,
                playingStreams = sessions.count { it.playing },
                bitPerfectActive = false,
            )
        }
    }

    private inner class ProbeSession : BridgeEvents {
        private lateinit var peer: Credentials
        var playing = false
        var snapshot = PipelineSnapshot()
        private var nextDeadline = 0L
        override fun onGuestConnected(peer: Credentials, handshake: BridgeHandshake) = synchronized(lock) {
            this.peer = peer
            snapshot = PipelineSnapshot(sourceFormat = handshake.format, guestPeer = "capture probe")
            sessions.add(this)
            publish(this)
        }
        override fun onFormatChanged(format: PcmFormat, streamEpoch: Long) = synchronized(lock) {
            snapshot = snapshot.copy(sourceFormat = format)
            publish(this)
        }
        override fun onPcm(pcm: ByteArray, sequence: Long) {
            val format = requireNotNull(snapshot.sourceFormat)
            check(pcm.size % format.frameSizeBytes == 0)
            // Pace ACKs to exercise backpressure and make pause/overlap observations deterministic.
            val duration = pcm.size.toLong() / format.frameSizeBytes * 1_000_000_000L / format.sampleRate
            nextDeadline = maxOf(nextDeadline, System.nanoTime()) + duration
            val wait = (nextDeadline - System.nanoTime()) / 1_000_000L
            if (wait > 0) SystemClock.sleep(wait)
            observePcm?.invoke(peer, format, pcm)
            synchronized(lock) {
                snapshot = snapshot.copy(
                    framesReceived = snapshot.framesReceived + pcm.size / format.frameSizeBytes,
                    bytesReceived = snapshot.bytesReceived + pcm.size,
                    phase = PipelinePhase.PLAYING,
                )
                publish(this)
            }
        }
        override fun onGuestStats(framesWritten: Long, droppedBytes: Long, reconnects: Long) = synchronized(lock) {
            snapshot = snapshot.copy(guestDroppedBytes = droppedBytes, reconnectCount = reconnects)
            publish(this)
        }
        override fun onPlaybackControl(command: BridgeControl) = synchronized(lock) {
            when (command) {
                BridgeControl.PLAY -> { playing = true; nextDeadline = 0 }
                BridgeControl.PAUSE -> { playing = false; snapshot = snapshot.copy(phase = PipelinePhase.PAUSED) }
                BridgeControl.STOP -> { playing = false }
                BridgeControl.FLUSH -> { nextDeadline = 0 }
            }
            publish(this)
        }
        override fun onVolume(left: Float, right: Float) = synchronized(lock) {
            snapshot = snapshot.copy(applicationGainLeft = left, applicationGainRight = right)
            publish(this)
        }
        override fun playbackPosition() = synchronized(lock) {
            BridgePosition(snapshot.framesReceived, System.nanoTime())
        }
        override fun onGuestDisconnected(reason: String?) = synchronized(lock) {
            sessions.remove(this)
            publish(null)
        }
    }
}
