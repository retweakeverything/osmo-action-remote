package com.retweakeverything.osmoremote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.retweakeverything.osmoremote.AppContainer
import com.retweakeverything.osmoremote.core.CameraDevice
import com.retweakeverything.osmoremote.core.ConnectionState
import com.retweakeverything.osmoremote.core.DiscoveredCamera
import com.retweakeverything.osmoremote.core.workflow.Templates
import com.retweakeverything.osmoremote.core.workflow.Workflow
import com.retweakeverything.osmoremote.core.workflow.WorkflowJson
import com.retweakeverything.osmoremote.protocol.CameraMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

/** Thin adapter between the UI and the app container. No protocol logic here. */
class MainViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.connection.state
    val status = c.connection.status
    val runState = c.runner.state
    val userWorkflows = c.workflows.workflows
    val templates: List<Workflow> = Templates.all
    val logs = c.logger.lines
    val bluetoothOn = c.bluetooth.enabled

    private val _discovered = MutableStateFlow<List<DiscoveredCamera>>(emptyList())
    val discovered: StateFlow<List<DiscoveredCamera>> = _discovered
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _busy = MutableStateFlow<String?>(null)
    /** Label of the camera command currently in flight, if any. */
    val busy: StateFlow<String?> = _busy

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _autoReconnect = MutableStateFlow(c.settings.autoReconnect)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect
    private val _keepAlive = MutableStateFlow(c.settings.keepAliveInBackground)
    val keepAlive: StateFlow<Boolean> = _keepAlive

    private var scanJob: Job? = null

    fun isPaired(address: String) = c.pairingStore.isPaired(address)
    fun lastDevice(): CameraDevice? = c.pairingStore.lastDevice()

    // ---- connection ----

    fun startScan() {
        if (scanJob?.isActive == true) return
        _discovered.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                c.scanner.scan()
                    .catch { e -> say(e.message ?: "scan failed") }
                    .collect { _discovered.value = it }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() { scanJob?.cancel() }

    fun connect(device: CameraDevice) {
        stopScan()
        c.connection.connect(device)
    }

    fun disconnect() = viewModelScope.launch {
        if (c.runner.isRunning) c.runner.stop()
        c.connection.disconnect()
    }

    fun forget() = viewModelScope.launch {
        val device = connection.value.device ?: c.pairingStore.lastDevice() ?: return@launch
        if (c.runner.isRunning) c.runner.stop()
        c.connection.forget(device)
        say("Forgot ${device.name}. Next connection will ask for confirmation on the camera.")
    }

    fun setAutoReconnect(on: Boolean) {
        c.settings.autoReconnect = on
        c.connection.autoReconnect = on
        _autoReconnect.value = on
    }

    fun setKeepAlive(on: Boolean) {
        c.settings.keepAliveInBackground = on
        _keepAlive.value = on
    }

    // ---- remote ----

    fun toggleRecord() = command(if (status.value?.isCapturing == true) "Stopping" else "Starting") {
        if (status.value?.isCapturing == true) c.connection.stopRecording() else c.connection.startRecording()
    }

    fun takePhoto() = command("Photo") { c.connection.takePhoto() }
    fun setMode(mode: CameraMode) = command("Switching to ${mode.label}") { c.connection.setMode(mode) }

    fun highlight() = command("Highlight") {
        c.connection.highlight()
        say("Highlight sent. The camera does not confirm highlights.")
    }

    fun sleepCamera() = command("Sleep") {
        c.connection.sleep()
        say("Camera is asleep. Press its power button to wake it.")
    }

    private fun command(label: String, block: suspend () -> Unit) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = label
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "command failed")
            } finally {
                _busy.value = null
            }
        }
    }

    // ---- workflows ----

    fun run(workflow: Workflow) {
        if (connection.value !is ConnectionState.Ready) { say("Connect to the camera first"); return }
        if (c.runner.isRunning) { say("A workflow is already running"); return }
        c.runner.start(workflow)
    }

    fun pause() = c.runner.pause()
    fun resume() = c.runner.resume()
    fun stopRun() = c.runner.stop()
    fun dismissRun() = c.runner.dismiss()

    fun newDraft(from: Workflow? = null): Workflow = Workflow(
        id = UUID.randomUUID().toString(),
        name = from?.let { if (it.template) it.name else "${it.name} copy" } ?: "My workflow",
        steps = from?.steps ?: emptyList(),
    )

    fun save(workflow: Workflow) = viewModelScope.launch {
        c.workflows.save(workflow.copy(template = false))
        say("Saved \"${workflow.name}\"")
    }

    fun delete(workflow: Workflow) = viewModelScope.launch { c.workflows.delete(workflow.id) }

    fun exportJson(workflow: Workflow): String = WorkflowJson.encode(workflow.copy(template = false))

    /** Imports a shared workflow under a fresh id so it never overwrites an existing one. */
    fun importJson(text: String): Boolean = try {
        val wf = WorkflowJson.decode(text.trim())
        save(wf.copy(id = UUID.randomUUID().toString(), template = false))
        true
    } catch (e: Exception) {
        say("Not a valid workflow: ${e.message?.take(80)}")
        false
    }

    fun say(message: String) { _messages.tryEmit(message) }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(c) as T
    }
}
