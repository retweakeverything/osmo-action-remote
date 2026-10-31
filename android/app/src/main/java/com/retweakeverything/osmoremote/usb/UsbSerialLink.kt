package com.retweakeverything.osmoremote.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.retweakeverything.osmoremote.core.Logger
import com.retweakeverything.osmoremote.core.TransportException
import com.retweakeverything.osmoremote.core.bridge.SerialLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * USB serial port to the ESP32 relay, through usb-serial-for-android. Works
 * with the ESP32-C3/S3 built-in USB (CDC-ACM) and with CP210x / CH34x bridge
 * chips on older boards. DTR and RTS stay low: on ESP32 boards toggling them
 * resets the chip.
 */
class UsbSerialLink(
    private val context: Context,
    private val logger: Logger,
) : SerialLink {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    override val incoming: SharedFlow<ByteArray> = _incoming
    private val _open = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _open

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var detachReceiver: BroadcastReceiver? = null

    /** The first plugged-in USB serial device, if any. */
    fun findDriver(): UsbSerialDriver? = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()

    override suspend fun open() = withContext(Dispatchers.IO) {
        if (_open.value) return@withContext
        val driver = findDriver()
            ?: throw TransportException("No ESP32 bridge on USB. Plug it in with a USB-C (OTG) cable.")
        val device = driver.device
        if (!usb.hasPermission(device) && !requestPermission(device)) {
            throw TransportException("USB permission was not granted")
        }
        val connection = usb.openDevice(device) ?: throw TransportException("Could not open the USB device")
        val p = driver.ports.first()
        try {
            p.open(connection)
            p.setParameters(115_200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.dtr = false; p.rts = false }
        } catch (e: Exception) {
            runCatching { p.close() }
            throw TransportException("USB serial open failed: ${e.message}")
        }
        port = p
        io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) { _incoming.tryEmit(data) }
            override fun onRunError(e: Exception) {
                logger.log("usb: ${e.message ?: "read error"}")
                markClosed()
            }
        }).also { it.start() }
        watchDetach(device)
        _open.value = true
        logger.log("usb: opened ${driver.javaClass.simpleName} vid=%04x pid=%04x".format(device.vendorId, device.productId))
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val p = port ?: throw TransportException("USB bridge not open")
        try {
            p.write(bytes, WRITE_TIMEOUT_MS)
        } catch (e: Exception) {
            markClosed()
            throw TransportException("USB write failed: ${e.message}")
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) { markClosed() }

    private fun markClosed() {
        io?.let { runCatching { it.stop() } }
        io = null
        port?.let { runCatching { it.close() } }
        port = null
        detachReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        detachReceiver = null
        _open.value = false
    }

    private fun watchDetach(device: UsbDevice) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val gone = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (gone?.deviceName == device.deviceName) {
                    logger.log("usb: bridge unplugged")
                    markClosed()
                }
            }
        }
        // Detach is a protected system broadcast, so the receiver can stay unexported.
        ContextCompat.registerReceiver(context, r, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
        detachReceiver = r
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean = suspendCancellableCoroutine { cont ->
        val action = "${context.packageName}.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        // Mutable so the system can add EXTRA_PERMISSION_GRANTED; explicit package keeps it in-app.
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE,
        )
        logger.log("usb: asking for permission to use the bridge")
        usb.requestPermission(device, pi)
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 1_000
    }
}
