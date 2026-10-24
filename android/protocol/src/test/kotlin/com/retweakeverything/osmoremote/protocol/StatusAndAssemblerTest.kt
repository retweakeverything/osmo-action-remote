package com.retweakeverything.osmoremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusAndAssemblerTest {

    /** Build a status payload with the field values observed live on the camera. */
    private fun statusPayload(
        mode: Int = 0x01, state: Int = 0x03, recSec: Int = 3,
        freeMb: Long = 44014, photos: Long = 0, recLeft: Long = 6926, battery: Int = 67,
    ): ByteArray {
        val p = ByteArray(38)
        p[0] = mode.toByte(); p[1] = state.toByte(); p[2] = 95; p[3] = 2
        p.putU16(5, recSec)
        p.putU32(15, freeMb); p.putU32(19, photos); p.putU32(23, recLeft)
        p[28] = 0; p[37] = battery.toByte()
        return p
    }

    @Test fun parsesLiveStatusFields() {
        val s = CameraStatus.parse(statusPayload())!!
        assertEquals(CameraMode.VIDEO, s.mode)
        assertTrue(s.isCapturing)
        assertEquals(3, s.recordSeconds)
        assertEquals(44014L, s.freeSpaceMb)
        assertEquals(6926L, s.remainingRecordSeconds)
        assertEquals(67, s.batteryPercent)
    }

    @Test fun shortPayloadIsNull() {
        assertNull(CameraStatus.parse(ByteArray(10)))
    }

    private fun statusFrame(seq: Int) =
        RsdkFrame(0x1D, 0x02, seq = seq, cmdType = CmdType.RESPONSE_NONE, payload = statusPayload()).encode()

    // Real DUML battery telemetry captured from the camera; must be skipped.
    private val duml = "552f046305254203000d0200fb0f00003cfeffff00000000000000004001004c000000000000200000000000019ed7".hexToBytes()

    @Test fun reassemblesFrameSplitAcross20ByteNotifications() {
        val a = FrameAssembler()
        val frames = statusFrame(1).toList().chunked(20).flatMap { a.feed(it.toByteArray()) }
        assertEquals(1, frames.size)
        assertEquals(Command.STATUS_PUSH, frames[0].command)
    }

    @Test fun skipsInterleavedDumlTelemetry() {
        val a = FrameAssembler()
        val frames = a.feed(duml + statusFrame(1) + duml + statusFrame(2))
        assertEquals(listOf(1, 2), frames.map { it.seq })
    }

    @Test fun resyncsAfterGarbage() {
        val a = FrameAssembler()
        val garbage = byteArrayOf(0x01, 0xAA.toByte(), 0x05, 0x00, 0x7F)
        val frames = a.feed(garbage + statusFrame(9))
        assertEquals(listOf(9), frames.map { it.seq })
    }
}
