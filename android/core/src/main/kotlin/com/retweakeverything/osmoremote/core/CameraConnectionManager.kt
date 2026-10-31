package com.retweakeverything.osmoremote.core

import com.retweakeverything.osmoremote.protocol.CameraMode
import com.retweakeverything.osmoremote.protocol.CameraStatus
import com.retweakeverything.osmoremote.protocol.Commands
import com.retweakeverything.osmoremote.protocol.KeyCode
import com.retweakeverything.osmoremote.protocol.RsdkFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

sealed interface ConnectionState {
    val device: CameraDevice?

    data object Idle : ConnectionState { override val device: CameraDevice? = null }
    data class Connecting(override val device: CameraDevice) : ConnectionState
    data class AwaitingConfirmation(override val device: CameraDevice, val code: Int) : ConnectionState
    data class Ready(override val device: CameraDevice, val model: CameraModel) : ConnectionState
    data class Reconnecting(override val device: CameraDevice, val attempt: Int, val reason: String) : ConnectionState
    data class Failed(override val device: CameraDevice?, val reason: String) : ConnectionState
}

/**
 * Owns the lifetime of the connection to one camera: link + handshake, then
 * watches the link and reconnects with backoff when it drops unexpectedly.
 * Also implements [CameraActions] on top of the current session.
 */
