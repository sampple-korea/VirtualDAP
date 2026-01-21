package com.virtualdap.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virtualdap.host.ui.theme.VirtualDAPTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VirtualDAPTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var guestStatus by remember { mutableStateOf("Initializing...") }
    var bridgeStatus by remember { mutableStateOf("Waiting for stream...") }
    var usbDacStatus by remember { mutableStateOf("No Device") }
    var logs by remember { mutableStateOf(listOf<String>()) }
    
    // Simulate boot sequence
    LaunchedEffect(Unit) {
        guestStatus = "Booting Guest OS..."
        addLog(logs) { logs = it + "[HOST] Booting Container..." }
        delay(2000)
        guestStatus = "Running"
        addLog(logs) { logs = it + "[HOST] Guest OS Ready." }
        delay(500)
        bridgeStatus = "Connected (SharedMem)"
        addLog(logs) { logs = it + "[BRIDGE] Connected to RingBuffer." }
        delay(1000)
        usbDacStatus = "USB DAC Simulation"
        addLog(logs) { logs = it + "[USB] Virtual DAC Attached." }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = "VirtualDAP",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            // Status Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusCard(label = "Guest OS", status = guestStatus, modifier = Modifier.weight(1f))
                StatusCard(label = "Bridge", status = bridgeStatus, modifier = Modifier.weight(1f))
                StatusCard(label = "DAC", status = usbDacStatus, modifier = Modifier.weight(1f))
            }

            // Guest OS Container Placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Guest OS View Surface\n(Music App UI Here)",
                    color = Color.Gray
                )
            }

            // Audio Stream Monitor
            AudioMonitorPanel()

            // Console Log
            LogConsole(logs)
        }
    }
}

@Composable
fun StatusCard(label: String, status: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = label, fontSize = 12.sp, color = Color.Gray)
            Text(text = status, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
fun AudioMonitorPanel() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Audio Stream Monitor", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Sample Rate", color = Color.Gray, fontSize = 12.sp)
                    Text("44.1 kHz", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Bit Depth", color = Color.Gray, fontSize = 12.sp)
                    Text("16 bit", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Bitrate", color = Color.Gray, fontSize = 12.sp)
                    Text("1411 kbps", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LogConsole(logs: List<String>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        items(logs) { log ->
            Text(text = log, color = Color.LightGray, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

fun addLog(currentLogs: List<String>, update: (List<String>) -> Unit) {
    val newLogs = currentLogs.toMutableList()
    // limit logs
    if (newLogs.size > 20) newLogs.removeAt(0)
    update(newLogs)
}
