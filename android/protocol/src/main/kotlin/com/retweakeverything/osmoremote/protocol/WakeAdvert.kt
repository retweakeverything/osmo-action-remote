package com.retweakeverything.osmoremote.protocol

/**
 * DJI's wake advertisement: the AD structure `0A FF 'W' 'K' 'P' m5 m4 m3 m2 m1 m0`
 * (camera MAC reversed), sent for about 2 s as a connectable undirected advert.
 *
 * Verified on an Osmo Action 5 Pro with camera firmware 01.06.01.04: it wakes
 * the camera only when the advertiser uses a PUBLIC Bluetooth address. On
 * firmware 01.02.03.40 it never woke the camera.
 */
object WakeAdvert {
    /** 'W','K' read as a little-endian manufacturer id, how Android's AdvertiseData expects it. */
    const val COMPANY_ID = 0x4B57

    const val DURATION_MS = 3_000L

    /** Manufacturer data after the company id: 'P' + camera MAC reversed. */
    fun manufacturerPayload(cameraAddress: String): ByteArray =
        byteArrayOf('P'.code.toByte()) + macBytes(cameraAddress).reversedArray()

    /** The complete advertising data, exactly as it goes on air. */
    fun advertisingData(cameraAddress: String): ByteArray =
        byteArrayOf(0x0A, 0xFF.toByte(), 'W'.code.toByte(), 'K'.code.toByte()) + manufacturerPayload(cameraAddress)

    /** "AA:BB:CC:DD:EE:FF" -> 6 bytes in display order. */
    fun macBytes(address: String): ByteArray {
        val parts = address.split(":")
        require(parts.size == 6 && parts.all { it.length == 2 }) { "not a Bluetooth address: $address" }
        return ByteArray(6) { parts[it].toInt(16).toByte() }
    }
}
