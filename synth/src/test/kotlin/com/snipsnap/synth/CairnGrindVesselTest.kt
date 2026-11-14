package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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

    private fun floatSha(s: FloatArray): String {
        val b = ByteBuffer.allocate(s.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in s) b.putFloat(x)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Power per FFT bin of [s], zero-padded to a power of two. */
    private fun power(s: FloatArray): Pair<DoubleArray, Double> {
        var n = 1; while (n < s.size) n *= 2
        val re = DoubleArray(n) { if (it < s.size) s[it].toDouble() else 0.0 }; val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= n) {
            val a = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val c = cos(a * k); val si = sin(a * k)
                val xr = re[i + k + len / 2] * c - im[i + k + len / 2] * si
                val xi = re[i + k + len / 2] * si + im[i + k + len / 2] * c
                re[i + k + len / 2] = re[i + k] - xr; im[i + k + len / 2] = im[i + k] - xi
                re[i + k] += xr; im[i + k] += xi
            }
            len *= 2
        }
        return DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] } to Cairn.RATE.toDouble() / n
    }

    private fun share(s: FloatArray, fromHz: Double, toHz: Double = 1e9): Double {
        val (p, hz) = power(s)
        val total = p.sum()
        return p.indices.filter { it * hz >= fromHz && it * hz < toHz }.sumOf { p[it] } / total
    }

    /**
     * The owner kept VESSEL V1-V4 (round cairn-voices-1, 14 Nov 2026). These are
     * every render those four clips were assembled from, byte for byte; the clips
     * were rebuilt from these renders to within one 16-bit step.
     */
    @Test fun `the VESSEL renders the owner kept stay byte-identical`() {
        val v = CairnVoice.VESSEL; val d = Cairn.defaults(v)
        val pins = listOf(
            Triple("V1 C4 soft", Cairn.render(v, d, 60, .35f), "1cddde74583c77bd7ae422c268fa2f90a72764e362ad3a6281ab3f8b25ec8246"),
            Triple("V1 C4 medium", Cairn.render(v, d, 60, .65f), "b118cf4e90a8b69cd14ecab0e2946b1c4bca2d0f66e53df022a729bc0838be76"),
            Triple("V1 C4 hard", Cairn.render(v, d, 60, 1f), "1c67b83607ca3e149759e8486888883add6671c8cdae3f9e9d568a65ae394185"),
            Triple("V2 deep bowl", Cairn.render(v, d + ("BODY" to .9f), 60, 1f), "622613f25b14298c2037edf6c9353cc920b90bcb5baf2bc0b38d6959ef2419dd"),
            Triple("V2 small stone", Cairn.render(v, d + ("WEIGHT" to .15f), 60, 1f), "9e5942b1d127bd26d2dc2c028c767ab23d57b94e57308cf16a1540e39658e159"),
            Triple("V3 loop", Cairn.render(v, d + ("HOLD" to 1f), 60, 1f), "622bfe229f608c553f3a5da83d8f6c0f87c409509a6848e6d6fec44ace2bc337"),
            Triple("V4 C3", Cairn.render(v, d, 48, 1f), "4da1ed6a7ef1e5fbc2386ca79fbf9c560ac13d760233947101e478b4b31845b0"),
            Triple("V4 G3", Cairn.render(v, d, 55, .7f), "24fdc632832edee7ba568b9683be8b7e9077fec350ac240ab5b1dbd0f4647e58"),
            Triple("V4 C4", Cairn.render(v, d, 60, .85f), "bfa0250adabd0c2a680d54522ae3ea93d142e31aef50f72661e6c72e9ee03c7e"),
            Triple("V4 G4", Cairn.render(v, d, 67, .6f), "41f9bcb11a14d79c844d43b58b8de1197c93649f82ad2c79868afb643cf6c0c5"),
            Triple("V4 C5", Cairn.render(v, d, 72, .9f), "2b4133678518dab1df41b150401227d92065b8d232ab287d5ac435a25ef2969a"))
        for ((name, snip, sha) in pins) assertEquals(sha, floatSha(snip.samples), "kept VESSEL render $name moved")
    }

    /**
     * Round 1's GRIND had 13% of its energy above 4 kHz; the owner heard the top
     * as overpowering and static-y. The directly heard scrape now goes through a
     * two-pole lowpass: the top falls to about 2%, the 2-4 kHz grit stays, and
     * FRICTION still brightens it.
     */
    @Test fun `GRIND's heard scrape keeps its grit without the hiss`() {
        val g = CairnVoice.GRIND; val d = Cairn.defaults(g)
        for (note in listOf(48, 60, 72)) for (velocity in listOf(.35f, .65f, 1f)) {
            val s = Cairn.render(g, d, note, velocity).samples
            val top = share(s, 4000.0); val grit = share(s, 2000.0, 4000.0)
            assertTrue(top < .05, "GRIND MIDI$note v$velocity: ${100 * top}% above 4 kHz")
            assertTrue(share(s, 8000.0) < .01, "GRIND MIDI$note v$velocity: hiss above 8 kHz")
            assertTrue(grit > .02, "GRIND MIDI$note v$velocity lost its 2-4 kHz grit (${100 * grit}%)")
        }
        val dull = share(Cairn.render(g, d + ("FRICTION" to 0f)).samples, 2000.0)
        val bright = share(Cairn.render(g, d + ("FRICTION" to 1f)).samples, 2000.0)
        assertTrue(bright > 1.3 * dull, "FRICTION no longer brightens the scrape ($dull vs $bright)")
    }
}
