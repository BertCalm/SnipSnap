package com.snipsnap.cli

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.OpenSkyPadMaker
import com.snipsnap.synth.OpenSky
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.OpenSkyPresets
import com.snipsnap.synth.OpenSkyTimbre
import com.snipsnap.synth.OpenSkyVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenSkySynthCommandTest {
    @Test
    fun `factory auditions keep each complete source when note velocity gate and dry mode change`() {
        val dir = createTempDirectory("opensky-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(listOf("OPENSKY", "ANTHEM", "--all", "--midi", "72",
                "--velocity", ".35", "--gate", ".1", "--dry", "--out", dir.path), PrintStream(output)))
            val wavs = requireNotNull(dir.listFiles { it.extension == "wav" }).sortedBy { it.name }
            val presets = OpenSkyPresets.forVoice(OpenSkyVoice.ANTHEM)
            assertEquals(presets.size, wavs.size)
            for ((preset, wav) in presets.zip(wavs)) {
                val expected = preset.copy(midi = 72, velocity = .35f, gateSeconds = .1f, wet = false)
                val bytes = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }
                assertContentEquals(bytes.toByteArray(), wav.readBytes(), "${preset.name}: complete source/articulation is retained")
                assertEquals(2, WavReader.read(wav).channels)
            }
            assertTrue("(dry, stereo)" in output.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a saved patch retains its timbre and permits explicit wet override`() {
        val root = createTempDirectory("opensky-cli-patch").toFile()
        try {
            val patch = OpenSkyPatch("CUSTOM SKY", OpenSkyVoice.CRYSTAL,
                OpenSky.defaults(OpenSkyVoice.CRYSTAL) + mapOf("GLOW" to 0f, "RISE" to .2f),
                midi = 67, velocity = .4f, gateSeconds = .05f, wet = false,
                timbre = OpenSkyTimbre(pulseMix = .2f, sineMix = .3f, attackScale = .7f,
                    sustainLevel = .5f, releaseScale = .3f, bellBrightnessScale = .4f, bellDecayScale = .4f))
            val recipe = File(root, "patch.json").also { it.writeText(patch.toJsonText()) }
            val dir = File(root, "wavs")
            assertEquals(0, SynthCommand.run(listOf("OPENSKY", "CRYSTAL", "--patch", recipe.path,
                "--midi", "76", "--velocity", ".6", "--wet", "--out", dir.path), PrintStream(ByteArrayOutputStream())))
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, patch.copy(midi = 76, velocity = .6f, wet = true).render()) }
            assertContentEquals(expected.toByteArray(), requireNotNull(dir.listFiles { it.extension == "wav" }).single().readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a saved HALO becomes a stereo instrument at its root with explicit release`() {
        val root = createTempDirectory("opensky-cli-held").toFile()
        try {
            val patch = OpenSkyPatch("MY HALO", OpenSkyVoice.HALO,
                OpenSky.defaults(OpenSkyVoice.HALO) + mapOf("RISE" to 0f, "GLOW" to 0f, "WIDTH" to .35f),
                midi = 72, velocity = .3f, wet = false,
                timbre = OpenSkyTimbre(sineMix = .8f, attackScale = 1.7f, toneScale = .5f))
            val recipe = File(root, "patch.json").also { it.writeText(patch.toJsonText()) }
            val dir = File(root, "instrument")
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(listOf("OPENSKY", "HALO", "--patch", recipe.path,
                "--instrument", "--release", ".8", "--out", dir.path), PrintStream(output)))
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(.8f, instrument.release)
            assertEquals((60..84 step 3).toList(), instrument.zones.map { it.rootNote })
            assertTrue(instrument.zones.all { it.loopStartFrame > 0 && it.loopStartFrame < it.frameCount })
            val middle = instrument.zones[4]
            val note = OpenSkyPadMaker.renderZone(OpenSkyPadMaker.Spec(patch, .8f), patch.midi)
            val sheet = SmplChunk(patch.midi, SmplChunk.Loop(note.loopStartFrame, note.snip.frameCount.toLong()))
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, note.snip, smpl = sheet) }
            val wav = File(sidecar.parentFile, middle.sample)
            assertEquals(2, WavReader.read(wav).channels)
            assertContentEquals(expected.toByteArray(), wav.readBytes(), "held zones keep the saved source and authoring velocity")
            assertTrue("9 zones, each holds" in output.toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid sky flags and unsupported instruments refuse before output is created`() {
        val root = createTempDirectory("opensky-cli-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            val cases = listOf(
                listOf("OPENSKY", "SOAR", "--midi", "128"),
                listOf("OPENSKY", "SOAR", "--velocity", "NaN"),
                listOf("OPENSKY", "SOAR", "--gate", "Infinity"),
                listOf("OPENSKY", "SOAR", "--gate", ".01"),
                listOf("OPENSKY", "SOAR", "--dry", "--wet"),
                listOf("OPENSKY", "CRYSTAL", "--instrument"),
                listOf("OPENSKY", "HALO", "--instrument", "--midi", "35"),
                listOf("OPENSKY", "HALO", "--instrument", "--release", "NaN"),
                listOf("OPENSKY", "HALO", "--instrument", "--attack", ".2"),
                listOf("OPENSKY", "HALO", "--instrument", "--gate", "2"),
                listOf("TINES", "BELL", "--gate", "1"),
                listOf("TINES", "BELL", "--wet"),
                listOf("TINES", "BELL", "--patch", "missing.json"),
            )
            for (args in cases) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run((listOf("synth") + args + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), "$args: $errors")
                assertFalse("Exception" in errors.toString(), "$args: $errors")
                assertTrue("--" in errors.toString(), "$args: refusal names its flag")
            }
            val patch = OpenSkyPresets.forVoice(OpenSkyVoice.HALO).first()
            val recipe = File(root, "patch.json").also { it.writeText(patch.toJsonText()) }
            for (args in listOf(
                listOf("OPENSKY", "SOAR", "--patch", recipe.path),
                listOf("OPENSKY", "HALO", "--patch", recipe.path, "--all"),
                listOf("OPENSKY", "HALO", "--patch", recipe.path, "--preset", "1"),
                listOf("OPENSKY", "HALO", "--patch", File(root, "missing.json").path),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run((listOf("synth") + args + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
                assertTrue("--patch" in errors.toString(), "$args: $errors")
            }
            assertFalse(dir.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
