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
        assertEquals(0, pad.velocityLayers.first().velStart)
        assertEquals(127, pad.velocityLayers.last().velEnd)
        assertEquals(pad.sampleFile, pad.velocityLayers.last().sampleFile, "main sample is the loudest zone")
        // Zones tile 0..127 without gap or overlap.
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
