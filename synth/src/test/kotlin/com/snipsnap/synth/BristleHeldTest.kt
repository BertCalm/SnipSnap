package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BristleHeldTest {
    @Test fun `all notes and touches close against genuine preceding audio and stay in tune`() {
        var worst = 0.0; var cents = 0.0
        for (route in Bristle.Route.entries) for (midi in 24..48) for (v in listOf(.35f,.65f,1f)) {
            val patch = BristlePatch("Held", route, Bristle.defaults, midi, v)
            val render = BristleHeld.render(patch)
            val note = render.note
            assertEquals(44100L, note.loopStartFrame)
            assertTrue(note.snip.samples.all { it.isFinite() && abs(it) <= .991f })
            assertTrue(abs(note.snip.samples.average()) < .005)
            assertTrue(render.seamError < Keys.MAX_SEAM_ERROR)
            worst = max(worst, render.seamError)
            cents = max(cents, abs(1200 * log2(render.actualHz / Bristle.frequencyFor(midi))))
            val x = note.snip.samples
            if (v == 1f && midi >= 28) {
                val steady = com.snipsnap.audio.Snip(x.copyOfRange(44100, x.size), 1, 44100)
                val pitch = assertNotNull(com.snipsnap.audio.Pitch.detect(steady, .05f, .2f))
                assertTrue(abs(1200 * log2(pitch.hz / Bristle.frequencyFor(midi))) < 15.0,
                    "rendered sustain root $route $midi: ${pitch.hz}")
            }
            // A broken seam must fail the same independent-history test.
            if (midi == 36 && v == 1f) {
                val broken = x.copyOf()
                for (i in broken.size-256 until broken.size) broken[i] = -broken[i]
                assertTrue(Keys.seamError(broken, note.loopStartFrame.toInt()) > Keys.MAX_SEAM_ERROR)
            }
        }
        assertTrue(cents < .1, "quantized sustain pitch: $cents cents")
        println("BRISTLE HELD ${Bristle.Route.entries.size * 25 * 3} cells: worst seam=$worst, max tuning error=$cents cents")
    }

    @Test fun `maximum slide bite and both hold extremes settle before the loop`() {
        for (route in Bristle.Route.entries) for (midi in listOf(24,36,48)) for (hold in listOf(0f,1f)) {
            val patch = BristlePatch("Corner", route, Bristle.defaults + mapOf(
                "SLIDE" to 1f, "BITE" to 1f, "GRIT" to 1f, "DARK" to 1f, "HOLD" to hold), midi)
            val a = BristleHeld.render(patch)
            assertTrue(a.seamError < Keys.MAX_SEAM_ERROR)
            assertContentEquals(a.note.snip.samples, BristleHeld.render(patch).note.snip.samples)
        }
    }

    @Test fun `held soft touch changes spectrum as well as level`() {
        for (patch in BristlePresets.all()) {
            val soft = BristleHeld.render(patch.copy(velocity = .35f)).note.snip
            val hard = BristleHeld.render(patch).note.snip
            assertTrue(com.snipsnap.audio.FeatureExtractor.extract(soft).centroidHz <
                com.snipsnap.audio.FeatureExtractor.extract(hard).centroidHz * .95f)
            assertTrue(soft.samples.maxOf { abs(it) } < hard.samples.maxOf { abs(it) })
        }
    }

    @Test fun `silent sources and cancelled work are refused`() {
        val patch = BristlePresets.all().first()
        assertFailsWith<IllegalArgumentException> { BristleHeld.render(patch.copy(velocity = 0f)) }
        assertFailsWith<java.util.concurrent.CancellationException> { BristleHeld.render(patch) { true } }
        var polls = 0
        assertFailsWith<java.util.concurrent.CancellationException> { BristleHeld.render(patch) { ++polls > 3 } }
    }
}
