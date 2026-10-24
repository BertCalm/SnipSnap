package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Filament
import com.snipsnap.synth.FilamentConfig
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.FilamentPresets
import com.snipsnap.synth.FilamentProfile
import com.snipsnap.synth.FilamentVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Velocity
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real shelf, pad, native-velocity and export paths behind the factory integration. */
class FilamentWorkflowTest {
    private val temp = kotlin.io.path.createTempDirectory("filament-workflow").toFile()

    @AfterTest
    fun cleanUp() { temp.deleteRecursively() }

    private fun authored() = FilamentPresets.forVoice(FilamentVoice.THREAD).first().copy(
        name = "MY GLASS", midi = 72, velocity = .58f,
        macros = FilamentPresets.forVoice(FilamentVoice.THREAD).first().macros + ("LENGTH" to .44f),
        config = FilamentConfig(seed = Int.MIN_VALUE + 23, profile = FilamentProfile.LATE),
    )

    private fun assertStoredWave(expected: Snip, file: File) {
        val stored = WavReader.read(file)
        assertEquals(expected.channels, stored.channels)
        assertEquals(expected.sampleRate, stored.sampleRate)
        assertEquals(expected.frameCount, stored.frameCount)
        val worst = expected.samples.indices.maxOf { abs(expected.samples[it] - stored.samples[it]) }
        assertTrue(worst < 1e-5f, "saved recipe no longer reproduces the PCM file: worst difference $worst")
    }

    @Test
    fun `factory names cannot be overwritten while all three saved models retain their complete geometry`() {
        for (factory in FilamentPresets.all()) {
            val name = UserPresets.normalize(factory.name)
            // The approved "Held Procession" label already exceeds the user naming limit.
            val refused = if (name.length > UserPresets.MAX_NAME) UserPresets.Check.TooLong(name)
                else UserPresets.Check.Factory(name)
            assertEquals(refused,
                UserPresets.check("  ${factory.name.lowercase()}  ", factory.engine, factory.voiceName, emptyList()))
        }

        val shelf = File(temp, "shelf")
        val recipes = listOf(FilamentPresets.original().first(), FilamentPresets.revision2().first(),
            FilamentPresets.all().first()).map { patch ->
            patch.copy(name = "M${patch.model} GEOMETRY", midi = 79, velocity = .37f,
                config = FilamentConfig(Int.MIN_VALUE + patch.model, FilamentProfile.COIL,
                    perfectRetention = patch.model == Filament.ORIGINAL_MODEL))
        }
        recipes.forEachIndexed { index, patch -> UserPresets.save(shelf, patch, nowMillis = 100L + index) }
        val restored = UserPresets.read(shelf).map { assertIs<FilamentPatch>(it.patch) }.associateBy { it.model }
        assertEquals(recipes.associateBy { it.model }, restored,
            "saving a factory edit must retain explicit note, velocity, every geometry field and its original model")
    }

    @Test
    fun `bench promotion keeps every field for old and current models`() {
        for (model in Filament.ORIGINAL_MODEL..Filament.MODEL_VERSION) {
            val patch = authored().copy(model = model,
                config = FilamentConfig(Int.MAX_VALUE - model, FilamentProfile.EARLY, perfectRetention = true))
            val line = UserPresets.rosterLine(patch)
            val prefix = "FilamentPatch.fromJsonText("
            assertTrue(line.startsWith(prefix) && line.endsWith("),"), line)
            val literal = line.removePrefix(prefix).removeSuffix("),")
            assertEquals(patch, FilamentPatch.fromJsonText(Json.parse(literal).str()),
                "a promoted Filament patch must retain more than its six macros")
        }
    }

