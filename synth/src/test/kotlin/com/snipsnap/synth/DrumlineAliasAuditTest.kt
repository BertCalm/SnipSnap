package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class DrumlineAliasAuditTest {
    private fun tone(hz: Double) = FloatArray(65536) { i ->
        // Smooth endpoints avoid mistaking a rectangular window's sidelobes for a tone.
        (sin(2*PI*hz*i/176400)*sin(PI*i/65535).pow(2)).toFloat()
    }

    @Test fun `in band control stays below the projection noise floor`() {
        val m=DrumlineAliasAudit.measure(tone(3000.0))
        assertTrue(m.foldedDb < -110,m.toString())
        assertTrue(m.bypassDb < -110,m.toString())
        assertTrue(m.reconstructionDb < -110,m.toString())
    }

    @Test fun `known high tone folds when the antialias decimator is bypassed`() {
        val low=tone(3000.0);val high=tone(30000.0)
        val m=DrumlineAliasAudit.measure(FloatArray(low.size) { low[it]+high[it] })
        // Equal-energy high and low tones: naive four-to-one sampling folds 30 kHz to 14.1 kHz.
        assertTrue(abs(m.bypassDb) < .1,m.toString())
        assertTrue(m.foldedDb < -60,m.toString())
        assertTrue(abs(m.folded20To18kDb-m.foldedDb) < .1,m.toString())
        assertTrue(m.reconstructionDb < -110,m.toString())
    }
}
