package com.retweakeverything.osmoremote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retweakeverything.osmoremote.core.workflow.RunState
import com.retweakeverything.osmoremote.core.workflow.StepTree
import com.retweakeverything.osmoremote.core.workflow.Workflow
import com.retweakeverything.osmoremote.core.workflow.describe
import com.retweakeverything.osmoremote.core.workflow.estimatedSeconds
import com.retweakeverything.osmoremote.core.workflow.formatSeconds

@Composable
fun WorkflowsScreen(
    vm: MainViewModel,
    onEdit: (Workflow) -> Unit,
    onShare: (Workflow) -> Unit,
    onImportFile: () -> Unit,
) {
    val run by vm.runState.collectAsStateWithLifecycle()
    val mine by vm.userWorkflows.collectAsStateWithLifecycle()
    var paste by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Title("Workflows")
        Subtitle("Sequences the phone runs while connected. Start from a template or stack your own blocks.")

        RunPanel(run, vm)

        SectionLabel("Templates")
        vm.templates.forEach { wf ->
            WorkflowCard(wf, onRun = { vm.run(wf) }, menu = listOf(
                "Copy & edit" to { onEdit(vm.newDraft(wf)) },
                "Share" to { onShare(wf) },
            ))
        }

        SectionLabel("My workflows")
        if (mine.isEmpty()) Text("Nothing saved yet.", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        mine.forEach { wf ->
            WorkflowCard(wf, onRun = { vm.run(wf) }, menu = listOf(
                "Edit" to { onEdit(wf) },
                "Duplicate" to { onEdit(vm.newDraft(wf)) },
                "Share" to { onShare(wf) },
                "Delete" to { vm.delete(wf) },
            ))
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton("Build new workflow") { onEdit(vm.newDraft()) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Import file", Modifier.weight(1f), onClick = onImportFile)
            GhostButton("Paste JSON", Modifier.weight(1f)) { paste = true }
        }
    }

    if (paste) PasteDialog(onDismiss = { paste = false }) { if (vm.importJson(it)) paste = false }
}

@Composable
private fun RunPanel(run: RunState, vm: MainViewModel) {
    when (run) {
        RunState.Idle -> Unit
        is RunState.Active -> Card(Modifier.fillMaxWidth().padding(bottom = 14.dp), borderColor = Palette.accent) {
            Column {
                val tag = when {
                    run.waitingForCamera -> "Waiting for camera to reconnect"
                    run.paused -> "Paused"
                    else -> "Running"
                }
                Text("$tag · ${run.workflow.name}".uppercase(), fontSize = 11.sp, color = if (run.waitingForCamera) Palette.warn else Palette.accent, letterSpacing = 0.5.sp)
                Text(run.current, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
                val total = StepTree.countBlocks(run.workflow.steps)
                if (run.loops.isNotEmpty()) Text("Loop ${run.loops.joinToString(" › ")}", color = Palette.dim, fontSize = 13.sp)
                run.countdown?.let { Text("${formatSeconds(it)} left in this step", color = Palette.dim, fontSize = 13.sp) }
                Text("${run.stepsDone} steps done · ${total} blocks in workflow", color = Palette.dim2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (!run.paused && !run.waitingForCamera) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp), color = Palette.accent, trackColor = Palette.bg)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (run.paused) GhostButton("Resume", Modifier.weight(1f)) { vm.resume() }
                    else GhostButton("Pause", Modifier.weight(1f)) { vm.pause() }
                    DangerButton("Stop", Modifier.weight(1f)) { vm.stopRun() }
                }
                Text(
                    "Pause freezes the timeline; the camera keeps doing what it was doing. Stop ends any recording this run started.",
                    color = Palette.dim2, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        is RunState.Finished -> ResultCard("Finished · ${run.workflow.name}", "${run.stepsDone} steps completed.", Palette.ok, vm)
        is RunState.Stopped -> ResultCard("Stopped · ${run.workflow.name}", "Stopped after ${run.stepsDone} steps.", Palette.warn, vm)
        is RunState.Failed -> ResultCard("Failed · ${run.workflow.name}", run.reason, Palette.rec, vm)
    }
}

@Composable
private fun ResultCard(title: String, detail: String, color: androidx.compose.ui.graphics.Color, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp), borderColor = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = color)
                Text(detail, color = Palette.dim, fontSize = 12.sp)
            }
            TextButton(onClick = { vm.dismissRun() }) { Text("Dismiss") }
        }
    }
}

@Composable
private fun WorkflowCard(wf: Workflow, onRun: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(wf.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val est = wf.steps.estimatedSeconds()?.let { "≈ ${formatSeconds(it.toInt())}" } ?: "runs until stopped"
                Text(
                    "${StepTree.countBlocks(wf.steps)} blocks · $est",
                    color = Palette.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onRun) { Icon(AppIcons.run, contentDescription = "Run", tint = Palette.accent) }
            Column {
                IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = Palette.dim) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    menu.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
                }
            }
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import workflow") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, minLines = 4, maxLines = 8,
                placeholder = { Text("Paste shared workflow JSON") }, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
