package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoomBapTest {
    private val snare get() = BoomBapKits.parts().single { it.id == "snare" }.patch
    private fun peak(s: Snip) = s.samples.maxOf { abs(it) }
    private fun brightness(s: Snip) = FeatureExtractor.extract(s).centroidHz
    private fun lateShare(s: Snip): Double {
        val split = (s.sampleRate * .08).toInt()
        val total = s.samples.sumOf { it.toDouble() * it }
        return s.samples.drop(split).sumOf { it.toDouble() * it } / total.coerceAtLeast(1e-20)
    }

    @Test fun `ghost snare changes the excitation and decay rather than filtering the finished hit`() {
        val p = snare
        val hits = listOf(.25f, .6f, 1f).map { Velocity.atVelocity(p, it) }
        val centers = hits.map(::brightness)
        assertTrue(centers.zipWithNext().all { (a, b) -> b > a * 1.05f }, "brightness $centers")
        // Isolate wires: the unchanged body can occupy a larger fraction of a
        // ghost's tail simply because its bright attack is quieter.
        val wires = listOf(.25f, 1f).map { Skin.render(SkinVoice.SNARE, p.macros + ("SNAP" to 1f), it) }
        val tails = wires.map(::lateShare)
        assertTrue(tails.first() < tails.last() * .8, "isolated wire tail shares $tails")
        assertFalse(Velocity.soften(p.render(), .75f).samples.contentEquals(hits.first().samples))
        assertContentEquals(Skin.render(SkinVoice.SNARE, p.macros, .25f).samples, hits.first().samples)
    }

    @Test fun `snare brightness approaches the unchanged hard hit continuously`() {
        val p = snare
        val hard = p.render().samples
        val near = Skin.render(p.voice, p.macros, .9999f).samples
        assertEquals(hard.size, near.size)
        assertTrue(hard.indices.maxOf { abs(hard[it] - near[it]) } < .002f)
    }

    @Test fun `every snare preset keeps a finite repeatable soft response across edge velocities`() {
        for (p in SkinPresets.forVoice(SkinVoice.SNARE)) {
            for (v in listOf(0f, .25f, .6f, 1f, Float.NaN)) {
                val s = Skin.render(p.voice, p.macros, v)
                assertTrue(s.samples.all { it.isFinite() && abs(it) <= 1f }, "${p.name} $v")
                assertTrue(peak(s) > .01f)
                assertTrue(abs(s.samples.average()) < .01, "${p.name} DC")
            }
            assertContentEquals(p.render().samples, Skin.render(p.voice, p.macros, Float.NaN).samples)
            val take = Skin.render(p.voice, p.macros, .3f, take = 2)
            assertContentEquals(take.samples, Skin.render(p.voice, p.macros, .3f, take = 2).samples)
            assertFalse(take.samples.contentEquals(Skin.render(p.voice, p.macros, .3f, take = 3).samples))
        }
    }

    @Test fun `a wet ghost preserves the rack tail and matches the reference peak`() {
        val p = snare
        val fx = BoomBapKits.coloration
        val ref = fx.process(p.render())
        val dry = Velocity.atVelocity(p, .25f)
        val wet = Velocity.layerAt(ref, p, fx, .25f, Velocity.brightnessSpec(p))
        val expected = fx.process(dry.copy(samples = FloatArray(dry.samples.size) { dry.samples[it] * .25f }))
        assertContentEquals(Velocity.peakMatch(ref, expected).samples, wet.samples)
        assertTrue(wet.frameCount > dry.frameCount, "the room tail must survive")
        assertEquals(peak(ref), peak(wet), 1e-5f)
        assertFalse(wet.samples.contentEquals(Velocity.soften(ref, .75f).samples))
        assertContentEquals(ref.samples, Velocity.layerAt(ref, p, fx, 1f, null).samples)
    }

    @Test fun `linear rack replay leaves its shape independent of the input drive scale`() {
        val p = snare
        val fx = FxChain(reverse = true)
        val ref = fx.process(p.render())
        val actual = Velocity.layerAt(ref, p, fx, .4f, null)
        val expected = Velocity.peakMatch(ref, fx.process(Velocity.atVelocity(p, .4f)))
        assertEquals(expected.frameCount, actual.frameCount)
        assertTrue(actual.samples.indices.all { abs(actual.samples[it] - expected.samples[it]) < 1e-6f })
    }

    @Test fun `richer recipes cannot lose their finishing stages when building layers`() {
        val simple = PadRecipe(snare, BoomBapKits.coloration)
        assertEquals(snare, Velocity.patchForLayers(simple))
        assertEquals(null, Velocity.patchForLayers(simple.copy(terminalFadeMs = 50f)))
        assertEquals(null, Velocity.patchForLayers(PadRecipe(fx = BoomBapKits.coloration)))
        assertEquals(null, Velocity.patchForLayers(null))
    }

    @Test fun `captured and incompatible formats keep the audio fallback`() {
        val p = snare
        val mono = p.render()
        val stereo = Snip(FloatArray(mono.frameCount * 2) { mono.samples[it / 2] }, 2, mono.sampleRate)
        for (ref in listOf(stereo, mono.copy(sampleRate = 48000))) {
            assertFalse(Velocity.canUseAtVelocity(ref, p, BoomBapKits.coloration))
            assertContentEquals(Velocity.soften(ref, .6f).samples,
                Velocity.layerAt(ref, p, BoomBapKits.coloration, .4f, null).samples)
        }
        assertFalse(Velocity.canUseAtVelocity(mono, null, BoomBapKits.coloration))
        assertContentEquals(Velocity.soften(mono, .6f).samples,
            Velocity.layerAt(mono, null, BoomBapKits.coloration, .4f, null).samples)
    }

    @Test fun `the dry and processed grooves retain one schedule without clipping`() {
        val dry = BoomBapAuditionGenerator.groove(false)
        val wet = BoomBapAuditionGenerator.groove(true)
        assertEquals(dry.frameCount, wet.frameCount)
        assertFalse(dry.samples.contentEquals(wet.samples))
        for (s in listOf(dry, wet)) {
            assertTrue(s.samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(peak(s) > .1f)
        }
        assertContentEquals(wet.samples, BoomBapAuditionGenerator.groove(true).samples)
    }

    @Test fun `study recipes replay and every pad has distinct soft layers`() {
        val pads = BoomBapKits.study()
        assertEquals(16, pads.size)
        assertEquals(6, pads.count { it != null })
        for (pad in pads.filterNotNull()) {
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            assertContentEquals(pad.snip.samples, recipe.render().samples)
            assertEquals(2, pad.softVariants.size)
            val layers = pad.softVariants + pad.snip
            for (s in layers) {
                assertTrue(s.samples.all { it.isFinite() && abs(it) <= 1f })
                assertEquals(peak(pad.snip), peak(s), 1e-5f)
            }
            assertFalse(layers[0].samples.contentEquals(layers[1].samples))
            assertFalse(layers[1].samples.contentEquals(layers[2].samples))
        }
    }
}
