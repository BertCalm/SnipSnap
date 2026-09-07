package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmearTest {

    // 100ms of lead-in silence, then a kick-like thump plus a decaying 220Hz
    // tone starting together, ~1.1s total @44.1k.
    //
    // Fixture change from the brief's original (thump/tone starting at frame
    // 0): Transients.detect finds a hit as a *rise* in short-time energy
    // (see Transients.kt's novelty = the half-wave-rectified energy
    // derivative). A fixture that starts already at peak amplitude at frame
    // 0 has no quieter frame before it to rise from - the very first
    // analysis window (1024 frames) already contains the burst's peak, so
    // `Transients.detect` returned an empty list for the *dry* signal too
    // (confirmed independent of Smear). Prepending real silence gives the
    // detector a genuine attack to find, which is what "the attack stays
    // crisp and in place" needs to test in the first place.
    private val lead = 4_410 // 100ms

    private fun fixture(): Snip {
        val n = lead + 44_100
        val out = FloatArray(n)
        // thump: 40ms decaying 60Hz burst, sharp attack
        for (i in 0 until 1_764) {
            val env = 1f - i / 1_764f
            out[lead + i] += (0.9f * env * env * kotlin.math.sin(2.0 * Math.PI * 60.0 * i / 44_100.0)).toFloat()
        }
        // A bare low-frequency burst is thin food for HPSS: a decaying sub
        // swell is a near-horizontal line on its own and reads mostly
        // harmonic (see SeparateTest's "a boomy kick's sub ... honestly
        // reads harmonic"), so the transient's energy would land mostly in
        // the stretched half rather than the untouched percussive one.
        // RetimeTest hit the same problem and fixed it the same way: a hat
        // layered on top for a crisp broadband attack.
        val hat = DrumSynth.closedHat()
        for (i in hat.samples.indices) {
            val idx = lead + i
            if (idx < out.size) out[idx] += hat.samples[i] * 0.6f
        }
        // tone: 220Hz, decays to silence 0.4s after it starts
        for (i in 0 until 17_640) {
            val env = 1f - i / 17_640f
            out[lead + i] += (0.5f * env * kotlin.math.sin(2.0 * Math.PI * 220.0 * i / 44_100.0)).toFloat()
        }
        return Snip(out, 1, 44_100)
    }

    // Goertzel band energy over a window - same probe SeparateTest uses
    // (CaptureDoctor.goertzel is internal, reachable from :audio's own tests).
    private fun bandEnergy(snip: Snip, hz: Float, fromFrame: Int, toFrame: Int): Float {
        val to = minOf(toFrame, snip.frameCount)
        if (to <= fromFrame) return 0f
        val seg = snip.samples.copyOfRange(fromFrame, to)
        return CaptureDoctor.goertzel(seg, seg.size, hz, snip.sampleRate)
    }

    @Test
    fun `amount zero is identity`() {
        val s = fixture()
        assertSame(s, Smear.process(s, 0f))
    }

    @Test
    fun `length is preserved`() {
        val s = fixture()
        assertEquals(s.frameCount, Smear.process(s, 0.6f).frameCount)
        assertEquals(s.frameCount, Smear.process(s, 1f).frameCount)
    }

    @Test
    fun `the attack stays crisp and in place`() {
        val s = fixture()
        val smeared = Smear.process(s, 1f)
        val dryOnset = Transients.detect(s).firstOrNull()
        val wetOnset = Transients.detect(smeared).firstOrNull()
        assertNotNull(wetOnset, "smeared output must still have a detectable attack")
        assertNotNull(dryOnset)
        assertTrue(
            kotlin.math.abs(wetOnset.frame - dryOnset.frame) <= 1_024,
            "attack moved: dry ${dryOnset.frame} vs wet ${wetOnset.frame}",
        )
    }

    @Test
    fun `the tonal body bleeds later than the dry tone`() {
        val s = fixture()
        val smeared = Smear.process(s, 1f)
        // Dry tone is over 0.4s after it starts (i.e. at lead+17_640); probe
        // a window starting 50ms after that, ending 0.8s in, where only the
        // smeared bed should still be singing.
        val lateDry = bandEnergy(s, 220f, lead + 19_845, lead + 35_280)
        val lateWet = bandEnergy(smeared, 220f, lead + 19_845, lead + 35_280)
        assertTrue(lateWet > lateDry * 2f, "expected the smeared bed to bleed past the dry decay (dry=$lateDry wet=$lateWet)")
    }
}
