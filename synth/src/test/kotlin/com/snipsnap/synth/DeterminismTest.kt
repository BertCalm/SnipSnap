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
    // again at a corrected pitch. One per voice, one-shot and LOOP. And a one-shot at BOW 1, where R1c's bite (CELLO's
    // longer and bigger one included) is at its largest: the bite is a relaxation in the velocity and the pressure, one more sum
    // in the loop. And a held 3 s note, which carries the vibrato at its full depth: CELLO's finger drifts in rate, depth and lean, each a sum of three slow sines of the time since the
    // vibrato began and nothing else, so it needs no seed either and must not read a clock or a random draw.
    @Test
    fun `ARCO is byte-identical across renders, both voices, one-shot and loop`() {
        for (voice in ArcoVoice.entries) {
            val shot = ArcoPatch("Canary", voice, Arco.defaults(voice))
            assertContentEquals(shot.render().samples, shot.render().samples, "$voice one-shot")
            val bitten = ArcoPatch("Canary", voice, Arco.defaults(voice) + ("BOW" to 1f))
            assertContentEquals(bitten.render().samples, bitten.render().samples, "$voice one-shot at BOW 1, with the bite at its largest")
            val held = ArcoPatch("Canary", voice, Arco.defaults(voice) + ("HOLD" to Arco.holdFor(3f)))
            assertContentEquals(held.render().samples, held.render().samples, "$voice held 3 s, with the vibrato at full depth")
            val loop = ArcoPatch("Canary", voice, Arco.defaults(voice) + ("HOLD" to 1f))
            assertContentEquals(loop.render().samples, loop.render().samples, "$voice loop")
        }
    }

    // GYRE seeds each string's pluck from Dsp.seedFor per voice, note and string, and its rotor
    // starts at phase 0 on every render - a saved recipe regenerates bit for bit.
    @Test
    fun `GYRE is byte-identical across renders, both voices`() {
        for (voice in GyreVoice.entries) {
            val p = GyrePatch("Canary", voice, Gyre.defaults(voice) + ("SPIN" to 0.6f))
            assertContentEquals(p.render().samples, p.render().samples, "$voice")
        }
    }
}
