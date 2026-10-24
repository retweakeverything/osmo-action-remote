package com.retweakeverything.osmoremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val bg = Color(0xFF0B0D10)
    val panel = Color(0xFF15191F)
    val panel2 = Color(0xFF1C2129)
    val line = Color(0xFF262C36)
    val text = Color(0xFFE8EDF2)
    val dim = Color(0xFF8C97A5)
    val dim2 = Color(0xFF5E6773)
    val accent = Color(0xFF3DA9FC)
    val rec = Color(0xFFFF4D4F)
    val ok = Color(0xFF2FD27A)
    val warn = Color(0xFFF5A623)
}

@Composable
fun OsmoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color(0xFF05121F),
            background = Palette.bg,
            onBackground = Palette.text,
            surface = Palette.panel,
            onSurface = Palette.text,
            surfaceVariant = Palette.panel2,
            onSurfaceVariant = Palette.dim,
            outline = Palette.line,
            error = Palette.rec,
        ),
    ) {
        // Overlays (camera, log, builder, pairing) sit outside the Scaffold, which is what
        // normally supplies content colour; without this their text falls back to black.
        CompositionLocalProvider(LocalContentColor provides Palette.text, content = content)
    }
}

private val cardShape = RoundedCornerShape(14.dp)

@Composable
fun Card(modifier: Modifier = Modifier, borderColor: Color = Palette.line, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(cardShape)
            .background(Palette.panel)
            .border(1.dp, borderColor, cardShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
    ) { content() }
}

@Composable
fun Title(text: String) = Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))

@Composable
fun Subtitle(text: String, modifier: Modifier = Modifier) =
    Text(text, fontSize = 13.sp, color = Palette.dim, lineHeight = 19.sp, modifier = modifier.padding(bottom = 14.dp))

@Composable
fun SectionLabel(text: String) = Text(
    text.uppercase(), fontSize = 11.sp, color = Palette.dim2, letterSpacing = 0.6.sp,
    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp, start = 2.dp),
)

@Composable
fun Dot(color: Color) = Box(Modifier.size(9.dp).clip(CircleShape).background(color))

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null) {
    Card(modifier) {
        Column {
            Text(label.uppercase(), fontSize = 11.sp, color = Palette.dim, letterSpacing = 0.4.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                if (unit != null) Text(" $unit", fontSize = 12.sp, color = Palette.dim, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = cardShape,
        contentPadding = PaddingValues(vertical = 15.dp),
        modifier = modifier.fillMaxWidth(),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = cardShape,
        border = BorderStroke(1.dp, Palette.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.text),
        contentPadding = PaddingValues(vertical = 14.dp),
        modifier = modifier.fillMaxWidth(),
    ) { Text(text, fontSize = 15.sp) }
}

@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = cardShape,
        border = BorderStroke(1.dp, Palette.rec.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Palette.rec.copy(alpha = 0.13f), contentColor = Color(0xFFFF8F90),
        ),
        contentPadding = PaddingValues(vertical = 14.dp),
        modifier = modifier.fillMaxWidth(),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(description, fontSize = 11.sp, color = Palette.dim, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent),
            )
        }
    }
}

@Composable
fun SmallAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(cardShape)
            .background(Palette.panel)
            .border(1.dp, Palette.line, cardShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val alpha = if (enabled) 1f else 0.35f
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = Palette.text.copy(alpha = alpha), modifier = Modifier.size(22.dp))
            Text(label, fontSize = 12.sp, color = Palette.dim.copy(alpha = alpha), modifier = Modifier.padding(top = 4.dp), maxLines = 1)
        }
    }
}

/** Small rounded tile holding an icon, used in lists. */
@Composable
fun IconTile(icon: ImageVector, size: Int = 40, tint: Color = Palette.text) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Palette.panel2), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

fun formatGb(mb: Long): String = "%.1f".format(mb / 1024.0)

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
