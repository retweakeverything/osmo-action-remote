package com.retweakeverything.osmoremote.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retweakeverything.osmoremote.core.workflow.Step
import com.retweakeverything.osmoremote.core.workflow.StepTree
import com.retweakeverything.osmoremote.core.workflow.Workflow
import com.retweakeverything.osmoremote.core.workflow.describe
import com.retweakeverything.osmoremote.core.workflow.estimatedSeconds
import com.retweakeverything.osmoremote.core.workflow.formatSeconds
import com.retweakeverything.osmoremote.protocol.CameraMode

/** Where a parameter dialog's result goes: replace an existing block or insert a new one. */
private sealed interface EditTarget {
    data class Existing(val path: List<Int>) : EditTarget
    data class New(val parent: List<Int>) : EditTarget
}

/** Actions a block row can trigger, addressed by tree path. */
private class TreeActions(
    val move: (List<Int>, Int) -> Unit,
    val edit: (List<Int>, Step) -> Unit,
    val delete: (List<Int>) -> Unit,
    val addInto: (List<Int>) -> Unit,
)

/**
 * Free-form block builder. Repeat blocks show their contents inline and
 * indented, with their own "Add block inside" button, so nesting is visible.
 * The user owns the logic: we never block a save or rewrite a sequence.
 */
@Composable
fun EditorScreen(initial: Workflow, onSave: (Workflow) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var steps by remember { mutableStateOf(initial.steps) }
    /** Parent path the palette inserts into; null = palette closed. */
    var paletteParent by remember { mutableStateOf<List<Int>?>(null) }
    var editing by remember { mutableStateOf<Pair<EditTarget, Step>?>(null) }

    BackHandler(onBack = onClose)
    val save = { onSave(initial.copy(name = name.ifBlank { "Untitled" }, steps = steps)) }

    val actions = TreeActions(
        move = { path, delta -> steps = StepTree.move(steps, path, delta) },
        edit = { path, step -> editing = EditTarget.Existing(path) to step },
        delete = { path -> steps = StepTree.remove(steps, path) },
        addInto = { parent -> paletteParent = parent },
    )

    Column(Modifier.fillMaxSize().background(Palette.bg).verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = save) { Text("Save", fontWeight = FontWeight.SemiBold) }
        }
        Title("Build workflow")
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp))

        StepList(steps, parent = emptyList(), actions = actions)
        AddRow("Add block", onClick = { paletteParent = emptyList() })

        if (steps.isNotEmpty()) {
            val est = steps.estimatedSeconds()?.let { "about ${formatSeconds(it.toInt())}" } ?: "until you stop it"
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Text("This will: ${narrate(steps)}. Runs $est.", color = Palette.dim, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = Palette.warn, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Blocks run exactly as ordered. Sequencing them sensibly is up to you, for example set Photo mode before Take photo.",
                color = Palette.warn, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Save workflow", onClick = save)
    }

    paletteParent?.let { parent ->
        BlockPalette(onDismiss = { paletteParent = null }) { picked ->
            paletteParent = null
            if (picked.hasParams()) editing = EditTarget.New(parent) to picked
            else steps = StepTree.insert(steps, parent, picked)
        }
    }

    editing?.let { (target, step) ->
        StepParamsDialog(step, onDismiss = { editing = null }) { updated ->
            editing = null
            when (target) {
                is EditTarget.Existing -> steps = StepTree.replace(steps, target.path, updated)
                is EditTarget.New -> {
                    val newIndex = childrenAt(steps, target.parent).size
                    steps = StepTree.insert(steps, target.parent, updated)
                    // A new Repeat is empty: go straight to choosing what goes inside it.
                    if (updated is Step.Repeat) paletteParent = target.parent + newIndex
                }
            }
        }
    }
}

private fun childrenAt(steps: List<Step>, parent: List<Int>): List<Step> =
    if (parent.isEmpty()) steps else (StepTree.get(steps, parent) as Step.Repeat).steps

/** Plain-English narration including what is inside each Repeat. */
private fun narrate(steps: List<Step>): String = steps.joinToString(" → ") { step ->
    if (step is Step.Repeat) {
        val head = step.times?.let { "repeat $it×" } ?: "repeat until stopped"
        "$head [${if (step.steps.isEmpty()) "nothing" else narrate(step.steps)}]"
    } else {
        step.describe().replaceFirstChar { it.lowercase() }
    }
}

@Composable
private fun StepList(list: List<Step>, parent: List<Int>, actions: TreeActions) {
    list.forEachIndexed { i, step ->
        val path = parent + i
        BlockRow(step, canUp = i > 0, canDown = i < list.lastIndex, path = path, actions = actions)
        if (step is Step.Repeat) {
            val line = Palette.accent.copy(alpha = 0.5f)
            Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, bottom = 6.dp)
                    .drawBehind { drawLine(line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2.dp.toPx()) }
                    .padding(start = 12.dp),
            ) {
                if (step.steps.isEmpty()) {
                    Text("Nothing inside yet. Add the blocks you want repeated.", color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                StepList(step.steps, path, actions)
                AddRow("Add block inside repeat", compact = true) { actions.addInto(path) }
            }
        }
    }
}

