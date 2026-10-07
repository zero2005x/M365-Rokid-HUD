package io.github.zero2005x.pev.core.codec.xiaomi

/** Bounds-checked register primitives. Reading bytes does not identify a vehicle profile. */
object XiaomiRegisterBytes {
    fun u8(data: ByteArray, offset: Int): Int? = Le.u8(data, offset)
    fun u16(data: ByteArray, offset: Int): Int? = Le.u16(data, offset)
    fun i16(data: ByteArray, offset: Int): Int? = Le.i16(data, offset)
    fun i32(data: ByteArray, offset: Int): Int? = Le.u32(data, offset)?.toInt()
}
