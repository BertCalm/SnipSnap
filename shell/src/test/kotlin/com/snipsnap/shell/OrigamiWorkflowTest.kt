package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiPresets
import com.snipsnap.synth.OrigamiVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Velocity
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real files behind SAVE AS PRESET, SEND TO PAD, replay, native velocity and program export. */
class OrigamiWorkflowTest {
    private val temp = kotlin.io.path.createTempDirectory("origami-workflow").toFile()

    @AfterTest
    fun cleanUp() { temp.deleteRecursively() }

    private fun authored() = OrigamiPresets.forVoice(OrigamiVoice.FAN).last().copy(
        name = "MY WARM PAPER", macros = OrigamiPresets.forVoice(OrigamiVoice.FAN).last().macros +
            mapOf("TUNE" to 0f, "CREASE" to .31f),
        midi = 72, velocity = .58f, seed = Long.MIN_VALUE + 23L, model = 4)

    private fun assertStoredWave(expected: Snip, file: File) {
        val stored = WavReader.read(file)
        assertEquals(expected.channels, stored.channels)
        assertEquals(expected.sampleRate, stored.sampleRate)
        assertEquals(expected.frameCount, stored.frameCount)
        val worst = expected.samples.indices.maxOf { abs(expected.samples[it] - stored.samples[it]) }
        assertTrue(worst < 1e-5f, "saved recipe no longer reproduces the PCM file: worst difference $worst")
    }

    @Test
    fun `factory names are reserved and saved user recipes reopen with the same complete sound`() {
        val shelf = File(temp, "shelf")
        for (factory in OrigamiPresets.all()) assertEquals(
            UserPresets.Check.Factory(UserPresets.normalize(factory.name)),
            UserPresets.check("  ${factory.name.lowercase()}  ", factory.engine, factory.voiceName, emptyList()))
        val patch = authored()
        val saved = UserPresets.save(shelf, patch, nowMillis = 1234L)
        assertTrue(UserPresets.file(shelf).isFile)
        val reopened = UserPresets.read(shelf)
        assertEquals(listOf(saved), reopened)
        val restored = assertIs<OrigamiPatch>(reopened.single().patch)
        assertEquals(patch, restored)
        assertEquals(72, restored.midi, "explicit saved pitch must survive a different TUNE macro")
        assertContentEquals(patch.render().samples, restored.render().samples,
            "the file must retain the source seed, model, velocity, absolute note and every macro")
    }

    @Test
    fun `bench promotion roundtrips every saved field rather than publishing only the macros`() {
        val patch = authored()
        val line = UserPresets.rosterLine(patch)
        val prefix = "OrigamiPatch.fromJsonText("
        assertTrue(line.startsWith(prefix) && line.endsWith("),"), line)
        val literal = line.removePrefix(prefix).removeSuffix("),")
        val json = Json.parse(literal).str()
        assertEquals(patch, OrigamiPatch.fromJsonText(json),
            "promoting a saved ORIGAMI preset must retain its explicit note, 64-bit seed, velocity and model")
    }

    @Test
    fun `saved preset lands on pads reopens replays native velocity and exports its actual files`() {
        val shelf = File(temp, "shelf")
        UserPresets.save(shelf, authored(), nowMillis = 99L)
        val patch = assertIs<OrigamiPatch>(UserPresets.read(shelf).single().patch)
        val recipe = PadRecipe(patch)
        val render = recipe.render()
        val dir = File(temp, "kit")
        val model = KitBuilderModel.create("Warm Paper", dir)
        val tags = mapOf("rootMidi" to "72", "rootNote" to Scales.nameOf(72))
        model.assign(1, render, DrumClass.TONAL, patch.name, knownRootMidi = 72)
        model.update(1) { it.copy(recipe = recipe.toJsonValue()) }
        model.assign(2, render, DrumClass.TONAL, "Replay destination")
        model.save()

        val reopened = KitBuilderModel.open(dir)
        val pad = assertNotNull(reopened.pad(1))
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertTrue(pad.oneShot, "finite ORIGAMI pads play the complete note")
        assertEquals(tags, pad.source)
        assertEquals(0, pad.tuneCoarse)
        assertEquals(0, pad.tuneFine)
        val restoredRecipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
        assertEquals(recipe, restoredRecipe)
        assertContentEquals(render.samples, restoredRecipe.render().samples)
        assertStoredWave(render, File(dir, pad.sampleFile))

        // DO IT AGAIN takes the real persisted recipe through its patch dispatcher and replacement door.
        val replayed = RecipeReplay.apply(reopened, 2, assertNotNull(pad.recipe), "Replay destination")
        assertEquals(pad.recipe, replayed.pad.recipe)
        assertContentEquals(File(dir, pad.sampleFile).readBytes(), File(dir, replayed.pad.sampleFile).readBytes())

        // GHOSTS must use the saved native recipe, including its explicit C5 and large negative seed.
        val main = WavReader.read(File(dir, pad.sampleFile))
        val layered = reopened.addGhostLayers(1)
        assertEquals(2, layered.velocityLayers.size)
        val expectedSoft = Velocity.peakMatch(main, patch.copy(velocity = .45f).render())
        assertStoredWave(expectedSoft, File(dir, layered.velocityLayers.first().sampleFile))
        reopened.save()
        val ready = KitBuilderModel.open(dir)
        assertEquals(recipe, PadRecipe.fromJsonValue(assertNotNull(ready.pad(1)?.recipe)))
        assertEquals(2, ready.pad(1)?.velocityLayers?.size)

        val findings = Preflight.check(ready.kit, dir)
        assertFalse(findings.blocked(), "saved ORIGAMI pad cannot reach the program export: $findings")
        val exported = KitExporter.exportProgramFolder(ready.kit, dir, File(temp, "card"))
        assertTrue(exported.program.isFile)
        for (fileName in listOf(pad.sampleFile, layered.velocityLayers.first().sampleFile, replayed.pad.sampleFile)) {
            assertContentEquals(File(dir, fileName).readBytes(), File(exported.directory, fileName).readBytes(),
                "export changed $fileName")
        }
    }

