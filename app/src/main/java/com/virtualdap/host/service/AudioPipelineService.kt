package com.virtualdap.host.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.virtualdap.host.MainActivity
import com.virtualdap.host.R
import com.virtualdap.host.audio.AndroidAudioSink
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.bridge.BridgeEvents
import com.virtualdap.host.bridge.BridgeHandshake
import com.virtualdap.host.bridge.LocalSocketBridgeServer
import com.virtualdap.host.bridge.BridgePeerPolicy
import com.virtualdap.host.guest.GuestRuntimeController
import com.virtualdap.host.model.LogLevel
import com.virtualdap.host.model.PipelinePhase
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class AudioPipelineService : Service(), BridgeEvents {
    private lateinit var audioManager: AudioManager
    private lateinit var sink: AndroidAudioSink
    private var bridge: LocalSocketBridgeServer? = null
    private var currentFormat: PcmFormat? = null
    private var configuredSourceFormat: PcmFormat? = null
    private var serviceStarted = false
    private val selfTestRunning = AtomicBoolean(false)
    private var receivedBytes = 0L
    private var receivedFrames = 0L

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshRoutes()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshRoutes()
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        sink = AndroidAudioSink(this)
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshRoutes()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startPipeline()
            ACTION_STOP -> stopPipeline(stopService = true)
            ACTION_SELECT_ROUTE -> selectRoute(intent?.getIntExtra(EXTRA_ROUTE_ID, DEFAULT_ROUTE_ID) ?: DEFAULT_ROUTE_ID)
            ACTION_SELF_TEST -> runOutputSelfTest()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        if (!serviceStarted) {
            createNotificationChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification("Waiting for guest audio"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else 0,
            )
            serviceStarted = true
        }
        if (bridge != null) return
        try {
            bridge = LocalSocketBridgeServer(
                events = this,
                peerPolicy = BridgePeerPolicy(
                    hostUid = android.os.Process.myUid(),
                    trustedRuntimeUid = GuestRuntimeController::trustedProviderUid,
                ),
            ).also { it.start() }
            PipelineStore.update {
                it.copy(enabled = true, phase = PipelinePhase.WAITING_FOR_GUEST, lastError = null)
            }
            PipelineStore.log("Bridge listening on @${LocalSocketBridgeServer.SOCKET_NAME}")
        } catch (error: Exception) {
            fail("Could not start bridge: ${error.message}")
        }
    }

    private fun stopPipeline(stopService: Boolean) {
        bridge?.close()
        bridge = null
        sink.close()
        currentFormat = null
        configuredSourceFormat = null
        PipelineStore.update {
            it.copy(
                enabled = false,
                phase = PipelinePhase.STOPPED,
                guestConnected = false,
                guestPeer = null,
                sourceFormat = null,
                sinkFormat = null,
                directPlayback = false,
                sourcePreserved = true,
            )
        }
        PipelineStore.log("Audio pipeline stopped")
        if (serviceStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            serviceStarted = false
        }
        if (stopService) stopSelf()
    }

    private fun selectRoute(routeId: Int) {
        val selected = routeId.takeUnless { it == DEFAULT_ROUTE_ID }
        sink.selectRoute(selected)
        PipelineStore.update { it.copy(selectedRouteId = selected) }
        val name = sink.routes().firstOrNull { it.id == selected }?.name ?: "system default"
        PipelineStore.log("Selected output: $name")
        currentFormat?.let { configureSink(it) }
    }

    override fun onGuestConnected(peer: android.net.Credentials, handshake: BridgeHandshake) {
        receivedBytes = 0
        receivedFrames = 0
        currentFormat = handshake.format
        PipelineStore.update {
            it.copy(
                guestConnected = true,
                guestPeer = "pid ${peer.pid} · uid ${peer.uid}",
                phase = PipelinePhase.BUFFERING,
                sourceFormat = handshake.format,
                lastError = null,
            )
        }
        PipelineStore.log("Guest connected: ${handshake.format.shortLabel()}")
        configureSink(handshake.format)
    }

    override fun onFormatChanged(format: PcmFormat, streamEpoch: Long) {
        currentFormat = format
        PipelineStore.update { it.copy(sourceFormat = format, phase = PipelinePhase.BUFFERING) }
        PipelineStore.log("Source format changed: ${format.shortLabel()} (epoch $streamEpoch)")
        configureSink(format)
    }

    override fun onPcm(pcm: ByteArray, sequence: Long) {
        val format = currentFormat ?: return
        try {
            if (configuredSourceFormat != format) configureSink(format)
            val written = sink.write(pcm)
            receivedBytes += written
            receivedFrames += written / format.frameSizeBytes
            val actualRoute = sink.routedOutput()
            val routeChanged = actualRoute != null &&
                actualRoute.id != PipelineStore.state.value.activeRoute?.id
            PipelineStore.update {
                it.copy(
                    phase = PipelinePhase.PLAYING,
                    bytesReceived = receivedBytes,
                    framesReceived = receivedFrames,
                    latencyMs = sink.queuedDurationMs(),
                    activeRoute = actualRoute ?: it.activeRoute,
                )
            }
            if (routeChanged) {
                val selected = PipelineStore.state.value.selectedRouteId
                PipelineStore.log(
                    "AudioTrack routed to ${actualRoute.name}" +
                        if (selected != null && selected != actualRoute.id) " (different from preferred output)" else "",
                    if (selected != null && selected != actualRoute.id) LogLevel.WARNING else LogLevel.INFO,
                )
            }
        } catch (error: Exception) {
            fail("Playback failed at packet $sequence: ${error.message}")
            throw error
        }
    }

    override fun onGuestStats(framesWritten: Long, droppedBytes: Long, reconnects: Long) {
        PipelineStore.update {
            it.copy(guestDroppedBytes = droppedBytes, reconnectCount = reconnects)
        }
    }

    override fun onGuestDisconnected(reason: String?) {
        sink.close()
        currentFormat = null
        configuredSourceFormat = null
        PipelineStore.update {
            it.copy(
                guestConnected = false,
                guestPeer = null,
                phase = when {
                    !it.enabled -> PipelinePhase.STOPPED
                    it.lastError != null -> PipelinePhase.ERROR
                    else -> PipelinePhase.WAITING_FOR_GUEST
                },
                sourceFormat = null,
                sinkFormat = null,
                directPlayback = false,
                sourcePreserved = true,
            )
        }
        PipelineStore.log(
            reason?.let { "Guest disconnected: $it" } ?: "Guest disconnected",
            if (reason == null) LogLevel.INFO else LogLevel.WARNING,
        )
    }

    private fun configureSink(format: PcmFormat) {
        try {
            val result = sink.configure(format)
            configuredSourceFormat = format
            PipelineStore.update {
                it.copy(
                    sinkFormat = result.configured,
                    activeRoute = result.route,
                    directPlayback = result.directPlayback,
                    sourcePreserved = result.sourcePreserved,
                    lastError = null,
                )
            }
            PipelineStore.log(
                "Output ready: ${result.route?.name ?: "system default"} · " + when {
                    !result.sourcePreserved -> "converted to ${result.configured.shortLabel()}"
                    result.directPlayback -> "exact format, direct supported"
                    else -> "exact AudioTrack format, Android mixer"
                },
            )
            updateNotification("Playing ${format.shortLabel()}")
        } catch (error: Exception) {
            fail("Output negotiation failed: ${error.message}")
        }
    }

    private fun runOutputSelfTest() {
        if (PipelineStore.state.value.guestConnected) {
            PipelineStore.log("Output self-test skipped while guest playback is active", LogLevel.WARNING)
            return
        }
        if (!selfTestRunning.compareAndSet(false, true)) return
        if (!serviceStarted) startPipeline()
        thread(name = "VirtualDAP-self-test") {
            val format = PcmFormat(48_000, 2, PcmEncoding.PCM_16)
            try {
                configureSink(format)
                PipelineStore.log("Output self-test started (440 Hz, 2 seconds)")
                val framesPerChunk = 480
                val chunk = ByteArray(framesPerChunk * format.frameSizeBytes)
                var frameIndex = 0
                repeat(200) {
                    for (frame in 0 until framesPerChunk) {
                        val sample = (sin(2.0 * PI * 440.0 * frameIndex / format.sampleRate) * 4_000).toInt()
                        val base = frame * 4
                        chunk[base] = sample.toByte()
                        chunk[base + 1] = (sample shr 8).toByte()
                        chunk[base + 2] = sample.toByte()
                        chunk[base + 3] = (sample shr 8).toByte()
                        frameIndex++
                    }
                    sink.write(chunk)
                }
                PipelineStore.log("Output self-test completed")
            } catch (error: Exception) {
                fail("Output self-test failed: ${error.message}")
            } finally {
                if (!PipelineStore.state.value.guestConnected) sink.close()
                selfTestRunning.set(false)
            }
        }
    }

    private fun refreshRoutes() {
        val routes = sink.routes()
        PipelineStore.update { current ->
            val selectionExists = current.selectedRouteId == null || routes.any { it.id == current.selectedRouteId }
            current.copy(
                availableRoutes = routes,
                selectedRouteId = current.selectedRouteId.takeIf { selectionExists },
            )
        }
    }

    private fun fail(message: String) {
        PipelineStore.update { it.copy(phase = PipelinePhase.ERROR, lastError = message) }
        PipelineStore.log(message, LogLevel.ERROR)
        updateNotification("Audio pipeline error")
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopPipeline(stopService = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio bridge", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VirtualDAP guest audio playback status"
            },
        )
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioPipelineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VirtualDAP")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        if (serviceStarted) getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val ACTION_START = "com.virtualdap.host.action.START"
        const val ACTION_STOP = "com.virtualdap.host.action.STOP"
        const val ACTION_SELECT_ROUTE = "com.virtualdap.host.action.SELECT_ROUTE"
        const val ACTION_SELF_TEST = "com.virtualdap.host.action.SELF_TEST"
        const val EXTRA_ROUTE_ID = "route_id"
        const val DEFAULT_ROUTE_ID = -1
        private const val CHANNEL_ID = "virtualdap_audio"
        private const val NOTIFICATION_ID = 42

        fun command(context: Context, action: String, routeId: Int? = null) {
            val intent = Intent(context, AudioPipelineService::class.java).setAction(action)
            routeId?.let { intent.putExtra(EXTRA_ROUTE_ID, it) }
            if (action == ACTION_START || action == ACTION_SELF_TEST) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
