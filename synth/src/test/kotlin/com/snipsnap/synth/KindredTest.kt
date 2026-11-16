package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The house probes on KINDRED's voices and its 56 kit recipes (round K1). */
class KindredTest {
    private val rate = Dsp.RATE

    private fun pad(r: KindredRecipes.Recipe, velocity: Float = 1f, take: Int = 0) = KindredPatch.of(r, velocity, take).render().samples
    private fun voice(v: KindredVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0) =
        Kindred.render(v, macros, velocity, take).samples
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** RMS difference of two hits' level-matched third-octave spectra (40 Hz to 10 kHz), dB: a change of shape, not of level. */
    private fun shapeDistanceDb(a: FloatArray, b: FloatArray): Float {
        val size = 1 shl 14
        fun bands(x: FloatArray): DoubleArray {
            val mag = Fft.magnitudeSpectrum(x.copyOf(size), size)
            val out = DoubleArray(24)
            var total = 0.0
            for (bin in mag.indices) {
                val hz = Fft.binToHz(bin, size, rate)
                if (hz < 40f || hz >= 10_000f) continue
                val band = (3.0 * kotlin.math.ln(hz / 40.0) / kotlin.math.ln(2.0)).toInt().coerceIn(0, 23)
                val p = mag[bin].toDouble() * mag[bin]
                out[band] += p; total += p
            }
            return DoubleArray(24) { 10 * log10(out[it] / (total + 1e-18) + 1e-12) }
        }
        val x = bands(a); val y = bands(b)
        var s = 0.0
        for (i in x.indices) s += (x[i] - y[i]) * (x[i] - y[i])
        return kotlin.math.sqrt(s / x.size).toFloat()
    }

    /** The share of a hit's energy in [lo]..[hi] Hz, dB of the total. */
    private fun bandShareDb(x: FloatArray, lo: Float, hi: Float): Float {
        val size = 1 shl 15
        val mag = Fft.magnitudeSpectrum(x.copyOf(size), size)
        var band = 0.0; var total = 0.0
        for (bin in mag.indices) {
            val hz = Fft.binToHz(bin, size, rate)
            if (hz < 25f || hz > 16_000f) continue
            val p = mag[bin].toDouble() * mag[bin]
            total += p
            if (hz in lo..hi) band += p
        }
        return (10 * log10(band / (total + 1e-18) + 1e-12)).toFloat()
    }

    // ---------- the house probes ----------

    @Test fun `every voice and every kit recipe renders finite, bounded, centred and short`() {
        val all = KindredVoice.entries.map { it.name to voice(it) } + KindredRecipes.ALL.map { it.id to pad(it) }
        for ((id, s) in all) {
            assertTrue(s.all { it.isFinite() }, "$id has a non-finite sample")
            assertTrue(peak(s) in 0.05f..1f, "$id peak ${peak(s)}")
            assertTrue(abs(s.average()) < 0.01, "$id DC ${s.average()}")
            assertTrue(s.size < 1.5f * rate, "$id is ${s.size / rate.toFloat()} s: a one-shot, not a loop")
        }
    }

    @Test fun `the same strike renders byte for byte`() {
        for (r in KindredRecipes.ALL.take(8)) assertContentEquals(pad(r, 0.6f, 2), pad(r, 0.6f, 2), r.id)
    }

    @Test fun `every macro a voice lists changes the sound, and an unknown or non-finite key does not`() {
        for (v in KindredVoice.entries) {
            val base = voice(v)
            for (spec in Kindred.macrosFor(v)) {
                val lo = voice(v, mapOf(spec.name to 0.15f))
                val hi = voice(v, mapOf(spec.name to 0.85f))
                assertFalse(lo.contentEquals(hi), "$v ${spec.name} does nothing")
            }
            assertContentEquals(base, voice(v, mapOf("NOPE" to 0.9f)), "$v unknown key")
            assertContentEquals(base, voice(v, mapOf("TONE" to Float.NaN)), "$v non-finite macro")
        }
    }

    @Test fun `DIVE and CLANG are listed only where they have something to move`() {
        fun without(name: String) = KindredVoice.entries.filter { v -> Kindred.macrosFor(v).none { it.name == name } }.toSet()
        assertEquals(setOf(KindredVoice.HAT, KindredVoice.CLAP, KindredVoice.COWBELL, KindredVoice.SWEEP, KindredVoice.PING, KindredVoice.SHAKER), without("DIVE"))
        assertEquals(setOf(KindredVoice.CLAP, KindredVoice.SWEEP, KindredVoice.SHAKER), without("CLANG"))
        for (r in KindredRecipes.ALL) assertTrue(r.macros.keys.all { k -> Kindred.macrosFor(r.voice).any { it.name == k } }, "${r.id} sets a macro its voice does not list")
    }

    @Test fun `velocity changes the tone of every kit pad, not only its level`() {
        val d = KindredRecipes.ALL.associate { it.id to shapeDistanceDb(pad(it, 0.3f), pad(it, 1f)) }
        println("KINDRED soft (0.3) against hard, shape dB: " + d.entries.sortedBy { it.value }.joinToString { "${it.key}=${"%.2f".format(it.value)}" })
        for ((id, v) in d) assertTrue(v > MIN_VELOCITY_SHAPE_DB, "$id soft against hard, level-matched third-octave shape: $v dB")
    }

    @Test fun `takes are fresh strikes, each deterministic, and never copies`() {
        for (r in KindredRecipes.ALL) {
            val takes = (0..3).map { pad(r, 1f, it) }
            for (i in takes.indices) for (j in i + 1 until takes.size) assertFalse(takes[i].contentEquals(takes[j]), "${r.id} takes $i and $j are copies")
            assertContentEquals(takes[2], pad(r, 1f, 2), "${r.id} take 2 twice")
        }
    }

