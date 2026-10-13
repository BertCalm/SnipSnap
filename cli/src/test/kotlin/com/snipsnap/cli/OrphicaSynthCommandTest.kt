package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Orphica
import com.snipsnap.synth.OrphicaMaterial
import com.snipsnap.synth.OrphicaPresets
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
                arrayOf("--scramble", "7"), arrayOf("--instrument"), arrayOf("--drone"),
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
}
