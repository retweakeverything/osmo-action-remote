package com.retweakeverything.osmoremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retweakeverything.osmoremote.core.ConnectionState
import com.retweakeverything.osmoremote.core.DiscoveredCamera
import com.retweakeverything.osmoremote.data.RouteOption
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalUriHandler

@Composable
fun ConnectScreen(
    vm: MainViewModel,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onManage: () -> Unit,
) {
    val bluetoothOn by vm.bluetoothOn.collectAsStateWithLifecycle()
    val state by vm.connection.collectAsStateWithLifecycle()
    val found by vm.discovered.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val radioReady = !route.needsPhoneBluetooth || (hasPermissions && bluetoothOn)

    val idle = state is ConnectionState.Idle || state is ConnectionState.Failed
    DisposableEffect(idle, radioReady, route) {
        if (idle && radioReady) vm.startScan()
        onDispose { vm.stopScan() }
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Title("Connect")
        RoutePicker(vm.routes, route, enabled = idle) { vm.selectRoute(it) }
        when {
            route.needsPhoneBluetooth && !hasPermissions -> {
                Subtitle("Bluetooth permission is needed to find and control your camera. Nothing else is accessed.")
                PrimaryButton("Grant Bluetooth permission", onClick = onRequestPermissions)
            }
            route.needsPhoneBluetooth && !bluetoothOn -> {
                Subtitle("Bluetooth is off. Turn it on to find and control your camera.")
                PrimaryButton("Turn on Bluetooth", onClick = onEnableBluetooth)
            }
            state is ConnectionState.Ready -> {
                val ready = state as ConnectionState.Ready
                Subtitle("Connected to ${ready.model.label}. Use the Remote tab to control it.")
                Card(Modifier.fillMaxWidth()) { DeviceRow(ready.device.name, ready.model.label, "connected") }
                Spacer(Modifier.height(12.dp))
                GhostButton("Manage camera", onClick = onManage)
                Spacer(Modifier.height(10.dp))
                GhostButton("Disconnect") { vm.disconnect() }
            }
            state is ConnectionState.Connecting || state is ConnectionState.Reconnecting -> {
                Subtitle("Opening a link to ${state.device?.name}. Keep the camera on and nearby.")
                Progress(if (state is ConnectionState.Reconnecting) "Reconnecting…" else "Connecting…")
                Spacer(Modifier.height(12.dp))
                GhostButton("Cancel") { vm.disconnect() }
            }
            else -> {
                Subtitle("Power on your Osmo Action and keep it nearby. Cameras that were paired before reconnect without a prompt.")
                if (state is ConnectionState.Failed) {
                    Text("Last attempt failed: ${(state as ConnectionState.Failed).reason}", color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
                if (scanning) Progress("Scanning for cameras…")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
                    items(found, key = { it.device.address }) { cam -> FoundCamera(cam, vm.isPaired(cam.device.address)) { vm.connect(cam.device) } }
                }
                if (!scanning && found.isEmpty()) {
                    Text("No cameras found yet.", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                }
                Spacer(Modifier.height(12.dp))
                GhostButton(if (scanning) "Stop scanning" else "Rescan") { if (scanning) vm.stopScan() else vm.startScan() }
            }
        }
    }
}

/** Choose how commands reach the camera. Everything else in the app is the same for both. */
@Composable
private fun RoutePicker(options: List<RouteOption>, current: RouteOption, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val selected = o.route.id == current.route.id
            FilterChip(
                selected = selected, enabled = enabled,
                onClick = { onSelect(o.route.id) },
                label = { Text(o.label) },
                leadingIcon = { Icon(if (o.needsPhoneBluetooth) Icons.Rounded.Bluetooth else Icons.Rounded.Usb, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.accent.copy(alpha = 0.18f),
                    selectedLabelColor = Palette.text, selectedLeadingIconColor = Palette.accent,
                    labelColor = Palette.dim, iconColor = Palette.dim,
                ),
            )
        }
    }
    Text(current.description, color = Palette.dim2, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
    if (!current.needsPhoneBluetooth) {
        val uri = LocalUriHandler.current
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { uri.openUri(Links.RELEASES) }) { Text("Download firmware") }
            TextButton(onClick = { uri.openUri(Links.ESP32_GUIDE) }) { Text("How to flash") }
        }
    }
}

@Composable
private fun FoundCamera(cam: DiscoveredCamera, paired: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), onClick = onClick) {
        DeviceRow(
            cam.device.name,
            cam.device.model.label + if (paired) " · paired before" else " · new",
            "${cam.rssi} dBm",
            rightColor = if (cam.rssi > -75) Palette.ok else Palette.warn,
        )
    }
}

@Composable
fun DeviceRow(name: String, meta: String, right: String, rightColor: androidx.compose.ui.graphics.Color = Palette.ok) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(AppIcons.camera)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(meta, fontSize = 11.sp, color = Palette.dim)
        }
        Text(right, fontSize = 11.sp, color = rightColor)
    }
}

@Composable
fun Progress(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.accent)
        Spacer(Modifier.width(10.dp))
        Text(label, color = Palette.dim, fontSize = 13.sp)
    }
}

/** Full-screen overlay while the user confirms first-time pairing on the camera. */
@Composable
fun PairingOverlay(state: ConnectionState.AwaitingConfirmation, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconTile(AppIcons.camera, size = 84, tint = Palette.accent)
        Spacer(Modifier.height(18.dp))
        Text("Confirm pairing", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "The camera is asking whether to allow this phone. Confirm it on the camera screen.",
            color = Palette.dim, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp,
        )
        Text(
            state.code.toString().toCharArray().joinToString(" "),
            fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.accent, letterSpacing = 4.sp,
            modifier = Modifier.padding(vertical = 18.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("1. Look at your camera's screen", color = Palette.dim, fontSize = 13.sp, lineHeight = 24.sp)
                Text("2. If it shows a code, it should match the one above", color = Palette.dim, fontSize = 13.sp, lineHeight = 24.sp)
                Text("3. Tap Confirm on the camera", color = Palette.dim, fontSize = 13.sp, lineHeight = 24.sp)
            }
        }
        Progress("Waiting for you to confirm on the camera…")
        GhostButton("Cancel", onClick = onCancel)
    }
}
