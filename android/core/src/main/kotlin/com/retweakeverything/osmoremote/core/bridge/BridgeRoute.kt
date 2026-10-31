package com.retweakeverything.osmoremote.core.bridge

import com.retweakeverything.osmoremote.core.CameraDevice
import com.retweakeverything.osmoremote.core.CameraRoute
import com.retweakeverything.osmoremote.core.CameraScanner
import com.retweakeverything.osmoremote.core.CameraTransport
import com.retweakeverything.osmoremote.core.CameraWaker
import com.retweakeverything.osmoremote.core.DiscoveredCamera
import com.retweakeverything.osmoremote.core.LinkState
import com.retweakeverything.osmoremote.core.TransportException
import com.retweakeverything.osmoremote.protocol.WakeAdvert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Camera reached through an ESP32 running firmware/osmo-bridge on USB. */
class BridgeRoute(private val client: BridgeClient) : CameraRoute {
    override val id: String = "usb"
    override val scanner: CameraScanner = BridgeScanner(client)
    override val waker: CameraWaker = BridgeWaker(client)
    override fun newTransport(): CameraTransport = BridgeTransport(client)
}

class BridgeTransport(private val client: BridgeClient) : CameraTransport {
    override val linkState: StateFlow<LinkState> = client.cameraLink
    override val incoming: SharedFlow<ByteArray> = client.rx

    override suspend fun connect(device: CameraDevice) {
        client.open()
        client.connectCamera(device.address)
    }

    override suspend fun write(bytes: ByteArray) {
        if (client.cameraLink.value !is LinkState.Connected) throw TransportException("not connected")
        client.send(bytes)
    }

    override suspend fun disconnect() {
        client.disconnectCamera()
    }
}

class BridgeWaker(private val client: BridgeClient) : CameraWaker {
    override suspend fun wake(device: CameraDevice) {
        client.open()
        client.wake(device.address, WakeAdvert.DURATION_MS)
    }
}

/** Repeats short ESP32 scans and emits the cumulative list of DJI cameras seen. */
class BridgeScanner(private val client: BridgeClient, private val roundSeconds: Int = 4) : CameraScanner {
    override fun scan(): Flow<List<DiscoveredCamera>> = channelFlow {
        client.open()
        val found = linkedMapOf<String, DiscoveredCamera>()
        launch {
            client.lines.collect { line ->
                parseScanResult(line)?.let {
                    found[it.device.address] = it
                    send(found.values.sortedByDescending { c -> c.rssi })
                }
            }
        }
        send(emptyList())
        while (true) {
            client.request("SCAN $roundSeconds", listOf("SCAN_DONE"), roundSeconds * 1_000L + 4_000)
        }
    }

    companion object {
        /** "SCAN_RESULT <addr> <type> <rssi> <mfr-hex|-> <name>" */
        fun parseScanResult(line: String): DiscoveredCamera? {
            if (!line.startsWith("SCAN_RESULT ")) return null
            val p = line.split(' ', limit = 6)
            if (p.size < 6) return null
            val rssi = p[3].toIntOrNull() ?: return null
            val name = p[5].trim().takeIf { it != "-" } ?: p[1].uppercase()
            return DiscoveredCamera(CameraDevice(p[1].uppercase(), name), rssi)
        }
    }
}
