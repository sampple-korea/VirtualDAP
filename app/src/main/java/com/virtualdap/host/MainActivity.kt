package com.virtualdap.host

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.virtualdap.host.audio.AndroidAudioSink
import com.virtualdap.host.audio.DsdOutputMode
import com.virtualdap.host.audio.usb.UsbHostController
import com.virtualdap.host.audio.usb.UsbHostSnapshot
import com.virtualdap.host.container.ContainerPhase
import com.virtualdap.host.container.ContainerRuntime
import com.virtualdap.host.container.ContainerSnapshot
import com.virtualdap.host.model.*
import com.virtualdap.host.service.AudioPipelineService
import com.virtualdap.host.service.OutputSupportPresentation
import com.virtualdap.host.service.PipelineStore
import com.virtualdap.host.ui.theme.VirtualDAPTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val outputs = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshOutputs()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshOutputs()
    }
    private fun refreshOutputs() {
        AndroidAudioSink(this).use { discovery ->
            val routes = discovery.routes() + UsbHostController.routes()
            PipelineStore.update { it.copy(availableRoutes = routes) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsbHostController.initialize(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbHostController.state.collect { refreshOutputs() }
            }
        }
        setContent { VirtualDAPTheme { VirtualDAPApp() } }
    }
    override fun onStart() {
        super.onStart()
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(outputs, null)
        UsbHostController.refresh()
        refreshOutputs()
    }
    override fun onStop() {
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(outputs)
        super.onStop()
    }
}

