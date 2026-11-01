package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class RimeHeldTest {
    @Test fun `all keyboard maps retain the saved root at range boundaries`() {
        for (midi in Rime.MIN_MIDI..Rime.MAX_MIDI) {
            val roots = Keys.rimePadMidis(RimePatch("Root map", RimeVoice.GLINT, midi = midi))
            assertEquals(9, roots.size)
            assertTrue(midi in roots)
            assertTrue(roots.all { it in Rime.MIN_MIDI..Rime.MAX_MIDI })
            assertTrue(roots.zipWithNext().all { (a,b) -> b - a == 3 })
        }
    }

    @Test fun `finite model five preserves all nine accepted recipes exactly`() {
        for (p in RimePresets.all()) assertContentEquals(p.render().samples, p.copy(model = 5).render().samples, p.name)
    }

    @Test fun `held cycles close independently with bounded energy pitch and dc across registers`() {
        for (voice in RimeVoice.entries) for ((midi, body) in listOf(24 to 1f, Rime.rootMidi(voice) to .5f, 96 to 0f)) {
            val p = RimePresets.starter(voice).copy(model = 5, midi = midi,
                macros = Rime.defaults(voice) + mapOf("HOLD" to 1f, "BODY" to body, "EDGE" to 1f, "UNFOLD" to 1f))
            val began = System.nanoTime()
            val r = RimeHeld.render(p)
            val cents = 1200 * log2(r.actualHz / (440 * 2.0.pow((midi - 69) / 12.0)))
            println("RIME HOLD $voice/$midi: seam=${r.seamError}, cents=$cents, ms=${(System.nanoTime()-began)/1e6}")
            assertTrue(r.seamError < Keys.MAX_SEAM_ERROR)
            assertTrue(abs(cents) < .1)
            val rootHz = Keys.midiHz(midi)
            val detected = assertNotNull(FineTuning.measuredHz(
                com.snipsnap.audio.Snip(r.previousCycle + r.cycle, 1, Dsp.RATE), rootHz, fromSec = .02f, bodySeconds = .3f))
            assertTrue(abs(FineTuning.cents(detected.toDouble(), rootHz.toDouble())) < 5.0,
                "$voice/$midi detected root changed: $detected")
            assertTrue(r.cycle.all { it.isFinite() && abs(it) <= .94001f })
            assertTrue(abs(r.cycle.average()) < .001)
            assertTrue(r.maxBodyEnergy.isFinite() && r.maxBodyEnergy < 1e8)
            assertTrue(r.cycle.maxOf { abs(it) } > .005f)
            assertTrue(Keys.seamError(r.note.snip.samples, r.note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            val damaged = r.previousCycle + r.cycle.map { -it }.toFloatArray()
            assertTrue(Keys.seamError(damaged, r.cycle.size) > 1.0, "metric must detect a broken independently rendered cycle")
        }
    }

    @Test fun `unfold changes the opening while the resolved sustain remains stable`() {
        val p = RimePresets.starter(RimeVoice.LOOP).copy(model = 5)
        val a = RimeHeld.render(p.copy(macros = p.macros + ("UNFOLD" to 0f)))
        val b = RimeHeld.render(p.copy(macros = p.macros + ("UNFOLD" to 1f)))
        val difference = (0 until 4410).sumOf { (a.note.snip.samples[it] - b.note.snip.samples[it].toDouble()).pow(2) }
        assertTrue(difference > .01, "UNFOLD must retain an audible opening gesture")
        val steadyDifference = a.cycle.indices.sumOf { (a.cycle[it] - b.cycle[it].toDouble()).pow(2) } / a.cycle.sumOf { it.toDouble().pow(2) }
        assertTrue(steadyDifference < 1e-8)
        a.note.snip.samples.fill(99f)
        assertTrue(RimeHeld.render(p.copy(macros = p.macros + ("UNFOLD" to 0f))).note.snip.samples.all { abs(it) <= .94001f }, "cache must own its audio")
    }

    @Test fun `hold extends finite notes then switches to a saved periodic recipe`() {
        val p = RimePresets.starter(RimeVoice.GLINT).copy(model = 5, gateSeconds = .15f)
        assertFailsWith<IllegalArgumentException> {
            Rime.render(p.voice, p.macros + ("HOLD" to .5f), gateSeconds = -1f)
        }
        val short = p.render()
        val extended = p.copy(macros = p.macros + ("HOLD" to .5f)).render()
        assertTrue(extended.frameCount > short.frameCount)
        val held = p.copy(macros = p.macros + ("HOLD" to 1f))
        assertEquals(held, RimePatch.fromJsonText(held.toJsonText()))
        assertContentEquals(held.render().samples, held.render().samples)
        assertTrue(held.copy(velocity = 0f).render().samples.all { it == 0f })
        for (model in 1..4) assertFailsWith<IllegalArgumentException> { held.copy(model = model) }
    }
}
