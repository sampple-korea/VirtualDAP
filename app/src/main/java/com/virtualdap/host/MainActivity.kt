package com.virtualdap.host

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.virtualdap.host.guest.GuestAttestation
import com.virtualdap.host.guest.GuestRuntimeController
import com.virtualdap.host.guest.GuestRuntimePhase
import com.virtualdap.host.guest.GuestRuntimeSnapshot
import com.virtualdap.host.guest.GuestServices
import com.virtualdap.host.container.ContainerPhase
import com.virtualdap.host.container.ContainerRuntime
import com.virtualdap.host.container.ContainerSnapshot
import com.virtualdap.host.model.AppAudioPath
import com.virtualdap.host.model.LogLevel
import com.virtualdap.host.model.MusicAppCatalog
import com.virtualdap.host.model.OutputRoute
import com.virtualdap.host.model.PipelinePhase
import com.virtualdap.host.model.PipelineSnapshot
import com.virtualdap.host.service.AudioPipelineService
import com.virtualdap.host.service.PipelineStore
import com.virtualdap.host.ui.theme.Amber
import com.virtualdap.host.ui.theme.Ink
import com.virtualdap.host.ui.theme.Mint
import com.virtualdap.host.ui.theme.Muted
import com.virtualdap.host.ui.theme.Panel
import com.virtualdap.host.ui.theme.VirtualDAPTheme
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { VirtualDAPTheme { VirtualDAPApp() } }
    }
}

private enum class AppSection(val label: String, val icon: ImageVector) {
    PLAYER("Player", Icons.Rounded.Headphones),
    GUEST("Music space", Icons.Rounded.PhoneAndroid),
    APPS("Apps", Icons.Rounded.LibraryMusic),
    DIAGNOSTICS("Diagnostics", Icons.AutoMirrored.Rounded.List),
}