@Composable
private fun VirtualDAPApp() {
    val context = LocalContext.current
    val audio by PipelineStore.state.collectAsStateWithLifecycle()
    val apps by ContainerRuntime.state.collectAsStateWithLifecycle()
    val usb by UsbHostController.state.collectAsStateWithLifecycle()
    var outputTab by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf(AudioPipelineService.ACTION_START) }
    var pendingPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var dsdUri by rememberSaveable { mutableStateOf<String?>(null) }
    var dsdName by rememberSaveable { mutableStateOf<String?>(null) }
    var dsdMode by rememberSaveable(audio.outputMode) { mutableStateOf(DsdOutputMode.PCM_CONVERSION) }
    // Confirmation applies to this file and output only. Do not restore it after activity/process
    // recreation: the DAC or its hardware volume may have changed while the UI was absent.
    var dsdConfirmed by remember(audio.outputMode, audio.selectedRouteId, dsdMode, dsdUri) {
        mutableStateOf(false)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(ContainerRuntime::install)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            dsdConfirmed = false
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            dsdUri = uri.toString()
            dsdName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: "선택한 DSD 파일"
        }
    }
    fun performAction(action: String, packageName: String?) {
        if (action == AudioPipelineService.ACTION_PLAY_DSD) {
            dsdUri?.let { AudioPipelineService.playDsd(context, Uri.parse(it), dsdMode, dsdConfirmed, dsdName) }
        } else {
            AudioPipelineService.command(context, action)
            packageName?.let(ContainerRuntime::launch)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        performAction(pendingAction, pendingPackage)
        pendingPackage = null
    }
    fun start(action: String, packageName: String? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = action
            pendingPackage = packageName
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else performAction(action, packageName)
    }
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("VirtualDAP", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("도구") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        listOf("로그인 환경", "진단", "출력 소리 테스트", "로컬 DSD 파일").forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { menuOpen = false; tool = name })
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = !outputTab, onClick = { outputTab = false },
                    icon = { Icon(Icons.Rounded.LibraryMusic, null) }, label = { Text("음악 앱") })
                NavigationBarItem(selected = outputTab, onClick = { outputTab = true },
                    icon = { Icon(Icons.Rounded.Usb, null) }, label = { Text("오디오 출력") })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (outputTab) OutputScreen(audio, usb)
            else MusicScreen(apps, audio, onImport = {
                import.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream"))
            }, onLaunch = { start(AudioPipelineService.ACTION_START, it) }, onOutput = { outputTab = true })
        }
    }
    tool?.let { title ->
        AlertDialog(onDismissRequest = { tool = null }, title = { Text(title) },
            confirmButton = { TextButton(onClick = { tool = null }) { Text("닫기") } },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (title) {
                        "로그인 환경" -> {
                            item {
                                Text("Google 기반 음악 앱에서 사용하는 구성요소입니다. 휴대폰에 설치된 APK만 복사하며, 계정·비밀번호·앱 데이터는 가져오지 않습니다.")
                                Text("아래에 설치됨으로 표시되어도 로그인 호환성이 검증된 것은 아닙니다. Apple Music 계정은 Apple Music 안에서 직접 로그인하세요.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            items(GoogleServiceCatalog.packages, key = { it.packageName }) { dependency ->
                                val installed = apps.applications.any { it.packageName == dependency.packageName }
                                val available = apps.hostServices.any { it.packageName == dependency.packageName }
                                Text(dependency.name, fontWeight = FontWeight.Bold)
                                Text(dependency.description, style = MaterialTheme.typography.bodySmall)
                                Text(if (installed) "음악 공간에 설치됨" else "음악 공간에 없음")
                                if (available) TextButton(
                                    onClick = { ContainerRuntime.importHostApp(dependency.packageName) },
                                    enabled = apps.phase == ContainerPhase.READY,
                                ) { Text(if (installed) "휴대폰 버전으로 업데이트" else "휴대폰에서 가져오기") }
                                else Text("휴대폰에서 사용 가능한 패키지를 찾지 못했습니다.", style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider()
                            }
                            item {
                                if (apps.phase == ContainerPhase.INSTALLING) Text("구성요소를 확인하고 설치하는 중입니다…")
                                apps.lastError?.let { ErrorText(it) }
                                TextButton(onClick = {
                                    import.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream"))
                                }, enabled = apps.phase == ContainerPhase.READY) { Text("APK / APKS 파일로 추가") }
                                TextButton(onClick = ContainerRuntime::refresh, enabled = apps.phase.canRefresh) { Text("새로고침") }
                                Text("구성요소를 변경한 뒤에는 음악 앱을 중지하고 다시 여세요. 시스템 특권이나 기기 인증 상태는 변경되지 않습니다.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        "진단" -> {
                            item { Text("USB 연결 여부와 Android 공식 경로 지원 여부는 서로 다릅니다.") }
                            items(audio.availableRoutes, key = { it.id }) { route ->
                                Text(route.name, fontWeight = FontWeight.Bold)
                                Text("${routeKind(route)} · 경로 ${route.id}")
                                if (route.directUsbDeviceId != null) Text("직접 USB · 실제 지원 포맷은 재생 시 DAC와 협상합니다.")
                                else Text(OutputSupportPresentation.officialStatus(route))
                                HorizontalDivider()
                            }
                            item { Text("수신 ${audio.framesReceived} 프레임 · USB 완료 ${audio.outputFramesCompleted ?: 0} 프레임\n" +
                                "출력 언더런 ${audio.outputUnderruns} · 전송 손실 ${audio.guestDroppedBytes} 바이트") }
                            items(audio.logs.takeLast(20).reversed()) { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                        }
                        "출력 소리 테스트" -> item {
                            Text("선택한 출력으로 낮은 음량의 440 Hz 소리를 2초간 재생합니다. 음악 앱이나 전체 경로의 검증은 아닙니다.")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { start(AudioPipelineService.ACTION_SELF_TEST) },
                                enabled = !audio.guestConnected && !audio.dsdPlayback.active && OutputRoutePolicy.selected(audio) != null) {
                                Text("소리 테스트 시작")
                            }
                        }
                        else -> item {
                            Text("DSF / DSDIFF 파일용 보조 도구입니다. 음악 서비스는 기본 화면에서 실행하세요.")
                            TextButton(onClick = { filePicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                                enabled = !audio.dsdPlayback.active) { Text(dsdName ?: "파일 선택") }
                            DsdOutputMode.entries.filter {
                                audio.outputMode == OutputMode.USB || it != DsdOutputMode.NATIVE_DSD
                            }.forEach { mode ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = dsdMode == mode, onClick = { dsdMode = mode; dsdConfirmed = false },
                                        enabled = !audio.dsdPlayback.active)
                                    Text(dsdModeName(mode))
                                }
                            }
                            if (dsdMode != DsdOutputMode.PCM_CONVERSION) {
                                Text("DoP / 네이티브 DSD는 소프트웨어 볼륨을 적용하지 않습니다. DAC의 지원과 안전한 하드웨어 음량을 먼저 확인하세요.")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = dsdConfirmed, onCheckedChange = { dsdConfirmed = it })
                                    Text("DAC 지원 및 음량을 확인했습니다")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { start(AudioPipelineService.ACTION_PLAY_DSD) }, enabled = dsdUri != null &&
                                    !audio.dsdPlayback.active && !audio.guestConnected && OutputRoutePolicy.selected(audio) != null &&
                                    (audio.outputMode == OutputMode.USB || dsdMode != DsdOutputMode.NATIVE_DSD) &&
                                    (dsdMode == DsdOutputMode.PCM_CONVERSION || dsdConfirmed)) { Text("재생") }
                                TextButton(onClick = { AudioPipelineService.command(context, AudioPipelineService.ACTION_STOP_DSD) },
                                    enabled = audio.dsdPlayback.active) { Text("중지") }
                            }
                            if (audio.dsdPlayback.phase == DsdPlaybackPhase.PLAYING || audio.dsdPlayback.phase == DsdPlaybackPhase.PAUSED) {
                                TextButton(onClick = { AudioPipelineService.command(context,
                                    if (audio.dsdPlayback.phase == DsdPlaybackPhase.PAUSED) AudioPipelineService.ACTION_RESUME_DSD
                                    else AudioPipelineService.ACTION_PAUSE_DSD) }) {
                                    Text(if (audio.dsdPlayback.phase == DsdPlaybackPhase.PAUSED) "계속 재생" else "일시정지")
                                }
                            }
                            Text("진행 ${(audio.dsdPlayback.progress * 100).toInt()}%")
                            audio.dsdPlayback.lastError?.let { ErrorText(it) }
                        }
                    }
                }
            })
    }
}

