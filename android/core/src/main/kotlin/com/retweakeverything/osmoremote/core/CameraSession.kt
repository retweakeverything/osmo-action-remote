package com.retweakeverything.osmoremote.core

import com.retweakeverything.osmoremote.protocol.CameraStatus
import com.retweakeverything.osmoremote.protocol.Command
import com.retweakeverything.osmoremote.protocol.Commands
import com.retweakeverything.osmoremote.protocol.FrameAssembler
import com.retweakeverything.osmoremote.protocol.RsdkFrame
import com.retweakeverything.osmoremote.protocol.VerifyResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun interface Logger {
    fun log(message: String)

    companion object {
        val NONE = Logger { }
    }
}

/** Timing knobs, injectable so tests can shrink them. */
data class SessionTimeouts(
    val commandMs: Long = 3_000,
    val pairedHandshakeMs: Long = 10_000,
    val firstPairingMs: Long = 90_000,
    val subscribeMs: Long = 5_000,
    val cameraAckIntervalMs: Long = 300,
)

/**
 * One R SDK conversation over an already-connected [CameraTransport].
 *
 * Handshake verified on an Osmo Action 5 Pro:
 * 1. send Connection Request (verify_mode 1 on first pairing, else 0)
 * 2. camera replies, then sends its own Connection Request carrying ALLOW/REJECT
 * 3. we must ack that request with a Connection Response echoing its seq,
 *    otherwise the camera retransmits it continuously
 * 4. subscribe to status; the camera pushes 0x1D/0x02 at 2 Hz
 */
class CameraSession(
    private val transport: CameraTransport,
    private val scope: CoroutineScope,
    private val timeouts: SessionTimeouts = SessionTimeouts(),
    private val logger: Logger = Logger.NONE,
) {
    private val assembler = FrameAssembler()
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<RsdkFrame>>()
    private val writeLock = Mutex()
    private var verifyResult = CompletableDeferred<VerifyResult>()
    private var lastCameraAckAt = 0L
    private var reader: Job? = null

    private val _status = MutableStateFlow<CameraStatus?>(null)
    val status: StateFlow<CameraStatus?> = _status

    /** Learned from the camera's connection response; Action 5 Pro until known. */
    var deviceId: Long = CameraModel.ACTION_5_PRO.deviceId
        private set

    fun start() {
        // UNDISPATCHED so we are subscribed before the first write; the shared
        // flow drops anything emitted while nobody is collecting.
        reader = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.collect { chunk -> assembler.feed(chunk).forEach { onFrame(it) } }
        }
    }

    fun close() {
        reader?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    /**
     * Runs the handshake. [onAwaitingConfirmation] fires on first pairing while
     * the user confirms on the camera. Returns the camera's device id.
     */
    suspend fun handshake(firstPairing: Boolean, verifyCode: Int, onAwaitingConfirmation: () -> Unit = {}): Long {
        verifyResult = CompletableDeferred()
        val request = Commands.connectionRequest(next(), if (firstPairing) 1 else 0, verifyCode)
        val reply = sendAwait(request, timeouts.commandMs)
        if (reply.payload.size >= 4) {
            deviceId = (reply.payload[0].toLong() and 0xFF) or ((reply.payload[1].toLong() and 0xFF) shl 8) or
                ((reply.payload[2].toLong() and 0xFF) shl 16) or ((reply.payload[3].toLong() and 0xFF) shl 24)
        }
        if (firstPairing) onAwaitingConfirmation()

        val waitMs = if (firstPairing) timeouts.firstPairingMs else timeouts.pairedHandshakeMs
        val result = withTimeoutOrNull(waitMs) { verifyResult.await() }
            ?: throw HandshakeException(
                if (firstPairing) "Pairing was not confirmed on the camera" else "Camera did not finish the handshake",
                retryable = !firstPairing,
            )
        if (result == VerifyResult.REJECTED) throw HandshakeException("Camera rejected the connection", retryable = false)

        sendAwait(Commands.statusSubscribe(next()), timeouts.subscribeMs)
        logger.log("handshake complete, deviceId=0x%04x".format(deviceId))
        return deviceId
    }

    /** Sends a command and returns the camera's response frame. */
    suspend fun sendAwait(frame: RsdkFrame, timeoutMs: Long = timeouts.commandMs): RsdkFrame {
        val waiter = CompletableDeferred<RsdkFrame>()
        pending[frame.seq] = waiter
        try {
            logger.log("tx ${frame.command} seq=${frame.seq}")
            write(frame)
            val reply = withTimeout(timeoutMs) { waiter.await() }
            logger.log("rx ${reply.command} seq=${reply.seq} ret=${reply.payload.firstOrNull()?.toInt()?.and(0xFF)}")
            return reply
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.log("timeout ${frame.command} seq=${frame.seq} after ${timeoutMs}ms")
            throw CommandException("no response to ${frame.command} within ${timeoutMs}ms")
        } finally {
            pending.remove(frame.seq)
        }
    }

    /** Sends a command and waits briefly for an optional response. */
    suspend fun sendOptional(frame: RsdkFrame, timeoutMs: Long = 1_000): RsdkFrame? =
        runCatching { sendAwait(frame, timeoutMs) }.getOrNull()

    fun next(): Int = seq.incrementAndGet() and 0xFFFF

    private suspend fun write(frame: RsdkFrame) {
        writeLock.withLock { transport.write(frame.encode()) }
    }

    private fun onFrame(frame: RsdkFrame) {
        when {
            // The camera sends status pushes both unsolicited and as the subscribe reply.
            frame.command == Command.STATUS_PUSH ->
                CameraStatus.parse(frame.payload)?.let { _status.value = it }

            frame.command == Command.CONNECTION && !frame.isResponse -> onCameraConnectionRequest(frame)

            frame.isResponse -> pending[frame.seq]?.complete(frame)

            else -> logger.log("unhandled $frame")
        }
    }

    private fun onCameraConnectionRequest(frame: RsdkFrame) {
        val result = Commands.parseCameraVerifyResult(frame) ?: return
        verifyResult.complete(result)
        if (result != VerifyResult.ALLOWED) return
        // The camera may resend its request before our ack lands; ack at a bounded rate.
        val now = System.currentTimeMillis()
        if (now - lastCameraAckAt < timeouts.cameraAckIntervalMs) return
        lastCameraAckAt = now
        scope.launch { write(Commands.connectionResponse(frame.seq, deviceId)) }
    }
}

class HandshakeException(message: String, val retryable: Boolean) : Exception(message)
