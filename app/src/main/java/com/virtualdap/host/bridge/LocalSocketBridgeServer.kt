package com.virtualdap.host.bridge

import android.net.Credentials
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import com.virtualdap.host.audio.PcmFormat
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

interface BridgeEvents {
    fun onGuestConnected(peer: Credentials, handshake: BridgeHandshake)
    fun onFormatChanged(format: PcmFormat, streamEpoch: Long)
    fun onPcm(pcm: ByteArray, sequence: Long)
    fun onGuestStats(framesWritten: Long, droppedBytes: Long, reconnects: Long)
    fun onGuestDisconnected(reason: String?)
    fun onPlaybackControl(command: BridgeControl) {
        throw BridgeProtocolException("Playback controls are unavailable")
    }
    fun playbackPosition(): BridgePosition = BridgePosition(0, System.nanoTime())
    fun onVolume(left: Float, right: Float) {
        throw BridgeProtocolException("Playback volume is unavailable")
    }
}

/** Receives application-container PCM over a same-UID abstract Unix socket. */
class LocalSocketBridgeServer(
    private val events: BridgeEvents? = null,
    private val socketName: String = SOCKET_NAME,
    private val peerPolicy: BridgePeerPolicy = BridgePeerPolicy(Process.myUid()),
    private val eventsFactory: (() -> BridgeEvents)? = null,
) : Closeable {
    init { require((events == null) != (eventsFactory == null)) { "Provide events or a session factory" } }
    private val running = AtomicBoolean(false)
    @Volatile private var server: LocalServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val slots = Semaphore(if (eventsFactory == null) 1 else 16)
    private var worker: Thread? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "Bridge server is already running" }
        try {
            val listener = LocalServerSocket(socketName)
            server = listener
            worker = Thread({ acceptLoop(listener) }, "VirtualDAP-bridge").apply { start() }
        } catch (error: IOException) {
            running.set(false)
            server = null
            throw error
        }
    }

    private fun acceptLoop(listener: LocalServerSocket) {
        try {
            while (running.get()) {
                val accepted = try {
                    listener.accept()
                } catch (error: IOException) {
                    if (running.get()) Log.w(TAG, "Audio listener failed", error)
                    break
                }
                if (!running.get() || !slots.tryAcquire()) {
                    accepted.close()
                    continue
                }
                clients.add(accepted)
                if (!running.get()) {
                    clients.remove(accepted)
                    accepted.close()
                    slots.release()
                    break
                }
                Thread({
                    try { handleClient(accepted) } finally {
                        clients.remove(accepted)
                        slots.release()
                    }
                }, "VirtualDAP-stream").apply { isDaemon = true; start() }
            }
        } finally {
            try { listener.close() } catch (_: IOException) { }
            server = null
        }
    }

    private fun handleClient(socket: LocalSocket) {
        var disconnectReason: String? = null
        var session: BridgeEvents? = null
        try {
            val peer = socket.peerCredentials
            if (!peerPolicy.isAllowed(peer.uid)) {
                throw BridgeProtocolException("Rejected bridge peer uid ${peer.uid}")
            }
            socket.receiveBufferSize = 64 * 1024
            socket.soTimeout = 5_000
            val reader = BridgeWireReader(socket.inputStream)
            val output = socket.outputStream
            val handshake = reader.readHandshake()
            socket.soTimeout = 0 // A paused track may legitimately remain connected indefinitely.
            val events = eventsFactory?.invoke() ?: requireNotNull(this.events)
            session = events
            val controlled = handshake.version == BridgeWireProtocol.CONTROLLED_VERSION
            events.onGuestConnected(peer, handshake)
            var currentFormat = handshake.format
            var lastSequence = -1L
            while (running.get()) {
                val message = reader.readMessage() ?: break
                if (message.sequence <= lastSequence) {
                    throw BridgeProtocolException(
                        "Non-monotonic packet sequence ${message.sequence} after $lastSequence",
                    )
                }
                lastSequence = message.sequence
                when (message) {
                    is BridgeMessage.Audio -> {
                        if (message.pcm.size % currentFormat.frameSizeBytes != 0) {
                            throw BridgeProtocolException(
                                "PCM payload ${message.pcm.size} is not frame-aligned for ${currentFormat.frameSizeBytes}",
                            )
                        }
                        events.onPcm(message.pcm, message.sequence)
                    }
                    is BridgeMessage.Format -> {
                        currentFormat = message.format
                        events.onFormatChanged(message.format, message.streamEpoch)
                    }
                    is BridgeMessage.Stats -> events.onGuestStats(
                        message.framesWritten,
                        message.droppedBytes,
                        message.reconnects,
                    )
                    is BridgeMessage.Ping -> Unit
                    is BridgeMessage.Control -> {
                        if (!controlled) throw BridgeProtocolException("Playback control requires protocol 3")
                        events.onPlaybackControl(message.command)
                    }
                    is BridgeMessage.Volume -> {
                        if (!controlled) throw BridgeProtocolException("Playback volume requires protocol 3")
                        events.onVolume(message.left, message.right)
                    }
                }
                if (controlled || message is BridgeMessage.Audio) {
                    BridgeWireWriter.writeAck(
                        output, message.sequence, if (controlled) events.playbackPosition() else null,
                    )
                }
            }
        } catch (error: Exception) {
            disconnectReason = error.message ?: error.javaClass.simpleName
            Log.w(TAG, "Guest bridge disconnected", error)
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
                // Already closed.
            }
            session?.onGuestDisconnected(if (running.get()) disconnectReason else "Audio pipeline stopped")
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        clients.forEach { try { it.close() } catch (_: IOException) { } }
        try { server?.close() } catch (_: IOException) { }
        worker?.interrupt()
        worker = null
    }

    companion object {
        const val SOCKET_NAME = "virtualdap_audio_v1"
        private const val TAG = "VirtualDAP-Bridge"
    }
}
