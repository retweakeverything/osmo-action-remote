package com.retweakeverything.osmoremote.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A camera the app can connect to. [address] is the platform BLE address. */
data class CameraDevice(val address: String, val name: String) {
    val model: CameraModel get() = CameraModel.fromName(name)
}

/** Camera models speaking the same R SDK protocol. */
enum class CameraModel(val label: String, val deviceId: Long, private val namePattern: String?) {
    ACTION_4("Osmo Action 4", 0xFF33, "action4"),
    ACTION_5_PRO("Osmo Action 5 Pro", 0xFF44, "action5"),
    ACTION_6("Osmo Action 6", 0xFF55, "action6"),
    OSMO_360("Osmo 360", 0xFF66, "osmo360"),
    UNKNOWN("DJI camera", 0xFF44, null),
    ;

    companion object {
        fun fromName(name: String): CameraModel {
            val n = name.lowercase().filter { it.isLetterOrDigit() }
            return entries.firstOrNull { it.namePattern != null && n.contains(it.namePattern) } ?: UNKNOWN
        }

        fun fromDeviceId(id: Long): CameraModel = entries.firstOrNull { it.deviceId == id && it != UNKNOWN } ?: UNKNOWN
    }
}

/** State of the raw link, independent of the R SDK handshake. */
sealed interface LinkState {
    data object Disconnected : LinkState
    data object Connecting : LinkState
    data object Connected : LinkState
    data class Failed(val reason: String) : LinkState
}

/**
 * Byte pipe to one camera. The BLE implementation lives in the app module;
 * tests use a fake. The session, workflow engine and UI never see BLE directly.
 */
interface CameraTransport {
    val linkState: StateFlow<LinkState>

    /** Raw notification chunks. Frames may be split or interleaved with DUML. */
    val incoming: SharedFlow<ByteArray>

    /** Suspends until the link is ready for writes, or throws [TransportException]. */
    suspend fun connect(device: CameraDevice)

    /** Writes bytes, chunking to the link's payload size as needed. */
    suspend fun write(bytes: ByteArray)

    suspend fun disconnect()
}

class TransportException(message: String) : Exception(message)

data class DiscoveredCamera(val device: CameraDevice, val rssi: Int)

/** Emits the cumulative list of cameras seen while collected. */
interface CameraScanner {
    fun scan(): Flow<List<DiscoveredCamera>>
}

/** Remembers which cameras have completed first-time pairing. */
interface PairingStore {
    fun isPaired(address: String): Boolean
    fun markPaired(device: CameraDevice)
    fun forget(address: String)
    fun lastDevice(): CameraDevice?
}
