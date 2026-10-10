package com.m365bleapp.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NumberTextTest {
    private fun inUs(block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try { block() } finally { Locale.setDefault(previous) }
    }

    @Test fun `tiny negative readings show as zero rather than minus zero`() = inUs {
        for (value in doubleArrayOf(-0.001, -0.012, -0.049, 0.0, 0.049)) {
            assertEquals("0.0", formatOneDecimal(value))
        }
    }

    @Test fun `real reverse and forward speeds keep their sign`() = inUs {
        assertEquals("-0.1", formatOneDecimal(-0.05))
        assertEquals("-2.5", formatOneDecimal(-2.5))
        assertEquals("12.3", formatOneDecimal(12.34))
    }
}