@Composable
private fun BlockRow(step: Step, canUp: Boolean, canDown: Boolean, path: List<Int>, actions: TreeActions) {
    val loop = step is Step.Repeat
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape)
            .background(if (loop) Palette.accent.copy(alpha = 0.07f) else Palette.panel)
            .border(1.dp, if (loop) Palette.accent.copy(alpha = 0.5f) else Palette.line, shape)
            .clickable(enabled = step.hasParams()) { actions.edit(path, step) }
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(AppIcons.step(step), size = 30, tint = if (loop) Palette.accent else Palette.text)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (step is Step.Repeat) repeatTitle(step) else step.describe(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        RowIcon(Icons.Rounded.KeyboardArrowUp, "Move up", canUp) { actions.move(path, -1) }
        RowIcon(Icons.Rounded.KeyboardArrowDown, "Move down", canDown) { actions.move(path, +1) }
        if (step.hasParams()) RowIcon(Icons.Rounded.Edit, "Edit", true) { actions.edit(path, step) }
        RowIcon(Icons.Rounded.Close, "Delete", true) { actions.delete(path) }
    }
}

private fun repeatTitle(step: Step.Repeat): String = step.times?.let { "Repeat $it times" } ?: "Repeat until stopped"

@Composable
private fun RowIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = description, tint = if (enabled) Palette.dim else Palette.dim2.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AddRow(label: String, compact: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = if (compact) 6.dp else 12.dp).clip(shape)
            .border(1.5.dp, Palette.line, shape)
            .clickable(onClick = onClick)
            .padding(vertical = if (compact) 9.dp else 13.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = Palette.dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Palette.dim, fontSize = if (compact) 13.sp else 14.sp)
    }
}

private data class PaletteItem(val step: Step, val title: String, val hint: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockPalette(onDismiss: () -> Unit, onPick: (Step) -> Unit) {
    val items = listOf(
        PaletteItem(Step.SetMode(CameraMode.VIDEO), "Set mode", "Video, Photo, Timelapse…"),
        PaletteItem(Step.TakePhoto(), "Take photo", "1 or N shots"),
        PaletteItem(Step.RecordFor(10), "Record for…", "start, wait, stop"),
        PaletteItem(Step.StartRecording, "Start recording", "leave running"),
        PaletteItem(Step.StopRecording, "Stop recording", ""),
        PaletteItem(Step.Highlight, "Highlight", "tag while recording"),
        PaletteItem(Step.Wait(10), "Wait", "N seconds"),
        PaletteItem(Step.Repeat(5, emptyList()), "Repeat", "N times or until stop"),
        PaletteItem(Step.Sleep, "Sleep camera", "saves battery"),
        PaletteItem(Step.Wake, "Wake camera", "needs a public-address radio"),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.panel2) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Add a block", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Subtitle("Building blocks. Combine them however you like.")
            LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(430.dp)) {
                items(items) { item ->
                    Card(onClick = { onPick(item.step) }) {
                        Column {
                            Icon(AppIcons.step(item.step), contentDescription = null, tint = Palette.accent)
                            Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                            if (item.hint.isNotEmpty()) Text(item.hint, fontSize = 11.sp, color = Palette.dim)
                        }
                    }
                }
            }
        }
    }
}

private fun Step.hasParams(): Boolean = this is Step.SetMode || this is Step.TakePhoto || this is Step.RecordFor ||
    this is Step.Wait || this is Step.Repeat

@Composable
private fun StepParamsDialog(step: Step, onDismiss: () -> Unit, onDone: (Step) -> Unit) {
    var mode by remember { mutableStateOf((step as? Step.SetMode)?.mode ?: CameraMode.VIDEO) }
    var a by remember {
        mutableStateOf(
            when (step) {
                is Step.TakePhoto -> step.count.toString()
                is Step.RecordFor -> step.seconds.toString()
                is Step.Wait -> step.seconds.toString()
                is Step.Repeat -> (step.times ?: 5).toString()
                else -> ""
            },
        )
    }
    var b by remember { mutableStateOf((step as? Step.TakePhoto)?.intervalSeconds?.toString() ?: "2") }
    var forever by remember { mutableStateOf(step is Step.Repeat && step.times == null) }

    val n = a.toIntOrNull()
    val valid = when (step) {
        is Step.SetMode -> true
        is Step.TakePhoto -> (n ?: 0) >= 1 && (b.toIntOrNull() ?: -1) >= 0
        is Step.Repeat -> forever || (n ?: 0) >= 1
        else -> (n ?: 0) >= 1
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle(step)) },
        text = {
            Column {
                when (step) {
                    is Step.SetMode -> CameraMode.selectable.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { mode = m }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == m, onClick = { mode = m })
                            Icon(AppIcons.mode(m), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(m.label)
                        }
                    }
                    is Step.TakePhoto -> {
                        NumberField("Number of photos", a) { a = it }
                        NumberField("Seconds between photos", b) { b = it }
                    }
                    is Step.RecordFor -> NumberField("Record for (seconds)", a) { a = it }
                    is Step.Wait -> NumberField("Wait (seconds)", a) { a = it }
                    is Step.Repeat -> {
                        Text("You will add the blocks to repeat next.", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { forever = !forever }) {
                            Checkbox(checked = forever, onCheckedChange = { forever = it })
                            Text("Repeat until I stop the workflow")
                        }
                        if (!forever) NumberField("Times", a) { a = it }
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onDone(
                    when (step) {
                        is Step.SetMode -> Step.SetMode(mode)
                        is Step.TakePhoto -> Step.TakePhoto(n!!, b.toInt())
                        is Step.RecordFor -> Step.RecordFor(n!!)
                        is Step.Wait -> Step.Wait(n!!)
                        is Step.Repeat -> step.copy(times = if (forever) null else n)
                        else -> step
                    },
                )
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun dialogTitle(step: Step): String = when (step) {
    is Step.SetMode -> "Set mode"
    is Step.TakePhoto -> "Take photo"
    is Step.RecordFor -> "Record for"
    is Step.Wait -> "Wait"
    is Step.Repeat -> "Repeat"
    else -> step.describe()
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}
