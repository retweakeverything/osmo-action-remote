package com.retweakeverything.osmoremote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeAdvertTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun advertisingDataMatchesDjiDemoLayout() {
        // Same layout the ESP32 relay put on air when it woke the camera.
        assertEquals("0aff574b50665544332211", hex(WakeAdvert.advertisingData("11:22:33:44:55:66")))
    }

    @Test
    fun manufacturerPayloadIsPPlusReversedMac() {
        assertArrayEquals(
            byteArrayOf(0x50, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11),
            WakeAdvert.manufacturerPayload("11:22:33:44:55:66"),
        )
        assertEquals(0x4B57, WakeAdvert.COMPANY_ID)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadAddress() {
        WakeAdvert.macBytes("11:22:33")
    }
}