@Composable
private fun VirtualDAPApp() {
    val context = LocalContext.current
    val snapshot by PipelineStore.state.collectAsStateWithLifecycle()
    val containerSnapshot by ContainerRuntime.state.collectAsStateWithLifecycle()
    var selectedSection by rememberSaveable { mutableStateOf(AppSection.PLAYER) }
    var pendingPermissionAction by rememberSaveable { mutableStateOf(AudioPipelineService.ACTION_START) }
    var pendingPackageName by rememberSaveable { mutableStateOf<String?>(null) }
    val bundlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(ContainerRuntime::install)
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        AudioPipelineService.command(context, pendingPermissionAction)
        pendingPackageName?.let(ContainerRuntime::launch)
        pendingPackageName = null
    }

    fun startWithPermission(action: String, packageName: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissionAction = action
            pendingPackageName = packageName
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            AudioPipelineService.command(context, action)
            packageName?.let(ContainerRuntime::launch)
        }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Panel.copy(alpha = 0.98f)) {
                AppSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = selectedSection == section,
                        onClick = { selectedSection = section },
                        icon = { Icon(section.icon, contentDescription = null) },
                        label = { Text(section.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink,
                            selectedTextColor = Amber,
                            indicatorColor = Amber,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
    ) { insets ->
        AnimatedContent(
            targetState = selectedSection,
            label = "section",
            modifier = Modifier.padding(insets),
        ) { section ->
            when (section) {
                AppSection.PLAYER -> PlayerScreen(
                    snapshot = snapshot,
                    onStart = { startWithPermission(AudioPipelineService.ACTION_START) },
                    onStop = { AudioPipelineService.command(context, AudioPipelineService.ACTION_STOP) },
                    onSelectRoute = { route ->
                        AudioPipelineService.command(
                            context,
                            AudioPipelineService.ACTION_SELECT_ROUTE,
                            route?.id ?: AudioPipelineService.DEFAULT_ROUTE_ID,
                        )
                    },
                )
                AppSection.GUEST -> MusicSpaceScreen(
                    snapshot = containerSnapshot,
                    onImport = {
                        bundlePicker.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream"))
                    },
                    onLaunch = { startWithPermission(AudioPipelineService.ACTION_START, packageName = it) },
                    onStop = ContainerRuntime::stop,
                    onRefresh = ContainerRuntime::refresh,
                )
                AppSection.APPS -> AppsScreen()
                AppSection.DIAGNOSTICS -> DiagnosticsScreen(
                    snapshot = snapshot,
                    onSelfTest = { startWithPermission(AudioPipelineService.ACTION_SELF_TEST) },
                )
            }
        }
    }
}

@Composable
private fun MusicSpaceScreen(
    snapshot: ContainerSnapshot,
    onImport: () -> Unit,
    onLaunch: (String) -> Unit,
    onStop: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val busy = snapshot.phase == ContainerPhase.INITIALIZING || snapshot.phase == ContainerPhase.INSTALLING
    var chooseInstalled by rememberSaveable { mutableStateOf(false) }
    if (chooseInstalled) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { chooseInstalled = false },
            title = { Text("Add an installed music app") },
            text = {
                Column {
                    Text("Copies the app into your music space, not its accounts or private data. Sign in separately inside the imported app.")
                    Spacer(Modifier.height(12.dp))
                    if (snapshot.hostApplications.isEmpty()) {
                        Text("No catalog music apps were found on this device. Install one from its usual store, then refresh this list, or choose an APK file.")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(snapshot.hostApplications, key = { it.packageName }) { app ->
                                OutlinedButton(
                                    onClick = {
                                        chooseInstalled = false
                                        ContainerRuntime.importHostApp(app.packageName)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(app.name) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { chooseInstalled = false }) { Text("Close") }
            },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Music space",
                title = "Your apps. Your audio.",
                subtitle = "Add music apps to a separate app space. Runs on your Android without root.",
            )
        }
        item {
            SectionCard(title = "App container", icon = Icons.Rounded.PhoneAndroid) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Amber, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(snapshot.detail, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onImport, enabled = snapshot.phase == ContainerPhase.READY, modifier = Modifier.weight(1f)) {
                        Text("Add APK / APKS")
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Refresh")
                    }
                }
                OutlinedButton(
                    onClick = { onRefresh(); chooseInstalled = true },
                    enabled = snapshot.phase == ContainerPhase.READY,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add from installed apps") }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Uses Android ${Build.VERSION.RELEASE} on this device. Install only trusted apps: data is stored separately, but this container is not a security sandbox.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        snapshot.lastError?.let { error -> item { ErrorCard(error) } }
        if (snapshot.applications.isEmpty() && !busy) {
            item {
                SectionCard(title = "Ready for your music apps", icon = Icons.Rounded.LibraryMusic) {
                    Text(
                        "Choose an APK or a split APK set (.apks). Installed apps will appear here with launch and stop controls.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
            }
        }
        items(snapshot.applications, key = { it.packageName }) { app ->
            SectionCard(title = app.name, icon = Icons.Rounded.LibraryMusic) {
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onLaunch(app.packageName) },
                        enabled = snapshot.phase == ContainerPhase.READY,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open app")
                    }
                    OutlinedButton(onClick = { onStop(app.packageName) }, modifier = Modifier.weight(1f)) {
                        Text("Stop app")
                    }
                }
            }
        }
    }
}

@Composable
private fun GuestScreen(
    snapshot: GuestRuntimeSnapshot,
    onImport: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val manifest = snapshot.manifest
    val busy = snapshot.phase in setOf(
        GuestRuntimePhase.IMPORTING,
        GuestRuntimePhase.STARTING,
        GuestRuntimePhase.STOPPING,
    )
    val running = snapshot.phase == GuestRuntimePhase.RUNNING || snapshot.phase == GuestRuntimePhase.STARTING
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Android 13",
                title = "Your music guest.",
                subtitle = "Import a verified VirtualDAP image and run it through a trusted platform backend.",
            )
        }
        item {
            SectionCard(title = "Guest runtime", icon = Icons.Rounded.PhoneAndroid) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Amber, strokeWidth = 2.dp)
                    } else {
                        Box(
                            Modifier.size(10.dp).background(
                                if (snapshot.phase == GuestRuntimePhase.RUNNING) Mint else Muted,
                                CircleShape,
                            ),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(guestPhaseLabel(snapshot.phase), fontWeight = FontWeight.SemiBold)
                        Text(snapshot.detail ?: "No runtime activity", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                DiagnosticLine("Provider", snapshot.providerName ?: "Not installed")
                DiagnosticLine("Trust", if (snapshot.providerAvailable) "Platform-signed" else "Unavailable")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onImport, enabled = !busy && !running, modifier = Modifier.weight(1f)) {
                        Text(if (manifest == null) "Import bundle" else "Replace image")
                    }
                    Button(
                        onClick = if (running) onStop else onStart,
                        enabled = if (running) !busy else manifest != null && snapshot.providerAvailable && !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(if (running) "Stop guest" else "Start guest")
                    }
                }
            }
        }
        if (manifest != null) {
            item {
                SectionCard(title = "Installed image", icon = Icons.Rounded.Memory) {
                    Text(manifest.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    DiagnosticLine("Android", "13 · API ${manifest.androidApi}")
                    DiagnosticLine("Architecture", manifest.architecture)
                    DiagnosticLine("Image", humanBytes(manifest.imageBytes))
                    DiagnosticLine(
                        "Services",
                        when (manifest.services) {
                            GuestServices.AOSP -> "AOSP"
                            GuestServices.USER_PROVIDED_GMS -> "User-provided GMS"
                        },
                    )
                    DiagnosticLine(
                        "Attestation",
                        when (manifest.attestation) {
                            GuestAttestation.NOT_CERTIFIED -> "Not certified"
                            GuestAttestation.OEM_CERTIFIED -> "OEM-declared"
                        },
                    )
                    DiagnosticLine("Content check", "SHA-256 verified at import")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        manifest.buildFingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
            }
        }
        if (snapshot.phase == GuestRuntimePhase.RUNNING) {
            item { GuestDisplay() }
        }
        item {
            SectionCard(title = "Service integrity", icon = Icons.Rounded.Info) {
                Text(
                    "Image hashing detects corruption but does not create Play certification. Google services, Widevine, and hardware attestation must come from a lawfully provisioned OEM image. Identity or key-attestation spoofing is not installed by VirtualDAP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        snapshot.lastError?.let { error -> item { ErrorCard(error) } }
    }
}

@Composable
@SuppressLint("ClickableViewAccessibility") // The surface forwards raw multi-pointer gestures to the guest.
private fun GuestDisplay() {
    SectionCard(title = "Guest display", icon = Icons.Rounded.PhoneAndroid) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f)
                .background(Color.Black, RoundedCornerShape(12.dp)),
            factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnTouchListener { view, event ->
                        view.parent?.requestDisallowInterceptTouchEvent(
                            event.actionMasked != MotionEvent.ACTION_UP &&
                                event.actionMasked != MotionEvent.ACTION_CANCEL,
                        )
                        GuestRuntimeController.injectMotionEvent(event)
                        true
                    }
                    setOnKeyListener { _, keyCode, event ->
                        if (keyCode in setOf(
                                KeyEvent.KEYCODE_VOLUME_UP,
                                KeyEvent.KEYCODE_VOLUME_DOWN,
                                KeyEvent.KEYCODE_VOLUME_MUTE,
                            )
                        ) {
                            false
                        } else {
                            GuestRuntimeController.injectKeyEvent(event)
                            true
                        }
                    }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            requestFocus()
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            GuestRuntimeController.attachDisplay(
                                holder.surface,
                                width,
                                height,
                                resources.displayMetrics.densityDpi,
                            )
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            GuestRuntimeController.detachDisplay()
                        }
                    })
                }
            },
            update = { view ->
                if (view.holder.surface.isValid && view.width > 0 && view.height > 0) {
                    GuestRuntimeController.attachDisplay(
                        view.holder.surface,
                        view.width,
                        view.height,
                        view.resources.displayMetrics.densityDpi,
                    )
                }
            },
            onRelease = { GuestRuntimeController.detachDisplay() },
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { GuestRuntimeController.tapKey(KeyEvent.KEYCODE_BACK) },
                modifier = Modifier.weight(1f),
            ) { Text("Back") }
            OutlinedButton(
                onClick = { GuestRuntimeController.tapKey(KeyEvent.KEYCODE_HOME) },
                modifier = Modifier.weight(1f),
            ) { Text("Home") }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Touch and hardware-key events are forwarded to the isolated guest. Audio remains on the dedicated PCM bridge.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

