package com.retweakeverything.osmoremote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retweakeverything.osmoremote.core.ConnectionState

@Composable
fun ManageScreen(vm: MainViewModel, onShowLog: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val state by vm.connection.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val auto by vm.autoReconnect.collectAsStateWithLifecycle()
    val keep by vm.keepAlive.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ready = state as? ConnectionState.Ready
    val device = state.device ?: vm.lastDevice()
    var confirmSleep by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Palette.bg).verticalScroll(rememberScrollState()).padding(14.dp)) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Title("Camera")
        Subtitle(device?.let { "${it.name} · ${it.address}" } ?: "No camera selected yet.")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Model", ready?.model?.label ?: device?.model?.label ?: "–", Modifier.weight(1f))
            StatTile("Battery", status?.takeIf { ready != null }?.let { "${it.batteryPercent}%" } ?: "–", Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))

        ToggleRow("Auto-reconnect", "Re-link silently if the connection drops.", auto) { vm.setAutoReconnect(it) }
        ToggleRow(
            "Keep alive in background",
            "Keeps the link up with a notification so the camera stays connected with the screen off. Running workflows always keep it alive.",
            keep,
        ) { vm.setKeepAlive(it) }

        SectionLabel("Power")
        Text(
            "Wake uses DJI's wake broadcast, which the camera only accepts from a fixed (public) Bluetooth address. " +
                "An ESP32 on USB has one; most phones do not. Needs camera firmware 01.04.08.10 or later.",
            color = Palette.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Sleep", Modifier.weight(1f), enabled = ready != null && busy == null && status?.sleeping != true) { confirmSleep = true }
            GhostButton("Wake", Modifier.weight(1f), enabled = device != null && busy == null && status?.sleeping == true) { vm.wakeCamera() }
        }
        Spacer(Modifier.height(18.dp))
        if (state !is ConnectionState.Idle) GhostButton("Disconnect") { vm.disconnect(); onClose() }
        Spacer(Modifier.height(10.dp))
        if (device != null) DangerButton("Forget / unpair camera") { vm.forget(); onClose() }
        Spacer(Modifier.height(10.dp))
        GhostButton("View connection log", onClick = onShowLog)
        Spacer(Modifier.height(10.dp))
        val uri = LocalUriHandler.current
        GhostButton("Source code, help and ESP32 firmware") { uri.openUri(Links.REPO) }
    }

    if (confirmSleep) AlertDialog(
        onDismissRequest = { confirmSleep = false },
        title = { Text("Put the camera to sleep?") },
        text = { Text("If waking from this app does not work on your setup, press the camera's power button.") },
        confirmButton = { TextButton(onClick = { confirmSleep = false; vm.sleepCamera() }) { Text("Sleep") } },
        dismissButton = { TextButton(onClick = { confirmSleep = false }) { Text("Cancel") } },
    )
}

@Composable
fun LogScreen(vm: MainViewModel, onShare: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val lines by vm.logs.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(14.dp)) {
        Row {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onShare(lines.joinToString("\n")) }) { Text("Share") }
        }
        Title("Connection log")
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.dim, lineHeight = 15.sp) }
        }
    }
}
