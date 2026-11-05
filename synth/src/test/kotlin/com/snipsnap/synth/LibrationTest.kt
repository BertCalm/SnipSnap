package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.*
import kotlin.test.*

class LibrationTest {
    @Test fun `all equilibria have negligible residual and remain stationary`() {
        for (family in LibrationTrajectory.Family.entries) {
            val e = LibrationTrajectory.equilibrium(family)
            val a = LibrationTrajectory.acceleration(e)
            assertTrue(hypot(a.first, a.second) < 2e-12, "$family residual $a")
            val p = LibrationTrajectory.integrate(family, 0, e, 2.0, 100)
            assertTrue(p.report.maxExcursion < 1e-7, "$family equilibrium moved ${p.report.maxExcursion}")
        }
    }

    @Test fun `shipped triangular ranges remain confined and conserve Jacobi after the impulse`() {
        for (family in listOf(LibrationTrajectory.Family.L4, LibrationTrajectory.Family.L5))
            for (wander in listOf(0.0, 1.0)) for (nudge in listOf(0.0, 1.0)) for (id in listOf(0, 7, 15)) {
                val initial = LibrationTrajectory.initial(family, id, wander, nudge)
                val p = LibrationTrajectory.integrate(family, id, initial, 8.0, 500)
                assertEquals("horizon", p.report.termination, "$family $wander $nudge $id")
                assertTrue(p.report.maxExcursion < .12, "$family escaped: ${p.report}")
                assertTrue(p.report.jacobiDrift < 1e-6, "$family drift: ${p.report}")
            }
    }

    @Test fun `departure time changes with initialized nudge rather than an envelope timer`() {
        val family = LibrationTrajectory.Family.L2
        val times = listOf(0.0, .25, .5, .75, 1.0).map { nudge ->
            val initial = LibrationTrajectory.initial(family, 0, .5, nudge)
            val p = LibrationTrajectory.integrate(family, 0, initial, 6.0)
            assertTrue(p.report.jacobiDrift < 1e-6)
            assertNotNull(p.report.departureSeconds)
        }
        assertTrue(times.zipWithNext().all { (a, b) -> a - b > .20 }, "$times")
        assertTrue(times.first() - times.last() > 1.5, "$times")
        println("Model 2 NUDGE departure seconds: $times")
    }

    @Test fun `solver converges with tighter tolerances and finer control sampling`() {
        val family = LibrationTrajectory.Family.L2
        val initial = LibrationTrajectory.initial(family, 3, .5, .6)
        val a = LibrationTrajectory.integrate(family, 3, initial, 4.0, 250)
        val b = LibrationTrajectory.integrate(family, 3, initial, 4.0, 500, 1e-11, 1e-13)
        val c = LibrationTrajectory.integrate(family, 3, initial, 4.0, 1000, 1e-12, 1e-14)
        val ab = LibrationTrajectory.distance(a.states.last(), b.states.last())
        val bc = LibrationTrajectory.distance(b.states.last(), c.states.last())
        assertTrue(ab < 1e-7 && bc < 1e-7, "$ab $bc")
    }

    @Test fun `exclusion far-field and exhausted work have deterministic finite outcomes`() {
        val family = LibrationTrajectory.Family.L2
        assertFailsWith<IllegalArgumentException> {
            LibrationTrajectory.integrate(family, 0, LibrationTrajectory.State(1 - LibrationTrajectory.MU, 0.0, 0.0, 0.0), 1.0)
        }
        val cap = LibrationTrajectory.integrate(family, 0, LibrationTrajectory.initial(family, 0, .5, .5), 1.0, maxWork = 1)
        assertEquals("work-cap", cap.report.termination)
        assertTrue(cap.states.all { it.array().all(Double::isFinite) })
        val far = LibrationTrajectory.integrate(family, 0, LibrationTrajectory.State(7.99, 0.0, 5.0, 0.0), 1.0)
        assertEquals("far-field", far.report.termination)
        val near = LibrationTrajectory.integrate(family, 0,
            LibrationTrajectory.State(1 - LibrationTrajectory.MU + .0101, 0.0, -1.0, 0.0), .3)
        assertEquals("near-primary", near.report.termination)
    }