@Composable
private fun ScreenHeader(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).background(Amber, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("V", color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                eyebrow.uppercase(),
                color = Amber,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 1.5.sp,
            )
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
private fun PlayerScreen(
    snapshot: PipelineSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectRoute: (OutputRoute?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = "VirtualDAP",
                title = "Guest audio, intact.",
                subtitle = "A monitored PCM path from isolated music apps to the output you choose.",
            )
        }
        item { PipelineHero(snapshot, onStart, onStop) }
        item { SignalChain(snapshot) }
        item { StreamDetails(snapshot) }
        item { OutputSelector(snapshot, onSelectRoute) }
        item { MetricsRow(snapshot) }
        snapshot.lastError?.let { error -> item { ErrorCard(error) } }
    }
}

@Composable
private fun PipelineHero(snapshot: PipelineSnapshot, onStart: () -> Unit, onStop: () -> Unit) {
    val accent = when (snapshot.phase) {
        PipelinePhase.PLAYING -> Mint
        PipelinePhase.ERROR -> MaterialTheme.colorScheme.error
        PipelinePhase.BUFFERING -> Amber
        else -> Muted
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(phaseLabel(snapshot.phase), color = accent, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        snapshot.phase == PipelinePhase.PLAYING -> snapshot.sourceFormat?.shortLabel() ?: "PCM stream"
                        snapshot.enabled -> "Listening for the guest audio HAL"
                        else -> "Pipeline is off"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        snapshot.guestConnected -> snapshot.guestPeer ?: "Guest connected"
                        snapshot.enabled -> "Socket @${com.virtualdap.host.bridge.LocalSocketBridgeServer.SOCKET_NAME}"
                        else -> "Start when your guest environment is ready."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            Button(
                onClick = if (snapshot.enabled) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (snapshot.enabled) MaterialTheme.colorScheme.surfaceVariant else Amber,
                    contentColor = if (snapshot.enabled) MaterialTheme.colorScheme.onSurface else Ink,
                ),
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 11.dp),
            ) {
                Icon(
                    if (snapshot.enabled) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(if (snapshot.enabled) "Stop" else "Start")
            }
        }
    }
}

