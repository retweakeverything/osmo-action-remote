package com.retweakeverything.osmoremote

import android.app.Application
import android.util.Log
import com.retweakeverything.osmoremote.ble.BleCameraScanner
import com.retweakeverything.osmoremote.ble.BleCameraTransport
import com.retweakeverything.osmoremote.ble.BluetoothStateMonitor
import com.retweakeverything.osmoremote.core.CameraConnectionManager
import com.retweakeverything.osmoremote.core.Logger
import com.retweakeverything.osmoremote.core.workflow.WorkflowRunner
import com.retweakeverything.osmoremote.data.PrefsPairingStore
import com.retweakeverything.osmoremote.data.Settings
import com.retweakeverything.osmoremote.data.WorkflowRepository
import com.retweakeverything.osmoremote.service.CameraServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the last few hundred log lines in memory for the in-app log view. */
class RingLogger(private val capacity: Int = 300) : Logger {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    override fun log(message: String) {
        Log.d("OsmoRemote", message)
        val line = "${fmt.format(Date())}  $message"
        synchronized(this) { _lines.value = (_lines.value + line).takeLast(capacity) }
    }
}

/** Manual dependency wiring; every collaborator is constructed here and injected. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val logger = RingLogger()
    val settings = Settings(app)
    val bluetooth = BluetoothStateMonitor(app)
    val pairingStore = PrefsPairingStore(app)
    val scanner = BleCameraScanner(app, logger)
    val connection = CameraConnectionManager(
        transportFactory = { BleCameraTransport(app, logger) },
        pairingStore = pairingStore,
        scope = scope,
        logger = logger,
    ).also { it.autoReconnect = settings.autoReconnect }
    /** Workflows run off the main thread so a long or tight loop can never freeze the UI. */
    private val workflowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runner = WorkflowRunner(connection, workflowScope, logger = logger)
    val workflows = WorkflowRepository(app, Dispatchers.IO)
    val service = CameraServiceController(app, connection, runner, settings, scope)
}

class OsmoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.bluetooth.start()
        container.scope.launch { container.workflows.load() }
        container.service.start()
    }
}
