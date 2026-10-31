package com.retweakeverything.osmoremote.core

/** Sends DJI's wake advertisement for [device]. Returns once it has been broadcast. */
interface CameraWaker {
    suspend fun wake(device: CameraDevice)
}

/**
 * One way of reaching the camera. Every route speaks the same R SDK bytes and
 * sends the same wake payload; only the radio doing it differs (the phone's
 * Bluetooth, or an ESP32 relay on USB). The rest of the app never asks which
 * route is active.
 */
interface CameraRoute {
    /** Stable id, also used to keep pairings per route (each has its own Bluetooth identity). */
    val id: String
    val scanner: CameraScanner
    val waker: CameraWaker
    fun newTransport(): CameraTransport
}

/**
 * Keeps pairings separate per route: the camera sees the phone and the ESP32
 * as different remotes, so pairing one says nothing about the other.
 */
class RoutePairingStore(
    private val inner: PairingStore,
    private val route: () -> CameraRoute,
) : PairingStore {
    private fun key(address: String) = "${route().id}|$address"

    override fun isPaired(address: String): Boolean = inner.isPaired(key(address))
    override fun markPaired(device: CameraDevice) = inner.markPaired(device.copy(address = key(device.address)))
    override fun forget(address: String) = inner.forget(key(address))

    override fun lastDevice(): CameraDevice? {
        val d = inner.lastDevice() ?: return null
        val prefix = "${route().id}|"
        return if (d.address.startsWith(prefix)) d.copy(address = d.address.removePrefix(prefix)) else null
    }
}
