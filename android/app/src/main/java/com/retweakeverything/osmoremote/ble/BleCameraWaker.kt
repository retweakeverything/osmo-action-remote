package com.retweakeverything.osmoremote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import com.retweakeverything.osmoremote.core.CameraDevice
import com.retweakeverything.osmoremote.core.CameraWaker
import com.retweakeverything.osmoremote.core.CommandException
import com.retweakeverything.osmoremote.core.Logger
import com.retweakeverything.osmoremote.protocol.WakeAdvert
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends DJI's wake advertisement from the phone's own Bluetooth. Same payload
 * as the ESP32 route. Android picks the advertiser address, and normal apps
 * get a random one; the Action 5 Pro only wakes for a public address, so on
 * most phones the camera ignores this. It is sent anyway: a phone whose stack
 * advertises from its public address would work unchanged.
 *
 * Callers must hold BLUETOOTH_ADVERTISE.
 */
@SuppressLint("MissingPermission")
class BleCameraWaker(
    private val context: Context,
    private val logger: Logger,
) : CameraWaker {

    override suspend fun wake(device: CameraDevice) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw CommandException("Bluetooth not available")
        val advertiser = adapter.bluetoothLeAdvertiser ?: throw CommandException("this phone cannot advertise over Bluetooth")
        val payload = WakeAdvert.manufacturerPayload(device.address)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(WakeAdvert.COMPANY_ID, payload)
            .build()
        // Connectable: the camera filters on ADV_IND.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val started = CompletableDeferred<Int?>()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { started.complete(null) }
            override fun onStartFailure(errorCode: Int) { started.complete(errorCode) }
        }
        logger.log("wake: phone advertising %s".format(WakeAdvert.advertisingData(device.address).toHex()))
        advertiser.startAdvertising(settings, data, callback)
        try {
            val error = withTimeoutOrNull(2_000) { started.await() }
            if (error != null) throw CommandException("wake broadcast failed (error $error)")
            delay(WakeAdvert.DURATION_MS)
        } finally {
            runCatching { advertiser.stopAdvertising(callback) }
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