@Composable
private fun SignalChain(snapshot: PipelineSnapshot) {
    SectionCard(title = "Signal path", icon = Icons.Rounded.GraphicEq) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SignalNode(
                Modifier.weight(1f),
                label = "GUEST",
                value = if (snapshot.guestConnected) "Online" else "Waiting",
                active = snapshot.guestConnected,
            )
            SignalConnector(snapshot.guestConnected)
            SignalNode(
                Modifier.weight(1f),
                label = "BRIDGE",
                value = snapshot.sourceFormat?.let { "${it.sampleRate / 1000.0} kHz" } ?: "PCM",
                active = snapshot.phase == PipelinePhase.BUFFERING || snapshot.phase == PipelinePhase.PLAYING,
            )
            SignalConnector(snapshot.phase == PipelinePhase.PLAYING)
            SignalNode(
                Modifier.weight(1f),
                label = "OUTPUT",
                value = snapshot.activeRoute?.name ?: "System",
                active = snapshot.sinkFormat != null,
            )
        }
    }
}

@Composable
private fun SignalNode(modifier: Modifier, label: String, value: String, active: Boolean) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(42.dp)
                .background(if (active) Amber.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(9.dp).background(if (active) Amber else Muted.copy(alpha = 0.4f), CircleShape))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 1.sp)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SignalConnector(active: Boolean) {
    Box(
        Modifier.width(26.dp).height(1.dp)
            .background(if (active) Amber else Color.White.copy(alpha = 0.09f)),
    )
}

