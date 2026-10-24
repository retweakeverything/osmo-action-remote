package com.retweakeverything.osmoremote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.retweakeverything.osmoremote.core.CameraDevice
import com.retweakeverything.osmoremote.core.CameraTransport
import com.retweakeverything.osmoremote.core.LinkState
import com.retweakeverything.osmoremote.core.Logger
import com.retweakeverything.osmoremote.core.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE link to a DJI camera: service fff0, notifications on fff4, writes to
 * fff5 (write without response). One instance per connection attempt.
 *
 * Callers must hold BLUETOOTH_CONNECT; the UI requests it before connecting.
 */
@SuppressLint("MissingPermission")
class BleCameraTransport(
    private val context: Context,
    private val logger: Logger,
) : CameraTransport {
    private val _link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val linkState: StateFlow<LinkState> = _link

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    override val incoming: SharedFlow<ByteArray> = _incoming

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var setupDone = CompletableDeferred<Unit>()
    private val writeAcks = Channel<Int>(Channel.CONFLATED)
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var closing = false

    override suspend fun connect(device: CameraDevice) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw TransportException("Bluetooth not available")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is off")
        closing = false
        setupDone = CompletableDeferred()
        _link.value = LinkState.Connecting
        logger.log("ble: connecting to ${device.name} ${device.address}")
        val remote = adapter.getRemoteDevice(device.address)
        gatt = remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportException("connectGatt returned null")

        val ok = withTimeoutOrNull(SETUP_TIMEOUT_MS) { runCatching { setupDone.await() } }
        if (ok == null || ok.isFailure) {
            val reason = ok?.exceptionOrNull()?.message ?: "timed out setting up the link"
            close()
            _link.value = LinkState.Failed(reason)
            throw TransportException(reason)
        }
        _link.value = LinkState.Connected
        logger.log("ble: link ready, mtu=$mtu")
    }

    override suspend fun write(bytes: ByteArray) {
        val g = gatt ?: throw TransportException("not connected")
        val c = writeChar ?: throw TransportException("write characteristic missing")
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(bytes.size, offset + chunkSize))
            writeChunk(g, c, chunk)
            offset += chunk.size
        }
    }

    override suspend fun disconnect() {
        closing = true
        gatt?.disconnect()
        close()
        _link.value = LinkState.Disconnected
    }

    private suspend fun writeChunk(g: BluetoothGatt, c: BluetoothGattCharacteristic, chunk: ByteArray) {
        repeat(WRITE_RETRIES) {
            while (writeAcks.tryReceive().isSuccess) { /* drain stale acks */ }
            if (startWrite(g, c, chunk)) {
                // Android reports writes without response through onCharacteristicWrite too;
                // waiting for it is our flow control. Fall through on timeout.
                withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { writeAcks.receive() }
                return
            }
            delay(BUSY_BACKOFF_MS)
        }
        throw TransportException("BLE write failed (stack busy)")
    }

    @Suppress("DEPRECATION")
    private fun startWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, chunk: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            c.value = chunk
            g.writeCharacteristic(c)
        }

    private fun close() {
        gatt?.close()
        gatt = null
        writeChar = null
        notifyQueue.clear()
    }

    private fun fail(reason: String) {
        logger.log("ble: setup failed: $reason")
        setupDone.completeExceptionally(TransportException(reason))
    }

    @Suppress("DEPRECATION")
    private fun enableNextNotification(g: BluetoothGatt) {
        val c = notifyQueue.removeFirstOrNull()
        if (c == null) {
            setupDone.complete(Unit)
            return
        }
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD)
        if (cccd == null) {
            enableNextNotification(g)
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
        if (!started) fail("could not enable notifications on ${c.uuid.short()}")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            logger.log("ble: state=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!g.requestMtu(TARGET_MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!setupDone.isCompleted) fail("disconnected during setup (status $status)")
                    g.close()
                    if (gatt === g) gatt = null
                    _link.value = if (closing) LinkState.Disconnected else LinkState.Failed("link lost (status $status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            if (!g.discoverServices()) fail("service discovery did not start")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                fail("camera service fff0 not found (status $status)")
                return
            }
            writeChar = service.getCharacteristic(WRITE_CHAR) ?: run { fail("fff5 not found"); return }
            notifyQueue.clear()
            service.getCharacteristic(NOTIFY_CHAR)?.let { notifyQueue.addLast(it) }
                ?: run { fail("fff4 not found"); return }
            enableNextNotification(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("enabling notifications failed (status $status)")
                return
            }
            enableNextNotification(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAcks.trySend(status)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _incoming.tryEmit(value)
        }

        // API 31-32
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) _incoming.tryEmit(c.value ?: return)
        }
    }

    private companion object {
        val SERVICE: UUID = uuid16(0xFFF0)
        val NOTIFY_CHAR: UUID = uuid16(0xFFF4)
        val WRITE_CHAR: UUID = uuid16(0xFFF5)
        val CCCD: UUID = uuid16(0x2902)
        const val DEFAULT_MTU = 23
        const val TARGET_MTU = 247
        const val SETUP_TIMEOUT_MS = 15_000L
        const val WRITE_ACK_TIMEOUT_MS = 500L
        const val WRITE_RETRIES = 10
        const val BUSY_BACKOFF_MS = 20L

        fun uuid16(v: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))
        fun UUID.short(): String = toString().substring(4, 8)
    }
}