@Composable
private fun MusicScreen(apps: ContainerSnapshot, audio: PipelineSnapshot, onImport: () -> Unit,
    onLaunch: (String) -> Unit, onOutput: () -> Unit) {
    var showHostApps by rememberSaveable { mutableStateOf(false) }
    val ready = apps.phase == ContainerPhase.READY
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("음악을 시작하세요", style = MaterialTheme.typography.headlineMedium)
            Text("음악 앱을 추가하고 USB로 감상하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
              Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val route = OutputRoutePolicy.selected(audio)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(route?.name ?: "출력 장치를 선택해 주세요", modifier = Modifier.weight(1f))
                    TextButton(onClick = onOutput) { Text("출력 설정") }
                }
                Text(if (audio.outputMode == OutputMode.USB) "USB 모드" else "고급 · 공식 비트퍼펙트 모드",
                    style = MaterialTheme.typography.bodySmall)
                if (audio.guestConnected) {
                    Text("입력 ${audio.sourceFormat?.shortLabel() ?: "확인 중"}\n출력 ${audio.sinkFormat?.shortLabel() ?: "준비 중"}")
                    Text(if (audio.bitPerfectActive) "원본 비트 보존 조건 충족 · 실측 아님"
                        else "비트퍼펙트 미확인 · 변환 / 볼륨 / 전송 상태를 확인하세요")
                }
              }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, enabled = ready) { Text("음악 앱 추가") }
                TextButton(onClick = ContainerRuntime::refresh, enabled = apps.phase.canRefresh) {
                    Text(if (apps.phase == ContainerPhase.ERROR) "다시 시도" else "새로고침")
                }
            }
            if (!ready) Text(when (apps.phase) {
                ContainerPhase.INSTALLING -> "음악 앱을 추가하는 중입니다…"
                ContainerPhase.ERROR -> "음악 공간을 불러오지 못했습니다. 다시 시도해 주세요."
                else -> "음악 공간을 준비하는 중입니다…"
            })
            apps.lastError?.let { ErrorText(it) }
            audio.lastError?.let { ErrorText(it) }
        }
        if (apps.musicApplications.isEmpty()) item {
            Section("아직 추가한 음악 앱이 없습니다") {
                Text("APK / APKS 파일을 선택하거나, 휴대폰에 설치된 음악 앱을 가져오세요. 기존 계정과 앱 데이터는 복사하지 않습니다.")
            }
        }
        items(apps.musicApplications, key = { it.packageName }) { app ->
            Section(app.name) {
                Text(if (app.lastStartedPid != null) "시작됨 · 재생 상태는 앱에서 확인" else "실행 준비", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onLaunch(app.packageName) }, enabled = ready && OutputRoutePolicy.selected(audio) != null && !audio.dsdPlayback.active) { Text("앱 열기") }
                    TextButton(onClick = { ContainerRuntime.stop(app.packageName) }, enabled = ready) { Text("앱 중지") }
                }
            }
        }
        item { TextButton(onClick = { showHostApps = !showHostApps }) { Text("휴대폰에 설치된 음악 앱 가져오기") } }
        if (showHostApps) {
            if (apps.hostApplications.isEmpty()) item { Text("가져올 음악 앱을 찾지 못했습니다. 파일 추가를 이용해 주세요.") }
            items(apps.hostApplications, key = { "host-${it.packageName}" }) { app ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(app.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ContainerRuntime.importHostApp(app.packageName) }, enabled = ready) { Text("가져오기") }
                }
            }
        }
        item {
            Text("Apple Music · Spotify · YouTube Music 등 다양한 음악 앱을 지원 대상으로 개발하고 있습니다. 앱별 로그인·보호 콘텐츠·재생 호환성은 아직 모두 검증되지 않았습니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OutputScreen(audio: PipelineSnapshot, usb: UsbHostSnapshot) {
    val context = LocalContext.current
    val locked = audio.enabled || audio.dsdPlayback.active
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("오디오 출력", style = MaterialTheme.typography.headlineLarge)
            Text("출력 경로를 직접 선택합니다. 오류나 분리 시 스피커로 자동 전환하지 않습니다.")
        }
        item {
            Section("재생 모드") {
                OutputMode.entries.forEach { mode ->
                    Row(modifier = Modifier.fillMaxWidth().selectable(
                        selected = audio.outputMode == mode, enabled = !locked, role = Role.RadioButton,
                        onClick = { AudioPipelineService.selectMode(context, mode) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = audio.outputMode == mode, enabled = !locked, onClick = null)
                        Column {
                            Text(if (mode == OutputMode.USB) "USB 오디오 · 기본" else "공식 비트퍼펙트 · 고급")
                            Text(if (mode == OutputMode.USB) "DAC에 직접 전송 · 원본 포맷 우선 협상" else "Android가 제공한 정확한 포맷만 사용",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (locked) Text("모드를 변경하려면 먼저 출력을 중지하세요.")
            }
        }
        if (audio.outputMode == OutputMode.USB) {
            item {
                Section("USB 음량 ${(audio.usbGain * 100).toInt()}%") {
                    Slider(value = audio.usbGain, onValueChange = { gain ->
                        PipelineStore.update { it.copy(usbGain = gain, bitPerfectActive = false) }
                    })
                    Text("초기 음량은 25%입니다. Android 시스템 음량과 별도로 적용됩니다. 100% 미만에서는 소프트웨어 음량 조절로 원본 비트가 변경됩니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (usb.devices.isEmpty()) item { Text("USB DAC 또는 USB 이어폰을 연결해 주세요.") }
            items(usb.devices, key = { "usb-${it.id}" }) { device ->
                Section(device.name) {
                    Text(if (device.permission) "USB 접근 허용됨" else "Android USB 접근 권한이 필요합니다")
                    if (!device.permission) Button(onClick = { UsbHostController.requestPermission(device.id) }) { Text("USB 접근 허용") }
                    else {
                        val route = audio.availableRoutes.firstOrNull { it.directUsbDeviceId == device.id }
                        Button(onClick = { route?.let { AudioPipelineService.command(context, AudioPipelineService.ACTION_SELECT_ROUTE, it.id) } },
                            enabled = route != null && !audio.dsdPlayback.active) {
                            Text(if (route?.id == audio.selectedRouteId) "선택됨" else "이 장치로 출력")
                        }
                        Text("지원 포맷은 DAC와 협상합니다. 필요한 변환은 실제 출력 정보에 표시됩니다.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            usb.error?.let { message -> item { ErrorText(message) } }
        } else {
            items(audio.availableRoutes.filter { it.directUsbDeviceId == null }, key = { it.id }) { route ->
                Section("${route.name} · ${routeKind(route)}") {
                    Text(OutputSupportPresentation.officialStatus(route))
                    Button(onClick = { AudioPipelineService.command(context, AudioPipelineService.ACTION_SELECT_ROUTE, route.id) },
                        enabled = OutputRoutePolicy.eligible(route, audio.outputMode) && !audio.dsdPlayback.active) {
                        Text(if (audio.selectedRouteId == route.id) "선택됨" else "선택")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { AudioPipelineService.command(context, AudioPipelineService.ACTION_STOP) }, enabled = locked) { Text("출력 중지") }
                TextButton(onClick = { UsbHostController.refresh() }) { Text("장치 새로고침") }
            }
            audio.lastError?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable private fun ErrorText(message: String) {
    Text("작업을 완료하지 못했습니다.\n$message", color = MaterialTheme.colorScheme.error)
}
private fun routeKind(route: OutputRoute): String = when (route.type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "내장 스피커"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "수화부"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 오디오"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 헤드셋"
    else -> "출력 유형 ${route.type}"
}
private fun dsdModeName(mode: DsdOutputMode) = when (mode) {
    DsdOutputMode.PCM_CONVERSION -> "PCM으로 변환"
    DsdOutputMode.DOP -> "DoP 전송"
    DsdOutputMode.NATIVE_DSD -> "네이티브 DSD · 검증된 장치만"
}
