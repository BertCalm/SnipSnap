package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The per-macro fade law (docs/superpowers/specs/2026-10-21-pedal-board-benchmark.md, finding F2):
 * which macros are LOG, that each names a gain law, that AMT walks that gain linearly in decibels,
 * and that every macro and character not touched by a LOG fade is exactly what it was.
 */
class FadeLawTest {

    private val amounts = (1..19).map { it / 20f }

    @Test
    fun `VALVE DRIVE is the one LOG macro, and every LOG macro names a positive, rising gain law`() {
        val logs = FxChain.SECTION_NAMES.flatMap { s -> FxChain.macrosOf(s).filter { it.law == FadeLaw.LOG }.map { s to it.name } }
        assertEquals(listOf("valve" to "DRIVE"), logs)
        for ((section, macro) in logs) {
            val gain = assertNotNull(FxChain.gainOf(section, macro), "$section $macro is LOG with no gain law")
            var last = 0f
            for (i in 0..100) {
                val g = gain(i / 100f)
                assertTrue(g > 0f && g > last, "$section $macro's gain is not positive and rising at ${i / 100f}: $g after $last")
                last = g
            }
        }
        // And no section declares a gain law for a macro that does not fade by it.
        for (s in FxChain.SECTION_NAMES) for (spec in FxChain.macrosOf(s)) {
            if (spec.law != FadeLaw.LOG) assertEquals(null, FxChain.gainOf(s, spec.name), "$s ${spec.name} has a gain law but fades linearly")
        }
    }

    @Test
    fun `a LOG fade walks the gain linearly in decibels, and lands exactly on the ends`() {
        val full = Treatments.chain("driven", 1f)
        val drive = full.valve!!.getValue("DRIVE")
        val dbNeutral = 20 * log10(Valve.gainFor(0f).toDouble())
        val dbFull = 20 * log10(Valve.gainFor(drive).toDouble())
        for (a in amounts) {
            val faded = Treatments.fade(full, a).valve!!.getValue("DRIVE")
            val db = 20 * log10(Valve.gainFor(faded).toDouble())
            val want = dbNeutral + (dbFull - dbNeutral) * a
            assertTrue(abs(db - want) < 0.01, "at AMT $a DRIVE $faded sets ${"%.3f".format(db)} dB, the law wants ${"%.3f".format(want)}")
        }
        assertEquals(0f, Treatments.fade(full, 0f).valve!!.getValue("DRIVE"), "AMT 0 lands on the neutral")
        assertEquals(drive, Treatments.fade(full, 1f).valve!!.getValue("DRIVE"), "AMT 1 is the character")
    }

    @Test
    fun `every macro that is not LOG fades exactly as before, in every character, at every AMT`() {
        for (name in Treatments.names) {
            val full = Treatments.chain(name, 1f)
            for (a in amounts) {
                val law = Treatments.fade(full, a)
                val linear = Treatments.linearFade(full, a)
                for (section in FxChain.SECTION_NAMES) {
                    val l = law.section(section) ?: continue
                    val o = linear.section(section)!!
                    for (spec in FxChain.macrosOf(section)) {
                        val v = l[spec.name] ?: continue
                        if (spec.law == FadeLaw.LOG) continue
                        assertEquals(o.getValue(spec.name), v, "$name at $a: $section ${spec.name} moved")
                    }
                }
                if (full.valve?.containsKey("DRIVE") != true) {
                    assertEquals(linear, law, "$name at $a has no LOG macro and its chain changed")
                }
            }
        }
    }

    @Test
    fun `DRIVE at the sheet's AMT 0_7 is hotter than the linear walk left it - the Stage 0 finding, measured in gain`() {
        val full = Treatments.chain("driven", 1f)
        val log = Valve.gainFor(Treatments.fade(full, 0.7f).valve!!.getValue("DRIVE"))
        val lin = Valve.gainFor(Treatments.linearFade(full, 0.7f).valve!!.getValue("DRIVE"))
        assertTrue(abs(lin - 11.3f) < 0.1f, "the old walk left DRIVE at VALVE's own default gain 11.3, got $lin")
        assertTrue(log > 4f * lin, "the LOG walk sets gain $log at AMT 0.7, not well above the linear $lin")
        assertNotEquals(Treatments.chain("driven", 0.7f), Treatments.linearFade(full, 0.7f), "driven at AMT 0.7 did change: it is a LOG fade")
    }
}
