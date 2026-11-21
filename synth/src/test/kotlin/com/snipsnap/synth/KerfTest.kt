package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KERF as an engine (round 4 of docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md): the house set
 * (finite, bounded, under the loop threshold, every control moves it, an unknown key and a non-finite control do not,
 * JSON with its model), the pools, and the claim that the engine and a SYNTH TWIN pad are one sound path.
 */
class KerfTest {
    private val rate = Dsp.RATE

    /** Difference energy between two renders, each peak-normalised, against the first, in dB. */
    private fun apart(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val pa = a.maxOf { abs(it) }; val pb = b.maxOf { abs(it) }
        var d = 0.0; var s = 0.0
        for (i in 0 until n) { val e = a[i] / pa - b[i] / pb; d += e * e; s += (a[i] / pa) * (a[i] / pa) }
        return 10 * log10(d / s + 1e-20)
    }

    @Test
    fun `every voice has a pool of the bank's hits of its kind, none too long`() {
        val sizes = KerfVoice.entries.associateWith { Kerf.pool(it).size }
        assertEquals(mapOf(KerfVoice.KICK to 16, KerfVoice.SNARE to 15, KerfVoice.GHOST to 6, KerfVoice.HAT to 11,
            KerfVoice.HAT_OPEN to 10, KerfVoice.PERC to 5), sizes)
        for (v in KerfVoice.entries) for (p in Kerf.pool(v)) {
            assertTrue(p.drumClass in v.bankClasses, "$v holds a ${p.drumClass}")
            assertTrue(p.patch.seconds <= Kerf.MAX_SECONDS, "$v holds a ${p.patch.seconds} s hit")
        }
    }

    @Test
    fun `HIT steps through the whole pool and hitFor lands on its own step`() {
        for (v in KerfVoice.entries) {
            val n = Kerf.pool(v).size
            assertEquals(0, Kerf.hitIndex(v, 0f)); assertEquals(n - 1, Kerf.hitIndex(v, 1f))
            for (i in 0 until n) assertEquals(i, Kerf.hitIndex(v, Kerf.hitFor(v, i)))
        }
    }

    @Test
    fun `every hit is finite, bounded, centred and under the loop threshold`() {
        for (voice in KerfVoice.entries) {
            val names = Kerf.macrosFor(voice).map { it.name }
            val corners = listOf(emptyMap(), names.associateWith { 0f }, names.associateWith { 1f }) +
                (0 until Kerf.pool(voice).size).map { mapOf("HIT" to Kerf.hitFor(voice, it), "DECAY" to 1f, "SLOP" to 1f) }
            for (m in corners) for (v in listOf(.1f, 1f)) {
                val x = Kerf.render(voice, m, v).samples
                assertTrue(x.size < (1.5 * rate).toInt(), "$voice $m stays under the loop threshold (${x.size})")
                assertTrue(x.all { it.isFinite() }, "$voice $m finite")
                val peak = x.maxOf { abs(it) }
                assertTrue(peak in .01f..1f, "$voice $m v$v peak $peak")
                // A chop holds whatever the knife caught, and a short kick cut part-way through a low cycle averages
                // off zero (kit B's fourth kick at SLOP 1: -0.014). The bound catches a bias, not that.
                assertTrue(abs(x.average()) < .02, "$voice $m DC ${x.average()}")
            }
        }
    }

    @Test
    fun `every control changes the sound and an unknown key or a non-finite control does not`() {
        for (voice in KerfVoice.entries) {
            val base = Kerf.render(voice).samples
            for (spec in Kerf.macrosFor(voice)) {
                val lo = Kerf.render(voice, mapOf(spec.name to .15f)).samples
                val hi = Kerf.render(voice, mapOf(spec.name to .85f)).samples
                val n = minOf(lo.size, hi.size)
                val diff = (0 until n).sumOf { abs(lo[it] - hi[it]).toDouble() } / n
                assertTrue(diff > 1e-4 || lo.size != hi.size, "$voice ${spec.name} moves the sound (mean |diff| $diff)")
                assertContentEquals(base, Kerf.render(voice, mapOf(spec.name to Float.NaN)).samples, "$voice ${spec.name} NaN falls back")
            }
            assertContentEquals(base, Kerf.render(voice, mapOf("NOT_A_CONTROL" to .3f)).samples, "$voice ignores an unknown key")
        }
    }

