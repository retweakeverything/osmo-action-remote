package com.retweakeverything.osmoremote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Verified against DJI's golden Connection Request frames (connect_cmd_frame.txt). */
class RsdkFrameTest {
    private val golden = listOf(
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000FE1F00000000796C667C",
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000191A0000000033C8C7BF",
        "AA330002000000000100982F00197856341206383456789ABC000000000000000000000000000000008F1200000000AC768844",
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000050B00000000E1B45396",
    ).map { it.hexToBytes() }

    @Test fun goldenCrc16Matches() {
        golden.forEach { assertEquals(it.getU16(10), Crc.crc16(it, 0, 10)) }
    }

    @Test fun goldenCrc32Matches() {
        golden.forEach { assertEquals(it.getU32(it.size - 4), Crc.crc32(it, 0, it.size - 4)) }
    }

    @Test fun goldenFramesDecodeAsConnectionRequest() {
        golden.forEach { assertEquals(Command.CONNECTION, RsdkFrame.decode(it).command) }
    }

    @Test fun encoderReproducesGoldenByteForByte() {
        golden.forEach { raw ->
            val f = RsdkFrame.decode(raw)
            assertArrayEquals(raw, f.copy().encode())
        }
    }

    @Test fun corruptedPayloadIsRejected() {
        val raw = golden[0].copyOf().also { it[14] = (it[14].toInt() xor 0xFF).toByte() }
        try {
            RsdkFrame.decode(raw)
            fail("expected CRC32 mismatch")
        } catch (e: FrameException) {
            assertTrue(e.message!!.contains("CRC32"))
        }
    }

    @Test fun corruptedHeaderIsRejected() {
        val raw = golden[0].copyOf().also { it[8] = (it[8] + 1).toByte() }
        try {
            RsdkFrame.decode(raw)
            fail("expected CRC16 mismatch")
        } catch (e: FrameException) {
            assertTrue(e.message!!.contains("CRC16"))
        }
    }
}
