package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.OrphicaPadMaker
import com.snipsnap.synth.Orphica
import com.snipsnap.synth.OrphicaMaterial
import com.snipsnap.synth.OrphicaPresets
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrphicaSynthCommandTest {
    @Test
    fun `Orphica CLI note energy and full precision seed reach stereo audition`() {
        val dir = createTempDirectory("orphica-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "oRpHiCa", "nYlOn", "--preset", "2", "--midi", "65",
                    "--velocity", ".4", "--seed", Long.MAX_VALUE.toString(), "--out", dir.path),
                PrintStream(output), PrintStream(ByteArrayOutputStream()),
            ))
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            val original = OrphicaPresets.forVoice(OrphicaMaterial.NYLON)[1]
            val expectedPatch = original.copy(
                config = original.config.copy(midi = 65, velocity = .4f, seed = Long.MAX_VALUE),
                macros = original.macros + ("TUNE" to .5f),
            )
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, expectedPatch.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "note, energy and 64-bit seed reach the deterministic render")
            val rendered = WavReader.read(wav)
            assertEquals(2, rendered.channels)
            assertEquals(44_100, rendered.sampleRate)
            assertTrue(rendered.peak() > 0f)
            assertTrue("1 rendered into" in output.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Orphica CLI rejects invalid notes energy seeds and unsupported shapes before writing`() {
        val dir = createTempDirectory("orphica-invalid-cli").toFile()
        try {
            for (flags in listOf(
                arrayOf("--midi", (Orphica.MIDI_MIN - 1).toString()),
                arrayOf("--midi", (Orphica.MIDI_MAX + 1).toString()),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.1"),
                arrayOf("--seed", "1.5"), arrayOf("--seed", "9223372036854775808"),
                arrayOf("--scramble", "7"), arrayOf("--drone"),
                arrayOf("--instrument", "--attack", ".1"),
                arrayOf("--instrument", "--all"),
                arrayOf("--instrument", "--release", "NaN"),
                arrayOf("--instrument", "--release", "2"),
            )) {
                val errors = ByteArrayOutputStream()
                assertTrue(Cli.run(
                    arrayOf("synth", "ORPHICA", "NYLON", *flags, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ) != 0, flags.joinToString())
                assertTrue(errors.size() > 0)
                assertTrue(dir.listFiles()!!.isEmpty(), "invalid requests do not write auditions")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Orphica instrument CLI captures stereo keys with explicit root energy seed and release`() {
        val dir = createTempDirectory("orphica-instrument-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            val seed = Long.MIN_VALUE + 37L
            assertEquals(0, Cli.run(
                arrayOf("synth", "ORPHICA", "STEEL", "--preset", "1", "--instrument",
                    "--midi", "65", "--velocity", ".4", "--seed", seed.toString(),
                    "--release", ".25", "--out", dir.path),
                PrintStream(output), PrintStream(errors),
            ), errors.toString())
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(.25f, instrument.release)
            assertEquals((53..77 step 3).toList(), instrument.zones.map { it.rootNote })
            assertTrue(instrument.zones.all { it.loopStartFrame > 0 })
            assertTrue(dir.listFiles { f -> f.extension == "xty" }!!.single().isFile)
            assertTrue(dir.walkTopDown().any { it.extension == "xpm" })
            assertTrue("9 stereo zones, captured sustain" in output.toString())
            val preset = OrphicaPresets.forVoice(OrphicaMaterial.STEEL)[0]
            val spec = OrphicaPadMaker.Spec(
                preset.config.copy(midi = 65, velocity = .4f, seed = seed),
                preset.macros + ("TUNE" to .5f), releaseSeconds = .25f,
            )
            val expected = OrphicaPadMaker.renderZone(spec, 65)
            val center = instrument.zones.single { it.rootNote == 65 }
            val file = java.io.File(sidecar.parentFile, center.sample)
            val expectedBytes = ByteArrayOutputStream().also {
                WavWriter.write(it, expected.snip, smpl = com.snipsnap.audio.SmplChunk(
                    65, com.snipsnap.audio.SmplChunk.Loop(expected.loopStartFrame, expected.snip.frameCount.toLong()),
                ))
            }.toByteArray()
            assertContentEquals(expectedBytes, file.readBytes(), "root energy mode and all 64 seed bits reach the captured zone")
            assertEquals(2, WavReader.read(file).channels)
            assertEquals(center.loopStartFrame, assertNotNull(WavReader.readSmpl(file)?.loop).startFrame)
        } finally {
            dir.deleteRecursively()
        }
    }
}