    @Test fun `SWARM adds stable identities and REGION blends separate families within the voice cap`() {
        val options = Libration.Options(seconds = .3)
        val low = Libration.population(LibrationVoice.TWINS, mapOf("SWARM" to .3f), options)
        val high = Libration.population(LibrationVoice.TWINS, mapOf("SWARM" to 1f), options)
        for (particle in low.particles) {
            val same = high.particles.first { it.id == particle.id && it.family == particle.family }
            assertEquals(particle.path.states.toList(), same.path.states.toList())
        }
        val blended = Libration.population(LibrationVoice.GATHER, mapOf("REGION" to .375f, "SWARM" to 1f), options)
        assertEquals(setOf(LibrationTrajectory.Family.L1, LibrationTrajectory.Family.L2), blended.particles.map { it.family }.toSet())
        assertTrue(blended.particles.size <= 32)
    }

    @Test fun `audio is repeatable pitched finite and preserves velocity silence`() {
        val options = Libration.Options(seconds = .8)
        for (voice in LibrationVoice.entries) {
            val a = Libration.probe(voice, options = options)
            val b = Libration.probe(voice, Libration.defaults(voice).entries.reversed().associate { it.key to it.value }, options = options)
            assertContentEquals(a.samples.samples, b.samples.samples)
            assertTrue(a.raw.samples.all { it.isFinite() })
            assertTrue(a.samples.peak() <= .941f)
            assertTrue(a.diagnostics.maxPitchDeviation <= 12.01)
            val pitch = assertNotNull(Pitch.detect(a.samples, .15f, .3f))
            val cents = 1200 * log2(pitch.hz / Keys.midiHz(60))
            assertTrue(abs(cents) < 12, "$voice root $cents cents")
            assertTrue(Libration.probe(voice, velocity = 0f, options = options).samples.samples.all { it == 0f })
        }
    }

    @Test fun `patch and pad recipe recall complete note and model while rejecting malformed input`() {
        val patch = LibrationPatch("First flight", LibrationVoice.TWINS, midi = 48, velocity = .6f)
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        val recipe = PadRecipe(patch = patch)
        assertEquals(patch, PadRecipe.fromJsonText(recipe.toJsonText()).patch)
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("WANDER" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("HOLD" to 1f)) }
        assertEquals(1, (Patches.fromJsonText(patch.copy(model = 1).toJsonText()) as LibrationPatch).model)
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 4) }
        assertEquals(1f, Libration.settled(patch.voice, mapOf("SWARM" to 2f)).getValue("SWARM"))
    }

    @Test fun `middle resonance leaves measurable memory after the source closes without clipping`() {
        val options = Libration.Options(seconds = 2.0)
        fun rms(s: FloatArray, from: Double, to: Double): Double {
            val a = (from * Dsp.RATE).toInt(); val b = (to * Dsp.RATE).toInt()
            return sqrt((a until b).sumOf { s[it].toDouble().pow(2) } / (b - a))
        }
        val voice = LibrationVoice.DEPARTURE
        val m = mapOf("NUDGE" to .35f, "RESONANCE" to .5f)
        val p = Libration.probe(voice, m, options = options)
        val legacy = Libration.probe(voice, m, options = options, model = 1)
        val tail = rms(p.raw.samples, 2.1, 2.6)
        val oldTail = rms(legacy.raw.samples, 2.1, 2.6)
        assertTrue(tail > .002 && tail > 8 * oldTail, "$tail versus $oldTail")
        assertTrue(p.raw.peak() < .8f)
        val dry = Libration.probe(voice, m + ("RESONANCE" to 0f), options = options)
        val dryTail = rms(dry.raw.samples, 2.3, 2.8)
        assertTrue(dryTail < tail * .01, "$dryTail versus $tail")
        println("Model 2 middle resonance tail RMS: $tail; model 1: $oldTail")
    }

    @Test fun `cached physics accepts voicing changes and rejects stale physical controls or models`() {
        val voice = LibrationVoice.GATHER
        val options = Libration.Options(seconds = .2)
        val p = Libration.population(voice, emptyMap(), options)
        Libration.probe(voice, mapOf("RESONANCE" to .8f), midi = 48, options = options, cached = p)
        assertFailsWith<IllegalArgumentException> {
            Libration.probe(voice, mapOf("NUDGE" to 1f), options = options, cached = p)
        }
        assertFailsWith<IllegalArgumentException> { Libration.probe(voice, options = options, cached = p, model = 1) }
    }
}
