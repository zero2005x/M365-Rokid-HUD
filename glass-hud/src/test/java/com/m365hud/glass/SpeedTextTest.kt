package com.m365hud.glass

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SpeedTextTest {
    private fun inUs(block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try { block() } finally { Locale.setDefault(previous) }
    }

    @Test fun `tiny negative speeds show as zero rather than minus zero`() = inUs {
        for (kmh in floatArrayOf(-0.001f, -0.012f, -0.049f, 0f, 0.049f)) {
            assertEquals("0.0", formatSpeedKmh(kmh))
        }
    }

    @Test fun `reverse and forward speeds keep their sign`() = inUs {
        assertEquals("-2.5", formatSpeedKmh(-2.5f))
        assertEquals("12.3", formatSpeedKmh(12.34f))
    }
}
