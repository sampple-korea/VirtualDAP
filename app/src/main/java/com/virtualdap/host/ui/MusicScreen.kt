package com.virtualdap.host.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.virtualdap.host.container.*
import com.virtualdap.host.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore

/** Music is the main destination; package management and technical detail stay secondary. */
@Composable
internal fun MusicScreen(apps: ContainerSnapshot, audio: PipelineSnapshot, onImport: () -> Unit,
    onLaunch: (String) -> Unit, onOutput: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = apps.phase == ContainerPhase.READY
    val music = apps.musicApplications
    val filtered = music.filter { MusicInteraction.matches(it, query) }

    if (adding) AddMusicDialog(apps, onDismiss = { adding = false }, onFile = {
        adding = false; onImport()
    }, onHost = { adding = false; ContainerRuntime.importHostApp(it) })

    music.firstOrNull { it.packageName == removing }?.let { app ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("${app.name} 삭제") },
            text = { Text("음악 공간 안의 앱과 로그인 정보·다운로드가 삭제됩니다. 되돌릴 수 없습니다. 휴대폰에 원래 설치한 앱과 데이터는 유지됩니다.") },
            confirmButton = { TextButton(enabled = ready, onClick = {
                ContainerRuntime.remove(app.packageName); removing = null
            }) { Text("음악 공간에서 삭제") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("취소") } })
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("내 음악 공간", style = MaterialTheme.typography.headlineMedium)
                    Text("좋아하는 앱을 한곳에서", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
                FilledTonalIconButton(onClick = { adding = true }, enabled = ready) {
                    Icon(Icons.Rounded.Add, contentDescription = "음악 앱 추가")
                }
            }
        }
        item { OutputSummary(audio, onOutput) }
        if (!ready) item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (apps.phase != ContainerPhase.ERROR) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(when (apps.phase) {
                        ContainerPhase.INSTALLING -> "앱을 가져오는 중이에요"
                        ContainerPhase.REMOVING -> "앱을 삭제하는 중이에요"
                        ContainerPhase.ERROR -> "음악 공간을 불러오지 못했어요"
                        else -> "음악 공간을 준비하고 있어요"
                    }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (apps.phase.canRefresh) TextButton(onClick = ContainerRuntime::refresh) { Text("다시 시도") }
                }
            }
        }
        apps.lastError?.let { message -> item { FailureNotice("앱 작업을 완료하지 못했어요", message) } }
        audio.lastError?.let { message -> item { FailureNotice("출력 상태를 확인해 주세요", message) } }
        if (music.isEmpty() && ready) item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("첫 음악 앱을 추가하세요", style = MaterialTheme.typography.titleLarge)
                    Text("휴대폰에 설치된 앱이나 APK 파일로 시작하세요. 이어폰이 없어도 앱을 열고 설정할 수 있어요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { adding = true }) { Text("음악 앱 추가") }
                }
            }
        }
        if (music.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("추가한 앱 · ${music.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = ContainerRuntime::refresh, enabled = apps.phase.canRefresh) {
                        Icon(Icons.Rounded.Refresh, "앱 목록 새로고침", Modifier.size(20.dp))
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("앱 검색") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "검색 지우기") } })
            }
            if (filtered.isEmpty()) item { Text("검색 결과가 없어요. 다른 이름으로 찾아보세요.", style = MaterialTheme.typography.bodyMedium) }
            items(filtered, key = { it.packageName }) { app ->
                var menu by remember { mutableStateOf(false) }
                val block = MusicInteraction.launchBlock(apps.phase, app, audio)
                Surface(onClick = { onLaunch(app.packageName) }, enabled = block == null,
                    shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MusicAppIcon(app.packageName)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(block ?: if (app.lastStartedPid != null) "다시 열기" else "열기 · 연결 없이 설정 가능",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = ready) { Icon(Icons.Rounded.MoreVert, "${app.name} 관리") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("앱 중지") }, leadingIcon = { Icon(Icons.Rounded.Stop, null) }, onClick = {
                                    menu = false; ContainerRuntime.stop(app.packageName)
                                })
                                if (apps.hostApplications.any { it.packageName == app.packageName }) DropdownMenuItem(
                                    text = { Text("휴대폰 버전으로 업데이트") }, onClick = { menu = false; ContainerRuntime.importHostApp(app.packageName) })
                                DropdownMenuItem(text = { Text("음악 공간에서 삭제", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; removing = app.packageName })
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("앱 설정은 자유롭게. 소리 출력은 선택한 장치로만.", modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OutputSummary(audio: PipelineSnapshot, onOutput: () -> Unit) {
    val route = OutputRoutePolicy.selected(audio)
    Surface(onClick = onOutput, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Usb, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(route?.name ?: "출력 연결 전", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (route == null) "앱은 지금 열고, 출력은 나중에 연결하세요"
                        else if (audio.enabled) "출력 준비됨 · ${if (audio.outputMode == OutputMode.USB) "USB" else "공식 비트퍼펙트"}"
                        else "장치 선택됨 · 출력 설정에서 시작",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Icon(Icons.Rounded.ChevronRight, "출력 설정", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (audio.guestConnected) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("입력 ${audio.sourceFormat?.shortLabel() ?: "확인 중"}\n출력 ${audio.sinkFormat?.shortLabel() ?: "준비 중"}",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (audio.bitPerfectActive) "원본 비트 보존 조건 충족 · 실측 아님" else "변환·음량·전송 상태에 따라 원본 비트가 달라질 수 있어요",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MusicAppIcon(packageName: String, imported: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(null, packageName, imported) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val info = if (imported) BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, 0, 0)
                    else context.packageManager.getApplicationInfo(packageName, 0)
                info?.loadIcon(context.packageManager)?.toBitmap(96, 96)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(Modifier.size(48.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        val icon = bitmap
        if (icon != null) Image(icon, null, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
        else Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun AddMusicDialog(apps: ContainerSnapshot, onDismiss: () -> Unit, onFile: () -> Unit, onHost: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("음악 앱 추가") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedButton(onClick = onFile, enabled = apps.phase == ContainerPhase.READY, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("APK / APKS 파일 선택")
                    }
                }
                item { Text("휴대폰에서 가져오기", style = MaterialTheme.typography.titleSmall) }
                if (apps.hostApplications.isEmpty()) item { Text("가져올 음악 앱이 없어요. 파일을 선택해 추가해 주세요.") }
                items(apps.hostApplications, key = { it.packageName }) { app ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MusicAppIcon(app.packageName, imported = false)
                        Text(app.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onHost(app.packageName) }, enabled = apps.phase == ContainerPhase.READY) {
                            Text(if (apps.applications.any { it.packageName == app.packageName }) "업데이트" else "추가")
                        }
                    }
                }
                item { Text("앱 파일만 가져옵니다. 기존 계정·앱 데이터는 복사하지 않으며 앱별 로그인·재생 호환성은 다를 수 있어요.", style = MaterialTheme.typography.bodySmall) }
            }
        })
}

@Composable
private fun FailureNotice(title: String, detail: String) {
    var expanded by rememberSaveable(detail) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "오류 접기" else "오류 자세히 보기") }
            if (expanded) androidx.compose.foundation.text.selection.SelectionContainer {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
