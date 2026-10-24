package com.retweakeverything.osmoremote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.retweakeverything.osmoremote.core.CameraDevice
import com.retweakeverything.osmoremote.core.CameraScanner
import com.retweakeverything.osmoremote.core.DiscoveredCamera
import com.retweakeverything.osmoremote.core.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Scans for DJI cameras. A device matches when its advertisement carries
 * DJI's remote-protocol marker (manufacturer bytes AA 08 .. FA, per DJI's
 * demo) or its name contains "Osmo".
 */
@SuppressLint("MissingPermission")
class BleCameraScanner(private val context: Context, private val logger: Logger) : CameraScanner {

    override fun scan(): Flow<List<DiscoveredCamera>> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off or unavailable"))
            return@callbackFlow
        }
        val seen = linkedMapOf<String, DiscoveredCamera>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name ?: return
                val mfr = record?.getManufacturerSpecificData(DJI_COMPANY_ID)
                val djiMarker = mfr != null && mfr.size > 2 && mfr[2] == 0xFA.toByte()
                if (!djiMarker && !name.contains("osmo", ignoreCase = true)) return
                if (result.device.address !in seen) logger.log("scan: $name ${result.device.address} mfr=${mfr?.toHexString()}")
                seen[result.device.address] = DiscoveredCamera(CameraDevice(result.device.address, name), result.rssi)
                trySend(seen.values.sortedByDescending { it.rssi })
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed (error $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        trySend(emptyList())
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        /** Manufacturer field bytes 0-1 are AA 08, i.e. company id 0x08AA little-endian. */
        const val DJI_COMPANY_ID = 0x08AA
    }
}
