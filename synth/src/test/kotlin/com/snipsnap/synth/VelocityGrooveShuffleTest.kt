package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Tempo
import com.snipsnap.audio.Transients
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VelocityGrooveShuffleTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("vgs").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    // ---------- velocity layers ----------

    @Test
    fun `soft variants are darker, not different`() {
        val hard = Thump.render(ThumpVoice.SNARE)
        val variants = Velocity.variants(hard, count = 2)
        assertEquals(2, variants.size)
        val cSoft = FeatureExtractor.extract(variants[0]).centroidHz
        val cMid = FeatureExtractor.extract(variants[1]).centroidHz
        val cHard = FeatureExtractor.extract(hard).centroidHz
        assertTrue(cSoft < cMid && cMid < cHard, "brightness should step up with velocity: $cSoft, $cMid, $cHard")
        // Identity survives softness: a soft snare is still a snare.
        assertEquals(DrumClass.SNARE, Classifier.classify(variants[0]).drumClass)
    }

    @Test
    fun `velocity zones travel from assembler to xpm`() {
        val kick = Thump.render(ThumpVoice.KICK)
        val arranged = listOf(
            ArrangedPad(kick, DrumClass.KICK, softVariants = Velocity.variants(kick, 2)),
        )
        val kitDir = File(temp, "vel")
        val kit = KitAssembler.assembleArranged("Vel Kit", arranged, kitDir)

        val pad = kit.pad(1)!!
        assertEquals(3, pad.velocityLayers.size)
        // Velocity 0 is note-off: the first zone starts at 1 (Rex Rule #3).
        assertEquals(1, pad.velocityLayers.first().velStart)
        assertEquals(127, pad.velocityLayers.last().velEnd)
        assertEquals(pad.sampleFile, pad.velocityLayers.last().sampleFile, "main sample is the loudest zone")
        // Zones tile upward without gap or overlap.
        for (i in 1 until pad.velocityLayers.size) {
            assertEquals(pad.velocityLayers[i - 1].velEnd + 1, pad.velocityLayers[i].velStart)
        }
        // And the sidecar round-trips them.
        assertEquals(kit, KitStore.load(kitDir))

        val result = com.snipsnap.kit.KitExporter.exportProgramFolder(kit, kitDir, File(temp, "sd"))
        assertEquals(3, result.samples.size, "every zone's WAV travels")
        val xml = result.program.readText()
        assertTrue("<SampleName>A01_Kick_01_v1</SampleName>" in xml)
        assertTrue("<VelEnd>41</VelEnd>" in xml, "soft zone's window is written")
        assertTrue("<VelEnd>127</VelEnd>" in xml)
    }

    @Test
    fun `velocity re-renders rather than filtering the same waveform`() {
        val patch = TinesPresets.forVoice(TinesVoice.BELL).first()
        val soft = Velocity.atVelocity(patch, 0.25f)
        val hard = Velocity.atVelocity(patch, 1.0f)
        // A low-pass on one render leaves the onset sample-aligned; a real
        // re-render at a different index does not.
        val n = minOf(soft.samples.size, hard.samples.size)
        val differing = (0 until minOf(n, 400)).count {
            kotlin.math.abs(soft.samples[it] - hard.samples[it]) > 1e-4f
        }
        assertTrue(differing > 50, "onset should differ between velocities, only $differing samples did")
    }

    @Test
    fun `atVelocity finds the brightness macro even when the patch's own map leaves it unset`() {
        // TINES BELL's BRIGHT (and RATIO, DECAY) are left unset here -
        // Tines.render() still applies BRIGHT at its voice default
        // (defaults(voice).toMutableMap(), overlaid by whatever macros are
        // actually present), so atVelocity has to find BRIGHT via the
        // voice's own macro spec (Tines.macrosFor), not via a literal
        // `"BRIGHT" in patch.macros` check. The literal-key check would see
        // no BRIGHT key at all on this patch and silently fall back to
        // soften() - the exact frozen-waveform bug this task exists to fix,
        // and the shape Task 5b's recipe-rebuilt patches are expected to hit.
        val partial = TinesPatch("PARTIAL", TinesVoice.BELL, mapOf("TUNE" to 0.5f))
        val soft = Velocity.atVelocity(partial, 0.25f)
        val hard = Velocity.atVelocity(partial, 1.0f)
        val n = minOf(soft.samples.size, hard.samples.size)
        val differing = (0 until minOf(n, 400)).count {
            kotlin.math.abs(soft.samples[it] - hard.samples[it]) > 1e-4f
        }
        assertTrue(differing > 50, "a partial macro map should still re-render at velocity, only $differing samples did")
        // If atVelocity had fallen back, soft would be byte-identical to
        // soften()'s output over the unmodified render - assert it isn't.
        val fallbackWouldGive = Velocity.soften(partial.render(), 0.75f)
        assertTrue(
            !soft.samples.contentEquals(fallbackWouldGive.samples),
            "should re-render via the voice's BRIGHT default, not fall back to soften()",
        )
    }

    @Test
    fun `atVelocity never asks a macro past what the preset itself set`() {
        // BELL.first() is VESPER, whose BRIGHT is 0.25 (TinesPresets.kt:33,
        // not Tines.kt's voice default of 0.5) - struck at full velocity the
        // macro must land at exactly that preset's own ceiling, not above it.
        val patch = TinesPresets.forVoice(TinesVoice.BELL).first()
        val loud = Velocity.atVelocity(patch, 1.0f)
        val direct = patch.render()
        assertTrue(loud.samples.contentEquals(direct.samples), "full velocity should equal the preset's own render")
    }

    @Test
    fun `atVelocity is actually darker at low velocity, not merely different`() {
        // "Differs" (the brief's given test) isn't "duller" - a re-render
        // could in principle differ in a direction that isn't a brightness
        // change at all. One patch per BRIGHTNESS_MACROS entry, spectral
        // centroid must step down as velocity drops, same measure
        // `soft variants are darker, not different` already trusts.
        val cases = listOf(
            "TINES BELL (BRIGHT)" to TinesPresets.forVoice(TinesVoice.BELL).first(),
            "VELVET BASS (CUTOFF)" to VelvetPresets.forVoice(VelvetVoice.BASS).first(),
            "FATHOM DEEP (CUTOFF)" to FathomPresets.forVoice(FathomVoice.DEEP).first(),
            "THUMP SNARE (SNAP)" to ThumpPresets.forVoice(ThumpVoice.SNARE).first(),
            "THUMP HAT_CLOSED (METAL)" to ThumpPresets.forVoice(ThumpVoice.HAT_CLOSED).first(),
            "TONEWHEEL FULL (PERC)" to TonewheelPresets.forVoice(TonewheelVoice.FULL).first(),
        )
        for ((label, patch) in cases) {
            val soft = FeatureExtractor.extract(Velocity.atVelocity(patch, 0.25f)).centroidHz
            val hard = FeatureExtractor.extract(Velocity.atVelocity(patch, 1.0f)).centroidHz
            assertTrue(soft < hard, "$label: soft centroid $soft should be below hard centroid $hard")
        }
    }

    @Test
    fun `atVelocity falls back to soften for voices with no brightness macro`() {
        // KALIMBA specifically, not PLUCK generally: NYLON, KOTO and HARP
        // are now routed through PICK by Velocity's own PluckPatch override
        // (PluckTest's `a soft PLUCK is re-synthesised through PICK, not
        // low-passed`), and PICK isn't in BRIGHTNESS_MACROS either - the
        // override just names it directly. KALIMBA is carved out of that
        // override because its PICK sweep inverts, so it's the one voice
        // still falling back to soften() here - this test pins that
        // exclusion, byte-identical output and all, and it will need
        // updating (not just re-passing) once Phase 2 removes
        // PluckVoice.KALIMBA.
        val patch = PluckPresets.forVoice(PluckVoice.KALIMBA).first()
        val velocity = 0.3f
        val viaFallback = Velocity.atVelocity(patch, velocity)
        val viaSoftenDirect = Velocity.soften(patch.render(), 1f - velocity)
        assertTrue(
            viaFallback.samples.contentEquals(viaSoftenDirect.samples),
            "PLUCK has no brightness macro; atVelocity should match soften(render(), 1 - velocity) exactly",
        )
        // And it should actually be darker than the un-softened render -
        // the fallback isn't a silent no-op.
        val untouched = patch.render()
        assertTrue(!viaFallback.samples.contentEquals(untouched.samples), "the fallback should still soften something")
    }

    @Test
    fun `variantsAt with no patch is byte-identical to variants - the captured-audio branch StarterKits depends on`() {
        // StarterKits' VELOCITY starter is always THUMP-backed in
        // production, so there is no real captured pad reaching this
        // function through that door - this proves the fallback branch the
        // door WOULD take for one, the same guarantee layerAt gives Robin
        // and KitBuilder for their own captured-pad cases.
        val snip = Thump.render(ThumpVoice.SNARE)
        val viaVariants = Velocity.variants(snip, count = 2)
        val viaVariantsAt = Velocity.variantsAt(snip, patch = null, fx = null, count = 2)
        assertEquals(viaVariants.size, viaVariantsAt.size)
        for (i in viaVariants.indices) {
            assertTrue(
                viaVariants[i].samples.contentEquals(viaVariantsAt[i].samples),
                "variant $i: variantsAt(patch=null) should exactly match variants()",
            )
        }
    }

    @Test
    fun `variantsAt falls back for a patch+fx recipe too, and for voices with no brightness macro`() {
        val patch = ThumpPresets.forVoice(ThumpVoice.KICK).first() // measured non-monotonic DRIVE, excluded
        val fx = FxChain(reverse = false)
        val viaVariantsWithFx = Velocity.variantsAt(patch.render(), patch, fx, count = 2)
        val viaVariantsNoPatch = Velocity.variantsAt(patch.render(), null, null, count = 2)
        for (i in viaVariantsWithFx.indices) {
            assertTrue(
                viaVariantsWithFx[i].samples.contentEquals(viaVariantsNoPatch[i].samples),
                "variant $i: a patch+fx recipe should fall back exactly like no patch at all",
            )
        }
    }

    @Test
    fun `layerAt's hoisted useAtVelocity matches the inline probe it replaces - Task 5b`() {
        // Task 5b: the probe behind canUseAtVelocity used to re-run inside
        // layerAt on every single call (once per zone, once per ghost
        // layer) instead of being resolved once per pad alongside
        // brightnessSpec. Hoisting it must not change a single sample -
        // this proves the two-argument-list forms agree exactly, for a
        // patch-only pad, a patch+fx pad (falls back), and a captured
        // (patch=null) pad.
        val cases: List<Triple<String, Patch, FxChain?>> = listOf(
            Triple("VELVET BASS, no fx", VelvetPresets.forVoice(VelvetVoice.BASS).first(), null),
            Triple("THUMP KICK + fx", ThumpPresets.forVoice(ThumpVoice.KICK).first(), FxChain(reverse = false)),
        )
        for ((label, patch, fx) in cases) {
            val reference = patch.render()
            val spec = Velocity.brightnessSpec(patch)
            for (velocity in listOf(0f, 0.3f, 0.7f, 1f)) {
                val viaInlineProbe = Velocity.layerAt(reference, patch, fx, velocity, spec)
                val hoisted = Velocity.canUseAtVelocity(reference, patch, fx)
                val viaHoisted = Velocity.layerAt(reference, patch, fx, velocity, spec, hoisted)
                assertTrue(
                    viaInlineProbe.samples.contentEquals(viaHoisted.samples),
                    "$label at velocity=$velocity: hoisted useAtVelocity should match the inline probe exactly",
                )
            }
        }
        // The captured-audio (patch=null) case: canUseAtVelocity must
        // still short-circuit to false without touching patch.render() at
        // all (there is no patch), and layerAt must agree with itself.
        val snip = Thump.render(ThumpVoice.SNARE)
        val viaInlineProbe = Velocity.layerAt(snip, null, null, 0.3f, null)
        val hoisted = Velocity.canUseAtVelocity(snip, null, null)
        assertTrue(!hoisted, "no patch at all should never resolve to useAtVelocity")
        val viaHoisted = Velocity.layerAt(snip, null, null, 0.3f, null, hoisted)
        assertTrue(viaInlineProbe.samples.contentEquals(viaHoisted.samples))
    }

    @Test
    fun `THUMP KICK falls back to soften too - DRIVE measured out, not assumed`() {
        // KICK's only other macro (DRIVE) was excluded from BRIGHTNESS_MACROS
        // after measuring it: spectral centroid vs. DRIVE is U-shaped around
        // shipped presets' own settings, not monotonic, so scaling it down
        // for a soft hit does not reliably read as darker. KICK must fall
        // back to soften like PLUCK/VOX do.
        val patch = ThumpPresets.forVoice(ThumpVoice.KICK).first()
        val velocity = 0.3f
        val viaFallback = Velocity.atVelocity(patch, velocity)
        val viaSoftenDirect = Velocity.soften(patch.render(), 1f - velocity)
        assertTrue(
            viaFallback.samples.contentEquals(viaSoftenDirect.samples),
            "KICK's DRIVE was measured non-monotonic and excluded; atVelocity should match soften() exactly",
        )
    }

    // ---------- the groove ----------

    @Test
    fun `the demo groove is on the grid it was asked for`() {
        val groove = Groove.render(ThumpKits.classic(), bpm = 96f, bars = 4, seed = 3)
        assertTrue(groove.samples.all { it.isFinite() && it in -1f..1f })
        // ~10 seconds of 4/4 at 96 BPM plus a ring-out.
        assertEquals(4 * 4 * 60f / 96f + 1f, groove.durationSeconds, 0.05f)

        // Every audible hit sits on a sixteenth-note step. (Global tempo
        // estimation is deliberately not asserted: a syncopated pattern can
        // legitimately fold the estimator onto a 4/3 relation - onset-to-grid
        // alignment is the property the renderer actually owes us.)
        val onsets = Transients.detect(groove)
        assertTrue(onsets.size >= 16, "a groove has hits in it")
        val step = (60.0 / 96 / 4 * groove.sampleRate)
        val tolerance = (0.01 * groove.sampleRate) // 10 ms
        val aligned = onsets.count { o ->
            val phase = o.frame.toDouble() % step
            phase < tolerance || step - phase < tolerance
        }
        assertTrue(
            aligned >= onsets.size * 9 / 10,
            "$aligned of ${onsets.size} onsets on the grid",
        )
        assertNotNull(Tempo.estimate(groove), "a groove has a detectable pulse")
    }

    @Test
    fun `the groove is deterministic and works for melodic kits too`() {
        val a = Groove.render(SynthKits.melodic(), bpm = 84f, bars = 2, seed = 7)
        val b = Groove.render(SynthKits.melodic(), bpm = 84f, bars = 2, seed = 7)
        assertTrue(a.samples.contentEquals(b.samples))
        assertTrue(a.peak() > 0.5f, "an all-tonal kit still makes a groove")
    }

    // ---------- shuffle and the remix bank ----------

    @Test
    fun `a shuffled kit keeps every slot in class`() {
        val kit = Shuffle.kit(seed = 2026)
        assertEquals(16, kit.size)
        assertEquals(DrumClass.KICK, Classifier.classify(kit[0]!!.snip).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(kit[1]!!.snip).drumClass)
        assertTrue(
            Classifier.classify(kit[2]!!.snip).drumClass in setOf(DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN),
        )
        // Every pad carries an editable recipe of what the dice rolled.
        assertTrue(kit.all { it != null && it.recipe != null })
        // Same seed, same kit; different seed, different kit.
        assertTrue(Shuffle.kit(2026)[0]!!.snip.samples.contentEquals(kit[0]!!.snip.samples))
        assertTrue(!Shuffle.kit(99)[0]!!.snip.samples.contentEquals(kit[0]!!.snip.samples))
    }

    @Test
    fun `the remix bank doubles the kit onto pads 17-32`() {
        val base = ThumpKits.classic()
        val doubled = Shuffle.withRemixBank(base, seed = 5)
        assertEquals(32, doubled.size)
        // Bank A is untouched.
        for (i in 0 until 16) {
            assertTrue(doubled[i]!!.snip.samples.contentEquals(base[i]!!.snip.samples))
        }
        // Bank B differs from bank A and keeps each pad's class label.
        for (i in 16 until 32) {
            val remix = doubled[i]!!
            assertEquals(base[i - 16]!!.drumClass, remix.drumClass)
            assertTrue(!remix.snip.samples.contentEquals(base[i - 16]!!.snip.samples))
            // The fx-only recipe replays the treatment onto the bank-A sound.
            val recipe = PadRecipe.fromJsonValue(remix.recipe!!)
            assertTrue(
                recipe.process(base[i - 16]!!.snip).samples.contentEquals(remix.snip.samples),
                "pad ${i + 1}'s recipe should regenerate its remix",
            )
        }
        // And the whole 32-pad kit survives the pipeline - the first export
        // to exercise bank B.
        val kitDir = File(temp, "shuffle")
        val kit = KitAssembler.assembleArranged("AB Kit", doubled, kitDir)
        assertEquals(32, kit.pads.size)
        val result = com.snipsnap.kit.KitExporter.exportProgramFolder(kit, kitDir, File(temp, "sd2"))
        assertEquals(32, result.samples.size)
        assertTrue("<SampleName>A16_Tonal_02</SampleName>" in result.program.readText())
    }
}