@Composable
private fun StreamDetails(snapshot: PipelineSnapshot) {
    SectionCard(title = "Current stream", icon = Icons.Rounded.Memory) {
        val format = snapshot.sourceFormat
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DetailValue("SAMPLE RATE", format?.let { "${it.sampleRate / 1000.0} kHz" } ?: "—")
            DetailValue("BIT DEPTH", format?.encoding?.displayName ?: "—")
            DetailValue("CHANNELS", format?.channelCount?.toString() ?: "—")
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (snapshot.directPlayback) Icons.Rounded.GraphicEq else Icons.Rounded.Info,
                contentDescription = null,
                tint = if (snapshot.directPlayback) Mint else Amber,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    snapshot.sinkFormat == null -> "Output verification begins when PCM arrives."
                    snapshot.playingStreams > 1 -> "${snapshot.playingStreams} tracks are playing through the Android mixer. Bit-perfect output is disabled during overlap."
                    snapshot.activeRoute?.directUsbDeviceId != null && (snapshot.applicationGainLeft != 1f || snapshot.applicationGainRight != 1f) ->
                        "Application gain is applied before direct USB output; bit-perfect output is disabled."
                    snapshot.activeRoute?.directUsbDeviceId != null && snapshot.outputUnderruns > 0 ->
                        "Direct USB reported ${snapshot.outputUnderruns} queue underruns. Source bytes were not padded, but uninterrupted output is not verified."
                    snapshot.activeRoute?.directUsbDeviceId != null && snapshot.bitPerfectActive ->
                        "Direct USB: source precision preserved, clock negotiation accepted and complete USB transfers observed. DAC presentation timing is not measured."
                    snapshot.bitPerfectActive -> "USB bit-perfect: unchanged PCM, with the OS bit-perfect mixer active on the routed DAC."
                    !snapshot.sourcePreserved -> "Compatibility conversion active: ${snapshot.sinkFormat.shortLabel()}."
                    snapshot.activeRoute?.directUsbDeviceId != null -> "Direct USB transport. Waiting for clean completed transfers before confirming the output path."
                    snapshot.directPlayback -> "Source PCM is unchanged and Android reports direct support."
                    else -> "Source PCM reaches AudioTrack unchanged; the Android mixer may convert the hardware output."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        if (snapshot.sinkFormat != null) {
            Spacer(Modifier.height(10.dp))
            DiagnosticLine("AudioTrack input", snapshot.sinkFormat.shortLabel())
            DiagnosticLine("Application gain", "${(snapshot.applicationGainLeft * 100).toInt()}% / ${(snapshot.applicationGainRight * 100).toInt()}%")
            DiagnosticLine("Music tracks", "${snapshot.playingStreams} playing / ${snapshot.connectedStreams} connected")
            snapshot.outputFramesCompleted?.let { DiagnosticLine("USB completed frames", it.toString()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputSelector(snapshot: PipelineSnapshot, onSelectRoute: (OutputRoute?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = "Output device", icon = Icons.Rounded.Usb) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (snapshot.enabled) expanded = !expanded },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, snapshot.enabled),
                onClick = { if (snapshot.enabled) expanded = true },
                color = Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (snapshot.activeRoute?.isUsb == true) Icons.Rounded.Usb else Icons.Rounded.Speaker,
                        contentDescription = null,
                        tint = Amber,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            snapshot.availableRoutes.firstOrNull { it.id == snapshot.selectedRouteId }?.name
                                ?: "System default",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (snapshot.enabled) "Tap to choose a route" else "Start the pipeline to choose a route",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                }
            }
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("System default") },
                    onClick = { expanded = false; onSelectRoute(null) },
                )
                snapshot.availableRoutes.forEach { route ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(route.name)
                                Text(
                                    if (route.directUsbDeviceId != null) "Exclusive USB · one active stream; bypasses Android mixer"
                                    else if (route.isUsb) "Android USB output · supports overlapping streams" else "Android audio output",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                        },
                        onClick = { expanded = false; onSelectRoute(route) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsRow(snapshot: PipelineSnapshot) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(Modifier.weight(1f), "RECEIVED", humanBytes(snapshot.bytesReceived))
        MetricCard(Modifier.weight(1f), "TRACK QUEUE", snapshot.latencyMs?.let { "%.1f ms".format(it) } ?: "—")
        MetricCard(Modifier.weight(1f), "DROPPED", humanBytes(snapshot.guestDroppedBytes))
    }
}

