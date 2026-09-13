package com.virtualdap.host.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.virtualdap.host.MainActivity
import com.virtualdap.host.R
import com.virtualdap.host.audio.AndroidAudioSink
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.bridge.BridgePeerPolicy
import com.virtualdap.host.bridge.LocalSocketBridgeServer
import com.virtualdap.host.guest.GuestRuntimeController
import com.virtualdap.host.model.LogLevel
import com.virtualdap.host.model.PipelinePhase
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class AudioPipelineService : Service() {
    private lateinit var audioManager: AudioManager
    private lateinit var routes: AndroidAudioSink
    private var bridge: LocalSocketBridgeServer? = null
    private var mixer: AudioSessionMixer? = null
    @Volatile private var serviceStarted = false
    private val selfTestRunning = AtomicBoolean(false)
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshRoutes()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshRoutes()
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        routes = AndroidAudioSink(this)
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshRoutes()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startPipeline()
            ACTION_STOP -> stopPipeline(true)
            ACTION_SELECT_ROUTE -> selectRoute(intent?.getIntExtra(EXTRA_ROUTE_ID, DEFAULT_ROUTE_ID) ?: DEFAULT_ROUTE_ID)
            ACTION_SELF_TEST -> runOutputSelfTest()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        if (!serviceStarted) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Music space audio", NotificationManager.IMPORTANCE_LOW),
            )
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Waiting for music"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            serviceStarted = true
        }
        if (bridge != null) return
        val sessions = AudioSessionMixer(this, ::updateNotification)
        try {
            mixer = sessions
            bridge = LocalSocketBridgeServer(
                eventsFactory = sessions::createSession,
                peerPolicy = BridgePeerPolicy(android.os.Process.myUid(), GuestRuntimeController::trustedProviderUid),
            ).also { it.start() }
            PipelineStore.update { it.copy(enabled = true, phase = PipelinePhase.WAITING_FOR_GUEST, lastError = null) }
            PipelineStore.log("Audio bridge ready; independent bounded connections for each music track")
        } catch (error: Exception) {
            sessions.close()
            mixer = null
            fail("Could not start audio bridge: ${error.message}")
        }
    }

    private fun stopPipeline(stopService: Boolean) {
        bridge?.close()
        bridge = null
        mixer?.close()
        mixer = null
        PipelineStore.update { it.copy(
            enabled = false, phase = PipelinePhase.STOPPED, guestConnected = false, guestPeer = null,
            sourceFormat = null, sinkFormat = null, bitPerfectActive = false, directPlayback = false,
            sourcePreserved = true, connectedStreams = 0, playingStreams = 0, latencyMs = null,
        ) }
        PipelineStore.log("Audio pipeline stopped")
        if (serviceStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            serviceStarted = false
        }
        if (stopService) stopSelf()
    }

    private fun selectRoute(routeId: Int) {
        val selected = routeId.takeUnless { it == DEFAULT_ROUTE_ID }
        mixer?.selectRoute(selected)
        PipelineStore.update { it.copy(selectedRouteId = selected) }
    }

    private fun refreshRoutes() {
        val available = routes.routes()
        val old = PipelineStore.state.value.selectedRouteId
        val selected = old?.takeIf { id -> available.any { it.id == id } }
        PipelineStore.update { it.copy(availableRoutes = available, selectedRouteId = selected) }
        if (selected != old) mixer?.selectRoute(selected)
    }

    private fun runOutputSelfTest() {
        if (PipelineStore.state.value.guestConnected) {
            PipelineStore.log("Output self-test skipped while a music track is connected", LogLevel.WARNING)
            return
        }
        if (!selfTestRunning.compareAndSet(false, true)) return
        if (!serviceStarted) startPipeline()
        thread(name = "VirtualDAP-self-test") {
            val format = PcmFormat(48_000, 2, PcmEncoding.PCM_16)
            val testSink = AndroidAudioSink(this)
            testSink.setExclusiveAllowed(false)
            try {
                testSink.selectRoute(PipelineStore.state.value.selectedRouteId)
                testSink.configure(format)
                PipelineStore.log("Host-only output self-test started (440 Hz, 2 seconds)")
                val packet = ByteArray(480 * format.frameSizeBytes)
                var frameIndex = 0
                repeat(200) {
                    if (!serviceStarted || PipelineStore.state.value.guestConnected) return@thread
                    repeat(480) { frame ->
                        val sample = (sin(2 * PI * 440 * frameIndex++ / format.sampleRate) * 4000).toInt()
                        val offset = frame * 4
                        packet[offset] = sample.toByte()
                        packet[offset + 1] = (sample shr 8).toByte()
                        packet[offset + 2] = sample.toByte()
                        packet[offset + 3] = (sample shr 8).toByte()
                    }
                    testSink.write(packet)
                }
                testSink.finish()
                PipelineStore.log("Output self-test completed")
            } catch (error: Exception) {
                if (!PipelineStore.state.value.guestConnected) fail("Output self-test failed: ${error.message}")
            } finally {
                testSink.close()
                selfTestRunning.set(false)
            }
        }
    }

    private fun fail(message: String) {
        PipelineStore.update { it.copy(phase = PipelinePhase.ERROR, lastError = message) }
        PipelineStore.log(message, LogLevel.ERROR)
        updateNotification("Audio pipeline needs attention")
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopPipeline(false)
        routes.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AudioPipelineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("VirtualDAP").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0, "Stop", stop).build()
    }

    private fun updateNotification(text: String) {
        if (serviceStarted) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
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
            if (action == ACTION_START || action == ACTION_SELF_TEST) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
