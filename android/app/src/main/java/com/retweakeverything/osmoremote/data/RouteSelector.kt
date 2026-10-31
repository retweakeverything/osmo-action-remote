package com.retweakeverything.osmoremote.data

import com.retweakeverything.osmoremote.core.CameraRoute
import com.retweakeverything.osmoremote.core.CameraScanner
import com.retweakeverything.osmoremote.core.DiscoveredCamera
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A route the user can pick, with the text the UI shows for it. */
data class RouteOption(
    val route: CameraRoute,
    val label: String,
    val description: String,
    /** The phone's own Bluetooth must be on and permitted for this route. */
    val needsPhoneBluetooth: Boolean,
)

/**
 * Holds the route the user picked and persists it. Everything that talks to
 * the camera goes through [current]; nothing else knows which route is active.
 */
class RouteSelector(val options: List<RouteOption>, private val settings: Settings) {
    private val _current = MutableStateFlow(options.firstOrNull { it.route.id == settings.routeId } ?: options.first())
    val current: StateFlow<RouteOption> = _current

    fun select(id: String) {
        val next = options.first { it.route.id == id }
        settings.routeId = next.route.id
        _current.value = next
    }

    /** Scanner of whichever route is active when scanning starts. */
    val scanner: CameraScanner = object : CameraScanner {
        override fun scan(): Flow<List<DiscoveredCamera>> = _current.value.route.scanner.scan()
    }
}