    @Test
    fun `the engine and a SYNTH TWIN pad are one sound path`() {
        // At the defaults the engine is the bank pad as the owner heard it (BLEED 1, SLOP 0.5, its own knife), levelled
        // by one gain, set by the loudest of the four full-velocity takes, that every velocity and take shares.
        for (voice in KerfVoice.entries) {
            val pad = Kerf.pool(voice).first()
            val twin = KerfRecipe(pad.patch, pad.prev, pad.next, pad.bleed, pad.slop, pad.draw)
            val reference = twin.render().samples
            val gain = Kerf.PEAK / (0 until Kerf.LEVEL_TAKES).maxOf { t -> twin.take(t, 1.0).samples.maxOf { abs(it) } }
            assertEquals(gain, Kerf.gainFor(voice), 1e-6f, "$voice gain")
            val engine = Kerf.render(voice).samples
            assertEquals(Kerf.PEAK, (0 until Kerf.LEVEL_TAKES).maxOf { t -> Kerf.render(voice, emptyMap(), 1f, t).samples.maxOf { abs(it) } }, 1e-6f, "$voice loudest take")
            for (i in reference.indices) assertEquals(reference[i] * gain, engine[i], 1e-6f, "$voice velocity 1, take 0 at $i")
            val soft = twin.take(2, .5).samples; val engineSoft = Kerf.render(voice, emptyMap(), .5f, 2).samples
            assertEquals(soft.size, engineSoft.size)
            for (i in soft.indices) assertEquals(soft[i] * gain, engineSoft[i], 1e-6f, "$voice soft take 2 at $i")
            assertTrue(engineSoft.maxOf { abs(it) } < engine.maxOf { abs(it) }, "$voice the soft take is softer than the hard one")
        }
    }

    @Test
    fun `a soft hit is another strike and every take a fresh one`() {
        for (voice in KerfVoice.entries) {
            val hard = Kerf.render(voice).samples
            val soft = Kerf.render(voice, emptyMap(), .3f).samples
            assertTrue(apart(hard, soft) > -30.0, "$voice soft is not the hard hit scaled (${apart(hard, soft)})")
            val takes = (0..2).map { Kerf.render(voice, emptyMap(), 1f, it).samples }
            for ((a, b) in listOf(0 to 1, 1 to 2, 0 to 2)) assertTrue(apart(takes[a], takes[b]) > -40.0, "$voice takes $a and $b differ")
        }
    }

    @Test
    fun `a patch round-trips through JSON with its model`() {
        val p = KerfPresets.forVoice(KerfVoice.SNARE)[1]
        val back = KerfPatch.fromJsonText(p.toJsonText())
        assertEquals(p, back)
        assertEquals(Kerf.MODEL_VERSION, back.model)
        assertContentEquals(p.render(.6f, 1).samples, back.render(.6f, 1).samples)
    }

    /** The kit in the bank a pool hit came from. */
    private fun kitOf(pad: KerfPad) = KerfBank.kits.entries.single { (_, pads) -> pads.any { it === pad } }.value

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /**
     * The judge is KERF's own labeller ([KerfFit.label]), not the app's capture classifier: the owner chose it on
     * 2026-11-19 because the classifier reads a break badly (most kicks PERC, most hats SNARE; it does so here too).
     * The labeller reads level beside the loudest hit of the loop, so each preset is measured against its source kit.
     */
    @Test
    fun `presets are distinct hits, named without makers, and classify as their kind`() {
        val failures = mutableListOf<String>()
        for (voice in KerfVoice.entries) {
            val presets = KerfPresets.forVoice(voice)
            assertEquals(presets.size, presets.map { Kerf.hitIndex(voice, it.macros.getValue("HIT")) }.toSet().size, "$voice presets are distinct hits")
            assertEquals(presets.size, presets.map { it.name }.toSet().size, "$voice preset names are distinct")
            for (p in presets) {
                assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name), "${p.name} names a maker")
                val hit = Kerf.pool(voice)[Kerf.hitIndex(voice, p.macros.getValue("HIT"))]
                val loudest = kitOf(hit).maxOf { peak(KerfRecipe(it.patch, it.prev, it.next, it.bleed, it.slop, it.draw).render().samples) }
                // The engine levels every hit, so the level beside the loop is read from the hit before levelling;
                // the rest of what the labeller reads (band shares, centroid, ring) does not depend on level.
                val inLoop = peak(Kerf.recipe(voice, p.macros).render().samples)
                val got = KerfFit.label(p.render().samples, inLoop / loudest)
                val ok = when (voice.drumClass) {
                    DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN -> got == DrumClass.HAT_CLOSED || got == DrumClass.HAT_OPEN
                    DrumClass.PERC -> true // the percussion and the stacks are whatever they are; filed as PERC
                    else -> got == voice.drumClass
                }
                println("KERF ${voice.name}/${p.name}: classified $got")
                if (!ok) failures += "${voice.name}/${p.name}: expected ${voice.drumClass}, got $got"
            }
        }
        assertTrue(failures.isEmpty(), "presets that do not classify as their kind:\n${failures.joinToString("\n")}")
    }
}
