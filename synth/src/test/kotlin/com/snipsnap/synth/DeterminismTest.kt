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
        val patch = GlintPatch("Canary", GlintVoice.SWEEP, Glint.defaults(GlintVoice.SWEEP))
        assertContentEquals(patch.render().samples, patch.render().samples)
    }

    // SIREN has no seed at all (its LFO starts at a fixed phase), and its
    // LOOP render fits a pitch and cuts at a crossing: both paths here.
    @Test
    fun `SIREN is byte-identical across renders, one-shot and LOOP`() {
        val shot = SirenPatch("Canary", SirenVoice.WAIL, Siren.defaults(SirenVoice.WAIL))
        assertContentEquals(shot.render().samples, shot.render().samples)
        val loop = SirenPatch("Canary Loop", SirenVoice.LASER, Siren.defaults(SirenVoice.LASER) + ("HOLD" to 1f))
        assertContentEquals(loop.render().samples, loop.render().samples)
    }

    // FORK seeds its hammer noise from the voice and the note
    // (Fork.kt's own `excite`), so it joins the canaries above.
    @Test
    fun `FORK is byte-identical across renders`() {
        val patch = ForkPresets.forVoice(ForkVoice.TINE).first()
        assertContentEquals(patch.render().samples, patch.render().samples)
    }

    // SILK seeds its course detune and its exciters from Dsp.seedFor per
    // voice and note (Silk.kt's oud/guzheng), so it joins the canaries
    // above too - one per voice, since OUD's course and GUZHENG's
    // dispersion/press are different code paths.
    @Test
    fun `SILK is byte-identical across renders, all four voices`() {
        val oud = SilkPatch("Canary", SilkVoice.OUD, Silk.defaults(SilkVoice.OUD))
        assertContentEquals(oud.render().samples, oud.render().samples)
        val guzheng = SilkPatch("Canary", SilkVoice.GUZHENG, Silk.defaults(SilkVoice.GUZHENG))
        assertContentEquals(guzheng.render().samples, guzheng.render().samples)
        val santur = SilkPatch("Canary", SilkVoice.SANTUR, Silk.defaults(SilkVoice.SANTUR))
        assertContentEquals(santur.render().samples, santur.render().samples)
        val shamisen = SilkPatch("Canary", SilkVoice.SHAMISEN, Silk.defaults(SilkVoice.SHAMISEN))
        assertContentEquals(shamisen.render().samples, shamisen.render().samples)
    }

    // BORE seeds its turbulence from Dsp.seedFor per voice and note (Bore.blow), and its LOOP step
    // runs a measure-and-correct retune on top of the render - both are deterministic or a saved
    // recipe would not regenerate. One per voice, one-shot and LOOP.
    @Test
    fun `BORE is byte-identical across renders, both voices, one-shot and loop`() {
        for (voice in BoreVoice.entries) {
            val shot = BorePatch("Canary", voice, Bore.defaults(voice))
            assertContentEquals(shot.render().samples, shot.render().samples, "$voice one-shot")
            val loop = BorePatch("Canary", voice, Bore.defaults(voice) + ("HOLD" to 1f))
            assertContentEquals(loop.render().samples, loop.render().samples, "$voice loop")
        }
    }

    // ARCO has no seed at all (a bow needs no noise: the render is a pure function of the macros), so this
    // canary does not guard a seed. It guards the float arithmetic of the friction table and the bow loop (a
    // limit cycle, where one stray last bit in a sum would move a note's lock-in and every sample after it), and
    // the measure-and-correct LOOP retune, which renders the bow, reads its own output twice and renders it
    // again at a corrected pitch. One per voice, one-shot and LOOP.
    @Test
    fun `ARCO is byte-identical across renders, both voices, one-shot and loop`() {
        for (voice in ArcoVoice.entries) {
            val shot = ArcoPatch("Canary", voice, Arco.defaults(voice))
            assertContentEquals(shot.render().samples, shot.render().samples, "$voice one-shot")
            val loop = ArcoPatch("Canary", voice, Arco.defaults(voice) + ("HOLD" to 1f))
            assertContentEquals(loop.render().samples, loop.render().samples, "$voice loop")
        }
    }

    // TERRA seeds its exciters (11, 31, 17, 19), CLACK's noise (29) and
    // BUZZ's noise (13, 23) per voice (Terra.kt), so a saved pad
    // regenerates only if all of them stay fixed. One per voice, with BUZZ
    // and CLACK up so the noise paths run.
    @Test
    fun `TERRA is byte-identical across renders, all four voices`() {
        for (voice in TerraVoice.entries) {
            val loud = when (voice) {
                TerraVoice.RESONANT_CAVITY, TerraVoice.TUNED_BAR -> mapOf("BUZZ" to 1f)
                TerraVoice.CONICAL_BELL -> mapOf("CLACK" to 1f)
                TerraVoice.COMPOUND_MEMBRANE -> emptyMap()
            }
            val patch = TerraPatch("Canary", voice, Terra.defaults(voice) + loud)
            assertContentEquals(patch.render().samples, patch.render().samples, voice.name)
        }
    }

    // A struck TERRA pad renders from the head stored in its recipe; the
    // capture is data, so nothing else is read and every render must agree.
    @Test
    fun `TERRA struck by a stored head is byte-identical across renders`() {
        val head = Terra.captureStriker(Thump.render(ThumpVoice.SNARE)) ?: error("a snare is not silent")
        for (voice in TerraVoice.entries) {
            val patch = TerraPatch("Canary", voice, Terra.defaults(voice), TerraPatch.Striker(head, 0.75f, "A02"))
            assertContentEquals(patch.render().samples, patch.render().samples, voice.name)
        }
    }
}
