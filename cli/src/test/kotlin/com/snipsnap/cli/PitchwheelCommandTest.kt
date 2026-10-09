package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.PitchwheelPresets
import com.snipsnap.synth.PitchwheelVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitchwheelCommandTest {
    @Test
    fun `wheel CLI note and velocity overrides reach the saved recipe`() {
        val dir = createTempDirectory("pitchwheel-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(
                0,
                Cli.run(
                    arrayOf("synth", "pitchwheel", "pluck", "--all", "--midi", "65", "--velocity", ".4", "--out", dir.path),
                    PrintStream(output), PrintStream(ByteArrayOutputStream()),
                ),
            )
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            assertEquals("PITCHWHEEL_PLUCK_01_Pitched_Tooth.wav", wav.name)
            val preset = PitchwheelPresets.forVoice(PitchwheelVoice.PLUCK).single().copy(midi = 65, velocity = .4f)
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, preset.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "requested root and energy reach the deterministic render")
            val rendered = WavReader.read(wav)
            assertEquals(1, rendered.channels)
            assertEquals(44_100, rendered.sampleRate)
            assertTrue(rendered.peak() > 0f)
            assertTrue("1 rendered into" in output.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `wheel CLI selects the held preset and preserves its note overrides`() {
        val dir = createTempDirectory("pitchwheel-held-cli").toFile()
        try {
            assertEquals(
                0,
                Cli.run(
                    arrayOf("synth", "PiTcHwHeEl", "TuRn", "--preset", "2", "--midi", "72", "--velocity", ".35", "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()),
                ),
            )
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            assertEquals("PITCHWHEEL_TURN_01_Endless_Turn.wav", wav.name)
            val preset = PitchwheelPresets.forVoice(PitchwheelVoice.TURN)[1].copy(midi = 72, velocity = .35f)
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, preset.render()) }.toByteArray()
            assertTrue(expected.contentEquals(wav.readBytes()), "HOLD and note state survive CLI preset selection")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `wheel CLI rejects unsupported note energy and other audition shapes`() {
        val dir = createTempDirectory("pitchwheel-invalid-cli").toFile()
        try {
            for (flags in listOf(
                arrayOf("--midi", "23"), arrayOf("--midi", "97"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.1"),
                arrayOf("--midi", "60", "--instrument"), arrayOf("--velocity", ".5", "--drone"),
            )) {
                val errors = ByteArrayOutputStream()
                assertTrue(
                    Cli.run(
                        arrayOf("synth", "pitchwheel", "clunk", *flags, "--out", dir.path),
                        PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                    ) != 0,
                    flags.joinToString(),
                )
                assertTrue(errors.size() > 0)
                assertTrue(dir.listFiles()!!.isEmpty(), "invalid requests do not write auditions")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
