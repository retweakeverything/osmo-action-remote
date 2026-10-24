package com.retweakeverything.osmoremote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retweakeverything.osmoremote.core.ConnectionState

/** Always-visible, honest connection indicator. Tap opens camera management. */
@Composable
fun ConnectionPill(state: ConnectionState, workflowActive: Boolean, onClick: () -> Unit) {
    val (color, title, sub) = when (state) {
        ConnectionState.Idle -> Triple(Palette.dim2, "Not connected", "Pick a camera to begin")
        is ConnectionState.Connecting -> Triple(Palette.warn, "Connecting…", state.device.name)
        is ConnectionState.AwaitingConfirmation -> Triple(Palette.warn, "Pairing…", state.device.name)
        is ConnectionState.Ready -> Triple(
            Palette.ok, if (workflowActive) "Connected · workflow active" else "Connected", state.device.name,
        )
        is ConnectionState.Reconnecting -> Triple(Palette.warn, "Reconnecting (attempt ${state.attempt})", state.reason)
        is ConnectionState.Failed -> Triple(Palette.rec, "Connection failed", state.reason)
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp)
                Text(sub, fontSize = 11.sp, color = Palette.dim, maxLines = 1)
            }
            Icon(Icons.Rounded.MoreVert, contentDescription = "Manage camera", tint = Palette.dim2)
        }
    }
}
