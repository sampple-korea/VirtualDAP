package com.virtualdap.host.audio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.virtualdap.host.model.LogLevel
import com.virtualdap.host.model.OutputRoute
import com.virtualdap.host.service.PipelineStore
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UsbAudioDevice(
    val id: Int, val name: String, val vendorId: Int, val productId: Int, val permission: Boolean,
    val profiles: List<UsbAudioStreamingProfile> = emptyList(),
)
data class UsbHostSnapshot(
    val devices: List<UsbAudioDevice> = emptyList(), val busy: Boolean = false, val error: String? = null,
)

/** Host process only. Permission is rechecked with UsbManager, never trusted from broadcast extras. */
object UsbHostController {
    private var context: Context? = null
    private var manager: UsbManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val stateRef = MutableStateFlow(UsbHostSnapshot())
    val state = stateRef.asStateFlow()
    private val directLease = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
            if (intent.action == permissionAction(context)) {
                manager?.deviceList?.values?.filter { manager?.hasPermission(it) == true }
                    ?.forEach { inspect(it.deviceId) }
            }
        }
    }

    @Synchronized fun initialize(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        manager = context.applicationContext.getSystemService(UsbManager::class.java)
        val filter = IntentFilter(permissionAction(context)).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        val usb = manager ?: return
        scope.launch {
            try {
                val previous = stateRef.value.devices.associateBy { it.id }
                val devices = usb.deviceList.values.filter(::hasAudioOutput).map { device ->
                    val permitted = usb.hasPermission(device)
                    UsbAudioDevice(
                        device.deviceId, device.productName ?: "USB audio ${device.vendorId}:${device.productId}",
                        device.vendorId, device.productId, permitted,
                        previous[device.deviceId]?.profiles.orEmpty().takeIf { permitted }.orEmpty(),
                    )
                }.sortedBy { it.name }
                stateRef.update { it.copy(devices = devices, error = null) }
            } catch (error: Exception) {
                stateRef.update { it.copy(error = "Could not list USB audio devices: ${error.message}") }
            }
        }
    }

    fun routes(): List<OutputRoute> = stateRef.value.devices.filter { it.permission }.map { device ->
        OutputRoute(
            id = -1_000_000 - device.id, name = "Direct USB · ${device.name}",
            type = android.media.AudioDeviceInfo.TYPE_USB_DEVICE, isUsb = true,
            sampleRates = device.profiles.flatMap { it.rates }.filter { it.minimum == it.maximum }.map { it.minimum }.distinct(),
            encodings = emptyList(), directUsbDeviceId = device.id,
        )
    }
    fun isDirectRoute(id: Int?): Boolean = id != null && id <= -1_000_000
    fun isDirectDeviceConnected(id: Int?): Boolean {
        val usb = manager ?: return false
        return usb.deviceList.values.any { -1_000_000 - it.deviceId == id && usb.hasPermission(it) }
    }

    fun requestPermission(deviceId: Int) {
        val app = context ?: return
        val usb = manager ?: return
        val device = usb.deviceList.values.firstOrNull { it.deviceId == deviceId && hasAudioOutput(it) }
            ?: return
        if (usb.hasPermission(device)) { inspect(deviceId); return }
        try {
            val result = PendingIntent.getBroadcast(
                app, deviceId, Intent(permissionAction(app)).setPackage(app.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usb.requestPermission(device, result)
        } catch (error: Exception) {
            stateRef.update { it.copy(error = "USB permission request failed: ${error.message}") }
        }
    }

    /** Descriptor inspection does not claim an interface, change clocks, or start USB transfers. */
    fun inspect(deviceId: Int) {
        val usb = manager ?: return
        scope.launch {
            stateRef.update { it.copy(busy = true, error = null) }
            try {
                val device = usb.deviceList.values.firstOrNull { it.deviceId == deviceId }
                    ?: error("USB device was disconnected")
                check(usb.hasPermission(device)) { "Grant USB access first" }
                val connection = usb.openDevice(device) ?: error("Could not open the permitted USB device")
                val profiles = try { UsbDsdDeviceRules.parseQualified(connection.rawDescriptors) } finally { connection.close() }
                stateRef.update { snapshot -> snapshot.copy(
                    devices = snapshot.devices.map { if (it.id == deviceId) it.copy(permission = true, profiles = profiles) else it },
                    busy = false,
                ) }
                PipelineStore.log("USB descriptors: ${device.productName} · ${profiles.size} audio output profiles")
            } catch (error: Exception) {
                stateRef.update { it.copy(busy = false, error = "USB inspection failed: ${error.message}") }
                PipelineStore.log("USB inspection failed: ${error.message}", LogLevel.WARNING)
            }
        }
    }

    /** One explicit direct-USB owner. Caller must select/verify a profile before starting output. */
    fun openDirect(deviceId: Int, choose: (List<UsbAudioStreamingProfile>) -> UsbAudioStreamingProfile): DirectConnection {
        val usb = manager ?: error("USB controller is not initialized")
        check(directLease.compareAndSet(false, true)) { "Another direct USB output owns the device" }
        var connection: UsbDeviceConnection? = null
        try {
            val device = usb.deviceList.values.firstOrNull { it.deviceId == deviceId && hasAudioOutput(it) }
                ?: error("USB audio device is disconnected")
            check(usb.hasPermission(device)) { "USB permission is not granted" }
            connection = usb.openDevice(device) ?: error("Could not open the permitted USB device")
            val activeConfig = ByteArray(1)
            check(connection.controlTransfer(0x80, 8, 0, 0, activeConfig, 1, 1000) == 1) { "Could not read the active USB configuration" }
            val profile = choose(UsbDsdDeviceRules.parseQualified(connection.rawDescriptors).filter {
                it.configuration == (activeConfig[0].toInt() and 0xff)
            })
            val opened = connection
            return DirectConnection(NativeUsbOutput(opened.fileDescriptor, profile)) {
                opened.close()
                directLease.set(false)
            }
        } catch (failure: Throwable) {
            connection?.close()
            directLease.set(false)
            throw failure
        }
    }

    class DirectConnection internal constructor(val output: UsbOutputTransport, private val releaseDevice: () -> Unit) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { output.close() } finally {
                releaseDevice()
            }
        }
    }

    private fun hasAudioOutput(device: UsbDevice): Boolean {
        for (configuration in 0 until device.configurationCount) {
            val config = device.getConfiguration(configuration)
            for (index in 0 until config.interfaceCount) {
                val intf = config.getInterface(index)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_AUDIO || intf.interfaceSubclass != 2) continue
                for (endpoint in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(endpoint)
                    if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) return true
                }
            }
        }
        return false
    }
    private fun permissionAction(context: Context) = "${context.packageName}.USB_PERMISSION_RESULT"
}
