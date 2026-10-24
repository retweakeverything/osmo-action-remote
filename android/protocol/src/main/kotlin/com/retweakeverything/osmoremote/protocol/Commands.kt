package com.retweakeverything.osmoremote.protocol

/** (CmdSet, CmdId) pair identifying an R SDK function. */
data class Command(val set: Int, val id: Int) {
    override fun toString(): String = "0x%02x/0x%02x".format(set, id)

    companion object {
        val CONNECTION = Command(0x00, 0x19)
        val KEY_REPORT = Command(0x00, 0x11)
        val GPS_PUSH = Command(0x00, 0x17)
        val POWER_MODE = Command(0x00, 0x1A)
        val RECORD_CONTROL = Command(0x1D, 0x03)
        val MODE_SWITCH = Command(0x1D, 0x04)
        val STATUS_SUBSCRIBE = Command(0x1D, 0x05)
        val STATUS_PUSH = Command(0x1D, 0x02)
        val STATUS_PUSH_NEW = Command(0x1D, 0x06)
    }
}

/** DJI device ids carried in command payloads. */
object DeviceId {
    const val ACTION_4 = 0xFF33L
    const val ACTION_5_PRO = 0xFF44L
    const val ACTION_6 = 0xFF55L
    const val OSMO_360 = 0xFF66L
}

/** Key codes for Key Report (0x00/0x11). */
object KeyCode {
    const val RECORD = 0x01

    /** QS button. A short press while recording inserts a highlight tag. */
    const val QS = 0x02
    const val SNAPSHOT = 0x03
}

/**
 * Payload builders for every command verified on the Action 5 Pro.
 * Layouts follow DJI's docs/protocol_data_segment.md.
 */
object Commands {
    private const val VERIFY_MODE_OFFSET = 26

    /**
     * Connection Request (0x00/0x19).
     * @param verifyMode 1 = first pairing (camera shows a code), 0 = already paired.
     */
    fun connectionRequest(
        seq: Int,
        verifyMode: Int,
        verifyCode: Int,
        senderDeviceId: Long = 0,
        mac: ByteArray = ByteArray(0),
    ): RsdkFrame {
        require(mac.size <= 16) { "mac too long" }
        val payload = LeWriter(33)
            .u32(senderDeviceId)
            .u8(mac.size).bytes(mac).zeros(16 - mac.size)
            .u32(0) // fw_version
            .u8(0) // conidx
            .u8(verifyMode)
            .u16(verifyCode and 0xFFFF)
            .zeros(4)
            .build()
        return RsdkFrame(Command.CONNECTION.set, Command.CONNECTION.id, seq, CmdType.RESPONSE_REQUIRED, payload = payload)
    }

    /**
     * The remote's reply to the camera's own connection request. Without it
     * the camera retransmits its request continuously (observed on hardware).
     */
    fun connectionResponse(cameraSeq: Int, deviceId: Long, cameraNumber: Long = 0): RsdkFrame {
        val payload = LeWriter(9).u32(deviceId).u8(0).u32(cameraNumber).build()
        return RsdkFrame(
            Command.CONNECTION.set, Command.CONNECTION.id, cameraSeq,
            CmdType.FRAME_RESPONSE or CmdType.RESPONSE_NONE, payload = payload,
        )
    }

    /** Camera's connection request with verify_mode=2 carries the verify result. */
    fun parseCameraVerifyResult(frame: RsdkFrame): VerifyResult? {
        if (frame.command != Command.CONNECTION || frame.isResponse) return null
        val p = frame.payload
        if (p.size <= VERIFY_MODE_OFFSET + 1 || p.getU8(VERIFY_MODE_OFFSET) != 2) return null
        return if (p.getU8(VERIFY_MODE_OFFSET + 1) == 0) VerifyResult.ALLOWED else VerifyResult.REJECTED
    }

    fun recordControl(seq: Int, start: Boolean, deviceId: Long): RsdkFrame = deviceCommand(
        Command.RECORD_CONTROL, seq, deviceId, if (start) 0 else 1,
    )

    fun modeSwitch(seq: Int, mode: CameraMode, deviceId: Long): RsdkFrame =
        deviceCommand(Command.MODE_SWITCH, seq, deviceId, mode.code)

    /** push_mode 3 = periodic + on change; push_freq is fixed at 20 (2 Hz). */
    fun statusSubscribe(seq: Int, pushMode: Int = 3, pushFreq: Int = 20): RsdkFrame {
        val payload = LeWriter(6).u8(pushMode).u8(pushFreq).zeros(4).build()
        return RsdkFrame(Command.STATUS_SUBSCRIBE.set, Command.STATUS_SUBSCRIBE.id, seq, CmdType.RESPONSE_REQUIRED, payload = payload)
    }

    /** Key Report (0x00/0x11): mode 1 = key event, value 0 = short press. */
    fun keyReport(seq: Int, keyCode: Int, mode: Int = 1, keyValue: Int = 0): RsdkFrame {
        val payload = LeWriter(4).u8(keyCode).u8(mode).u16(keyValue).build()
        return RsdkFrame(Command.KEY_REPORT.set, Command.KEY_REPORT.id, seq, CmdType.RESPONSE_OPTIONAL, payload = payload)
    }

    /** Power mode (0x00/0x1A): 0 = normal, 3 = sleep. */
    fun powerMode(seq: Int, sleep: Boolean): RsdkFrame {
        val payload = byteArrayOf(if (sleep) 3 else 0)
        return RsdkFrame(Command.POWER_MODE.set, Command.POWER_MODE.id, seq, CmdType.RESPONSE_REQUIRED, payload = payload)
    }

    /** First byte of most command responses is the return code (0 = success). */
    fun returnCode(frame: RsdkFrame): Int? = frame.payload.firstOrNull()?.toInt()?.and(0xFF)

    private fun deviceCommand(cmd: Command, seq: Int, deviceId: Long, arg: Int): RsdkFrame {
        val payload = LeWriter(9).u32(deviceId).u8(arg).zeros(4).build()
        return RsdkFrame(cmd.set, cmd.id, seq, CmdType.RESPONSE_REQUIRED, payload = payload)
    }
}

enum class VerifyResult { ALLOWED, REJECTED }
