package com.virtualdap.host.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.virtualdap.host.MainActivity
import com.virtualdap.host.R
import com.virtualdap.host.audio.AndroidAudioSink
import com.virtualdap.host.audio.DsdContainerReader
import com.virtualdap.host.audio.DsdDopPacketOutput
import com.virtualdap.host.audio.DsdOutputMode
import com.virtualdap.host.audio.DsdPacketOutput
import com.virtualdap.host.audio.DsdPlaybackEvent
import com.virtualdap.host.audio.DsdPlaybackTask
import com.virtualdap.host.audio.DsdPcmPacketOutput
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.bridge.BridgePeerPolicy
import com.virtualdap.host.bridge.LocalSocketBridgeServer
import com.virtualdap.host.model.LogLevel
import com.virtualdap.host.model.DsdPlaybackPhase
import com.virtualdap.host.model.DsdPlaybackSnapshot
import com.virtualdap.host.model.PipelinePhase
import kotlinx.coroutines.*
import java.io.Closeable
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
    @Volatile private var activeDsd: ActiveDsd? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!serviceStarted) return
            if (activeDsd != null) {
                stopDsd("Audio output disconnected during DSD playback")
                return
            }
            stopPipeline(false)
            PipelineStore.update { it.copy(lastError = "Audio output disconnected. Choose an output and start playback again.") }
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshRoutes()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshRoutes()
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        routes = AndroidAudioSink(this)
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        refreshRoutes()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy every startForegroundService request, including unsupported-output failures.
        if (intent?.action in setOf(ACTION_START, ACTION_SELF_TEST, ACTION_PLAY_DSD)) ensureForeground("Preparing audio")
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startPipeline()
            ACTION_STOP -> {
                stopDsd()
                stopPipeline(true)
            }
            ACTION_SELECT_ROUTE -> selectRoute(intent?.getIntExtra(EXTRA_ROUTE_ID, DEFAULT_ROUTE_ID) ?: DEFAULT_ROUTE_ID)
            ACTION_SELF_TEST -> runOutputSelfTest()
            ACTION_PLAY_DSD -> intent?.let(::startDsd)
            ACTION_PAUSE_DSD -> pauseDsd()
            ACTION_RESUME_DSD -> resumeDsd()
            ACTION_STOP_DSD -> stopDsd()
        }
        if (serviceStarted && bridge == null && activeDsd == null && !selfTestRunning.get()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            serviceStarted = false
        }
        if (!serviceStarted) stopSelfResult(startId)
        return if (serviceStarted) START_STICKY else START_NOT_STICKY
    }

    private fun startPipeline() {
        if (activeDsd?.thread?.isAlive == true) {
            PipelineStore.log("Stop local DSD playback before starting the music-app bridge", LogLevel.WARNING)
            return
        }
        val selected = PipelineStore.state.value.let { state ->
            state.availableRoutes.firstOrNull {
                it.id == state.selectedRouteId && it.officialBitPerfectFormats.isNotEmpty()
            }
        }
        if (selected == null) {
            PipelineStore.update {
                it.copy(phase = PipelinePhase.ERROR, lastError = "Select an output supported by Android's official bit-perfect path")
            }
            PipelineStore.log("Music bridge not started: no supported official bit-perfect output is selected", LogLevel.ERROR)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            serviceStarted = false
            return
        }
        ensureForeground("Waiting for music")
        if (bridge != null) return
        val sessions = AudioSessionMixer(
            this, ::updateNotification,
            onPlaybackActivity = { active -> mainHandler.post { updatePlaybackWakeLock(active && serviceStarted) } },
            beforeOutputStart = {
                val deadline = SystemClock.elapsedRealtime() + 4000
                while (selfTestRunning.get()) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Output self-test did not release the output" }
                    Thread.sleep(2)
                }
            },
        )
        try {
            mixer = sessions
            bridge = LocalSocketBridgeServer(
                eventsFactory = sessions::createSession,
                peerPolicy = BridgePeerPolicy(android.os.Process.myUid()),
            ).also { it.start() }
            PipelineStore.update { it.copy(enabled = true, phase = PipelinePhase.WAITING_FOR_GUEST, lastError = null) }
            PipelineStore.log("Audio bridge ready; one active track may use the selected official bit-perfect output")
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
        updatePlaybackWakeLock(false)
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
        if (activeDsd != null) {
            PipelineStore.log("Output changes are locked during local DSD playback", LogLevel.WARNING)
            return
        }
        val selected = routeId.takeUnless { it == DEFAULT_ROUTE_ID }?.let { requested ->
            PipelineStore.state.value.availableRoutes.firstOrNull {
                it.id == requested && it.officialBitPerfectFormats.isNotEmpty()
            }?.id
        }
        mixer?.selectRoute(selected)
        PipelineStore.update {
            it.copy(
                selectedRouteId = selected,
                lastError = if (routeId != DEFAULT_ROUTE_ID && selected == null) {
                    "That output does not expose Android's official bit-perfect capability"
                } else null,
            )
        }
    }

    private fun refreshRoutes() {
        val available = routes.routes()
        val old = PipelineStore.state.value.selectedRouteId
        val selected = old?.takeIf { id ->
            available.any { it.id == id && it.officialBitPerfectFormats.isNotEmpty() }
        }
        PipelineStore.update { it.copy(availableRoutes = available, selectedRouteId = selected) }
        if (selected != old) {
            if (serviceStarted && old != null) {
                if (activeDsd != null) {
                    stopDsd("Selected output disconnected; DSD playback was stopped without falling back to a speaker")
                } else {
                    stopPipeline(false)
                    PipelineStore.update { it.copy(lastError = "Selected output disconnected. Playback stopped to avoid switching to a speaker.") }
                }
            } else mixer?.selectRoute(selected)
        }
    }

    private fun runOutputSelfTest() {
        if (activeDsd?.thread?.isAlive == true) {
            PipelineStore.log("Output self-test skipped during local DSD playback", LogLevel.WARNING)
            return
        }
        if (PipelineStore.state.value.guestConnected) {
            PipelineStore.log("Output self-test skipped while a music track is connected", LogLevel.WARNING)
            return
        }
        if (!selfTestRunning.compareAndSet(false, true)) return
        if (bridge == null) startPipeline()
        if (!serviceStarted) { selfTestRunning.set(false); return }
        updatePlaybackWakeLock(true)
        thread(name = "VirtualDAP-self-test") {
            val format = PcmFormat(48_000, 2, PcmEncoding.PCM_16)
            val testSink = AndroidAudioSink(this)
            try {
                if (PipelineStore.state.value.guestConnected) return@thread
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
                mainHandler.post { updatePlaybackWakeLock(serviceStarted && PipelineStore.state.value.playingStreams > 0) }
            }
        }
    }

    private fun fail(message: String) {
        PipelineStore.update { it.copy(phase = PipelinePhase.ERROR, lastError = message) }
        PipelineStore.log(message, LogLevel.ERROR)
        updateNotification("Audio pipeline needs attention")
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun updatePlaybackWakeLock(active: Boolean) {
        if (active) {
            // USB bypasses AudioFlinger's wake lock. Hold ours only while a track is active;
            // pause, disconnect, explicit stop, destruction and process death release it.
            val lock = playbackWakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VirtualDAP:AudioPlayback")
                .apply { setReferenceCounted(false) }
                .also { playbackWakeLock = it }
            if (!lock.isHeld) lock.acquire()
        } else {
            playbackWakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    override fun onDestroy() {
        activeDsd?.let {
            it.cancelError = "Audio service stopped"
            it.task?.cancel()
            runCatching { it.source?.close() }
            it.thread.interrupt()
        }
        scope.cancel()
        unregisterReceiver(noisyReceiver)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopPipeline(false)
        routes.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("VirtualDAP").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        val dsd = PipelineStore.state.value.dsdPlayback
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AudioPipelineService::class.java).setAction(
                if (dsd.active) ACTION_STOP_DSD else ACTION_STOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (dsd.phase == DsdPlaybackPhase.PLAYING) {
            val pause = PendingIntent.getService(this, 2,
                Intent(this, AudioPipelineService::class.java).setAction(ACTION_PAUSE_DSD),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, "Pause", pause)
        } else if (dsd.phase == DsdPlaybackPhase.PAUSED) {
            val resume = PendingIntent.getService(this, 3,
                Intent(this, AudioPipelineService::class.java).setAction(ACTION_RESUME_DSD),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, "Resume", resume)
        }
        return builder.addAction(0, "Stop", stop).build()
    }

    private fun updateNotification(text: String) {
        if (serviceStarted) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun ensureForeground(text: String) {
        if (!serviceStarted) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Music space audio", NotificationManager.IMPORTANCE_LOW),
            )
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            serviceStarted = true
        } else updateNotification(text)
    }

    private fun startDsd(intent: Intent) {
        val uri = intent.data
        if (uri == null) {
            failDsd("No DSD file was selected")
            return
        }
        if (activeDsd?.thread?.isAlive == true) {
            failDsd("A DSD file is already playing; stop it before selecting another")
            return
        }
        if (selfTestRunning.get()) {
            failDsd("Wait for the output self-test to finish before playing DSD")
            return
        }
        val mode = runCatching {
            DsdOutputMode.valueOf(intent.getStringExtra(EXTRA_DSD_MODE) ?: DsdOutputMode.PCM_CONVERSION.name)
        }.getOrElse {
            failDsd("Invalid DSD output mode")
            return
        }
        val fileName = intent.getStringExtra(EXTRA_DSD_NAME)?.takeIf(String::isNotBlank)
            ?: displayName(uri)
            ?: "Selected DSD file"
        if (bridge != null || mixer != null) stopPipeline(false)
        ensureForeground("Preparing $fileName")
        updatePlaybackWakeLock(true)
        PipelineStore.update {
            it.copy(
                dsdPlayback = DsdPlaybackSnapshot(
                    phase = DsdPlaybackPhase.PREPARING,
                    fileName = fileName,
                    mode = mode,
                ),
                lastError = null,
            )
        }

        lateinit var active: ActiveDsd
        val worker = thread(start = false, name = "VirtualDAP-DSD") {
            var reader: com.virtualdap.host.audio.DsdStreamReader? = null
            var packetOutput: DsdPacketOutput? = null
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: error("Android could not open the selected file")
                active.source = stream
                val openedReader = DsdContainerReader.open(stream)
                reader = openedReader
                active.source = openedReader
                if (active.cancelError != null || Thread.currentThread().isInterrupted) {
                    throw CancellationException("DSD playback cancelled while opening the file")
                }
                val selectedRoute = PipelineStore.state.value.let { state ->
                    state.availableRoutes.firstOrNull {
                        it.id == state.selectedRouteId && it.officialBitPerfectFormats.isNotEmpty()
                    }
                } ?: error("Select an output supported by Android's official bit-perfect path")
                val openedOutput = when (mode) {
                    DsdOutputMode.PCM_CONVERSION -> DsdPcmPacketOutput(
                        this,
                        selectedRoute,
                        openedReader.format,
                    )
                    DsdOutputMode.DOP -> DsdDopPacketOutput(
                            this,
                            selectedRoute,
                            openedReader.format,
                            intent.getBooleanExtra(EXTRA_DOP_CONFIRMED, false),
                        )
                    DsdOutputMode.NATIVE_DSD -> error(
                        "Native DSD is unavailable in official-output mode; choose DoP or explicit DSD-to-PCM",
                    )
                }
                packetOutput = openedOutput
                val outputSnapshot = dsdOutputSnapshot(openedOutput, selectedRoute)
                PipelineStore.update { state ->
                    if (activeDsd !== active) state else state.copy(
                        dsdPlayback = state.dsdPlayback.copy(
                            format = openedReader.format,
                            sampleCountPerChannel = openedReader.sampleCountPerChannel,
                            durationMillis = openedReader.durationMillis,
                            outputFormat = outputSnapshot.outputFormat,
                            outputRoute = outputSnapshot.route,
                            transportRate = outputSnapshot.transportRate,
                            qualification = outputSnapshot.qualification,
                        ),
                    )
                }
                val task = DsdPlaybackTask(openedReader, openedOutput) { event ->
                    handleDsdEvent(active, openedOutput, event)
                }
                active.task = task
                if (active.cancelError != null) task.cancel()
                task.run()
                active.source = null
                reader = null
                packetOutput = null
            } catch (failure: Throwable) {
                runCatching { reader?.close() }
                runCatching { packetOutput?.close() }
                if (active.cancelError != null || failure is CancellationException) {
                    handleDsdEvent(active, packetOutput, DsdPlaybackEvent.Cancelled)
                } else {
                    handleDsdEvent(active, packetOutput, DsdPlaybackEvent.Failed(failure))
                }
            }
        }
        active = ActiveDsd(worker)
        activeDsd = active
        worker.start()
    }

    private data class DsdOutputSnapshot(
        val outputFormat: PcmFormat?,
        val route: com.virtualdap.host.model.OutputRoute?,
        val transportRate: Int?,
        val qualification: String?,
    )

    private fun dsdOutputSnapshot(output: DsdPacketOutput?, fallbackRoute: com.virtualdap.host.model.OutputRoute?) =
        when (output) {
            is DsdDopPacketOutput -> DsdOutputSnapshot(
                output.outputFormat,
                output.routedOutput() ?: fallbackRoute,
                output.outputFormat.sampleRate,
                "DoP 1.1 in an exact Android official bit-perfect PCM carrier",
            )
            is DsdPcmPacketOutput -> DsdOutputSnapshot(
                output.configuration.configured,
                output.routedOutput() ?: fallbackRoute,
                null,
                "DSD converted at a fixed 8:1 rate with a stateful 96-tap low-pass filter; no output resampling",
            )
            else -> DsdOutputSnapshot(null, fallbackRoute, null, null)
        }

    private fun handleDsdEvent(active: ActiveDsd, output: DsdPacketOutput?, event: DsdPlaybackEvent) {
        if (activeDsd !== active) return
        val outputState = dsdOutputSnapshot(output, PipelineStore.state.value.dsdPlayback.outputRoute)
        val preserved = output is DsdDopPacketOutput && output.sourcePreserved()
        when (event) {
            is DsdPlaybackEvent.Started -> {
                PipelineStore.update { it.copy(dsdPlayback = it.dsdPlayback.copy(phase = DsdPlaybackPhase.PLAYING)) }
                PipelineStore.log("DSD playback started: ${event.format.shortLabel()} via ${PipelineStore.state.value.dsdPlayback.mode.name}")
                updateNotification("Playing ${PipelineStore.state.value.dsdPlayback.fileName}")
            }
            is DsdPlaybackEvent.Progress -> {
                val now = SystemClock.elapsedRealtime()
                if (event.samplePosition == event.sampleCountPerChannel || now - active.lastProgressAt >= 100) {
                    active.lastProgressAt = now
                    PipelineStore.update {
                        it.copy(dsdPlayback = it.dsdPlayback.copy(
                            samplePosition = event.samplePosition,
                            outputRoute = outputState.route,
                            sourcePreserved = preserved,
                        ))
                    }
                }
            }
            DsdPlaybackEvent.Paused -> {
                PipelineStore.update { it.copy(dsdPlayback = it.dsdPlayback.copy(phase = DsdPlaybackPhase.PAUSED)) }
                mainHandler.post { updatePlaybackWakeLock(false) }
                updateNotification("DSD playback paused")
            }
            DsdPlaybackEvent.Resumed -> {
                PipelineStore.update { it.copy(dsdPlayback = it.dsdPlayback.copy(phase = DsdPlaybackPhase.PLAYING)) }
                mainHandler.post { updatePlaybackWakeLock(true) }
                updateNotification("Playing ${PipelineStore.state.value.dsdPlayback.fileName}")
            }
            DsdPlaybackEvent.Completed -> {
                PipelineStore.update { it.copy(dsdPlayback = it.dsdPlayback.copy(
                    phase = DsdPlaybackPhase.COMPLETED,
                    samplePosition = it.dsdPlayback.sampleCountPerChannel,
                    outputRoute = outputState.route,
                    sourcePreserved = preserved,
                )) }
                PipelineStore.log("DSD playback completed")
                finishDsd(active)
            }
            DsdPlaybackEvent.Cancelled -> {
                val reason = active.cancelError
                PipelineStore.update { it.copy(dsdPlayback = it.dsdPlayback.copy(
                    phase = if (reason == null) DsdPlaybackPhase.IDLE else DsdPlaybackPhase.ERROR,
                    lastError = reason,
                )) }
                PipelineStore.log(reason ?: "DSD playback stopped", if (reason == null) LogLevel.INFO else LogLevel.ERROR)
                finishDsd(active)
            }
            is DsdPlaybackEvent.Failed -> {
                val message = event.error.message ?: event.error.javaClass.simpleName
                PipelineStore.update { it.copy(dsdPlayback = it.dsdPlayback.copy(
                    phase = DsdPlaybackPhase.ERROR,
                    lastError = message,
                )) }
                PipelineStore.log("DSD playback failed: $message", LogLevel.ERROR)
                finishDsd(active)
            }
        }
    }

    private fun pauseDsd() {
        val active = activeDsd ?: return
        if (PipelineStore.state.value.dsdPlayback.phase == DsdPlaybackPhase.PLAYING) active.task?.pause()
    }

    private fun resumeDsd() {
        val active = activeDsd ?: return
        if (PipelineStore.state.value.dsdPlayback.phase == DsdPlaybackPhase.PAUSED) active.task?.resume()
    }

    private fun stopDsd(error: String? = null) {
        val active = activeDsd ?: return
        active.cancelError = error
        PipelineStore.update { it.copy(dsdPlayback = it.dsdPlayback.copy(phase = DsdPlaybackPhase.STOPPING)) }
        active.task?.cancel()
        runCatching { active.source?.close() }
        active.thread.interrupt()
    }

    private fun finishDsd(active: ActiveDsd) {
        if (activeDsd === active) activeDsd = null
        mainHandler.post {
            updatePlaybackWakeLock(false)
            if (activeDsd == null && bridge == null) {
                if (serviceStarted) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                serviceStarted = false
                stopSelf()
            }
        }
    }

    private fun failDsd(message: String) {
        PipelineStore.update { it.copy(dsdPlayback = it.dsdPlayback.copy(
            phase = DsdPlaybackPhase.ERROR,
            lastError = message,
        )) }
        PipelineStore.log(message, LogLevel.ERROR)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }.getOrNull()

    private class ActiveDsd(val thread: Thread) {
        @Volatile var task: DsdPlaybackTask? = null
        @Volatile var source: Closeable? = null
        @Volatile var cancelError: String? = null
        var lastProgressAt: Long = 0
    }

    companion object {
        const val ACTION_START = "com.virtualdap.host.action.START"
        const val ACTION_STOP = "com.virtualdap.host.action.STOP"
        const val ACTION_SELECT_ROUTE = "com.virtualdap.host.action.SELECT_ROUTE"
        const val ACTION_SELF_TEST = "com.virtualdap.host.action.SELF_TEST"
        const val ACTION_PLAY_DSD = "com.virtualdap.host.action.PLAY_DSD"
        const val ACTION_PAUSE_DSD = "com.virtualdap.host.action.PAUSE_DSD"
        const val ACTION_RESUME_DSD = "com.virtualdap.host.action.RESUME_DSD"
        const val ACTION_STOP_DSD = "com.virtualdap.host.action.STOP_DSD"
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_DSD_MODE = "dsd_mode"
        const val EXTRA_DOP_CONFIRMED = "dop_confirmed"
        const val EXTRA_DSD_NAME = "dsd_name"
        const val DEFAULT_ROUTE_ID = -1
        private const val CHANNEL_ID = "virtualdap_audio"
        private const val NOTIFICATION_ID = 42

        fun command(context: Context, action: String, routeId: Int? = null) {
            val intent = Intent(context, AudioPipelineService::class.java).setAction(action)
            routeId?.let { intent.putExtra(EXTRA_ROUTE_ID, it) }
            if (action == ACTION_START || action == ACTION_SELF_TEST || action == ACTION_PLAY_DSD) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun playDsd(
            context: Context,
            uri: Uri,
            mode: DsdOutputMode,
            dopCapabilityConfirmed: Boolean,
            displayName: String? = null,
        ) {
            val intent = Intent(context, AudioPipelineService::class.java)
                .setAction(ACTION_PLAY_DSD)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_DSD_MODE, mode.name)
                .putExtra(EXTRA_DOP_CONFIRMED, dopCapabilityConfirmed)
            displayName?.let { intent.putExtra(EXTRA_DSD_NAME, it) }
            context.startForegroundService(intent)
        }
    }
}
