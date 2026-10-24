package com.retweakeverything.osmoremote.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tracks whether the Bluetooth adapter is on, so the UI can ask the user to enable it. */
class BluetoothStateMonitor(private val context: Context) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isAvailable: Boolean get() = adapter != null

    private val _enabled = MutableStateFlow(adapter?.isEnabled == true)
    val enabled: StateFlow<Boolean> = _enabled

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            _enabled.value = state == BluetoothAdapter.STATE_ON
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        _enabled.value = adapter?.isEnabled == true
    }
}
