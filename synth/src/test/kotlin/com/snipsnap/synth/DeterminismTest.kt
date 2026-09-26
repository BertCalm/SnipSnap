package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The seeding contract: a render is decorrelated from its neighbours but
 * reproducible from its own patch. Golden files and `--undo` byte-identity
 * both depend on the second half.
 */
class DeterminismTest {

    @Test
    fun `the same seed gives the same phases`() {
        assertContentEquals(Dsp.phases(4, Dsp.seedFor("TINES", "BELL")), Dsp.phases(4, Dsp.seedFor("TINES", "BELL")))
    }

    @Test
    fun `different voices get different phases`() {
        val a = Dsp.phases(4, Dsp.seedFor("TINES", "BELL"))
        val b = Dsp.phases(4, Dsp.seedFor("TINES", "CHIME"))
        assertTrue(a.indices.any { a[it] != b[it] }, "two voices should not share a phase set")
    }

    @Test
    fun `phases are spread, not all zero`() {
        val p = Dsp.phases(8, Dsp.seedFor("VELVET", "BASS"))
        assertTrue(p.any { it > 0.01 }, "phases must not all start at 0.0")
        assertTrue(p.all { it >= 0.0 && it < 1.0 }, "phases are a fraction of a cycle")
    }

    @Test
    fun `a rendered patch is byte-identical across renders`() {
        val patch = TinesPresets.forVoice(TinesVoice.BELL).first()
        assertContentEquals(patch.render().samples, patch.render().samples)
    }

    // TINES above never touches Dsp.phases/seedFor at all - it's the wrong
    // canary for this contract. FATHOM, VELVET, VOX, TONEWHEEL and RESIN
    // are the five engines that actually call Dsp.seedFor per voice
    // (Fathom.kt:212, Velvet.kt:203, Vox.kt:116, Tonewheel.kt:102,
    // Resin.kt), and none of them had a byte-identity regression test
    // before this - a seed collision or a stray Random() slipping into any
    // one of them would have shipped silently. Same assertion, same real
    // preset -> render() path, one per engine.

    @Test
    fun `FATHOM is byte-identical across renders`() {
        val patch = FathomPresets.forVoice(FathomVoice.GRIND).first()
        assertContentEquals(patch.render().samples, patch.render().samples)
    }

    @Test
    fun `RESIN is byte-identical across renders`() {
        val patch = ResinPatch("Canary", ResinVoice.LEAD, Resin.defaults(ResinVoice.LEAD))
        assertContentEquals(patch.render().samples, patch.render().samples)
    }

    // TIDE seeds WANDER from the recipe itself (Tide.seedFor), so this is
    // the canary for the regenerate-from-kit.json promise at full WANDER.
    @Test
    fun `TIDE is byte-identical across renders`() {
        val patch = TidePatch("Canary", TideVoice.BONGO, Tide.defaults(TideVoice.BONGO) + ("WANDER" to 1f))
        assertContentEquals(patch.render().samples, patch.render().samples)
    }

    @Test
    fun `VELVET is byte-identical across renders`() {
        val patch = VelvetPresets.forVoice(VelvetVoice.BASS).first()
        assertContentEquals(patch.render().samples, patch.render().samples)
    }

    @Test
    fun `VOX is byte-identical across renders`() {
        val patch = VoxPresets.forVoice(VoxVoice.CHOIR).first()
        assertContentEquals(patch.render().samples, patch.render().samples)
    }

    @Test
    fun `TONEWHEEL is byte-identical across renders`() {
        val patch = TonewheelPresets.forVoice(TonewheelVoice.FULL).first()
        assertContentEquals(patch.render().samples, patch.render().samples)
    }

    @Test
    fun `GLINT is byte-identical across renders`() {
        val patch = GlintPatch("Canary", GlintVoice.BOTTLE, Glint.defaults(GlintVoice.BOTTLE))
        assertContentEquals(patch.render().samples, patch.render().samples)
    }
}
