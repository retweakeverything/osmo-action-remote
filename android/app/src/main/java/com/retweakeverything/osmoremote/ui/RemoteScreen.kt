package com.retweakeverything.osmoremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retweakeverything.osmoremote.core.ConnectionState
import com.retweakeverything.osmoremote.protocol.CameraMode
import com.retweakeverything.osmoremote.protocol.CaptureState

@Composable
fun RemoteScreen(vm: MainViewModel, onGoConnect: () -> Unit) {
    val state by vm.connection.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pickMode by remember { mutableStateOf(false) }

    val ready = state is ConnectionState.Ready
    val s = status
    val capturing = s?.isCapturing == true
    val playback = s?.captureState == CaptureState.PLAYBACK
    val asleep = s?.sleeping == true
    val photoMode = s?.mode == CameraMode.PHOTO

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        if (!ready) {
            Title("Remote")
            Subtitle("Connect to a camera to use the remote.")
            PrimaryButton("Go to Connect", onClick = onGoConnect)
            return@Column
        }

        if (capturing) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Palette.rec.copy(alpha = 0.12f))
                    .border(1.dp, Palette.rec.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.rec))
                Spacer(Modifier.width(10.dp))
                Text("REC ${formatDuration(s.recordSeconds.toLong())} · ${s.modeLabel}", color = Color(0xFFFF8F90), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
        }
        if (asleep) Notice(Icons.Rounded.Bedtime, "Camera is asleep", "It ignores commands. Press the camera's power button to wake it.")
        if (playback) Notice(Icons.Rounded.Warning, "Camera is in playback", "Leave playback on the camera to record or take photos.")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Battery", s?.let { "${it.batteryPercent}%" } ?: "–", Modifier.weight(1f))
            StatTile("Mode", s?.modeLabel ?: "–", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("SD free", s?.let { formatGb(it.freeSpaceMb) } ?: "–", Modifier.weight(1f), unit = "GB")
            when {
                capturing -> StatTile("Elapsed", formatDuration(s.recordSeconds.toLong()), Modifier.weight(1f))
                photoMode -> StatTile("Photos left", s.remainingPhotos.toString(), Modifier.weight(1f))
                else -> StatTile("Record left", s?.let { formatDuration(it.remainingRecordSeconds) } ?: "–", Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(24.dp))
        val label = when {
            busy != null -> "$busy…"
            capturing -> "Stop"
            photoMode -> "Take photo"
            else -> "Record"
        }
        CaptureButton(
            capturing = capturing,
            label = label,
            enabled = busy == null && !playback && !asleep,
            onClick = { if (photoMode && !capturing) vm.takePhoto() else vm.toggleRecord() },
        )
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction(Icons.Rounded.Star, if (capturing) "Highlight" else "Highlight (while recording)", Modifier.weight(1f), enabled = capturing && !asleep) { vm.highlight() }
            SmallAction(AppIcons.mode(s?.mode), "Mode: ${s?.modeLabel ?: "–"}", Modifier.weight(1f), enabled = busy == null && !capturing && !asleep) { pickMode = true }
        }
    }

    if (pickMode) ModePicker(current = s?.mode, onDismiss = { pickMode = false }) {
        pickMode = false
        vm.setMode(it)
    }
}

@Composable
private fun Notice(icon: ImageVector, title: String, body: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), borderColor = Palette.warn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Palette.warn)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, color = Palette.warn)
                Text(body, color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

/** Big shutter. The label sits under the button so the target stays a clean shape. */
@Composable
private fun CaptureButton(capturing: Boolean, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(150.dp).clip(CircleShape)
                .background(Palette.panel2)
                .border(5.dp, Color(0xFF2A3038), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val inner = if (capturing) Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)) else Modifier.size(78.dp).clip(CircleShape)
            Box(inner.background(if (enabled) Palette.rec else Palette.rec.copy(alpha = 0.35f)))
        }
        Text(
            label.uppercase(), fontSize = 14.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) Palette.text else Palette.dim2, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModePicker(current: CameraMode?, onDismiss: () -> Unit, onPick: (CameraMode) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.panel2) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Switch mode", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Subtitle("The camera confirms the change in its status.")
            CameraMode.selectable.forEach { mode ->
                val selected = mode == current
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Palette.accent.copy(alpha = 0.1f) else Palette.panel)
                        .border(1.dp, if (selected) Palette.accent else Palette.line, RoundedCornerShape(12.dp))
                        .clickable { onPick(mode) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.mode(mode), contentDescription = null, tint = if (selected) Palette.accent else Palette.text)
                    Spacer(Modifier.width(12.dp))
                    Text(mode.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = "current", tint = Palette.accent)
                }
            }
        }
    }
}
