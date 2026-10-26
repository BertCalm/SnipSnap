package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Apsis
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.ApsisConfig
import com.snipsnap.synth.ApsisPatch
import com.snipsnap.synth.ApsisPresets
import com.snipsnap.synth.Patches
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import java.io.ByteArrayOutputStream
import java.io.File

class ApsisIntegrationTest {
    @Test
    fun `native rendered notes survive bench save reopen and both kit exporters`() {
        val root = createTempDirectory("apsis-delivery").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            KitStore.save(Kit("Apsis desk", emptyList()), source)
            val model = KitBuilderModel.open(source)
            val patches = listOf(ApsisPresets.all()[1], ApsisPresets.all().last()).map {
                it.copy(midi = 48, velocity = .43f, config = ApsisConfig(3, -91))
            }
            for ((index, patch) in patches.withIndex()) {
                val slot = index + 1
                val recipe = PadRecipe(patch)
                val rendered = recipe.render()
                val cls = Apsis.drumClassFor(patch.voice, patch.macros)
                model.assign(slot, rendered, cls, patch.name,
                    knownRootMidi = patch.midi.takeIf { cls == DrumClass.TONAL })
                model.update(slot) { ApsisPad.configure(it.copy(recipe = recipe.toJsonValue()), patch) }
                model.save()
                val saved = requireNotNull(KitStore.load(source).pad(slot))
                assertEquals(recipe, PadRecipe.fromJsonValue(requireNotNull(saved.recipe)))
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, rendered) }.toByteArray()
                assertContentEquals(WavReader.read(expected).samples, WavReader.read(File(source, saved.sampleFile)).samples)
            }
            val kit = KitStore.load(source)
            val native = Mpc3Exporter.exportTrack(kit, source, File(root, "native"), mpc2Twin = true)
            val legacy = KitExporter.exportProgramFolder(kit, source, File(root, "legacy"))
            assertTrue(native.program.isFile)
            assertTrue(legacy.program.isFile)
            for (sample in native.samples + legacy.samples) assertTrue(sample.length() > 44)
            assertFalse(kit.pads[0].loop)
            assertTrue(kit.pads[1].loop)
            assertEquals(0, kit.pads[0].tuneCoarse)
            assertEquals(0, kit.pads[0].tuneFine)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `edited recipes preserve every saved model and seed through drafts presets and reopened work`() {
        val shelf = createTempDirectory("apsis-integration").toFile()
        try {
            for (model in 1..3) {
                val original = ApsisPresets.all().first().copy(
                    midi = 43, velocity = .37f, config = ApsisConfig(model, -193),
                )
                val edited = Patches.edited(original, "MY APSIS $model",
                    original.macros + mapOf("STRETCH" to .91f, "COUPLE" to .73f)) as ApsisPatch
                val settings = mutableMapOf("engine" to "APSIS", "voice" to "PASSAGE")
                SynthDrafts.write(settings, listOf(edited))
                assertEquals(edited, SynthDrafts.read(settings).single())
                UserPresets.save(shelf, edited, model.toLong())
                assertEquals(edited, UserPresets.read(shelf).single { it.name == edited.name }.patch)
                val work = SynthWork.Working(com.snipsnap.synth.PadRecipe(edited), SynthWork.Source("My kit", 1))
                SynthWork.replace(settings, listOf(work))
                assertEquals(work, SynthWork.read(settings).single())
                assertEquals(original.config, edited.config)
                assertEquals(43f, Spread.detect(Snip(FloatArray(2048), 1, 44100), edited))
            }
        } finally { shelf.deleteRecursively() }
    }

    @Test
    fun `held delivery repeats the entire sample while finite notes ignore release`() {
        val held = ApsisPresets.all().last()
        val pad = ApsisPad.configure(KitPad(1, "cycle.wav"), held)
        assertFalse(pad.oneShot)
        assertTrue(pad.loop)
        assertEquals(DrumClass.LOOP, pad.drumClass)
        val hit = requireNotNull(PadHit.resolve(pad, 1f, 0) { 1000L })
        assertEquals(0L, hit.loopStartFrame)
        assertEquals(1000L, hit.endFrameExclusive)
        val allocator = VoiceAllocator()
        val voice = allocator.noteOn(pad.slot, 1f, pad.muteGroup, pad.oneShot).started
        assertTrue(allocator.noteOff(pad.slot).any { it.id == voice.id })
        val finite = ApsisPad.configure(pad, held.copy(macros = held.macros + ("HOLD" to 0f)))
        assertTrue(finite.oneShot)
        assertFalse(finite.loop)
        assertEquals(DrumClass.TONAL, finite.drumClass)
        assertEquals(-1L, requireNotNull(PadHit.resolve(finite, 1f, 0) { 1000L }).loopStartFrame)
        assertEquals(-1L, requireNotNull(PadHit.resolve(pad.copy(oneShot = true), 1f, 0) { 1000L }).loopStartFrame)
    }
}
