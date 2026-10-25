package com.snipsnap.cli

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.FlockPadMaker
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockPresets
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Presets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlockSynthCommandTest {
    @Test
    fun `factory voices render their recipes with note energy and full Long seed`() {
        val directory = createTempDirectory("flock-cli").toFile()
        try {
            for (voice in FlockVoice.entries) {
                val destination = File(directory, voice.name)
                val all = voice == FlockVoice.GATHER
                val seed = if (all) Long.MAX_VALUE else Long.MIN_VALUE
                val errors = ByteArrayOutputStream()
                val output = ByteArrayOutputStream()
                val args = listOf("synth", "fLoCk", voice.name.lowercase(), "--midi", "63",
                    "--velocity", ".35", "--seed", seed.toString(), "--out", destination.path) +
                    if (all) listOf("--all") else listOf("--preset", "1")
                assertEquals(0, Cli.run(args.toTypedArray(), PrintStream(output), PrintStream(errors)), errors.toString())
                val files = destination.listFiles { it.extension == "wav" }!!.sortedBy { it.name }
                val presets = FlockPresets.forVoice(voice).let { if (all) it else it.take(1) }
                assertEquals(presets.size, files.size)
                for ((index, preset) in presets.withIndex()) {
                    val patch = preset.copy(midi = 63, velocity = .35f, seed = seed)
                    val expected = ByteArrayOutputStream().also { WavWriter.write(it, patch.render()) }.toByteArray()
                    assertContentEquals(expected, files[index].readBytes(), "${voice.name}: all performance fields reach the renderer")
                }
                assertTrue("${files.size} rendered into" in output.toString())
                assertFalse("lands with" in output.toString())
                assertEquals(FlockPresets.forVoice(voice), Presets.forVoice(FlockPatch.ENGINE, voice.name))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `recipe and saved preset promotion preserve the complete seeded performance`() {
        val directory = createTempDirectory("flock-saved").toFile()
        try {
            for (seed in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
                val patch = FlockPatch("MY FLOCK", FlockVoice.SPLIT,
                    Flock.defaults(FlockVoice.SPLIT) + ("HOLD" to .1f), midi = 71, velocity = .4f, seed = seed)
                val recipe = PadRecipe(patch)
                assertEquals(patch, PadRecipe.fromJsonText(recipe.toJsonText()).patch)
                UserPresets.save(directory, patch, nowMillis = 12L)
                assertEquals(patch, UserPresets.read(directory).single().patch)
                val literal = if (seed == Long.MIN_VALUE) "Long.MIN_VALUE" else "${seed}L"
                val line = UserPresets.rosterLine(patch)
                assertTrue(line.startsWith("p(FlockVoice.SPLIT, \"MY FLOCK\", $literal, "))
                assertTrue("\"HOLD\" to 0.1f" in line)
                assertTrue("midi = 71, velocity = 0.4f, model = ${Flock.MODEL_VERSION}" in line)
                assertEquals("FlockPresets.kt", UserPresets.rosterFile(patch.engine))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `saved RIBBON exports held native notes with complete model seed velocity and release`() {
        val directory = createTempDirectory("flock-cli-held").toFile()
        try {
            val patch = FlockPatch("MY HELD RIBBON", FlockVoice.RIBBON,
                Flock.defaults(FlockVoice.RIBBON) + mapOf("FLOW" to .4f, "HOLD" to .1f),
                midi = 37, velocity = .4f, seed = Long.MAX_VALUE, model = 1)
            val source = File(directory, "input.patch.json").also { it.writeText(patch.toJsonText()) }
            val destination = File(directory, "instrument")
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(listOf("FLOCK", "RIBBON", "--patch", source.path,
                "--instrument", "--midi", "84", "--velocity", ".35", "--seed", Long.MIN_VALUE.toString(),
                "--release", "1.2", "--out", destination.path), PrintStream(output)))
            val expectedPatch = patch.copy(midi = 84, velocity = .35f, seed = Long.MIN_VALUE,
                playback = FlockPlayback.HELD, releaseSeconds = 1.2f)
            val replay = FlockPatch.fromJsonText(File(destination, "${patch.name}.patch.json").readText())
            assertEquals(expectedPatch, replay)
            assertEquals(expectedPatch, PadRecipe.fromJsonText(PadRecipe(replay).toJsonText()).patch)
            val (sidecar, instrument) = InstrumentStore.list(destination).single()
            assertEquals(1.2f, instrument.release)
            assertEquals((60..84 step 3).toList(), instrument.zones.map { it.rootNote })
            assertEquals(60, instrument.zones.first().lowNote)
            assertEquals(84, instrument.zones.last().highNote)
            val rootZone = instrument.zones.last()
            val note = FlockPadMaker.renderZone(FlockPadMaker.Spec(expectedPatch), 84)
            val sheet = SmplChunk(84, SmplChunk.Loop(note.loopStartFrame, note.snip.frameCount.toLong()))
            val expectedWav = ByteArrayOutputStream().also { WavWriter.write(it, note.snip, smpl = sheet) }
            assertContentEquals(expectedWav.toByteArray(), File(sidecar.parentFile, rootZone.sample).readBytes())
            assertEquals(sheet, WavReader.readSmpl(File(sidecar.parentFile, rootZone.sample)))
            assertTrue("9 native zones, captured RIBBON sustain" in output.toString())
            assertTrue("recipe:" in output.toString())

            val audition = File(directory, "held-wav")
            val savedSource = File(destination, "${patch.name}.patch.json")
            assertEquals(0, SynthCommand.run(listOf("FLOCK", "RIBBON", "--patch", savedSource.path,
                "--out", audition.path), PrintStream(ByteArrayOutputStream())))
            val heldWav = audition.listFiles { it.extension == "wav" }!!.single()
            assertContentEquals(expectedWav.toByteArray(), heldWav.readBytes(), "saved HELD replay keeps native loop metadata")
            assertEquals(expectedPatch, FlockPatch.fromJsonText(File(audition, "${heldWav.nameWithoutExtension}.patch.json").readText()))

            UserPresets.save(File(directory, "presets"), expectedPatch, nowMillis = 12L)
            val line = UserPresets.rosterLine(expectedPatch)
            assertTrue("playback = FlockPlayback.HELD" in line)
            assertTrue("releaseSeconds = 1.2f" in line)
            assertTrue("heldVersion = 1" in line)
            assertTrue("model = 1" in line)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `held route input errors refuse before creating any output`() {
        val directory = createTempDirectory("flock-cli-held-invalid").toFile()
        val destination = File(directory, "not-created")
        try {
            val patch = FlockPresets.forVoice(FlockVoice.RIBBON).first()
            val source = File(directory, "input.patch.json").also { it.writeText(patch.toJsonText()) }
            val cases = listOf(
                listOf("RIBBON", "--instrument", "--release", "NaN"),
                listOf("RIBBON", "--instrument", "--release", "Infinity"),
                listOf("RIBBON", "--instrument", "--release", ".09"),
                listOf("RIBBON", "--instrument", "--release", "1.51"),
                listOf("RIBBON", "--instrument", "--attack", ".1"),
                listOf("RIBBON", "--instrument", "--all"),
                listOf("GATHER", "--instrument"),
                listOf("SPLIT", "--instrument"),
                listOf("GATHER", "--patch", source.path),
                listOf("RIBBON", "--patch", source.path, "--all"),
                listOf("RIBBON", "--patch", source.path, "--preset", "1"),
            )
            for (args in cases) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run((listOf("synth", "FLOCK") + args + listOf("--out", destination.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), "$args: $errors")
                assertTrue("--" in errors.toString(), "$args: refusal names its flag")
                assertFalse(destination.exists(), "$args must not create output")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid controls and unsupported held routes fail before writing`() {
        val directory = createTempDirectory("flock-cli-invalid").toFile()
        val destination = File(directory, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "35"), arrayOf("--midi", "85"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.01"), arrayOf("--velocity", "-.01"),
                arrayOf("--seed", "1.5"), arrayOf("--seed", "9223372036854775808"),
                arrayOf("--seed", "-9223372036854775809"),
                arrayOf("--instrument"), arrayOf("--drone"), arrayOf("--scramble", "3"),
                arrayOf("--model", "1"), arrayOf("--seconds", "2"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "FLOCK", "GATHER", *flags, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.joinToString())
                assertTrue(errors.size() > 0)
                assertFalse(destination.exists(), "invalid input must not create an audition directory")
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
