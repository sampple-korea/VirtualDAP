package com.virtualdap.host.bridge

import android.net.Credentials
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.virtualdap.host.audio.PcmFormat
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

interface BridgeEvents {
    fun onGuestConnected(peer: Credentials, handshake: BridgeHandshake)
    fun onFormatChanged(format: PcmFormat, streamEpoch: Long)
    fun onPcm(pcm: ByteArray, sequence: Long)
    fun onGuestStats(framesWritten: Long, droppedBytes: Long, reconnects: Long)
    fun onGuestDisconnected(reason: String?)
}

/** Receives guest HAL PCM over the shared-kernel abstract Unix socket namespace. */
class LocalSocketBridgeServer(
    private val events: BridgeEvents,
    private val socketName: String = SOCKET_NAME,
) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var client: LocalSocket? = null
    private var worker: Thread? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "Bridge server is already running" }
        worker = Thread(::acceptLoop, "VirtualDAP-bridge").apply { start() }
    }

    private fun acceptLoop() {
        try {
            val listener = LocalServerSocket(socketName)
            server = listener
            while (running.get()) {
                val accepted = try {
                    listener.accept()
                } catch (error: IOException) {
                    if (running.get()) events.onGuestDisconnected(error.message)
                    break
                }
                client = accepted
                handleClient(accepted)
                client = null
            }
        } catch (error: IOException) {
            if (running.get()) events.onGuestDisconnected("Unable to open bridge socket: ${error.message}")
        } finally {
            server = null
        }
    }

    private fun handleClient(socket: LocalSocket) {
        var disconnectReason: String? = null
        try {
            socket.receiveBufferSize = 256 * 1024
            val reader = BridgeWireReader(socket.inputStream)
            val handshake = reader.readHandshake()
            events.onGuestConnected(socket.peerCredentials, handshake)
            var currentFormat = handshake.format
            while (running.get()) {
                when (val message = reader.readMessage() ?: break) {
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
            if (running.get()) events.onGuestDisconnected(disconnectReason)
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try { client?.close() } catch (_: IOException) { }
        try { server?.close() } catch (_: IOException) { }
        worker?.interrupt()
        worker = null
    }

    companion object {
        const val SOCKET_NAME = "virtualdap_audio_v1"
        private const val TAG = "VirtualDAP-Bridge"
    }
}
