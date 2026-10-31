package com.retweakeverything.osmoremote.ble

import android.content.Context
import com.retweakeverything.osmoremote.core.CameraRoute
import com.retweakeverything.osmoremote.core.CameraScanner
import com.retweakeverything.osmoremote.core.CameraTransport
import com.retweakeverything.osmoremote.core.CameraWaker
import com.retweakeverything.osmoremote.core.Logger

/** Camera reached with the phone's own Bluetooth. */
class PhoneRoute(
    private val context: Context,
    private val logger: Logger,
    override val scanner: CameraScanner,
    override val waker: CameraWaker,
) : CameraRoute {
    override val id: String = "phone"
    override fun newTransport(): CameraTransport = BleCameraTransport(context, logger)
}
