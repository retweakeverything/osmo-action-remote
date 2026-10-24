package com.retweakeverything.osmoremote.protocol

/**
 * CRCs used by the DJI R SDK frame (0xAA control protocol).
 *
 * Parameters come from DJI's Osmo-GPS-Controller-Demo headers and were
 * verified against DJI's golden Connection Request frames and against live
 * traffic from an Osmo Action 5 Pro (see docs/PROTOCOL.md).
 *
 * - CRC16: ARC table (reflected poly 0xA001), init 0x3AA3, no final xor.
 * - CRC32: reflected poly 0xEDB88320, init 0x00003AA3, no final xor.
 */
object Crc {
    private val table16 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        c
    }

    private val table32 = LongArray(256) { i ->
        var c = i.toLong()
        repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }
        c
    }

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0x3AA3
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            crc = (table16[(crc xor b) and 0xFF] xor (crc ushr 8)) and 0xFFFF
        }
        return crc
    }

    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        var crc = 0x00003AA3L
        for (i in offset until offset + length) {
            val b = data[i].toLong() and 0xFF
            crc = (table32[((crc xor b) and 0xFF).toInt()] xor (crc ushr 8)) and 0xFFFFFFFFL
        }
        return crc
    }
}
