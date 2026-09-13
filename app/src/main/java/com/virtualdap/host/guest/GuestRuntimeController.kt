package com.virtualdap.host.guest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.virtualdap.host.model.LogLevel
import com.virtualdap.host.service.PipelineStore
import com.virtualdap.runtime.IGuestRuntimeCallback
import com.virtualdap.runtime.IGuestRuntimeService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GuestRuntimePhase {
    NOT_INSTALLED,
    IMPORTING,
    READY,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class GuestRuntimeSnapshot(
    val phase: GuestRuntimePhase = GuestRuntimePhase.NOT_INSTALLED,
    val manifest: GuestBundleManifest? = null,
    val providerAvailable: Boolean = false,
    val providerName: String? = null,
    val detail: String? = null,
    val lastError: String? = null,
)

/** Coordinates durable guest installation with a separately privileged, platform-owned backend. */
object GuestRuntimeController {
    const val ACTION_RUNTIME = "com.virtualdap.runtime.GUEST_RUNTIME"
    const val BIND_PERMISSION = "com.virtualdap.host.permission.BIND_GUEST_RUNTIME"
    const val PROTOCOL_VERSION = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(GuestRuntimeSnapshot())
    val state: StateFlow<GuestRuntimeSnapshot> = mutableState.asStateFlow()

    @Volatile private var initialized = false
    private lateinit var appContext: Context
    private lateinit var installationRoot: File
    private var runtime: IGuestRuntimeService? = null
    private var connection: ServiceConnection? = null
    private val installer = GuestBundleInstaller()

    private val callback = object : IGuestRuntimeCallback.Stub() {
        override fun onStateChanged(state: Int, detail: String?) {
            val phase = when (state) {
                RuntimeState.STARTING -> GuestRuntimePhase.STARTING
                RuntimeState.RUNNING -> GuestRuntimePhase.RUNNING
                RuntimeState.STOPPING -> GuestRuntimePhase.STOPPING
                RuntimeState.STOPPED -> GuestRuntimePhase.READY
                else -> GuestRuntimePhase.ERROR
            }
            mutableState.update {
                it.copy(
                    phase = phase,
                    detail = detail,
                    lastError = detail.takeIf { phase == GuestRuntimePhase.ERROR },
                )
            }
            PipelineStore.log("Guest runtime: ${detail ?: phase.name.lowercase()}", if (phase == GuestRuntimePhase.ERROR) LogLevel.ERROR else LogLevel.INFO)
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            installationRoot = File(appContext.filesDir, "guest")
            val manifest = runCatching { installer.readInstalled(installationRoot) }.getOrElse { error ->
                mutableState.update { it.copy(lastError = "Installed guest is invalid: ${error.message}") }
                null
            }
            mutableState.update {
                it.copy(
                    manifest = manifest,
                    phase = if (manifest == null) GuestRuntimePhase.NOT_INSTALLED else GuestRuntimePhase.READY,
                )
            }
            initialized = true
            connectProvider()
        }
    }

    fun importBundle(uri: Uri) {
        check(initialized) { "Guest runtime controller is not initialized" }
        if (mutableState.value.phase in setOf(GuestRuntimePhase.STARTING, GuestRuntimePhase.RUNNING, GuestRuntimePhase.STOPPING)) {
            mutableState.update { it.copy(lastError = "Stop the guest before replacing its image") }
            return
        }
        mutableState.update { it.copy(phase = GuestRuntimePhase.IMPORTING, detail = "Verifying bundle", lastError = null) }
        scope.launch {
            try {
                val manifest = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    installer.install(input, installationRoot) { candidate ->
                        if (!hostSupports(candidate.architecture)) {
                            throw GuestBundleException(
                                "Guest architecture ${candidate.architecture} does not match this host " +
                                    "(${Build.SUPPORTED_64_BIT_ABIS.joinToString()})",
                            )
                        }
                    }
                } ?: throw GuestBundleException("Could not open the selected bundle")
                mutableState.update {
                    it.copy(
                        phase = GuestRuntimePhase.READY,
                        manifest = manifest,
                        detail = "Image size and SHA-256 verified",
                        lastError = null,
                    )
                }
                PipelineStore.log("Installed ${manifest.displayName}; image hash verified")
            } catch (error: Exception) {
                val installed = runCatching { installer.readInstalled(installationRoot) }.getOrNull()
                mutableState.update {
                    it.copy(
                        phase = if (installed == null) GuestRuntimePhase.NOT_INSTALLED else GuestRuntimePhase.READY,
                        manifest = installed,
                        detail = null,
                        lastError = "Guest import failed: ${error.message}",
                    )
                }
                PipelineStore.log("Guest import failed: ${error.message}", LogLevel.ERROR)
            }
        }
    }

    fun start() {
        check(initialized) { "Guest runtime controller is not initialized" }
        val service = runtime
        val manifest = mutableState.value.manifest
        if (manifest == null) {
            mutableState.update { it.copy(lastError = "Import an Android 13 guest bundle first") }
            return
        }
        if (service == null) {
            mutableState.update { it.copy(lastError = "No trusted platform guest runtime is installed") }
            return
        }
        mutableState.update { it.copy(phase = GuestRuntimePhase.STARTING, detail = "Rechecking image integrity", lastError = null) }
        scope.launch {
            try {
                installer.verifyInstalled(installationRoot, manifest)
                mutableState.update { it.copy(detail = "Starting platform runtime") }
                val image = installer.installedImage(installationRoot)
                val descriptor = ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    service.start(descriptor, manifest.asProperties(), callback)
                } finally {
                    descriptor.close()
                }
            } catch (error: Exception) {
                runtimeFailed("Could not start guest: ${error.message}")
            }
        }
    }

    fun stop() {
        val service = runtime ?: return
        mutableState.update { it.copy(phase = GuestRuntimePhase.STOPPING, detail = "Stopping guest", lastError = null) }
        scope.launch {
            try {
                service.stop()
            } catch (error: Exception) {
                runtimeFailed("Could not stop guest: ${error.message}")
            }
        }
    }

    private fun connectProvider() {
        val intent = Intent(ACTION_RUNTIME)
        val candidates = appContext.packageManager.queryIntentServices(intent, 0)
            .filter { result ->
                result.serviceInfo.permission == BIND_PERMISSION &&
                    result.serviceInfo.applicationInfo.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
                    appContext.packageManager.checkSignatures("android", result.serviceInfo.packageName) ==
                    PackageManager.SIGNATURE_MATCH
            }
        if (candidates.size != 1) {
            mutableState.update {
                it.copy(
                    providerAvailable = false,
                    providerName = null,
                    detail = if (candidates.isEmpty()) "Platform runtime service not found" else "Multiple runtime services found; refusing ambiguous provider",
                )
            }
            return
        }
        val serviceInfo = candidates.single().serviceInfo
        intent.component = ComponentName(serviceInfo.packageName, serviceInfo.name)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val candidate = IGuestRuntimeService.Stub.asInterface(binder)
                try {
                    if (candidate.protocolVersion != PROTOCOL_VERSION) {
                        appContext.unbindService(this)
                        runtimeFailed("Runtime protocol ${candidate.protocolVersion} is unsupported")
                        return
                    }
                    runtime = candidate
                    val phase = when (candidate.state) {
                        RuntimeState.STARTING -> GuestRuntimePhase.STARTING
                        RuntimeState.RUNNING -> GuestRuntimePhase.RUNNING
                        RuntimeState.STOPPING -> GuestRuntimePhase.STOPPING
                        RuntimeState.ERROR -> GuestRuntimePhase.ERROR
                        else -> if (mutableState.value.manifest == null) {
                            GuestRuntimePhase.NOT_INSTALLED
                        } else {
                            GuestRuntimePhase.READY
                        }
                    }
                    mutableState.update {
                        it.copy(
                            phase = phase,
                            providerAvailable = true,
                            providerName = candidate.backendName,
                            detail = candidate.stateDetail,
                            lastError = null,
                        )
                    }
                } catch (error: Exception) {
                    runtimeFailed("Runtime handshake failed: ${error.message}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                runtime = null
                mutableState.update {
                    it.copy(providerAvailable = false, providerName = null, detail = "Platform runtime disconnected")
                }
            }

            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        connection = serviceConnection
        if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
            connection = null
            mutableState.update { it.copy(detail = "Platform runtime rejected the connection") }
        }
    }

    private fun runtimeFailed(message: String) {
        mutableState.update {
            it.copy(
                phase = if (it.manifest == null) GuestRuntimePhase.NOT_INSTALLED else GuestRuntimePhase.ERROR,
                lastError = message,
                detail = null,
            )
        }
        PipelineStore.log(message, LogLevel.ERROR)
    }

    private fun hostSupports(architecture: String): Boolean = when (architecture) {
        "arm64-v8a" -> "arm64-v8a" in Build.SUPPORTED_64_BIT_ABIS
        "x86_64" -> "x86_64" in Build.SUPPORTED_64_BIT_ABIS
        else -> false
    }

    object RuntimeState {
        const val STOPPED = 0
        const val STARTING = 1
        const val RUNNING = 2
        const val STOPPING = 3
        const val ERROR = 4
    }
}