@Composable
private fun MetricCard(modifier: Modifier, label: String, value: String) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.height(5.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun AppsScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Compatibility",
                title = "One PCM path.",
                subtitle = "Target services for the music space. Catalog entries are not verified compatibility results.",
            )
            Spacer(Modifier.height(8.dp))
        }
        items(MusicAppCatalog.popularApps, key = { it.packageName }) { app ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(app.name.take(1), color = Amber, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            CompatibilityBadge(app.audioPath)
                        }
                        Text(app.note, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
        item {
            Text(
                "The container shares the host Android version. Service login, Play Integrity and Widevine remain provider-controlled. VirtualDAP does not bypass DRM.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CompatibilityBadge(path: AppAudioPath) {
    val (text, color) = when (path) {
        AppAudioPath.SYSTEM_PCM -> "PCM" to Mint
        AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS -> "PCM · DRM" to Amber
        AppAudioPath.LOCAL_HI_RES_PCM -> "HI-RES" to Mint
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun DiagnosticsScreen(snapshot: PipelineSnapshot, onSelfTest: () -> Unit) {
    val usb by com.virtualdap.host.audio.usb.UsbHostController.state.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val timeFormatter = remember(locale) { SimpleDateFormat("HH:mm:ss", locale) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Diagnostics",
                title = "See every handoff.",
                subtitle = "Verify routing before involving a guest music service.",
            )
        }
        item {
            SectionCard(title = "USB audio access", icon = Icons.Rounded.Usb) {
                Text(
                    "USB access is granted by Android. Inspecting a device does not claim its audio interface or start playback.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
                if (usb.devices.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("No USB audio output is connected.", color = Muted)
                }
                usb.devices.forEach { device ->
                    Spacer(Modifier.height(12.dp))
                    Text(device.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "USB %04x:%04x".format(device.vendorId, device.productId),
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                    OutlinedButton(
                        onClick = {
                            if (device.permission) com.virtualdap.host.audio.usb.UsbHostController.inspect(device.id)
                            else com.virtualdap.host.audio.usb.UsbHostController.requestPermission(device.id)
                        },
                        enabled = !usb.busy,
                    ) { Text(if (device.permission) "Inspect formats" else "Grant USB access") }
                    if (device.profiles.isNotEmpty()) {
                        Text(
                            device.profiles.take(8).joinToString("\n") {
                                "${it.channelCount} ch · ${it.bitResolution}-bit / ${it.subslotBytes}-byte slots · alt ${it.alternateSetting}" +
                                    if (it.rawData) " · raw-data candidate (not confirmed DSD)" else ""
                            },
                            style = MaterialTheme.typography.bodySmall, color = Muted,
                        )
                    }
                }
                usb.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                androidx.compose.material3.TextButton(
                    onClick = com.virtualdap.host.audio.usb.UsbHostController::refresh, enabled = !usb.busy,
                ) { Text("Refresh USB devices") }
            }
        }
        item {
            SectionCard(title = "Output self-test", icon = Icons.Rounded.PlayArrow) {
                Text(
                    "Plays a quiet two-second 440 Hz tone at 48 kHz / 16-bit stereo. This tests only the host output route.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Spacer(Modifier.height(13.dp))
                Button(onClick = onSelfTest, enabled = !snapshot.guestConnected) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Run output test")
                }
            }
        }
        item {
            SectionCard(title = "Session", icon = Icons.Rounded.Info) {
                DiagnosticLine("Bridge", if (snapshot.enabled) "Listening" else "Stopped")
                DiagnosticLine("Guest", if (snapshot.guestConnected) snapshot.guestPeer ?: "Connected" else "Not connected")
                DiagnosticLine("Reconnects", snapshot.reconnectCount.toString())
                DiagnosticLine("Frames", snapshot.framesReceived.toString())
            }
        }
        item {
            SectionCard(title = "Event log", icon = Icons.AutoMirrored.Rounded.List) {
                if (snapshot.logs.isEmpty()) {
                    Text("No events yet.", style = MaterialTheme.typography.bodySmall, color = Muted)
                } else {
                    snapshot.logs.asReversed().forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        Row(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                timeFormatter.format(Date(entry.timestampMillis)),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (entry.level) {
                                    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                                    LogLevel.WARNING -> Amber
                                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Amber, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(15.dp))
            content()
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun phaseLabel(phase: PipelinePhase): String = when (phase) {
    PipelinePhase.STOPPED -> "Stopped"
    PipelinePhase.WAITING_FOR_GUEST -> "Ready for guest"
    PipelinePhase.BUFFERING -> "Buffering"
    PipelinePhase.PLAYING -> "Playing"
    PipelinePhase.PAUSED -> "Paused"
    PipelinePhase.ERROR -> "Needs attention"
}

private fun guestPhaseLabel(phase: GuestRuntimePhase): String = when (phase) {
    GuestRuntimePhase.NOT_INSTALLED -> "No guest image"
    GuestRuntimePhase.IMPORTING -> "Verifying and installing"
    GuestRuntimePhase.READY -> "Ready"
    GuestRuntimePhase.STARTING -> "Starting"
    GuestRuntimePhase.RUNNING -> "Running"
    GuestRuntimePhase.STOPPING -> "Stopping"
    GuestRuntimePhase.ERROR -> "Needs attention"
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
