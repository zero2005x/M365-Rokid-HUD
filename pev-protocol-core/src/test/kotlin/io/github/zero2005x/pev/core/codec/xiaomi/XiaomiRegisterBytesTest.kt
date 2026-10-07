package io.github.zero2005x.pev.core.codec.xiaomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiRegisterBytesTest {
    @Test fun `readers check bounds and signedness`() {
        val bytes = byteArrayOf(0x34, 0x12, -1, -1)
        assertEquals(255, XiaomiRegisterBytes.u8(bytes, 3))
        assertEquals(0x1234, XiaomiRegisterBytes.u16(bytes, 0))
        assertEquals(65535, XiaomiRegisterBytes.u16(bytes, 2))
        assertEquals(-1, XiaomiRegisterBytes.i16(bytes, 2))
        assertEquals(-60876, XiaomiRegisterBytes.i32(bytes, 0))
        assertNull(XiaomiRegisterBytes.u8(bytes, -1))
        assertNull(XiaomiRegisterBytes.u8(bytes, 4))
        assertNull(XiaomiRegisterBytes.u16(bytes, 3))
        assertNull(XiaomiRegisterBytes.i16(bytes, 4))
        assertNull(XiaomiRegisterBytes.i32(bytes, 1))
        assertNull(XiaomiRegisterBytes.i32(bytes, -1))
        assertNull(XiaomiRegisterBytes.u16(bytes, Int.MAX_VALUE))
        assertNull(XiaomiRegisterBytes.i32(bytes, Int.MAX_VALUE))
    }
}
