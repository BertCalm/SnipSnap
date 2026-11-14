package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.*

class PatioCharacterTest {
    @Test fun `all gestures replay and stay bounded at control corners`() {
        for (v in PatioCharacter.Voice.entries) {
            val a = PatioCharacter.render(v)
            assertContentEquals(a.samples, PatioCharacter.render(v).samples)
            for (tune in listOf(0f, 1f)) for (touch in listOf(0f, 1f)) for (hold in listOf(0f, 1f)) {
                val s = PatioCharacter.render(v, tune, touch, hold)
                assertTrue(s.samples.all { it.isFinite() }, "$v")
                assertTrue(s.peak() in .001f..1f, "$v ${s.peak()}")
                assertTrue(abs(s.samples.average()) < .001, "$v DC")
                assertTrue(abs(s.samples.last()) < 1e-6f)
            }
        }
    }
    @Test fun `slap sheds body energy sooner than open stroke at same tuning`() {
        fun lateFraction(v: PatioCharacter.Voice): Double {
            val s = PatioCharacter.render(v).samples
            return s.drop((Dsp.RATE * .15).toInt()).sumOf { it.toDouble() * it } / s.sumOf { it.toDouble() * it }
        }
        assertTrue(lateFraction(PatioCharacter.Voice.SLAP) < lateFraction(PatioCharacter.Voice.OPEN) * .25)
    }
    @Test fun `control and seed changes survive conditioning`() {
        for (v in PatioCharacter.Voice.entries) {
            val a = PatioCharacter.render(v).samples
            assertFalse(a.contentEquals(PatioCharacter.render(v, tune = .2f).samples))
            assertFalse(a.contentEquals(PatioCharacter.render(v, touch = .2f).samples))
            assertFalse(a.contentEquals(PatioCharacter.render(v, hold = .8f).samples))
            assertFalse(a.contentEquals(PatioCharacter.render(v, seed = 32).samples))
        }
        assertFailsWith<IllegalArgumentException> { PatioCharacter.render(PatioCharacter.Voice.OPEN, tune = Float.NaN) }
    }
}
