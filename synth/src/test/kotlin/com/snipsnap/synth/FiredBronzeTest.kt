package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.test.*

class FiredBronzeTest {
    private fun energy(s: Snip, from: Double = 0.0, until: Double = s.durationSeconds.toDouble()): Double =
        s.samples.sliceArray((from * s.sampleRate).toInt() until minOf(s.frameCount, (until * s.sampleRate).toInt()))
            .sumOf { it.toDouble() * it }

    @Test fun `all sixteen sources and treatments are finite bounded mono one shots`() {
        for (p in FiredBronze.Part.entries) for (stage in FiredBronze.Stage.entries) {
            val s = FiredBronze.Recipe(p, stage = stage).render()
            assertEquals(1, s.channels)
            assertEquals(44100, s.sampleRate)
            assertTrue(s.durationSeconds < 1.5f, "$p $stage duration ${s.durationSeconds}")
            assertTrue(s.samples.all { it.isFinite() && abs(it) <= 1f }, "$p $stage")
            assertTrue(s.peak() > .01f, "$p $stage silence")
            assertTrue(abs(s.samples.average()) < .005, "$p $stage DC ${s.samples.average()}")
            assertTrue(abs(s.samples.last()) < .002, "$p $stage ending")
        }
    }

    @Test fun `study JSON round trips every part and refuses unknown model versions`() {
        for (p in FiredBronze.Part.entries) {
            val r = FiredBronze.Recipe(p, velocity = .65f, bpm = 117f, take = 4)
            val replay = FiredBronze.Recipe.fromJsonText(r.toJsonText())
            assertEquals(r, replay)
            assertContentEquals(r.render().samples, replay.render().samples)
            val future = JsonValue.Obj(Json.parse(r.toJsonText()).obj() + ("version" to JsonValue.Num(99.0)))
            assertFailsWith<IllegalArgumentException> {
                FiredBronze.Recipe.fromJsonText(Json.write(future))
            }
        }
    }

    @Test fun `damped dayan changes partial balance and loses late energy`() {
        val open = FiredBronze.Recipe(FiredBronze.Part.DAYAN_OPEN, stage = FiredBronze.Stage.DRY).render()
        val damped = FiredBronze.Recipe(FiredBronze.Part.DAYAN_DAMPED, stage = FiredBronze.Stage.DRY).render()
        assertTrue(energy(damped, .15) / energy(damped) < energy(open, .15) / energy(open) * .15)
        // Compare early normalized correlation as well: a shorter copy of the
        // open hit must not be accepted as a different articulation.
        val n = (open.sampleRate * .025).toInt()
        var dot = 0.0; var a = 0.0; var b = 0.0
        for (i in 0 until n) { dot += open.samples[i] * damped.samples[i]; a += open.samples[i] * open.samples[i]; b += damped.samples[i] * damped.samples[i] }
        assertTrue(abs(dot / kotlin.math.sqrt(a * b)) < .85)
    }

    @Test fun `pressure is a continuous rise hold release rather than a kick droop`() {
        assertEquals(1.0, FiredBronze.pressureRatio(0.0, .8f))
        assertTrue(FiredBronze.pressureRatio(.20, .8f) > 1.2)
        assertEquals(1.0, FiredBronze.pressureRatio(.60, .8f))
        for (join in listOf(.06, .18, .25, .55)) assertTrue(abs(
            FiredBronze.pressureRatio(join - 1e-6, .8f) - FiredBronze.pressureRatio(join + 1e-6, .8f)) < 1e-4)
        // Observe the audio, not just the parameter law: zero-crossing pitch
        // rises in the pressed window and returns near its unpressed value.
        val s = FiredBronze.Recipe(FiredBronze.Part.BAYAN, stage = FiredBronze.Stage.DRY).render()
        fun frequency(from: Double, to: Double): Double {
            val start = (from * s.sampleRate).toInt(); val stop = (to * s.sampleRate).toInt()
            val crossings = (start + 1 until stop).filter { s.samples[it - 1] <= 0f && s.samples[it] > 0f }
            return (crossings.size - 1) * s.sampleRate.toDouble() / (crossings.last() - crossings.first())
        }
        val pressed = frequency(.19, .24)
        val released = frequency(.6, .75)
        assertTrue(pressed > released * 1.1, "$pressed vs $released")
    }

