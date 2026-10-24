package com.retweakeverything.osmoremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Byte-for-byte parity with tools/mac-probe (rsdk_cmds.py), whose frames were
 * accepted by a real Osmo Action 5 Pro. If these drift, the camera will
 * silently ignore our commands.
 */
class CommandsParityTest {
    private val dev = DeviceId.ACTION_5_PRO

    private fun check(expected: String, frame: RsdkFrame) = assertEquals(expected, frame.encode().toHex())

    @Test fun connectionRequestFirstPair() = check(
        "aa330002000000000100982f00190000000000000000000000000000000000000000000000000000013930000000000f06e04f",
        Commands.connectionRequest(seq = 1, verifyMode = 1, verifyCode = 12345),
    )

    @Test fun connectionRequestAlreadyPaired() = check(
        "aa3300020000000009009fef00190000000000000000000000000000000000000000000000000000000000000000001a530212",
        Commands.connectionRequest(seq = 9, verifyMode = 0, verifyCode = 0),
    )

    @Test fun connectionResponse() = check(
        "aa1b0020000000008d1b40e7001944ff00000000000000cf0f8c7a",
        Commands.connectionResponse(cameraSeq = 7053, deviceId = dev),
    )

    @Test fun recordStart() = check(
        "aa1b000200000000020066de1d0344ff00000000000000bf1dce56",
        Commands.recordControl(seq = 2, start = true, deviceId = dev),
    )

    @Test fun recordStop() = check(
        "aa1b000200000000050064ee1d0344ff000001000000005ab8da84",
        Commands.recordControl(seq = 5, start = false, deviceId = dev),
    )

    @Test fun modeSwitchToPhoto() = check(
        "aa1b0002000000000400657e1d0444ff000005000000009285349b",
        Commands.modeSwitch(seq = 4, mode = CameraMode.PHOTO, deviceId = dev),
    )

    @Test fun statusSubscribe() = check(
        "aa18000200000000030073be1d0503140000000003b28432",
        Commands.statusSubscribe(seq = 3),
    )

    @Test fun highlightKey() = check(
        "aa1600010000000006000f8e00110201000064761f00",
        Commands.keyReport(seq = 6, keyCode = KeyCode.QS),
    )

    @Test fun powerSleep() = check(
        "aa13000200000000080007be001a03d70e3dac",
        Commands.powerMode(seq = 8, sleep = true),
    )

    @Test fun parsesCameraAllowFromRealPayload() {
        // Payload of the camera's own connection request, captured on hardware.
        val payload = "44ff00000000000000000000000000000000000000000000000002000000000000".hexToBytes()
        val frame = RsdkFrame(0x00, 0x19, seq = 7053, cmdType = CmdType.RESPONSE_REQUIRED, payload = payload)
        assertEquals(VerifyResult.ALLOWED, Commands.parseCameraVerifyResult(frame))
    }

    @Test fun ignoresConnectionResponseWhenLookingForVerifyResult() {
        val payload = "44ff00000000000000".hexToBytes() // camera's short response frame
        val frame = RsdkFrame(0x00, 0x19, seq = 1, cmdType = CmdType.FRAME_RESPONSE, payload = payload)
        assertNull(Commands.parseCameraVerifyResult(frame))
    }
}