class CameraConnectionManager(
    /** The route in use; asked for a fresh transport on every connection attempt. */
    private val route: () -> CameraRoute,
    private val pairingStore: PairingStore,
    private val scope: CoroutineScope,
    private val timeouts: SessionTimeouts = SessionTimeouts(),
    private val reconnectDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000),
    private val codeGenerator: () -> Int = { Random.nextInt(1000, 10000) },
    private val logger: Logger = Logger.NONE,
    private val wakeConfirmMs: Long = 10_000,
) : CameraActions {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    private val _status = MutableStateFlow<CameraStatus?>(null)
    override val status: StateFlow<CameraStatus?> = _status

    override val ready: StateFlow<Boolean> =
        _state.map { it is ConnectionState.Ready }.stateIn(scope, SharingStarted.Eagerly, false)

    @Volatile var autoReconnect: Boolean = true

    private var job: Job? = null
    private var session: CameraSession? = null
    private var sessionScope: CoroutineScope? = null
    private var transport: CameraTransport? = null

    fun connect(device: CameraDevice) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            run(device)
        }
    }

    suspend fun disconnect() {
        job?.cancelAndJoin()
        job = null
        teardown()
        _status.value = null
        _state.value = ConnectionState.Idle
    }

    suspend fun forget(device: CameraDevice) {
        if (_state.value.device?.address == device.address) disconnect()
        pairingStore.forget(device.address)
    }

    private suspend fun run(device: CameraDevice) {
        var attempt = 0
        while (true) {
            val outcome = try {
                _state.value = if (attempt == 0) ConnectionState.Connecting(device)
                else ConnectionState.Reconnecting(device, attempt, (_state.value as? ConnectionState.Reconnecting)?.reason ?: "link lost")
                connectOnce(device)
                attempt = 0
                awaitLinkLoss()
            } catch (e: CancellationException) {
                teardown()
                throw e
            } catch (e: HandshakeException) {
                if (!e.retryable) {
                    teardown()
                    _state.value = ConnectionState.Failed(device, e.message ?: "handshake failed")
                    return
                }
                e.message ?: "handshake failed"
            } catch (e: Exception) {
                e.message ?: e::class.simpleName ?: "connection error"
            }
            teardown()
            if (!autoReconnect) {
                _state.value = ConnectionState.Failed(device, outcome)
                return
            }
            attempt++
            logger.log("connection lost ($outcome), reconnect attempt $attempt")
            _state.value = ConnectionState.Reconnecting(device, attempt, outcome)
            delay(reconnectDelaysMs[(attempt - 1).coerceAtMost(reconnectDelaysMs.lastIndex)])
        }
    }

    private suspend fun connectOnce(device: CameraDevice) {
        val t = route().newTransport().also { transport = it }
        t.connect(device)
        // Child of the running connection job, so cancelling the job also stops the session.
        val parent = currentCoroutineContext()[Job]
        val sScope = CoroutineScope(currentCoroutineContext() + SupervisorJob(parent)).also { sessionScope = it }
        val s = CameraSession(t, sScope, timeouts, logger)
        session = s
        s.start()
        val firstPairing = !pairingStore.isPaired(device.address)
        val code = codeGenerator()
        val deviceId = s.handshake(firstPairing, code) {
            _state.value = ConnectionState.AwaitingConfirmation(device, code)
        }
        pairingStore.markPaired(device)
        sScope.launch { s.status.collect { if (it != null) _status.value = it } }
        val model = CameraModel.fromDeviceId(deviceId).takeIf { it != CameraModel.UNKNOWN } ?: device.model
        _state.value = ConnectionState.Ready(device, model)
    }

    private suspend fun awaitLinkLoss(): String {
        val t = transport ?: return "no transport"
        val lost = t.linkState.first { it is LinkState.Disconnected || it is LinkState.Failed }
        return (lost as? LinkState.Failed)?.reason ?: "link lost"
    }

    private suspend fun teardown() {
        session?.close()
        session = null
        sessionScope?.cancel()
        sessionScope = null
        transport?.let { runCatching { it.disconnect() } }
        transport = null
    }

    // ---- CameraActions ---------------------------------------------------

    private fun requireSession(): CameraSession =
        session?.takeIf { _state.value is ConnectionState.Ready } ?: throw CameraUnavailableException()

    private fun requireAwake(): CameraSession {
        val s = requireSession()
        if (status.value?.sleeping == true) throw CommandException("camera is asleep: wake it first")
        return s
    }

    private suspend fun ack(build: (CameraSession) -> RsdkFrame) {
        val s = requireAwake()
        val reply = try {
            s.sendAwait(build(s))
        } catch (e: CommandException) {
            if (_state.value !is ConnectionState.Ready) throw CameraUnavailableException(e.message ?: "")
            throw e
        }
        val code = Commands.returnCode(reply)
        if (code != null && code != 0) throw CommandException("camera returned error 0x%02x".format(code))
    }

    override suspend fun startRecording() {
        ack { Commands.recordControl(it.next(), start = true, deviceId = it.deviceId) }
        awaitStatus(3_000) { it.isCapturing }
    }

    override suspend fun stopRecording() {
        ack { Commands.recordControl(it.next(), start = false, deviceId = it.deviceId) }
        awaitStatus(3_000) { !it.isCapturing }
    }

    override suspend fun takePhoto() {
        ack { Commands.recordControl(it.next(), start = true, deviceId = it.deviceId) }
        awaitStatus(3_000) { !it.isCapturing }
    }

    override suspend fun setMode(mode: CameraMode) {
        ack { Commands.modeSwitch(it.next(), mode, it.deviceId) }
        awaitStatus(4_000) { it.modeCode == mode.code }
    }

    override suspend fun highlight() {
        val s = requireAwake()
        s.sendOptional(Commands.keyReport(s.next(), KeyCode.QS))
    }

    override suspend fun sleep() {
        val s = requireSession()
        s.sendOptional(Commands.powerMode(s.next(), sleep = true), timeoutMs = 2_000)
    }

    override suspend fun wake() {
        val device = _state.value.device ?: throw CameraUnavailableException()
        if (_state.value is ConnectionState.Ready && status.value?.sleeping == false) return
        route().waker.wake(device)
        // The camera answers on the same link (or after a reconnect) with an awake status push.
        withTimeoutOrNull(wakeConfirmMs) { status.first { it != null && !it.sleeping } }
            ?: throw CommandException("camera did not wake up")
    }

    /** Best effort: waits for the status push to reflect a change, never throws. */
    private suspend fun awaitStatus(timeoutMs: Long, predicate: (CameraStatus) -> Boolean) {
        withTimeoutOrNull(timeoutMs) { status.first { it != null && predicate(it) } }
    }
}