    @Test
    fun `the actual starter menu writes sixteen dry regenerable tonal pads with known roots`() {
        val starter = StarterKits.ALL.single { it.id == "origami" }
        assertFalse(starter.seeded)
        val dir = File(temp, "starter")
        starter.render("Paper Starter", dir)
        val model = KitBuilderModel.open(dir)
        assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
        assertEquals(16, model.kit.pads.size)
        val expectedMidi = listOf(48, 51, 53, 55, 58, 60, 63, 65) + List(8) { 60 }
        val patches = model.kit.pads.sortedBy { it.slot }.mapIndexed { index, pad ->
            val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            val patch = assertIs<OrigamiPatch>(recipe.patch)
            assertEquals(4, patch.model)
            assertNull(recipe.fx, "${patch.name} must remain the accepted dry instrument")
            assertFalse(recipe.alias)
            assertEquals(DrumClass.TONAL, pad.drumClass)
            assertTrue(pad.oneShot)
            assertEquals(expectedMidi[index].toString(), pad.source["rootMidi"])
            assertEquals(Scales.nameOf(expectedMidi[index]), pad.source["rootNote"])
            val midi = patch.midi ?: Origami.midiFor(patch.voice, patch.macros.getValue("TUNE"))
            assertEquals(expectedMidi[index], midi)
            val audio = WavReader.read(File(dir, pad.sampleFile))
            assertTrue(audio.samples.all { it.isFinite() && it in -1f..1f }, patch.name)
            assertTrue(audio.peak() > .01f, "${patch.name} is silent on the starter grid")
            patch
        }
        assertEquals(OrigamiVoice.entries.toSet(), patches.map { it.voice }.toSet())
        for (factory in OrigamiPresets.all()) assertTrue(factory in patches, "${factory.name} missing from starter")
        assertTrue(patches.take(8).all { it.voice == OrigamiVoice.CRANE })
        assertTrue(patches.takeLast(2).all { it.macros.getValue("FOLD") == 0f })
        for (voice in OrigamiVoice.entries) {
            val pad = model.kit.pads.single { it.displayName == OrigamiPresets.forVoice(voice).first().name }
            assertStoredWave(PadRecipe.fromJsonValue(assertNotNull(pad.recipe)).render(), File(dir, pad.sampleFile))
        }
        val findings = Preflight.check(model.kit, dir)
        assertFalse(findings.blocked(), "ORIGAMI starter does not survive its actual disk path: $findings")
    }

    @Test
    fun `known synth notes land in key without detuning their accurate fundamental or writing invalid audio`() {
        val dir = File(temp, "keyed")
        val model = KitBuilderModel.create("Paper In Key", dir)
        model.setKey(KeySpec.parse("Cminpent"))
        // The preserved moving CRANE can pull an autocorrelation estimate off its accurate root.
        // Authored C3 is already in C minor pentatonic and needs no fractional correction.
        val root = OrigamiPatch("C3 Paper", OrigamiVoice.CRANE,
            Origami.defaults(OrigamiVoice.CRANE) + ("TUNE" to 0f),
            velocity = .85f, seed = 20261012L, model = 4)
        val audio = root.render()
        val inKey = model.assign(1, audio, DrumClass.TONAL, root.name, knownRootMidi = 48)
        model.update(1) { it.copy(recipe = PadRecipe(root).toJsonValue()) }
        assertEquals(0, inKey.tuneCoarse)
        assertEquals(0, inKey.tuneFine, "the pad must not flatten an accurately generated C3")
        assertEquals("48", inKey.source["rootMidi"])
        assertEquals(Scales.nameOf(48), inKey.source["rootNote"])
        assertStoredWave(audio, File(dir, inKey.sampleFile))

        // An authored C-sharp still follows the normal in-key policy: a whole semitone to C.
        val sharp = root.copy(name = "C Sharp Paper", midi = 49)
        val sharpAudio = sharp.render()
        val placed = model.assign(2, sharpAudio, DrumClass.TONAL, sharp.name, knownRootMidi = 49)
        assertEquals(-1, placed.tuneCoarse)
        assertEquals(0, placed.tuneFine)
        assertEquals(-1 to 0, model.tuningForKnownRoot(49), "REPLACE uses the same authored-note placement")
        assertStoredWave(sharpAudio, File(dir, placed.sampleFile))

        val files = dir.listFiles().orEmpty().filter { it.extension == "wav" }.map { it.name }.toSet()
        for (invalid in listOf(-1, 128)) {
            assertFailsWith<IllegalArgumentException> {
                model.assign(3, audio, DrumClass.TONAL, knownRootMidi = invalid)
            }
            assertNull(model.pad(3))
            assertEquals(files, dir.listFiles().orEmpty().filter { it.extension == "wav" }.map { it.name }.toSet(),
                "an invalid known root wrote an orphan WAV")
        }
        model.save()
        val reopened = KitBuilderModel.open(dir)
        assertEquals(0, reopened.pad(1)?.tuneFine)
        assertEquals(-1, reopened.pad(2)?.tuneCoarse)
        assertEquals(0, reopened.pad(2)?.tuneFine)
    }
}
