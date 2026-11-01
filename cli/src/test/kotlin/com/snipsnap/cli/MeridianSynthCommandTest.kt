package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.SmplChunk
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianVoice
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeridianSynthCommandTest {
    @Test
    fun `the full Meridian factory bank exports eight complete recipes with the correct sampler metadata`() {
        val root = kotlin.io.path.createTempDirectory("meridian-cli-bank").toFile()
        try {
            val factory = Presets.forVoice("MERIDIAN", "SWEEP").map { assertIs<MeridianPatch>(it) }
            assertEquals(8, factory.size)
            assertEquals(0, SynthCommand.run(listOf("MERIDIAN", "SWEEP", "--all", "--out", root.path), quiet()))
            val recipes = requireNotNull(root.listFiles { it.extension == "patch" }).sortedBy { it.name }
            val wavs = requireNotNull(root.listFiles { it.extension == "wav" })
            assertEquals(8, recipes.size)
            assertEquals(8, wavs.size)
            val exported = recipes.map { assertIs<MeridianPatch>(Patches.fromJsonText(it.readText())) }
            assertEquals(factory.toSet(), exported.toSet(), "--all must retain the registered factory recipes")
            for ((recipe, patch) in recipes.zip(exported)) {
                val wav = File(root, recipe.nameWithoutExtension + ".wav")
                assertTrue(wav.isFile, "${patch.name} has no matching WAV")
                val decoded = WavReader.read(wav)
                assertEquals(1, decoded.channels)
                assertEquals(44_100, decoded.sampleRate)
                assertTrue(decoded.samples.all { it.isFinite() } && decoded.peak() > 0f, "${patch.name} produced invalid audio")
                val smpl = WavReader.readSmpl(wav)
                if (Meridian.isLoop(patch.macros, patch.model)) {
                    val metadata = assertNotNull(smpl, "${patch.name} omitted held sampler metadata")
                    assertEquals(Meridian.midiFor(patch.voice, patch.macros["TUNE"] ?: .5f, patch.midi), metadata.rootNote)
                    val loop = assertNotNull(metadata.loop)
                    assertTrue(loop.startFrame >= 256)
                    assertEquals(decoded.frameCount.toLong(), loop.endFrameExclusive)
                } else {
                    assertNull(smpl, "${patch.name} finite recipe acquired a sampler loop")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `selecting FINE STRAND retains its exact factory recipe PCM and held markers`() {
        val root = kotlin.io.path.createTempDirectory("meridian-cli-factory-held").toFile()
        try {
            val factory = Presets.forVoice("MERIDIAN", "SWEEP").map { assertIs<MeridianPatch>(it) }
            val expected = factory.single { it.name == "FINE STRAND" }
            assertTrue(Meridian.isLoop(expected.macros, expected.model))
            // The shared CLI selects a factory preset by its displayed 1-based number.
            val selection = (factory.indexOf(expected) + 1).toString()
            assertEquals(0, SynthCommand.run(listOf("MERIDIAN", "SWEEP", "--preset", selection,
                "--out", root.path), quiet()))
            assertRenderedRecipe(expected, root)
            val wav = requireNotNull(root.listFiles { it.extension == "wav" }).single()
            val smpl = assertNotNull(WavReader.readSmpl(wav))
            assertEquals(expected.midi, smpl.rootNote)
            assertNotNull(smpl.loop)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `saved Meridian recipes and playing overrides produce exact PCM and complete replayable metadata`() {
        val root = kotlin.io.path.createTempDirectory("meridian-cli-replay").toFile()
        try {
            val source = MeridianPatch(
                "Saved material journey", MeridianVoice.SWEEP,
                Meridian.defaults(MeridianVoice.SWEEP) + mapOf("LATITUDE" to .8f, "REACH" to .45f, "MEMORY" to .3f),
                midi = 57, velocity = .7f, seedContext = -11,
            )
            val recipe = File(root, "source.patch").also { it.writeText(source.toJsonText()) }
            assertEquals(source, Patches.fromJsonText(recipe.readText()))

            val exact = File(root, "exact")
            assertEquals(0, SynthCommand.run(
                listOf("MERIDIAN", "SWEEP", "--patch", recipe.path, "--out", exact.path), quiet(),
            ))
            assertRenderedRecipe(source, exact)

            val edited = File(root, "edited")
            assertEquals(0, SynthCommand.run(
                listOf("MERIDIAN", "SWEEP", "--patch", recipe.path, "--midi", "69", "--velocity", ".35",
                    "--seed", "-29", "--macros", " latitude = .2, REACH=.75,MEMORY=.65 ", "--out", edited.path),
                quiet(),
            ))
            val expected = source.copy(
                midi = 69, velocity = .35f, seedContext = -29,
                macros = source.macros + mapOf("LATITUDE" to .2f, "REACH" to .75f, "MEMORY" to .65f),
            )
            val saved = assertRenderedRecipe(expected, edited)
            val replay = File(root, "replayed")
            assertEquals(0, SynthCommand.run(
                listOf("MERIDIAN", "SWEEP", "--patch", saved.path, "--out", replay.path), quiet(),
            ))
            assertEquals(expected, Patches.fromJsonText(requireNotNull(replay.listFiles { it.extension == "patch" }).single().readText()))
            assertContentEquals(
                requireNotNull(edited.listFiles { it.extension == "wav" }).single().readBytes(),
                requireNotNull(replay.listFiles { it.extension == "wav" }).single().readBytes(),
                "the exported complete recipe must recreate its own PCM without CLI overrides",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid Meridian controls and ambiguous pitch fail before creating output`() {
        val root = kotlin.io.path.createTempDirectory("meridian-cli-refusals").toFile()
        val destination = File(root, "not-created")
        try {
            for (flags in listOf(
                listOf("--macros", "UNKNOWN=.5"), listOf("--macros", "LATITUDE=NaN"),
                listOf("--macros", "MEMORY=Infinity"), listOf("--macros", "REACH=1.01"),
                listOf("--macros", "LATITUDE=.2,latitude=.8"), listOf("--macros", "SURFACE"),
                listOf("--macros", "TUNE=.75", "--midi", "69"),
                listOf("--midi", (Meridian.MIDI_MIN - 1).toString()),
                listOf("--velocity", "NaN"), listOf("--seed", "1.5"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    (listOf("synth", "MERIDIAN", "SWEEP") + flags + listOf("--out", destination.path)).toTypedArray(),
                    quiet(), PrintStream(errors),
                ), "$flags: $errors")
                assertTrue(errors.size() > 0, "$flags should explain its refusal")
                assertFalse("Exception" in errors.toString(), "$flags should produce a CLI error: $errors")
                assertFalse(destination.exists(), "$flags created an output directory")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `held Meridian export carries its played root actual loop bounds and replayable recipe`() {
        val root = kotlin.io.path.createTempDirectory("meridian-cli-held").toFile()
        try {
            val patch = MeridianPatch("Held tuned ring", MeridianVoice.SWEEP,
                mapOf("HOLD" to 1f, "TUNE" to .75f, "MEMORY" to .7f),
                midi = 57, velocity = .65f, seedContext = -29)
            val source = File(root, "source.patch").also { it.writeText(patch.toJsonText()) }
            val output = File(root, "held")
            assertEquals(0, SynthCommand.run(listOf("MERIDIAN", "SWEEP", "--patch", source.path,
                "--out", output.path), quiet()))
            val wav = requireNotNull(output.listFiles { it.extension == "wav" }).single()
            val recipe = requireNotNull(output.listFiles { it.extension == "patch" }).single()
            val saved = assertIs<MeridianPatch>(Patches.fromJsonText(recipe.readText()))
            assertEquals(patch, saved)
            assertTrue(Meridian.isLoop(saved.macros, saved.model))
            val held = Meridian.renderHeld(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seedContext)
            val playedMidi = Meridian.midiFor(patch.voice, .75f, patch.midi)
            assertEquals(63, playedMidi, "fixture must distinguish saved native note from played TUNE")
            val smpl = assertNotNull(WavReader.readSmpl(wav), "held export omitted sampler metadata")
            assertEquals(playedMidi, smpl.rootNote)
            val loop = assertNotNull(smpl.loop)
            assertEquals(held.loopStartFrame.toLong(), loop.startFrame)
            assertEquals(held.loopEndFrameExclusive.toLong(), loop.endFrameExclusive)
            val decoded = WavReader.read(wav)
            assertEquals(decoded.frameCount.toLong(), loop.endFrameExclusive)
            assertTrue(Keys.seamError(decoded.samples, loop.startFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            val expected = ByteArrayOutputStream().also {
                WavWriter.write(it, held.snip, smpl = SmplChunk(playedMidi,
                    SmplChunk.Loop(held.loopStartFrame.toLong(), held.loopEndFrameExclusive.toLong())))
            }
            assertContentEquals(expected.toByteArray(), wav.readBytes(), "CLI changed held PCM or its actual cycle markers")
            val replay = File(root, "replay")
            assertEquals(0, SynthCommand.run(listOf("MERIDIAN", "SWEEP", "--patch", recipe.path,
                "--out", replay.path), quiet()))
            assertContentEquals(wav.readBytes(), requireNotNull(replay.listFiles { it.extension == "wav" }).single().readBytes(),
                "saved held recipe did not recreate PCM plus sampler metadata")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unversioned legacy top HOLD remains a finite export without a sampler loop`() {
        val root = kotlin.io.path.createTempDirectory("meridian-cli-legacy-top").toFile()
        try {
            val legacy = MeridianPatch("Original finite ring", MeridianVoice.SWEEP,
                mapOf("HOLD" to 1f), midi = 57, velocity = .7f, model = 1, seedContext = 17)
            val unversioned = JsonValue.Obj(LinkedHashMap(legacy.toJsonValue().entries).apply { remove("model") })
            val source = File(root, "legacy.patch").also { it.writeText(Json.write(unversioned)) }
            val output = File(root, "finite")
            assertEquals(0, SynthCommand.run(listOf("MERIDIAN", "SWEEP", "--patch", source.path,
                "--out", output.path), quiet()))
            val recipe = assertRenderedRecipe(legacy, output)
            val wav = requireNotNull(output.listFiles { it.extension == "wav" }).single()
            assertNull(WavReader.readSmpl(wav), "loading a legacy recipe silently converted its finite top step to HOLD")
            assertTrue(WavReader.read(wav).durationSeconds > 4f)
            val replay = File(root, "replay")
            assertEquals(0, SynthCommand.run(listOf("MERIDIAN", "SWEEP", "--patch", recipe.path,
                "--out", replay.path), quiet()))
            val replayWav = requireNotNull(replay.listFiles { it.extension == "wav" }).single()
            assertNull(WavReader.readSmpl(replayWav))
            assertContentEquals(wav.readBytes(), replayWav.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun quiet() = PrintStream(ByteArrayOutputStream())

    private fun assertRenderedRecipe(expected: MeridianPatch, directory: File): File {
        val wav = requireNotNull(directory.listFiles { it.extension == "wav" }).single()
        val recipe = requireNotNull(directory.listFiles { it.extension == "patch" }).single()
        val saved = assertIs<MeridianPatch>(Patches.fromJsonText(recipe.readText()))
        assertEquals(wav.nameWithoutExtension, recipe.nameWithoutExtension)
        assertEquals(expected, saved, "note, velocity, model, seed context and all macros must survive CLI overrides")
        val held = if (Meridian.isLoop(expected.macros, expected.model)) {
            Meridian.renderHeld(expected.voice, expected.macros, expected.midi, expected.velocity, expected.seedContext)
        } else null
        val metadata = held?.let { SmplChunk(
            Meridian.midiFor(expected.voice, expected.macros["TUNE"] ?: .5f, expected.midi),
            SmplChunk.Loop(it.loopStartFrame.toLong(), it.loopEndFrameExclusive.toLong()),
        ) }
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, held?.snip ?: expected.render(), smpl = metadata) }
        assertContentEquals(bytes.toByteArray(), wav.readBytes(), "CLI must write the exact saved source's PCM")
        return recipe
    }
}
