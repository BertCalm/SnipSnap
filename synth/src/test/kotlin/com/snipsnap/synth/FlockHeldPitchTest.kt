package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlockHeldPitchTest {
    @Test
    fun `native capture roots remain tuned through the held body at both register limits`() {
        // A narrow, strongly restored flock gives a meaningful tuning probe. A
        // deliberately wide cloud need not give one confident monophonic estimate.
        val patch = FlockPatch("TUNING PROBE", FlockVoice.RIBBON,
            Flock.defaults(FlockVoice.RIBBON) + mapOf("SPREAD" to 0f, "PULL" to 1f, "SCATTER" to 0f, "EDGE" to 0f),
            seed = 42001L, playback = FlockPlayback.HELD)
        // Native-rate probes cover the register cheaply; the highest note also
        // goes through production's four-times synthesis and decimation.
        for ((midi, oversample) in listOf(36 to 1, 60 to 1, 84 to 1, 84 to 4)) {
            val held = FlockHeld.renderUncached(patch, midi, oversample)
            for (from in listOf(2.5f, 5.5f)) {
                val estimate = assertNotNull(Pitch.detect(held.snip, fromSec = from, windowSec = .4f),
                    "MIDI $midi at ${oversample}x has no pitched body at $from s")
                val cents = 1200 * ln(estimate.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
                assertTrue(estimate.confidence >= .5f,
                    "MIDI $midi at ${oversample}x lacks a confident narrow-flock root: $estimate")
                assertTrue(abs(cents) < 25,
                    "MIDI $midi at ${oversample}x is $cents cents from its native root at $from s ($estimate)")
            }
        }
    }
}
