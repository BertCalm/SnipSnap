package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CairnGrindVesselTest {
    private val earlier = listOf(CairnVoice.KNOCK, CairnVoice.ANSWER, CairnVoice.TUMBLE, CairnVoice.SING)
    private val added = listOf(CairnVoice.GRIND, CairnVoice.VESSEL)

    private fun spread(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test fun `the four earlier voices render byte-identically at models 5, 6 and 7`() {
        for (voice in earlier) for (note in listOf(48, 60, 72)) for (hold in listOf(0f, .5f, 1f)) {
            val m = Cairn.defaults(voice) + ("HOLD" to hold)
            val five = Cairn.render(voice, m, note, model = 5).samples
            for (model in 6..7) assertContentEquals(five, Cairn.render(voice, m, note, model = model).samples,
                "$voice MIDI$note HOLD$hold changed at model $model")
        }
        for (voice in earlier) {
            val m = Cairn.defaults(voice) + mapOf("WEIGHT" to .1f, "GROOVE" to .9f, "BODY" to .8f)
            assertContentEquals(Cairn.render(voice, m, 55, .4f, startAtContact = false, model = 5).samples,
                Cairn.render(voice, m, 55, .4f, startAtContact = false, model = 7).samples, "$voice moved")
        }
    }

    @Test fun `GRIND needs model 6 and VESSEL model 7, and both save and replay`() {
        assertEquals(7, Cairn.MODEL_VERSION)
        assertFailsWith<IllegalArgumentException> { CairnPatch("Drag", CairnVoice.GRIND, emptyMap(), model = 5) }
        assertFailsWith<IllegalArgumentException> { CairnPatch("Bowl", CairnVoice.VESSEL, emptyMap(), model = 6) }
        assertFailsWith<IllegalArgumentException> { Cairn.render(CairnVoice.GRIND, model = 5) }
        assertFailsWith<IllegalArgumentException> { Cairn.render(CairnVoice.VESSEL, model = 6) }
        CairnPatch("Drag", CairnVoice.GRIND, emptyMap(), model = 6)
        for (voice in added) {
            val patch = CairnPatch("New stone", voice, Cairn.defaults(voice), midi = 57, velocity = .7f)
            assertEquals(7, patch.model)
            val saved = CairnPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, saved)
            assertContentEquals(patch.render().samples, saved.render().samples)
        }
    }

    @Test fun `GRIND's work ledger closes and it never gains energy the drag did not supply`() {
        for (macros in listOf(emptyMap(), mapOf("GROOVE" to 0f), mapOf("GROOVE" to 1f, "SLOPE" to 1f),
            mapOf("WEIGHT" to 1f, "FRICTION" to 1f), mapOf("WEIGHT" to 0f, "FRICTION" to 0f, "SLOPE" to 0f)))
            for (velocity in listOf(.2f, .6f, 1f)) for (hold in listOf(0f, 1f)) {
                val m = Cairn.motion(CairnVoice.GRIND, macros + ("HOLD" to hold), velocity)
                val ledger = m.initialEnergy + m.suppliedWork - m.finalEnergy - m.withdrawnWork - m.dissipatedWork
                assertTrue(abs(ledger) <= 1e-8 * maxOf(1.0, m.suppliedWork), "$macros v$velocity H$hold ledger $ledger")
                assertTrue(m.finalEnergy <= m.initialEnergy + m.suppliedWork + 1e-9, "$macros v$velocity H$hold gained energy")
                assertTrue(m.events.any { it.stone == CairnGrind.CATCH }, "$macros v$velocity H$hold caught nothing")
                // A soft, slow drag is short: even then it slips more than once.
                val slips = m.events.count { it.stone == CairnGrind.SLIP }
                assertTrue(slips >= 2, "$macros v$velocity H$hold slipped $slips times")
            }
    }

    @Test fun `GRIND's slips are irregular, where TUMBLE's flights contract steadily`() {
        fun irregularity(times: List<Double>): Double {
            val gaps = times.zipWithNext { a, b -> b - a }.filter { it > 1e-6 }
            return spread(gaps.zipWithNext { a, b -> ln(b / a) })
        }
        for (macros in listOf(emptyMap(), mapOf("GROOVE" to .85f), mapOf("SLOPE" to .2f))) {
            val slips = Cairn.motion(CairnVoice.GRIND, macros).events.filter { it.stone == CairnGrind.SLIP }
            assertTrue(irregularity(slips.map { it.seconds }) > .3, "GRIND $macros slips too regular")
        }
        val tumble = Cairn.motion(CairnVoice.TUMBLE).events.map { it.seconds }
        assertTrue(irregularity(tumble) < .1, "TUMBLE should contract steadily")
    }

    @Test fun `both new voices stay in tune across MIDI 48 to 72`() {
        for (voice in added) for (note in listOf(48, 60, 72)) {
            val hz = Keys.midiHz(note)
            val s = Cairn.render(voice, Cairn.defaults(voice), note).samples
            val peak = PluckSpectra.peakHz(s, Cairn.RATE, hz, fromSec = .05f, seconds = .6f)
            val cents = 1200 * abs(log2(peak / hz))
            assertTrue(cents < 12, "$voice MIDI$note peak $peak Hz is $cents cents off")
        }
    }

    @Test fun `HOLD's top step locks both voices to a closed two-second loop`() {
        for (voice in added) for (macros in listOf(emptyMap(), mapOf("GROOVE" to 0f), mapOf("GROOVE" to 1f),
            mapOf("SLOPE" to 0f), mapOf("SLOPE" to 1f, "WEIGHT" to 1f))) for (note in listOf(48, 72)) {
            val r = Cairn.inspect(voice, Cairn.defaults(voice) + macros + ("HOLD" to 1f), note, .8f)
            val seam = assertNotNull(r.diagnostics.seamError)
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "$voice $macros MIDI$note seam $seam")
            assertEquals(2.0, r.diagnostics.motion.periodSeconds)
            assertEquals(2 * Cairn.RATE, r.samples.size)
        }
    }

    @Test fun `VESSEL's circles narrow and speed up as it settles`() {
        for (macros in listOf(emptyMap(), mapOf("SLOPE" to 1f), mapOf("FRICTION" to 0f))) {
            val m = Cairn.motion(CairnVoice.VESSEL, macros)
            val wall = m.states.filter { it.seconds > .02 && it.positions[0] > CairnVessel.bottomRadius(.4) * 1.01 }
            val first = wall.first(); val last = wall.last()
            assertTrue(last.positions[0] < .5 * first.positions[0], "$macros radius did not narrow")
            assertTrue(last.positions[1] > 1.4 * first.positions[1], "$macros orbit did not speed up")
            val knocks = m.events.filter { it.stone == CairnVessel.KNOCK }.map { it.seconds }
            val gaps = knocks.zipWithNext { a, b -> b - a }
            assertTrue(gaps.takeLast(3).average() < gaps.take(3).average(), "$macros knocks did not quicken")
        }
    }

    @Test fun `VESSEL's low air resonance follows BODY`() {
        fun airShare(body: Float): Double {
            val s = Cairn.render(CairnVoice.VESSEL, Cairn.defaults(CairnVoice.VESSEL) + ("BODY" to body)).samples
            val snip = com.snipsnap.audio.Snip(s, 1, Cairn.RATE)
            val hz = Keys.midiHz(60)
            return PluckSpectra.toneEnergy(snip, hz / 2, 1f) / PluckSpectra.toneEnergy(snip, hz, 1f)
        }
        assertTrue(airShare(.9f) > 1.5 * airShare(.1f))
    }

    @Test fun `both are silent at zero velocity and reproduce from a cold cache`() {
        for (voice in added) for (hold in listOf(0f, 1f)) {
            val m = Cairn.defaults(voice) + ("HOLD" to hold)
            assertTrue(Cairn.inspect(voice, m, velocity = 0f).raw.all { it == 0f }, "$voice H$hold not silent")
            val a = Cairn.render(voice, m, 55, .6f).samples
            Cairn.clearMotionCache()
            assertContentEquals(a, Cairn.render(voice, m, 55, .6f).samples, "$voice H$hold not deterministic")
        }
    }
}
