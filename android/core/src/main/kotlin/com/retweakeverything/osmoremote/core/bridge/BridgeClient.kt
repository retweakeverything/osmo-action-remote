package com.retweakeverything.osmoremote.core.bridge

import com.retweakeverything.osmoremote.core.LinkState
import com.retweakeverything.osmoremote.core.Logger
import com.retweakeverything.osmoremote.core.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A byte pipe to the ESP32 (USB serial). The app module implements it with
 * Android's USB host API; tests use a fake.
 */
interface SerialLink {
    /** Opens the port. Throws [TransportException] when no bridge is plugged in or permission is refused. */
    suspend fun open()
    suspend fun write(bytes: ByteArray)
    suspend fun close()

    /** Bytes from the ESP32, in arbitrary chunks. */
    val incoming: SharedFlow<ByteArray>

    /** False once the port is closed or the device is unplugged. */
    val isOpen: StateFlow<Boolean>
}

/**
 * Speaks the osmo-bridge line protocol (firmware/osmo-bridge). The ESP32 knows
 * nothing about the camera protocol: it scans, connects, relays bytes and sends
 * the wake advertisement. One command is in flight at a time; replies are
 * matched by prefix, asynchronous lines (RX, LINK_DOWN, SCAN_RESULT, LOG) are
 * routed to their flows.
 */
class BridgeClient(
    private val link: SerialLink,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.NONE,
) {
    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 1024)
    val lines: SharedFlow<String> = _lines

    private val _rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    /** Camera notifications relayed by the ESP32. */
    val rx: SharedFlow<ByteArray> = _rx

    private val _camera = MutableStateFlow<LinkState>(LinkState.Disconnected)
    /** State of the ESP32's link to the camera (not of the USB port). */
    val cameraLink: StateFlow<LinkState> = _camera

    private val commandLock = Mutex()
    private val openLock = Mutex()
    private var reader: Job? = null
    @Volatile private var disconnectRequested = false

    /** Firmware banner from HELLO, e.g. "osmo-bridge 4 aa:bb:cc:dd:ee:ff". */
    @Volatile var banner: String? = null
        private set

    suspend fun open() = openLock.withLock {
        if (link.isOpen.value && reader?.isActive == true) return@withLock
        link.open()
        val buf = StringBuilder()
        reader?.cancel()
        reader = scope.launch {
            launch {
                link.isOpen.first { !it }
                if (_camera.value !is LinkState.Disconnected) _camera.value = LinkState.Failed("USB bridge unplugged")
            }
            link.incoming.collect { chunk ->
                for (b in chunk) {
                    val c = b.toInt().toChar()
                    if (c == '\n' || c == '\r') {
                        if (buf.isNotEmpty()) { onLine(buf.toString()); buf.setLength(0) }
                    } else if (buf.length < MAX_LINE) buf.append(c)
                }
            }
        }
        // The ESP32 may still be booting (or resetting) when the port opens.
        var hello: String? = null
        for (attempt in 1..3) {
            hello = runCatching { request("HELLO", listOf("HELLO osmo-bridge"), 1_500) }.getOrNull()
            if (hello != null) break
        }
        if (hello == null) {
            close()
            throw TransportException("No osmo-bridge firmware answered on USB")
        }
        banner = hello.removePrefix("HELLO ").trim()
        logger.log("bridge: $banner")
    }

    suspend fun close() {
        reader?.cancel()
        reader = null
        runCatching { link.close() }
        _camera.value = LinkState.Disconnected
    }

    private fun onLine(line: String) {
        when {
            line.startsWith("RX ") -> _rx.tryEmit(hexToBytes(line.substring(3)))
            line.startsWith("LINK_UP") -> _camera.value = LinkState.Connected
            line.startsWith("LINK_DOWN") -> {
                val reason = line.substringAfter(' ', "").trim()
                if (_camera.value is LinkState.Connected) {
                    _camera.value = if (disconnectRequested) LinkState.Disconnected
                    else LinkState.Failed("link lost (reason $reason)")
                }
                logger.log("bridge: $line")
            }
            line.startsWith("LOG ") || line.startsWith("ERR") || line.startsWith("READY") -> logger.log("bridge: $line")
        }
        _lines.tryEmit(line)
    }

    /**
     * Sends one command line and waits for the first line starting with one of
     * [expect]. A line starting with "ERR" fails the request.
     */
    suspend fun request(command: String, expect: List<String>, timeoutMs: Long): String = commandLock.withLock {
        coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                _lines.first { l -> l.startsWith("ERR") || expect.any { l.startsWith(it) } }
            }
            link.write("$command\n".toByteArray())
            val line = withTimeoutOrNull(timeoutMs) { reply.await() }
            if (line == null) {
                reply.cancel()
                throw TransportException("bridge did not answer ${command.substringBefore(' ')}")
            }
            if (line.startsWith("ERR")) throw TransportException("bridge: ${line.removePrefix("ERR").trim()}")
            line
        }
    }

    suspend fun connectCamera(address: String, addressType: Int = 0) {
        // Drop any previous link first: the firmware reports that LINK_DOWN
        // asynchronously, and it must not land after the new LINK_UP.
        disconnectCamera()
        disconnectRequested = false
        _camera.value = LinkState.Connecting
        try {
            request("CONNECT ${address.lowercase()} $addressType", listOf("LINK_UP"), CONNECT_TIMEOUT_MS)
        } catch (e: TransportException) {
            _camera.value = LinkState.Failed(e.message ?: "connect failed")
            throw e
        }
        _camera.value = LinkState.Connected
    }

    suspend fun disconnectCamera() {
        disconnectRequested = true
        runCatching { request("DISCONNECT", listOf("LINK_DOWN"), 5_000) }
        _camera.value = LinkState.Disconnected
    }

    suspend fun send(bytes: ByteArray) {
        request("TX " + bytesToHex(bytes), listOf("OK TX"), 3_000)
    }

    /** Wake advertisement from the ESP32's public address. The firmware builds the same payload as [com.retweakeverything.osmoremote.protocol.WakeAdvert]. */
    suspend fun wake(cameraAddress: String, durationMs: Long) {
        val id = cameraAddress.replace(":", "").lowercase()
        request("WAKE $id $durationMs public", listOf("WAKE_DONE"), durationMs + 5_000)
    }

    private companion object {
        const val MAX_LINE = 4_096
        const val CONNECT_TIMEOUT_MS = 20_000L

        fun hexToBytes(s: String): ByteArray {
            val t = s.trim()
            return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }
}
