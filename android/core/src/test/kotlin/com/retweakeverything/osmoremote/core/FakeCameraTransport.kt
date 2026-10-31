package com.retweakeverything.osmoremote.core

import com.retweakeverything.osmoremote.protocol.CmdType
import com.retweakeverything.osmoremote.protocol.Command
import com.retweakeverything.osmoremote.protocol.FrameAssembler
import com.retweakeverything.osmoremote.protocol.RsdkFrame
import com.retweakeverything.osmoremote.protocol.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Simulates the Action 5 Pro's observed behavior at the byte level: replies
 * to the connection request, then keeps resending its own ALLOW request
 * until acked, then streams status pushes once subscribed. Notifications are
 * split into 20-byte chunks and interleaved with DUML telemetry.
 */
class FakeCameraTransport(
    private val scope: CoroutineScope,
    var allowConnection: Boolean = true,
    var confirmDelayMs: Long = 200,
) : CameraTransport {
    private val _link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val linkState: StateFlow<LinkState> = _link
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    override val incoming: SharedFlow<ByteArray> = _incoming

    private val fromApp = FrameAssembler()
    val received = mutableListOf<RsdkFrame>()
    var acksReceived = 0
    var cameraRequestsSent = 0
    var capturing = false
    var modeCode = 0x01
    var sleeping = false
    private var acked = false
    private var jobs = mutableListOf<Job>()

    private val duml = "552f046305254203000d0200fb0f00003cfeffff00000000000000004001004c000000000000200000000000019ed7".hexToBytes()

    override suspend fun connect(device: CameraDevice) {
        _link.value = LinkState.Connecting
        acked = false
        delay(10)
        _link.value = LinkState.Connected
    }

    override suspend fun write(bytes: ByteArray) {
        check(_link.value == LinkState.Connected) { "write while not connected" }
        fromApp.feed(bytes).forEach { handle(it) }
    }

    override suspend fun disconnect() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        _link.value = LinkState.Disconnected
    }

    /** Simulates the camera going out of range. */
    fun dropLink() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        _link.value = LinkState.Disconnected
    }

    private fun handle(f: RsdkFrame) {
        received += f
        when {
            f.command == Command.CONNECTION && f.isResponse -> { acksReceived++; acked = true }
            f.command == Command.CONNECTION -> {
                reply(f, "44ff00000000000000")
                jobs += scope.launch {
                    delay(confirmDelayMs)
                    val result = if (allowConnection) "00" else "01"
                    val req = "44ff000000000000000000000000000000000000000000000000" + "02" + result + "000000000000"
                    var seq = 7000
                    while (isActive && !acked) {
                        cameraRequestsSent++
                        notify(RsdkFrame(0x00, 0x19, seq++, CmdType.RESPONSE_REQUIRED, payload = req.hexToBytes()))
                        delay(50)
                    }
                }
            }
            f.command == Command.STATUS_SUBSCRIBE -> {
                reply(f, "0000000000")
                jobs += scope.launch {
                    while (isActive) {
                        notify(statusFrame())
                        emitChunked(duml)
                        delay(500)
                    }
                }
            }
            f.command == Command.RECORD_CONTROL -> {
                val start = f.payload[4].toInt() == 0
                capturing = start
                reply(f, "00")
                notify(statusFrame())
            }
            f.command == Command.MODE_SWITCH -> {
                modeCode = f.payload[4].toInt() and 0xFF
                reply(f, "00")
                notify(statusFrame())
            }
            f.command == Command.POWER_MODE -> {
                sleeping = f.payload[0].toInt() == 3
                reply(f, "00")
                notify(statusFrame())
            }
            else -> reply(f, "00")
        }
    }

    /** What the camera does when it hears the wake advertisement. */
    fun hearWakeAdvert() {
        sleeping = false
        notify(statusFrame())
    }

    private fun reply(to: RsdkFrame, payloadHex: String) =
        notify(RsdkFrame(to.cmdSet, to.cmdId, to.seq, CmdType.FRAME_RESPONSE, payload = payloadHex.hexToBytes()))

    private fun statusFrame(): RsdkFrame {
        val p = ByteArray(38)
        p[0] = modeCode.toByte()
        p[1] = (if (capturing) 0x03 else 0x01).toByte()
        p[28] = (if (sleeping) 3 else 0).toByte()
        p[37] = 63
        return RsdkFrame(0x1D, 0x02, 0, CmdType.RESPONSE_NONE, payload = p)
    }

    private fun notify(frame: RsdkFrame) = emitChunked(frame.encode())

    private fun emitChunked(bytes: ByteArray) {
        bytes.toList().chunked(20).forEach { check(_incoming.tryEmit(it.toByteArray())) }
    }
}

class InMemoryPairingStore : PairingStore {
    val paired = mutableSetOf<String>()
    var last: CameraDevice? = null
    override fun isPaired(address: String) = address in paired
    override fun markPaired(device: CameraDevice) { paired += device.address; last = device }
    override fun forget(address: String) { paired -= address }
    override fun lastDevice() = last
}

/** A route over one fake camera; [wakes] decides whether the advertisement reaches it. */
class FakeRoute(
    private val transport: () -> CameraTransport,
    var wakes: (CameraDevice) -> Unit = {},
    override val id: String = "fake",
) : CameraRoute {
    var wakeCalls = 0
    override val scanner: CameraScanner = object : CameraScanner {
        override fun scan() = kotlinx.coroutines.flow.emptyFlow<List<DiscoveredCamera>>()
    }
    override val waker: CameraWaker = object : CameraWaker {
        override suspend fun wake(device: CameraDevice) { wakeCalls++; wakes(device) }
    }
    override fun newTransport(): CameraTransport = transport()
}
