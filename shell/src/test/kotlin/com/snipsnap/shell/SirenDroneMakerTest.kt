package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.loop.DroneFit
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.synth.Siren
import com.snipsnap.synth.SirenDrone
import com.snipsnap.synth.SirenVoice
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SirenDroneMakerTest {

    @Test
    fun `every voice shares SIREN's own register`() {
        val want = Siren.ROOT_MIDI..(Siren.ROOT_MIDI + Siren.TUNE_SEMITONES)
        for (voice in SirenVoice.entries) assertEquals(want, SirenDroneMaker.roots(voice))
    }

    @Test
    fun `the default root is the kit's key at the bottom of the register`() {
        assertEquals(Siren.ROOT_MIDI, SirenDroneMaker.defaultRoot(SirenVoice.WAIL, null))
        assertEquals(Siren.ROOT_MIDI + 2, SirenDroneMaker.defaultRoot(SirenVoice.WAIL, KeySpec(2, Scale.entries.first()))) // D4
    }

    @Test
    fun `a recipe keeps only what a drone hears`() {
        val s = SirenDroneMaker.spec(SirenVoice.WAIL, mapOf("RATE" to 0.2f, "DEPTH" to 0.9f, "GRIT" to 0.1f, "HOLD" to 0.5f, "TUNE" to 0.5f, "SWEEP" to 0.5f))
        assertEquals(setOf("RATE", "DEPTH", "GRIT"), s.macros.keys)
    }

    /**
     * Not [DroneMaker]'s own model (review finding on PR #368): that
     * assumes RESIN's even-only sub-octave snap, while SIREN's own carrier
     * allows any whole cycle count and depends on DEPTH through the LFO's
     * phase integral. [SirenDroneMaker.span] must pick the shortest span
     * whose nudge, measured off [SirenDrone.nudgeCents] directly, is within
     * the tuning promise — not merely whatever [DroneFit.spanFor]'s own
     * pitch-only formula happens to say for the same root.
     */
    @Test
    fun `span is chosen against SIREN's own nudge, not RESIN's model`() {
        val fresh = SessionBuilder.empty(44_100)
        val spec = SirenDrone.Spec(SirenVoice.WAIL, mapOf("RATE" to 0.5f, "DEPTH" to 1f, "GRIT" to 0.3f))
        val root = Siren.ROOT_MIDI
        val span = SirenDroneMaker.span(spec, root, fresh)
        assertTrue(span in DroneFit.SPANS, "span $span must be one DroneFit itself offers")
        val frames = span.toLong() * fresh.intervalFrames
        val cents = SirenDrone.nudgeCents(spec.voice, spec.macros, root, frames, fresh.sampleRate)
        assertTrue(abs(cents) <= DroneFit.MAX_NUDGE_CENTS, "span $span misses its own tuning promise by $cents cents")
        // A span one step shorter (when there is one) must fail SIREN's own
        // promise, or spanFor would have picked it instead.
        val shorter = DroneFit.SPANS.takeWhile { it < span }.lastOrNull()
        if (shorter != null) {
            val shortCents = SirenDrone.nudgeCents(spec.voice, spec.macros, root, shorter.toLong() * fresh.intervalFrames, fresh.sampleRate)
            assertTrue(abs(shortCents) > DroneFit.MAX_NUDGE_CENTS, "span $shorter already met the promise; spanFor should have chosen it")
        }
    }

    @Test
    fun `the readout names the note the render actually lands on`() {
        val fresh = SessionBuilder.empty(44_100)
        val spec = SirenDrone.Spec(SirenVoice.LASER, mapOf("RATE" to 0.4f, "DEPTH" to 0.6f))
        val root = Siren.ROOT_MIDI + 6
        val label = SirenDroneMaker.label(spec, root, fresh)
        val span = SirenDroneMaker.span(spec, root, fresh)
        val cents = SirenDrone.nudgeCents(spec.voice, spec.macros, root, span.toLong() * fresh.intervalFrames, fresh.sampleRate)
        assertTrue(label.contains("%+.2f¢".format(java.util.Locale.ROOT, cents)), "label '$label' does not carry the measured nudge $cents¢")
    }
}
