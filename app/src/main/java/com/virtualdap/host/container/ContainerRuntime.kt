package com.virtualdap.host.container

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.virtualdap.host.model.LogLevel
import com.virtualdap.host.service.PipelineStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.virtualdap.host.model.MusicAppCatalog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

enum class ContainerPhase { INITIALIZING, READY, INSTALLING, ERROR }

data class ContainerApp(
    val packageName: String,
    val name: String,
    val minimumApi: Int,
    val lastStartedPid: Int? = null,
)

data class ContainerSnapshot(
    val phase: ContainerPhase = ContainerPhase.INITIALIZING,
    val applications: List<ContainerApp> = emptyList(),
    val detail: String = "Preparing the music space",
    val lastError: String? = null,
    val hostApplications: List<ContainerApp> = emptyList(),
)

/** Ordinary-UID application container sharing the host Android framework. */
object ContainerRuntime {
    private const val USER = 0
    private const val MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutableState = MutableStateFlow(ContainerSnapshot())
    val state = mutableState.asStateFlow()
    private var hostContext: Context? = null
    private var attached = false
    private val pendingLaunches = ConcurrentHashMap<String, String>()

    fun attach(context: Context) {
        hostContext = context
        val hostPackage = context.packageName
        try {
            val core = BlackBoxCore.get()
            core.doAttachBaseContext(context, object : ClientConfiguration() {
                override fun getHostPackageName(): String = hostPackage
                override fun isEnableDaemonService(): Boolean = false
                override fun isUseVpnNetwork(): Boolean = false
                override fun isDisableFlagSecure(): Boolean = false
                override fun isHideRoot(): Boolean = false
                override fun getLogSenderChatId(): String = ""
            })
            core.addAppLifecycleCallback(object : AppLifecycleCallback() {
                override fun beforeCreateApplication(
                    packageName: String, processName: String, context: Context, userId: Int,
                ) {
                    check(top.niunaijun.blackbox.core.AudioCapture.install()) {
                        "Audio interception is unavailable on this Android runtime"
                    }
                }

                override fun afterApplicationOnCreate(
                    packageName: String,
                    processName: String,
                    application: Application,
                    userId: Int,
                ) {
                    val extras = Bundle().apply {
                        putInt("pid", Process.myPid())
                        putString("process", processName)
                    }
                    runCatching {
                        context.contentResolver.call(
                            Uri.parse("content://$hostPackage.container.events"),
                            "started", packageName, extras,
                        )
                    }.onFailure { error ->
                        android.util.Log.w("VirtualDAP-Container", "Could not report app startup", error)
                    }
                }
            })
            attached = true
        } catch (error: Throwable) {
            fail("Container initialization failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun onCreate(): Boolean {
        if (!attached) return true
        val core = BlackBoxCore.get()
        if (!core.isMainProcess) {
            core.doCreate()
            return false
        }
        scope.launch {
            try {
                core.doCreate()
                check(core.createUser(USER) != null) { "Container user service did not initialize" }
                loadApplications()
            } catch (error: Throwable) {
                fail("Music space could not start: ${error.message ?: error.javaClass.simpleName}")
            }
        }
        return true
    }

    fun refresh() {
        if (!attached) return
        scope.launch {
            try { loadApplications() } catch (error: Exception) { fail("Could not read music apps: ${error.message}") }
        }
    }

    fun install(uri: Uri) = importPackage { context, staging ->
        val source = File(staging, "package.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(source).use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= MAX_IMPORT_BYTES) { "App package exceeds 2 GiB" }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        } ?: error("Could not open the selected file")
        source
    }

    /** Copy APK code only; host app accounts, private data and permissions are never copied. */
    fun importHostApp(packageName: String) = importPackage { context, staging ->
        require(MusicAppCatalog.popularApps.any { it.packageName == packageName }) { "Not a catalog music app" }
        val info = context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        val files = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        val bundle = File(staging, "installed-app.apks")
        var total = 0L
        FileOutputStream(bundle).use { stream ->
            ZipOutputStream(stream).use { zip ->
                files.forEachIndexed { index, path ->
                    zip.putNextEntry(ZipEntry(if (index == 0) "base.apk" else "split-$index.apk"))
                    File(path).inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_IMPORT_BYTES) { "Installed APK set exceeds 2 GiB" }
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
                zip.finish()
                stream.fd.sync()
            }
        }
        bundle
    }

    private fun importPackage(prepare: (Context, File) -> File) {
        val context = hostContext ?: return
        val previous = mutableState.value
        if (previous.phase != ContainerPhase.READY ||
            !mutableState.compareAndSet(previous, previous.copy(
                phase = ContainerPhase.INSTALLING, detail = "Preparing and checking the app package", lastError = null,
            ))
        ) return
        scope.launch {
            val staging = File(context.cacheDir, "container-import-${UUID.randomUUID()}")
            try {
                check(staging.mkdir()) { "Could not create the app import directory" }
                var source = prepare(context, staging)
                val normalized = File(staging, "package.apks")
                if (ApkArchiveNormalizer.normalizeIfArchive(
                        source,
                        normalized,
                        bundletoolProfile(context),
                        MAX_IMPORT_BYTES,
                    )
                ) {
                    source = normalized
                } else {
                    val info = context.packageManager.getPackageArchiveInfo(
                        source.path, PackageManager.PackageInfoFlags.of(0),
                    ) ?: error("The selected file is not a readable APK")
                    require((info.applicationInfo?.minSdkVersion ?: 0) <= Build.VERSION.SDK_INT) {
                        "This app requires a newer Android version than the host"
                    }
                }
                source.setReadOnly()
                mutableState.update { it.copy(detail = "Installing into the music space") }
                val result = BlackBoxCore.get().installPackageAsUser(source, USER)
                check(result.success && result.packageName != null &&
                    BlackBoxCore.get().isInstalled(result.packageName, USER)
                ) { result.msg ?: "Container rejected the app package" }
                PipelineStore.log("Installed music-space app: ${result.packageName}")
                loadApplications()
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(phase = ContainerPhase.READY, detail = "App import failed", lastError = error.message)
                }
                PipelineStore.log("Container import failed: ${error.message}", LogLevel.ERROR)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    fun launch(packageName: String) {
        if (mutableState.value.phase != ContainerPhase.READY) return
        val request = UUID.randomUUID().toString()
        val previousPid = mutableState.value.applications.firstOrNull { it.packageName == packageName }?.lastStartedPid
        pendingLaunches[packageName] = request
        mutableState.update { it.copy(
            applications = it.applications.map { app ->
                if (app.packageName == packageName) app.copy(lastStartedPid = null) else app
            },
            detail = "Starting $packageName", lastError = null,
        ) }
        scope.launch {
            try {
                if (pendingLaunches[packageName] != request) return@launch
                check(mutableState.value.applications.any { it.packageName == packageName }) { "App is not installed in the music space" }
                MusicOutputReadiness.await(PipelineStore.state)
                if (pendingLaunches[packageName] != request) return@launch
                check(BlackBoxCore.get().launchApk(packageName, USER)) { "No launchable activity was found" }
                if (previousPid != null && hostContext?.getSystemService(android.app.ActivityManager::class.java)
                        ?.runningAppProcesses.orEmpty().any { it.pid == previousPid }
                ) appStarted(packageName, previousPid)
                withTimeout(30_000) {
                    mutableState.first { snapshot ->
                        pendingLaunches[packageName] != request ||
                            snapshot.applications.any { it.packageName == packageName && it.lastStartedPid != null }
                    }
                }
                pendingLaunches.remove(packageName, request)
            } catch (error: Throwable) {
                if (pendingLaunches.remove(packageName, request)) {
                    val message = if (error is MusicOutputUnavailable) {
                        error.message ?: "Music output is unavailable"
                    } else if (error is kotlinx.coroutines.TimeoutCancellationException) {
                        "$packageName did not finish startup. Check app/Android/CPU compatibility and Diagnostics."
                    } else "Could not launch $packageName: ${error.message}"
                    mutableState.update { it.copy(
                        lastError = message,
                        detail = if (error is MusicOutputUnavailable) "Audio output not ready" else "App startup failed",
                    ) }
                    PipelineStore.log(message, LogLevel.ERROR)
                }
            }
        }
    }

    fun stop(packageName: String) {
        pendingLaunches.remove(packageName)
        scope.launch {
            try {
                BlackBoxCore.get().stopPackage(packageName, USER)
                mutableState.update {
                    it.copy(
                        detail = "Stopped $packageName",
                        applications = it.applications.map { app ->
                            if (app.packageName == packageName) app.copy(lastStartedPid = null) else app
                        },
                    )
                }
            } catch (error: Throwable) {
                mutableState.update { it.copy(lastError = "Could not stop $packageName: ${error.message}") }
            }
        }
    }

    fun appStarted(packageName: String, pid: Int) {
        mutableState.update {
            it.copy(
                detail = "$packageName started",
                applications = it.applications.map { app ->
                    if (app.packageName == packageName) app.copy(lastStartedPid = pid) else app
                },
                lastError = null,
            )
        }
        PipelineStore.log("Container app created: $packageName (pid $pid)")
    }

    private fun loadApplications() {
        val context = hostContext ?: return
        val previous = mutableState.value.applications.associateBy { it.packageName }
        val runningPids = context.getSystemService(android.app.ActivityManager::class.java)
            .runningAppProcesses.orEmpty().map { it.pid }.toSet()
        val apps = BlackBoxCore.get().getInstalledApplications(0, USER).map { info ->
            ContainerApp(
                packageName = info.packageName,
                name = runCatching { info.loadLabel(context.packageManager).toString() }.getOrDefault(info.packageName),
                minimumApi = info.minSdkVersion,
                lastStartedPid = previous[info.packageName]?.lastStartedPid?.takeIf { it in runningPids },
            )
        }.sortedBy { it.name.lowercase() }
        mutableState.value = ContainerSnapshot(
            ContainerPhase.READY, apps, "Android ${Build.VERSION.RELEASE} app container · no root required",
            hostApplications = MusicAppCatalog.popularApps.mapNotNull { candidate ->
                try {
                    val installed = context.packageManager.getApplicationInfo(
                        candidate.packageName, PackageManager.ApplicationInfoFlags.of(0),
                    )
                    if (!installed.enabled) null else ContainerApp(
                        candidate.packageName, candidate.name, installed.minSdkVersion,
                    )
                } catch (_: PackageManager.NameNotFoundException) { null }
            },
        )
    }

    private fun bundletoolProfile(context: Context): BundletoolDeviceProfile {
        val processAbis = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS
        } else {
            Build.SUPPORTED_32_BIT_ABIS
        }.toList()
        val glVersion = context.getSystemService(android.app.ActivityManager::class.java)
            ?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val textureFormats = buildList {
            // Android Extension Pack includes ASTC LDR; GLES 3.x includes ETC2/EAC.
            if (context.packageManager.hasSystemFeature("android.hardware.opengles.aep")) add(9)
            if (glVersion >= 0x0003_0000) add(10)
            add(1) // ETC1 fallback used by bundletool for older/basic devices.
        }.distinct()
        return BundletoolDeviceProfile(
            sdkVersion = Build.VERSION.SDK_INT,
            supportedAbis = processAbis,
            screenDensityDpi = context.resources.displayMetrics.densityDpi,
            textureCompressionFormats = textureFormats,
        )
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(phase = ContainerPhase.ERROR, detail = message, lastError = message) }
        PipelineStore.log(message, LogLevel.ERROR)
    }
}
