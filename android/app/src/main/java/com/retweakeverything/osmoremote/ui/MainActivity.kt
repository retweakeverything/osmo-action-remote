package com.retweakeverything.osmoremote.ui

import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retweakeverything.osmoremote.OsmoApp
import com.retweakeverything.osmoremote.core.ConnectionState
import com.retweakeverything.osmoremote.core.workflow.RunState
import com.retweakeverything.osmoremote.core.workflow.Workflow

private enum class Tab(val icon: ImageVector, val label: String) { CONNECT(AppIcons.connect, "Connect"), REMOTE(AppIcons.remote, "Remote"), WORKFLOWS(AppIcons.workflows, "Workflows") }

private sealed interface Overlay {
    data object Manage : Overlay
    data object Log : Overlay
    data class Editor(val workflow: Workflow) : Overlay
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.Factory((application as OsmoApp).container) }

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE) // phone route's wake broadcast
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun hasBluetooth(): Boolean = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OsmoTheme { App() } }
    }

    @Composable
    private fun App() {
        var granted by remember { mutableStateOf(hasBluetooth()) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasBluetooth()
            if (!granted) vm.say("Bluetooth permission denied. The app cannot reach the camera without it.")
        }
        val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!vm.bluetoothOn.value) vm.say("Bluetooth is still off")
        }
        val requestEnable = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        val bluetoothOn by vm.bluetoothOn.collectAsStateWithLifecycle()
        val route by vm.route.collectAsStateWithLifecycle()
        // Ask once per launch, as soon as we are allowed to, and only when the route uses the phone's radio.
        var askedEnable by remember { mutableStateOf(false) }
        LaunchedEffect(granted, bluetoothOn, route) {
            if (route.needsPhoneBluetooth && granted && !bluetoothOn && !askedEnable) { askedEnable = true; requestEnable() }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
                if (text == null) vm.say("Could not read that file") else vm.importJson(text)
            }
        }

        val state by vm.connection.collectAsStateWithLifecycle()
        val run by vm.runState.collectAsStateWithLifecycle()
        var tab by remember { mutableStateOf(Tab.CONNECT) }
        var overlay by remember { mutableStateOf<Overlay?>(null) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            if (!granted) permissionLauncher.launch(requiredPermissions)
            vm.messages.collect { snackbar.showSnackbar(it) }
        }
        // Land on the remote once connected; this is what the user came for.
        LaunchedEffect(state is ConnectionState.Ready) { if (state is ConnectionState.Ready && tab == Tab.CONNECT) tab = Tab.REMOTE }

        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            Scaffold(
                containerColor = Palette.bg,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = Palette.panel) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t, onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Palette.accent, selectedTextColor = Palette.accent,
                                    indicatorColor = Palette.panel2,
                                    unselectedIconColor = Palette.dim2, unselectedTextColor = Palette.dim2,
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.padding(padding)) {
                    ConnectionPill(state, run is RunState.Active) { overlay = Overlay.Manage }
                    when (tab) {
                        Tab.CONNECT -> ConnectScreen(
                            vm, granted,
                            onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                            onEnableBluetooth = requestEnable,
                        ) { overlay = Overlay.Manage }
                        Tab.REMOTE -> RemoteScreen(vm) { tab = Tab.CONNECT }
                        Tab.WORKFLOWS -> WorkflowsScreen(
                            vm,
                            onEdit = { overlay = Overlay.Editor(it) },
                            onShare = { share(it.name, vm.exportJson(it)) },
                            onImportFile = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        )
                    }
                }
            }

            Box(Modifier.safeDrawingPadding()) {
                when (val o = overlay) {
                    Overlay.Manage -> ManageScreen(vm, onShowLog = { overlay = Overlay.Log }) { overlay = null }
                    Overlay.Log -> LogScreen(vm, onShare = { share("Osmo Remote log", it) }) { overlay = Overlay.Manage }
                    is Overlay.Editor -> EditorScreen(o.workflow, onSave = { vm.save(it); overlay = null }) { overlay = null }
                    null -> Unit
                }
            }
            (state as? ConnectionState.AwaitingConfirmation)?.let {
                Box(Modifier.safeDrawingPadding()) { PairingOverlay(it) { vm.disconnect() } }
            }
        }
    }

    private fun share(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share"))
    }
}