    @Test fun `Restrike deals KINDRED takes as fresh strikes`() {
        val p = KindredPatch.of(KindredRecipes.byId("TECHNO_A01"))
        assertTrue(Restrike.canRestrike(p))
        val a = Restrike.take(p, 0.8f, 11, 1)!!.samples
        val b = Restrike.take(p, 0.8f, 11, 2)!!.samples
        assertFalse(a.contentEquals(b), "two dealt takes are copies")
        assertContentEquals(a, Restrike.take(p, 0.8f, 11, 1)!!.samples)
    }

    // ---------- model 2: the small-speaker fix ----------

    @Test fun `model 2's kicks carry the harmonics a small speaker plays and settle near 58 Hz`() {
        // Round 1's techno kick put -42 dB of its energy in 1 to 2.5 kHz and was heard as "a small radio speaker";
        // the reference kick puts -13. Model 2 measured -17 in the harness.
        for (id in listOf("TECHNO_A01", "INDUSTRIAL_A01", "ELECTRO_A01", "AMBIENT_A01")) {
            val k = pad(KindredRecipes.byId(id))
            val upper = bandShareDb(k, 1000f, 2500f)
            val sub = bandShareDb(k, 25f, 50f)
            println("KINDRED $id 1-2.5 kHz $upper dB, under 50 Hz $sub dB")
            assertTrue(upper > -30f, "$id 1 to 2.5 kHz share $upper dB: the kick has lost its attack")
            assertTrue(sub < -6f, "$id share under 50 Hz $sub dB: the kick has sunk below what a phone plays")
        }
    }

    @Test fun `the classifier's verdicts on the kit recipes`() {
        val verdicts = KindredRecipes.ALL.associate { it.id to Classifier.classify(Snip(pad(it), 1, rate)).drumClass }
        println("KINDRED classifier: " + verdicts.entries.joinToString { "${it.key}=${it.value}" })
        for ((id, c) in verdicts) assertTrue(c != DrumClass.LOOP && c != DrumClass.UNKNOWN, "$id: $c")
    }

    // ---------- the patch ----------

    @Test fun `a patch round-trips through JSON with its velocity, take, recipe and model`() {
        val p = KindredPatch.of(KindredRecipes.byId("INDUSTRIAL_A09"), velocity = 0.6f, take = 3)
        val back = Patches.fromJsonText(p.toJsonText())
        assertEquals(p, back)
        assertContentEquals(p.render().samples, back.render().samples)
        val plain = KindredPatch("Plain", KindredVoice.ZAP, mapOf("DIVE" to 0.8f))
        assertEquals(plain, Patches.fromJsonText(plain.toJsonText()))
    }

    @Test fun `a patch refuses a bad take, velocity, model or recipe`() {
        assertFailsWith<IllegalArgumentException> { KindredPatch("x", KindredVoice.KICK, emptyMap(), take = Kindred.MAX_TAKE + 1) }
        assertFailsWith<IllegalArgumentException> { KindredPatch("x", KindredVoice.KICK, emptyMap(), velocity = 1.5f) }
        assertFailsWith<IllegalArgumentException> { KindredPatch("x", KindredVoice.KICK, emptyMap(), model = 1) }
        assertFailsWith<IllegalArgumentException> { KindredPatch("x", KindredVoice.KICK, emptyMap(), recipe = "TECHNO_A02") }
        assertFailsWith<IllegalArgumentException> { KindredPatch("x", KindredVoice.KICK, emptyMap(), recipe = "NOPE") }
        assertFailsWith<IllegalArgumentException> { KindredPatch("x", KindredVoice.HAT, mapOf("DIVE" to 0.5f)) }
    }

    @Test fun `the recipe table is the four sixteen-pad kits' fourteen one-shots each`() {
        assertEquals(56, KindredRecipes.ALL.size)
        assertEquals(56, KindredRecipes.ALL.map { it.id }.toSet().size)
        for (kit in listOf("TECHNO", "INDUSTRIAL", "ELECTRO", "AMBIENT")) {
            assertEquals((1..14).map { "${kit}_A%02d".format(it) }, KindredRecipes.ALL.filter { it.id.startsWith("${kit}_") }.map { it.id })
        }
        assertEquals(KindredRecipes.ALL.map { it.role.drumClass }, KindredRecipes.ALL.map { Kindred.drumClassFor(it.voice, it.id) })
    }

    @Test fun `scramble stays in bounds`() {
        for (v in KindredVoice.entries) {
            val m = Kindred.scramble(v, kotlin.random.Random(7))
            assertEquals(Kindred.defaults(v).keys, m.keys)
            assertTrue(m.values.all { it in 0f..1f })
            val s = voice(v, m)
            assertTrue(s.all { it.isFinite() } && peak(s) <= 1f, "$v scrambled")
        }
    }

    @Test fun `settled drops unknown keys and clamps`() {
        val m = Kindred.settled(KindredVoice.ZAP, mapOf("DIVE" to 3f, "NOPE" to 1f, "TUNE" to -1f))
        assertEquals(1f, m.getValue("DIVE"))
        assertEquals(0f, m.getValue("TUNE"))
        assertFalse("NOPE" in m)
    }

    private companion object {
        /** Floor under the least soft-against-hard shape change of any kit pad: 1.96 dB (ELECTRO A04), measured at round K1. */
        const val MIN_VELOCITY_SHAPE_DB = 1.5f
    }
}
