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

    /**
     * `SirenDroneMaker.span`/`label` run on the SYNTH screen's own UI
     * thread (off it, via a coroutine, but still on a tap — a ROOT +/-
     * stepper, not a background job) unlike RESIN's own closed-form
     * `DroneMaker.label`, since SIREN's own nudge is read off an LFO phase
     * integral over the candidate span's own frame count. The grid's
     * slowest tempo (40 BPM) and longest bars (8) at the longest span (8
     * intervals) is the worst case that flow ever asks for; this holds
     * that it never throws, at DEPTH and RATE both pinned to their own
     * extremes (the most LFO motion `fitCarrier`'s own integral has to
     * do) — measured at ~850 ms per voice, which is why it runs off the
     * main thread rather than merely being asserted "fast enough" here.
     */
    @Test
    fun `span and label finish quickly even at the grid's slowest, longest setting`() {
        val slowest = com.snipsnap.loop.Session(
            tracks = List(com.snipsnap.loop.Session.TRACK_COUNT) {
                com.snipsnap.loop.Track(name = "EMPTY", chain = listOf(com.snipsnap.loop.SilenceBlock), engaged = false)
            },
            bpm = com.snipsnap.loop.Session.MIN_BPM,
            barsPerInterval = com.snipsnap.loop.Session.VALID_BARS.max(),
            sampleRate = 48_000,
        )
        for (voice in SirenVoice.entries) {
            val spec = SirenDrone.Spec(voice, mapOf("RATE" to 1f, "DEPTH" to 1f, "GRIT" to 1f))
            val root = Siren.ROOT_MIDI
            val t0 = System.nanoTime()
            val span = SirenDroneMaker.span(spec, root, slowest)
            val label = SirenDroneMaker.label(spec, root, slowest)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(span in DroneFit.SPANS, "$voice: span $span must be one DroneFit itself offers")
            assertTrue(label.isNotBlank(), "$voice: label came back blank")
            assertTrue(ms < 5_000, "$voice: span+label took ${ms}ms at the grid's worst setting")
        }
    }

    /** `span`/`label` thread `cancelled` all the way to `SirenDrone.fitCarrier`'s own check (review finding on PR #373) — a rapid run of ROOT taps must be able to stop one before starting the next. */
    @Test
    fun `span and label stop when nobody wants them any more`() {
        val fresh = SessionBuilder.empty(44_100)
        val spec = SirenDrone.Spec(SirenVoice.WAIL, mapOf("RATE" to 0.5f, "DEPTH" to 1f))
        val root = Siren.ROOT_MIDI
        var asked = 0
        val t0 = System.nanoTime()
        kotlin.test.assertFailsWith<java.util.concurrent.CancellationException> {
            SirenDroneMaker.span(spec, root, fresh) { ++asked > 0 }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(asked >= 1, "span never asked")
        assertTrue(ms < 200, "a cancelled span ran on for ${ms}ms")
    }
}
