package com.virtualdap.platformruntime

import android.app.Service
import android.content.Intent
import android.crosvm.ICrosvmAndroidDisplayService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.StatFs
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineException
import android.system.virtualmachine.VirtualMachineManager
import android.system.virtualizationservice_internal.IVirtualizationServiceInternal
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.virtualdap.runtime.IGuestRuntimeCallback
import com.virtualdap.runtime.IGuestRuntimeService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Platform-signed Android Virtualization Framework provider for a graphical Android 13 guest. */
class GuestRuntimeService : Service() {
    private val lock = Any()
    private lateinit var worker: ExecutorService
    private var virtualMachine: VirtualMachine? = null
    private var callback: IGuestRuntimeCallback? = null
    private var runtimeState = State.STOPPED
    private var runtimeDetail = "Ready"
    private var bridgeProxyRunning = false

    private val binder = object : IGuestRuntimeService.Stub() {
        override fun getProtocolVersion(): Int = PROTOCOL_VERSION
        override fun getBackendName(): String = BACKEND_NAME
        override fun getState(): Int = synchronized(lock) { runtimeState }
        override fun getStateDetail(): String = synchronized(lock) { runtimeDetail }

        override fun start(
            guestImage: ParcelFileDescriptor,
            manifest: String,
            bridgeToken: ByteArray,
            callback: IGuestRuntimeCallback,
        ) {
            val ownedImage = ParcelFileDescriptor.dup(guestImage.fileDescriptor)
            worker.execute { startInternal(ownedImage, manifest, bridgeToken.copyOf(), callback) }
        }

        override fun stop() {
            worker.execute { stopInternal() }
        }

        override fun attachDisplay(surface: Surface, width: Int, height: Int, densityDpi: Int) {
            require(width > 0 && height > 0 && densityDpi > 0 && surface.isValid)
            displayService().setSurface(surface, false)
        }

        override fun detachDisplay() {
            runCatching { displayService().removeSurface(false) }
        }

        override fun injectMotionEvent(event: MotionEvent) {
            synchronized(lock) { virtualMachine }?.sendMultiTouchEvent(event)
        }

        override fun injectKeyEvent(event: KeyEvent) {
            synchronized(lock) { virtualMachine }?.sendKeyEvent(event)
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "VirtualDAP-platform-runtime")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopInternal()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startInternal(
        imageDescriptor: ParcelFileDescriptor,
        manifestText: String,
        bridgeToken: ByteArray,
        requestedCallback: IGuestRuntimeCallback,
    ) {
        synchronized(lock) {
            if (runtimeState != State.STOPPED && runtimeState != State.ERROR) {
                imageDescriptor.close()
                notify(requestedCallback, runtimeState, "Guest is already active")
                return
            }
            callback = requestedCallback
        }
        try {
            require(bridgeToken.size == BRIDGE_TOKEN_BYTES) { "Bridge token must contain 32 bytes" }
            val manifest = RuntimeManifest.parse(manifestText)
            require(manifest.architecture in Build.SUPPORTED_64_BIT_ABIS) {
                "Guest architecture ${manifest.architecture} does not match this host"
            }
            require(File(BOOTLOADER_PATH).isFile) { "Verified crosvm bootloader is missing" }
            update(State.STARTING, "Preparing writable Android 13 disk")
            val workingImage = prepareWorkingImage(imageDescriptor, manifest)
            val tokenHex = bridgeToken.joinToString("") { "%02x".format(it) }
            if (!nativeStartBridgeProxy(VSOCK_PORT, HOST_SOCKET, bridgeToken)) {
                throw IllegalStateException("Could not start the authenticated vsock proxy")
            }
            bridgeProxyRunning = true
            update(State.STARTING, "Starting Android Virtualization Framework")
            val config = createVmConfig(workingImage, tokenHex)
            val manager = applicationContext.getSystemService(VirtualMachineManager::class.java)
                ?: throw IllegalStateException("Android Virtualization Framework is unavailable")
            var vm = manager.getOrCreate(VM_NAME, config)
            try {
                vm.config = config
            } catch (_: VirtualMachineException) {
                manager.delete(VM_NAME)
                vm = manager.create(VM_NAME, config)
            }
            vm.setCallback(worker, VmCallback())
            synchronized(lock) { virtualMachine = vm }
            vm.run()
            update(State.RUNNING, "Android 13 guest running")
        } catch (error: Exception) {
            runCatching { imageDescriptor.close() }
            File(filesDir, "guest-working.partial").delete()
            runCatching { synchronized(lock) { virtualMachine }?.stop() }
            stopProxy()
            synchronized(lock) { virtualMachine = null }
            update(State.ERROR, error.message ?: "Guest start failed")
        }
    }

    private fun stopInternal() {
        val vm = synchronized(lock) {
            if (runtimeState == State.STOPPED) return
            runtimeState = State.STOPPING
            runtimeDetail = "Stopping guest"
            virtualMachine
        }
        notifyCurrent()
        runCatching { vm?.stop() }
        synchronized(lock) { virtualMachine = null }
        stopProxy()
        update(State.STOPPED, "Guest stopped")
    }

    private fun prepareWorkingImage(
        descriptor: ParcelFileDescriptor,
        manifest: RuntimeManifest,
    ): File {
        val working = File(filesDir, "guest-working.img")
        val marker = File(filesDir, "guest-working.sha256")
        if (working.isFile && working.length() == manifest.imageBytes &&
            marker.readTextOrNull() == manifest.imageSha256 && hashFile(working) == manifest.imageSha256
        ) {
            descriptor.close()
            return working
        }

        val partial = File(filesDir, "guest-working.partial")
        partial.delete()
        working.delete()
        marker.delete()
        val availableBytes = StatFs(filesDir.absolutePath).availableBytes
        require(availableBytes >= manifest.imageBytes + MINIMUM_FREE_BYTES) {
            "Not enough storage for the writable guest disk"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            FileOutputStream(partial).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= manifest.imageBytes) { "Guest disk is larger than declared" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        require(copied == manifest.imageBytes) { "Guest disk byte count changed" }
        val actualHash = digest.digest().toHex()
        require(MessageDigest.isEqual(actualHash.toByteArray(), manifest.imageSha256.toByteArray())) {
            "Guest disk SHA-256 changed across the Binder boundary"
        }
        check(partial.renameTo(working)) { "Could not activate writable guest disk" }
        marker.writeText(manifest.imageSha256)
        return working
    }

    private fun createVmConfig(workingImage: File, bridgeTokenHex: String): VirtualMachineConfig {
        val custom = VirtualMachineCustomImageConfig.Builder()
            .setName(VM_NAME)
            .setBootloaderPath(BOOTLOADER_PATH)
            .useNetwork(true)
            .useTouch(true)
            .useKeyboard(true)
            .useMouse(false)
            .useTrackpad(false)
            .setAudioConfig(
                VirtualMachineCustomImageConfig.AudioConfig.Builder()
                    .setUseSpeaker(false)
                    .setUseMicrophone(false)
                    .build(),
            )
            .setDisplayConfig(
                VirtualMachineCustomImageConfig.DisplayConfig.Builder()
                    .setWidth(1080)
                    .setHeight(1920)
                    .setHorizontalDpi(420)
                    .setVerticalDpi(420)
                    .setRefreshRate(60)
                    .build(),
            )
            .setGpuConfig(
                VirtualMachineCustomImageConfig.GpuConfig.Builder()
                    .setBackend("gfxstream")
                    .setRendererUseEgl(false)
                    .setRendererUseGles(false)
                    .setRendererUseGlx(false)
                    .setRendererUseSurfaceless(true)
                    .setRendererUseVulkan(true)
                    .setContextTypes(arrayOf("gfxstream-vulkan", "gfxstream-composer"))
                    .build(),
            )
            .addParam("androidboot.virtualdap.bridge_token=$bridgeTokenHex")
            .addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(workingImage.absolutePath))
            .build()
        return VirtualMachineConfig.Builder(this)
            .setProtectedVm(false)
            .setMemoryBytes(3L * 1024 * 1024 * 1024)
            .setCpuTopology(VirtualMachineConfig.CPU_TOPOLOGY_MATCH_HOST)
            .setDebugLevel(VirtualMachineConfig.DEBUG_LEVEL_NONE)
            .setCustomImageConfig(custom)
            .build()
    }

    private fun displayService(): ICrosvmAndroidDisplayService {
        val service = IVirtualizationServiceInternal.Stub.asInterface(
            ServiceManager.waitForService("android.system.virtualizationservice"),
        ) ?: throw IllegalStateException("Virtualization service is unavailable")
        return ICrosvmAndroidDisplayService.Stub.asInterface(service.waitDisplayService())
            ?: throw IllegalStateException("VM display service is unavailable")
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun update(state: Int, detail: String) {
        synchronized(lock) {
            runtimeState = state
            runtimeDetail = detail
        }
        notifyCurrent()
    }

    private fun notifyCurrent() {
        val values = synchronized(lock) { Triple(callback, runtimeState, runtimeDetail) }
        values.first?.let { notify(it, values.second, values.third) }
    }

    private fun notify(callback: IGuestRuntimeCallback, state: Int, detail: String) {
        runCatching { callback.onStateChanged(state, detail) }
    }

    private fun stopProxy() {
        if (bridgeProxyRunning) nativeStopBridgeProxy()
        bridgeProxyRunning = false
    }

    private inner class VmCallback : VirtualMachineCallback {
        override fun onPayloadStarted(vm: VirtualMachine) = Unit
        override fun onPayloadReady(vm: VirtualMachine) = Unit
        override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) = Unit
        override fun onError(vm: VirtualMachine, errorCode: Int, message: String) {
            stopProxy()
            synchronized(lock) { virtualMachine = null }
            update(State.ERROR, "VM error $errorCode: $message")
        }

        override fun onStopped(vm: VirtualMachine, reason: Int) {
            stopProxy()
            synchronized(lock) { virtualMachine = null }
            update(State.STOPPED, "Guest stopped (reason $reason)")
        }
    }

    private external fun nativeStartBridgeProxy(port: Int, socketName: String, token: ByteArray): Boolean
    private external fun nativeStopBridgeProxy()

    private data class RuntimeManifest(
        val architecture: String,
        val imageBytes: Long,
        val imageSha256: String,
    ) {
        companion object {
            fun parse(text: String): RuntimeManifest {
                require(text.toByteArray().size <= 64 * 1024) { "Manifest is too large" }
                val values = text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }
                    .associate { line ->
                        val separator = line.indexOf('=')
                        require(separator > 0) { "Malformed manifest" }
                        line.substring(0, separator) to line.substring(separator + 1)
                    }
                require(values["formatVersion"] == "1") { "Unsupported guest bundle" }
                require(values["androidApi"] == "33") { "Android 13/API 33 is required" }
                require(values["backend"] == "virtualdap-platform-v1") { "Wrong runtime backend" }
                val architecture = values.getValue("architecture")
                require(architecture == "arm64-v8a" || architecture == "x86_64") {
                    "Unsupported guest architecture"
                }
                val bytes = values.getValue("imageBytes").toLong()
                require(bytes in 1..MAX_IMAGE_BYTES) { "Invalid guest disk size" }
                val hash = values.getValue("imageSha256").lowercase()
                require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid guest disk hash" }
                return RuntimeManifest(architecture, bytes, hash)
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    private object State {
        const val STOPPED = 0
        const val STARTING = 1
        const val RUNNING = 2
        const val STOPPING = 3
        const val ERROR = 4
    }

    companion object {
        private const val PROTOCOL_VERSION = 3
        private const val BACKEND_NAME = "Android AVF / crosvm"
        private const val VM_NAME = "VirtualDAPAndroid13"
        private const val BOOTLOADER_PATH = "/system/etc/virtualdap/u-boot.bin"
        private const val HOST_SOCKET = "virtualdap_audio_v1"
        private const val VSOCK_PORT = 45000
        private const val BRIDGE_TOKEN_BYTES = 32
        private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024 * 1024
        private const val MINIMUM_FREE_BYTES = 256L * 1024 * 1024

        init {
            System.loadLibrary("virtualdap_runtime")
        }
    }
}
