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

    // R1g: BODY above the middle of the knob is a lift on the plain note (a low shelf and a bell, a cap searched by bisection on the note's own peak, and for a loop a cut chosen on the lifted window), none of it seeded,
    // and none of the rows above renders above the knee (the default BODY is the knee itself, which is the old finish to the sample). So the same canary again at BODY 0.75 and BODY 1, both voices, one-shot and LOOP, at the
    // default note (where the cap is idle or nearly so) and at the root of the voice (CELLO C2, where the cap acts at both BODYs: its bisection and its halving check are arithmetic that must repeat to the last bit). The control
    // beside each row: the render differs from the same note at BODY 0.5, so the row is a row of the lift and not of the plain it would equal if BODY were ignored above the knee. Loops also close, under the Organ's bar at both BODYs.
    @Test
    fun `ARCO above the knee is byte-identical across renders, BODY 0 point 75 and 1, both voices, one-shot and loop`() {
        for (voice in ArcoVoice.entries) for ((noteName, base) in listOf("default note" to Arco.defaults(voice), "root" to Arco.defaults(voice) + ("TUNE" to 0f))) for (body in listOf(0.75f, 1f)) {
            val where = "$voice $noteName at BODY $body"
            val plainShot = ArcoPatch("Canary", voice, base + ("BODY" to 0.5f))
            val shot = ArcoPatch("Canary", voice, base + ("BODY" to body))
            assertContentEquals(shot.render().samples, shot.render().samples, "$where, one-shot")
            assertTrue(!plainShot.render().samples.contentEquals(shot.render().samples), "$where, one-shot: the render equals the plain's, so the row is not above the knee")
            val loopMacros = base + mapOf("BODY" to body, "HOLD" to 1f)
            val first = Arco.renderLoopMeasured(voice, loopMacros)
            val second = Arco.renderLoopMeasured(voice, loopMacros)
            assertContentEquals(first.loop, second.loop, "$where, loop")
            assertTrue(first.seam == second.seam, "$where, loop: the seam differs between renders (${first.seam} and ${second.seam})")
            assertTrue(first.seam < Keys.MAX_SEAM_ERROR, "$where, loop: the loop does not close (seam ${first.seam}, bar ${Keys.MAX_SEAM_ERROR})")
            val plainLoop = Arco.renderLoopMeasured(voice, base + mapOf("BODY" to 0.5f, "HOLD" to 1f))
            assertTrue(!plainLoop.loop.contentEquals(first.loop), "$where, loop: the render equals the plain's, so the row is not above the knee")
            println("ARCO determinism $where: one-shot and loop repeat to the sample, loop seam ${"%.2e".format(java.util.Locale.ROOT, first.seam)} (bar ${"%.0e".format(java.util.Locale.ROOT, Keys.MAX_SEAM_ERROR)})")
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

    // MERCURY seeds its strike burst, its contact roughness and its water's start from the voice and the note
    // (Dsp.seedFor), and solves its friction implicitly every sample, so this canary guards both: the seeds, and
    // the float arithmetic of a coupled bank driven by a nonlinear contact, where one stray last bit would move
    // every sample after it. One per voice, at its defaults and with every knob moved.
    @Test
    fun `MERCURY is byte-identical across renders, every voice`() {
        for (voice in MercuryVoice.entries) {
            val shot = MercuryPatch("Canary", voice, Mercury.defaults(voice))
            assertContentEquals(shot.render().samples, shot.render().samples, "$voice defaults")
            val moved = MercuryPatch("Canary", voice, mapOf("TUNE" to 0.3f, "BEND" to 0.8f, "RUB" to 0.6f, "WATER" to 0.7f, "GLASS" to 0.2f, "COUPLE" to 0.9f, "HOLD" to 0.2f))
            assertContentEquals(moved.render().samples, moved.render().samples, "$voice moved")
            val loop = MercuryPatch("Canary", voice, Mercury.defaults(voice) + ("HOLD" to 1f))
            assertContentEquals(loop.render().samples, loop.render().samples, "$voice LOOP")
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

    // MAGNET seeds its pluck burst from Dsp.seedFor per voice and note, and nothing else in the
    // render is random: a saved recipe must regenerate bit for bit.
    @Test
    fun `MAGNET is byte-identical across renders, both voices`() {
        for (voice in MagnetVoice.entries) {
            val a = MagnetPatch("Canary", voice, Magnet.defaults(voice))
            val b = MagnetPatch("Canary", voice, Magnet.defaults(voice))
            assertContentEquals(a.render().samples, b.render().samples, "$voice")
        }
    }

    // TREMOR seeds its drummers, its bead placement and its fault thresholds from the voice and
    // the note, and the bead/contact solver is ordered by index. A saved recipe regenerates bit for bit.
    @Test
    fun `TREMOR is byte-identical across renders, every voice`() {
        for (voice in TremorVoice.entries) {
            val a = TremorPatch("Canary", voice, Tremor.defaults(voice))
            val b = TremorPatch("Canary", voice, Tremor.defaults(voice))
            assertContentEquals(a.render().samples, b.render().samples, "$voice")
        }
    }
}
