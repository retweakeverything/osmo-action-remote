package com.retweakeverything.osmoremote.protocol

// Little-endian helpers. All R SDK multi-byte fields are little-endian.

internal fun ByteArray.getU8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.getU16(i: Int): Int = getU8(i) or (getU8(i + 1) shl 8)

internal fun ByteArray.getU32(i: Int): Long =
    getU8(i).toLong() or (getU8(i + 1).toLong() shl 8) or
        (getU8(i + 2).toLong() shl 16) or (getU8(i + 3).toLong() shl 24)

internal fun ByteArray.putU16(i: Int, v: Int) {
    this[i] = v.toByte()
    this[i + 1] = (v ushr 8).toByte()
}

internal fun ByteArray.putU32(i: Int, v: Long) {
    this[i] = v.toByte()
    this[i + 1] = (v ushr 8).toByte()
    this[i + 2] = (v ushr 16).toByte()
    this[i + 3] = (v ushr 24).toByte()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd-length hex string" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Small little-endian payload builder. */
internal class LeWriter(size: Int) {
    private val buf = ByteArray(size)
    private var pos = 0

    fun u8(v: Int) = apply { buf[pos++] = v.toByte() }
    fun u16(v: Int) = apply { buf.putU16(pos, v); pos += 2 }
    fun u32(v: Long) = apply { buf.putU32(pos, v); pos += 4 }
    fun bytes(b: ByteArray) = apply { b.copyInto(buf, pos); pos += b.size }
    fun zeros(n: Int) = apply { pos += n }

    fun build(): ByteArray {
        check(pos == buf.size) { "payload size mismatch: wrote $pos of ${buf.size}" }
        return buf
    }
}
