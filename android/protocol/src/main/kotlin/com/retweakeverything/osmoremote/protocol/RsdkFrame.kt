package com.retweakeverything.osmoremote.protocol

/**
 * One DJI R SDK frame. Wire format (little-endian):
 *
 * ```
 * [SOF=0xAA][Ver/Len:2][CmdType:1][Enc:1][Res:3][Seq:2][CRC16:2][CmdSet][CmdId][payload][CRC32:4]
 * ```
 * CRC16 covers bytes 0..9; CRC32 covers everything before the trailing 4 bytes.
 */
data class RsdkFrame(
    val cmdSet: Int,
    val cmdId: Int,
    val seq: Int = 0,
    val cmdType: Int = CmdType.RESPONSE_REQUIRED,
    val enc: Int = 0,
    val payload: ByteArray = ByteArray(0),
) {
    val isResponse: Boolean get() = cmdType and CmdType.FRAME_RESPONSE != 0
    val command: Command get() = Command(cmdSet, cmdId)

    fun encode(): ByteArray {
        val total = HEADER_SIZE + 2 + payload.size + 4
        require(total <= 0x3FF) { "frame exceeds 10-bit length field: $total" }
        val out = ByteArray(total)
        out[0] = SOF.toByte()
        out.putU16(1, total and 0x3FF)
        out[3] = cmdType.toByte()
        out[4] = enc.toByte()
        // bytes 5..7 reserved (zero)
        out.putU16(8, seq and 0xFFFF)
        out.putU16(10, Crc.crc16(out, 0, 10))
        out[12] = cmdSet.toByte()
        out[13] = cmdId.toByte()
        payload.copyInto(out, 14)
        out.putU32(total - 4, Crc.crc32(out, 0, total - 4))
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is RsdkFrame && cmdSet == other.cmdSet && cmdId == other.cmdId &&
            seq == other.seq && cmdType == other.cmdType && enc == other.enc &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        listOf(cmdSet, cmdId, seq, cmdType, enc, payload.contentHashCode()).hashCode()

    override fun toString(): String =
        "RsdkFrame(${if (isResponse) "resp" else "cmd"} seq=$seq " +
            "set=0x%02x id=0x%02x payload=%s)".format(cmdSet, cmdId, payload.toHex())

    companion object {
        const val SOF = 0xAA
        const val HEADER_SIZE = 12
        const val MIN_SIZE = HEADER_SIZE + 2 + 4

        /** Total frame length declared in a header starting at [offset]. */
        fun declaredLength(data: ByteArray, offset: Int = 0): Int =
            data.getU16(offset + 1) and 0x3FF

        fun decode(raw: ByteArray): RsdkFrame {
            if (raw.size < MIN_SIZE || (raw[0].toInt() and 0xFF) != SOF) {
                throw FrameException("not an R SDK frame")
            }
            val total = declaredLength(raw)
            if (total != raw.size) throw FrameException("length mismatch: header $total, got ${raw.size}")
            if (Crc.crc16(raw, 0, 10) != raw.getU16(10)) throw FrameException("CRC16 mismatch")
            if (Crc.crc32(raw, 0, raw.size - 4) != raw.getU32(raw.size - 4)) {
                throw FrameException("CRC32 mismatch")
            }
            return RsdkFrame(
                cmdSet = raw[12].toInt() and 0xFF,
                cmdId = raw[13].toInt() and 0xFF,
                seq = raw.getU16(8),
                cmdType = raw[3].toInt() and 0xFF,
                enc = raw[4].toInt() and 0xFF,
                payload = raw.copyOfRange(14, raw.size - 4),
            )
        }
    }
}

/** CmdType byte: bits[4:0] response type, bit 5 frame type (1 = response). */
object CmdType {
    const val RESPONSE_NONE = 0x00
    const val RESPONSE_OPTIONAL = 0x01
    const val RESPONSE_REQUIRED = 0x02
    const val FRAME_RESPONSE = 0x20
}

class FrameException(message: String) : Exception(message)