    @Test
    fun `saved recipe reopens on a pad replays native velocity and exports its actual files`() {
        val shelf = File(temp, "shelf")
        UserPresets.save(shelf, authored(), nowMillis = 99L)
        val patch = assertIs<FilamentPatch>(UserPresets.read(shelf).single().patch)
        val recipe = PadRecipe(patch)
        val render = recipe.render()
        val dir = File(temp, "kit")
        val model = KitBuilderModel.create("Glass Paths", dir)
        model.assign(1, render, DrumClass.TONAL, patch.name, knownRootMidi = patch.midi)
        model.update(1) { it.copy(recipe = recipe.toJsonValue()) }
        model.assign(2, render, DrumClass.TONAL, "Replay destination")
        model.save()

        val reopened = KitBuilderModel.open(dir)
        val pad = assertNotNull(reopened.pad(1))
        assertEquals(mapOf("rootMidi" to "72", "rootNote" to Scales.nameOf(72), "rootOrigin" to "authored"), pad.source)
        assertTrue(pad.oneShot)
        assertEquals(0, pad.tuneCoarse)
        assertEquals(0, pad.tuneFine)
        assertEquals(recipe, PadRecipe.fromJsonValue(assertNotNull(pad.recipe)))
        assertStoredWave(render, File(dir, pad.sampleFile))

        val replayed = RecipeReplay.apply(reopened, 2, assertNotNull(pad.recipe), "Replay destination")
        assertEquals(pad.recipe, replayed.pad.recipe)
        assertContentEquals(File(dir, pad.sampleFile).readBytes(), File(dir, replayed.pad.sampleFile).readBytes())

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
        assertFalse(findings.blocked(), "saved FILAMENT recipe cannot reach export: $findings")
        val exported = KitExporter.exportProgramFolder(ready.kit, dir, File(temp, "card"))
        assertTrue(exported.program.isFile)
        for (name in listOf(pad.sampleFile, layered.velocityLayers.first().sampleFile, replayed.pad.sampleFile)) {
            assertContentEquals(File(dir, name).readBytes(), File(exported.directory, name).readBytes(),
                "program export changed $name")
        }
    }

    @Test
    fun `starter menu writes four approved scale notes and all twelve dry recipes including held cycles`() {
        val starter = StarterKits.ALL.single { it.id == "filament" }
        assertFalse(starter.seeded)
        assertEquals(KeySpec.parse("Cminpent"), starter.key)
        val dir = File(temp, "starter")
        val kit = starter.render("Filament Paths", dir)
        assertEquals(kit, KitStore.load(dir))
        assertEquals((1..16).toList(), kit.pads.map { it.slot })
        val expectedMidi = listOf(48, 51, 53, 55) + List(12) { 48 }
        val patches = kit.pads.mapIndexed { index, pad ->
            val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            val patch = assertIs<FilamentPatch>(recipe.patch)
            assertEquals(Filament.MODEL_VERSION, patch.model)
            assertNull(recipe.fx, "${patch.name} must remain the approved dry instrument")
            assertNull(recipe.terminalFadeMs, "the held source must remain its original sustain cycle")
            assertFalse(recipe.alias)
            assertEquals(Filament.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            assertEquals(expectedMidi[index], patch.midi)
            assertEquals(patch.midi.toString(), pad.source["rootMidi"])
            assertEquals(Scales.nameOf(patch.midi), pad.source["rootNote"])
            assertEquals("authored", pad.source["rootOrigin"])
            assertTrue(pad.oneShot, "the drum starter delivers one buffer, including a held sustain cycle")
            assertEquals(0, pad.muteGroup)
            val audio = WavReader.read(File(dir, pad.sampleFile))
            assertTrue(audio.samples.all { it.isFinite() && it in -1f..1f }, patch.name)
            assertTrue(audio.peak() > .00001f, "${patch.name} is silent on the starter grid")
            patch
        }
        assertEquals(FilamentVoice.entries.toSet(), patches.map { it.voice }.toSet())
        assertEquals(FilamentPresets.all(), patches.drop(4))
        val approvedThread = FilamentPresets.forVoice(FilamentVoice.THREAD).first()
        patches.take(4).forEach { patch ->
            assertEquals(approvedThread.copy(name = patch.name, midi = patch.midi), patch,
                "the scale row must transpose the approved source rather than an older default")
        }
        val held = kit.pads.filter { it.drumClass == DrumClass.LOOP }
        assertEquals(3, held.size)
        val heldPad = held.first()
        assertStoredWave(PadRecipe.fromJsonValue(assertNotNull(heldPad.recipe)).render(), File(dir, heldPad.sampleFile))
        val findings = Preflight.check(kit, dir)
        assertFalse(findings.blocked(), "FILAMENT starter cannot reach export: $findings")
        val exported = KitExporter.exportProgramFolder(kit, dir, File(temp, "starter-card"))
        assertTrue(exported.program.isFile)
        for (pad in kit.pads) assertContentEquals(File(dir, pad.sampleFile).readBytes(),
            File(exported.directory, pad.sampleFile).readBytes(), "starter export changed ${pad.displayName}")
    }
}