    @Test fun `sixteenth delay follows saved tempo to within one sample`() {
        for (bpm in listOf(60f, 96f, 117f, 180f)) {
            val frames = Echo.delayFrames(FiredBronzeTreatment.delayControl(bpm), Dsp.RATE)
            assertTrue(abs(frames - 60f / bpm / 4 * Dsp.RATE) <= 1.1f)
        }
    }

    @Test fun `amp follows processor and is not commutative with its trails`() {
        val r = FiredBronze.Recipe(FiredBronze.Part.BONANG_LOW)
        val dry = r.copy(stage = FiredBronze.Stage.DRY).render()
        val proc = r.copy(stage = FiredBronze.Stage.PROCESSOR).render()
        val expected = FiredBronzeTreatment.amp(proc, r.part, r.macros, dry.frameCount)
        assertContentEquals(expected.samples, r.render().samples)
        val reversed = FiredBronzeTreatment.processor(
            FiredBronzeTreatment.amp(dry, r.part, r.macros, dry.frameCount), r.part, r.macros, r.bpm)
        assertFalse(expected.samples.contentEquals(reversed.samples))
    }

    @Test fun `each macro changes its intended stage and unsupported keys cannot alter synthesis`() {
        val base = FiredBronze.defaults()
        assertEquals(base, FiredBronze.settled(base + ("UNKNOWN" to 1f)))
        for (name in base.keys) {
            assertEquals(base.getValue(name), FiredBronze.settled(base + (name to Float.NaN)).getValue(name))
            val lo = FiredBronze.Recipe(FiredBronze.Part.DAYAN_OPEN, base + (name to .2f)).render()
            val hi = FiredBronze.Recipe(FiredBronze.Part.DAYAN_OPEN, base + (name to .7f)).render()
            val rms = kotlin.math.sqrt((0 until minOf(lo.frameCount, hi.frameCount)).sumOf {
                val d = lo.samples[it] - hi.samples[it]; (d * d).toDouble()
            } / minOf(lo.frameCount, hi.frameCount))
            assertTrue(rms > .0001, "$name inactive: $rms")
        }
    }

    @Test fun `corners and native velocity remain bounded and softer hits change timbre`() {
        for (part in FiredBronze.Part.entries) {
            val hard = FiredBronze.Recipe(part).render()
            val soft = FiredBronze.Recipe(part, velocity = .3f).render()
            assertTrue(soft.peak() < hard.peak())
            assertFalse(Velocity.peakMatch(hard, soft).samples.contentEquals(hard.samples))
            for (edge in listOf(0f, 1f)) {
                val s = FiredBronze.Recipe(part, FiredBronze.defaults().mapValues { edge }).render()
                assertTrue(s.samples.all { it.isFinite() && abs(it) <= 1f })
            }
            assertEquals(0f, FiredBronze.Recipe(part, velocity = 0f).render().peak())
        }
    }

    @Test fun `kit retains kick snare paired hats names and three timbre layers`() {
        val pads = FiredBronzeKits.study()
        assertEquals(16, pads.size)
        assertEquals(FiredBronze.Part.entries.map { it.drumClass }, pads.map { it.drumClass })
        assertEquals(1, pads[2].muteGroup); assertEquals(1, pads[3].muteGroup)
        assertEquals(16, pads.map { it.displayName }.toSet().size)
        for (pad in pads) {
            assertEquals(2, pad.softVariants.size)
            for (soft in pad.softVariants) assertEquals(pad.snip.peak(), soft.peak(), 1e-5f)
            assertNull(pad.recipe, "study recipes are not registered app recipes")
        }
    }
}
