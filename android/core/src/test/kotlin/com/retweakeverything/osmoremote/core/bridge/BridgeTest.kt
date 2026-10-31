package com.retweakeverything.osmoremote.core.bridge

import com.retweakeverything.osmoremote.core.CameraConnectionManager
import com.retweakeverything.osmoremote.core.CameraDevice
import com.retweakeverything.osmoremote.core.FakeCameraTransport
import com.retweakeverything.osmoremote.core.InMemoryPairingStore
import com.retweakeverything.osmoremote.core.LinkState
import com.retweakeverything.osmoremote.core.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plays the osmo-bridge firmware at the line level, with a fake camera behind
 * it, so the whole USB route can run without hardware.
 */
private class FakeBridgeFirmware(private val scope: CoroutineScope) : SerialLink {
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4096)
    override val incoming: SharedFlow<ByteArray> = _incoming
    private val _open = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _open

    val camera = FakeCameraTransport(scope)
    val commands = mutableListOf<String>()
    var answersHello = true
    private val pending = StringBuilder()

    init {
        scope.launch { camera.incoming.collect { out("RX " + it.joinToString("") { b -> "%02x".format(b) }) } }
    }

    override suspend fun open() { _open.value = true; out("READY osmo-bridge 4 aa:bb:cc:00:00:01") }
    override suspend fun close() { _open.value = false }
    fun unplug() { _open.value = false }

    override suspend fun write(bytes: ByteArray) {
        pending.append(String(bytes))
        while (true) {
            val i = pending.indexOf("\n")
            if (i < 0) break
            val line = pending.substring(0, i).trim()
            pending.delete(0, i + 1)
            if (line.isNotEmpty()) handle(line)
        }
    }

    private suspend fun handle(line: String) {
        commands += line
        val cmd = line.substringBefore(' ')
        when (cmd) {
            "HELLO" -> if (answersHello) out("HELLO osmo-bridge 4 aa:bb:cc:00:00:01")
            "SCAN" -> {
                out("SCAN_RESULT 11:22:33:44:55:66 0 -48 aa081500fa665544332211 OsmoAction5Pro0000")
                out("SCAN_DONE")
            }
            "CONNECT" -> { camera.connect(CameraDevice("11:22:33:44:55:66", "x")); out("LINK_UP 11:22:33:44:55:66 247") }
            "DISCONNECT" -> {
                val was = camera.linkState.value == LinkState.Connected
                camera.disconnect()
                out(if (was) "LINK_DOWN 534" else "LINK_DOWN 0")
            }
            "TX" -> {
                val hex = line.substringAfter(' ')
                camera.write(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
                out("OK TX")
            }
            "WAKE" -> {
                out("LOG wake advertising ... from public address")
                camera.hearWakeAdvert()
                out("WAKE_DONE")
            }
            else -> out("ERR unknown command $cmd")
        }
    }

    private fun out(line: String) { _incoming.tryEmit("$line\r\n".toByteArray()) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeTest {
    private val device = CameraDevice("11:22:33:44:55:66", "OsmoAction5Pro0000")

    @Test fun parsesScanResult() {
        val c = BridgeScanner.parseScanResult("SCAN_RESULT 04:aa:bb:cc:dd:ee 0 -53 aa08 OsmoAction5Pro0000")!!
        assertEquals("04:AA:BB:CC:DD:EE", c.device.address)
        assertEquals("OsmoAction5Pro0000", c.device.name)
        assertEquals(-53, c.rssi)
        assertEquals(null, BridgeScanner.parseScanResult("LOG hello"))
    }

    @Test fun scannerFindsCameraThroughBridge() = runTest {
        val fw = FakeBridgeFirmware(backgroundScope)
        val route = BridgeRoute(BridgeClient(fw, backgroundScope))
        val list = withTimeout(5_000) { route.scanner.scan().first { it.isNotEmpty() } }
        assertEquals("11:22:33:44:55:66", list.single().device.address)
    }

    @Test fun fullSessionOverBridge() = runTest {
        val fw = FakeBridgeFirmware(backgroundScope)
        val route = BridgeRoute(BridgeClient(fw, backgroundScope))
        val mgr = CameraConnectionManager({ route }, InMemoryPairingStore(), backgroundScope)
        mgr.connect(device)
        withTimeout(10_000) { mgr.ready.first { it } }
        assertTrue("disconnect must precede connect", fw.commands.indexOfFirst { it == "DISCONNECT" } <
            fw.commands.indexOfFirst { it.startsWith("CONNECT") })
        assertTrue(fw.commands.any { it == "CONNECT 11:22:33:44:55:66 0" })

        mgr.startRecording()
        assertTrue(mgr.status.value!!.isCapturing)
        mgr.stopRecording()

        mgr.sleep()
        withTimeout(2_000) { mgr.status.first { it?.sleeping == true } }
        mgr.wake()
        assertTrue(fw.commands.any { it == "WAKE 112233445566 3000 public" })
        assertEquals(false, mgr.status.value!!.sleeping)
        mgr.disconnect()
    }

    @Test fun missingFirmwareIsReported() = runTest {
        val fw = FakeBridgeFirmware(backgroundScope).also { it.answersHello = false }
        val client = BridgeClient(fw, backgroundScope)
        try {
            client.open()
            fail("expected TransportException")
        } catch (e: TransportException) {
            assertEquals("No osmo-bridge firmware answered on USB", e.message)
        }
    }

    @Test fun unplugFailsTheCameraLink() = runTest {
        val fw = FakeBridgeFirmware(backgroundScope)
        val client = BridgeClient(fw, backgroundScope)
        val t = BridgeTransport(client)
        t.connect(device)
        assertEquals(LinkState.Connected, t.linkState.value)
        fw.unplug()
        val s = withTimeout(2_000) { t.linkState.first { it is LinkState.Failed } }
        assertEquals(LinkState.Failed("USB bridge unplugged"), s)
    }
}
