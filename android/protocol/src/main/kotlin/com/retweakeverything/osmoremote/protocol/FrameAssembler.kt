package com.retweakeverything.osmoremote.protocol

/**
 * Reassembles R SDK frames from a BLE notification byte stream.
 *
 * On a small MTU a frame can span several notifications, and the camera also
 * interleaves DUML (0x55) telemetry on the same characteristic. This buffers
 * bytes, skips DUML frames by their declared length, validates R SDK frames,
 * and resyncs one byte at a time on corruption.
 */
class FrameAssembler(private val maxBuffer: Int = 4096) {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<RsdkFrame> {
        buf += chunk
        if (buf.size > maxBuffer) buf = buf.copyOfRange(buf.size - maxBuffer, buf.size)
        val out = mutableListOf<RsdkFrame>()
        while (true) {
            val start = buf.indexOfFirst { it.isSof() }
            if (start < 0) { buf = ByteArray(0); break }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 3) break

            val sof = buf.getU8(0)
            val len = if (sof == RsdkFrame.SOF) RsdkFrame.declaredLength(buf) else dumlLength(buf)
            val minLen = if (sof == RsdkFrame.SOF) RsdkFrame.MIN_SIZE else DUML_MIN
            if (len < minLen) { dropOne(); continue }
            if (buf.size < len) break

            val candidate = buf.copyOfRange(0, len)
            if (sof == RsdkFrame.SOF) {
                val frame = runCatching { RsdkFrame.decode(candidate) }.getOrNull()
                if (frame == null) { dropOne(); continue }
                out += frame
            }
            // DUML telemetry is consumed and ignored; the R SDK status push has it all.
            buf = buf.copyOfRange(len, buf.size)
        }
        return out
    }

    fun reset() { buf = ByteArray(0) }

    private fun dropOne() { buf = buf.copyOfRange(1, buf.size) }

    private fun Byte.isSof(): Boolean {
        val v = toInt() and 0xFF
        return v == RsdkFrame.SOF || v == DUML_SOF
    }

    private fun dumlLength(b: ByteArray): Int = b.getU8(1) or ((b.getU8(2) and 0x03) shl 8)

    private companion object {
        const val DUML_SOF = 0x55
        const val DUML_MIN = 13
    }
}
