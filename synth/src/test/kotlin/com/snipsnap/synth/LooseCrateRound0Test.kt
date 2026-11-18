package com.snipsnap.synth

import com.snipsnap.synth.LooseCrateRound0Generator.candidates
import com.snipsnap.synth.LooseCrateRound0Generator.pad
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 0's lessons, held on the renders: the same arguments give the same bytes, the print never
 * squares a groove off (the first render measured a 2.6 dB crest factor), a multi-strike pad's strikes
 * land where they were written, and a soft hat is a darker sound, not only a quieter one.
 */
class LooseCrateRound0Test {
    private val rate = Dsp.RATE

    @Test
    fun everyCandidateAndTheGrooveRenderTheSameBytesTwice() {
        for (c in candidates) assertContentEquals(pad(c, .65f, 2, printed = true).samples, pad(c, .65f, 2, printed = true).samples, c.name)
        assertContentEquals(LooseCrateRound0Generator.groove(true).samples, LooseCrateRound0Generator.groove(true).samples)
    }

    @Test
    fun thePrintedGrooveKeepsItsPeaksAndIsBarExact() {
        val g = LooseCrateRound0Generator.groove(printed = true).samples
        assertEquals(Math.round(16 * 60.0 / 88 * rate).toInt(), g.size)
        val peak = g.maxOf { abs(it) }
        val rms = sqrt(g.sumOf { it.toDouble() * it } / g.size)
        val crestDb = 20 * log10(peak / rms)
        // The references measure 10.8 to 12.6 dB at 88 BPM; a clipped print measured 2.6.
        assertTrue(crestDb > 8.0, "crest $crestDb dB")
        // Squared off would mean many samples sitting at the peak.
        assertTrue(g.count { abs(it) > .99f * peak } < 50, "samples at the peak: ${g.count { abs(it) > .99f * peak }}")
    }

    @Test
    fun theFlamsStrikesLandWhereTheyWereWritten() {
        // One strike alone, so the onsets can be read: a click per strike through an identity "drum".
        val clicks = KitStrikes.multi(listOf(0f, 7f, 23f, 40f), listOf(.5f, 1f, .6f, .7f)) { _, _ -> floatArrayOf(1f) }
        val x = clicks(1f, 0, 0f)
        val at = x.indices.filter { x[it] != 0f }
        assertEquals(listOf(0, 7, 23, 40).map { Math.round(it * rate / 1000f) }, at)
        assertEquals(listOf(.5f, 1f, .6f, .7f), at.map { x[it] })
    }

    @Test
    fun aSoftHatIsDarkerNotOnlyQuieter() {
        val hat = candidates.first { it.name == "dark-hat" }
        fun centroid(v: Float): Double {
            val x = pad(hat, v, 0, printed = true).samples
            // Magnitude-weighted centroid over the first 50 ms (the power-weighted one hides the change).
            val n = (.05 * rate).toInt().coerceAtMost(x.size)
            var num = 0.0; var den = 0.0
            for (k in 1 until n / 2) {
                var re = 0.0; var im = 0.0
                for (i in 0 until n) { val a = 2 * Math.PI * k * i / n; re += x[i] * Math.cos(a); im -= x[i] * Math.sin(a) }
                val mag = sqrt(re * re + im * im)
                num += mag * k * rate / n; den += mag
            }
            return num / den
        }
        val soft = centroid(.30f); val hard = centroid(1f)
        assertTrue(soft < hard * .95, "soft $soft Hz, hard $hard Hz")
    }
}
