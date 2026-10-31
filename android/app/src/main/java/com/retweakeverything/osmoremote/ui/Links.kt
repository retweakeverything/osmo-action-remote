package com.retweakeverything.osmoremote.ui

/** Public pages the app points to. */
object Links {
    const val REPO = "https://github.com/retweakeverything/osmo-action-remote"
    /** How to flash and use the ESP32 relay. */
    const val ESP32_GUIDE = "$REPO/tree/main/firmware#flashing"
    /** Ready-to-flash relay images (osmo-bridge-<chip>.bin) are attached to each release. */
    const val RELEASES = "$REPO/releases/latest"
}
