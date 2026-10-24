package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPresets
import com.snipsnap.synth.WickVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WickSynthCommandTest {
    @Test
    fun `WICK all auditions use the provisional roster and native note and velocity`() {
        val dir = kotlin.io.path.createTempDirectory("wick-cli-all").toFile()
        try {
            for (voice in listOf(WickVoice.PLUCK, WickVoice.WOOD, WickVoice.BREATH, WickVoice.FLICKER)) {
                assertTrue(Presets.forVoice("WICK", voice.name).isEmpty(), "CLI must find the provisional table directly")
                val output = ByteArrayOutputStream()
                val voiceDir = File(dir, voice.name)
                assertEquals(0, SynthCommand.run(
                    listOf("WICK", voice.name, "--all", "--midi", "72", "--velocity", "0.35", "--out", voiceDir.path),
                    PrintStream(output),
                ))
                val presets = WickPresets.forVoice(voice)
                val wavs = voiceDir.listFiles { file -> file.extension == "wav" }!!.sortedBy { it.name }
                assertEquals(presets.size, wavs.size, "--all exports each provisional $voice recipe")
                val expected = presets.first().copy(midi = 72, velocity = .35f)
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
                assertContentEquals(encoded, wavs.first().readBytes(), "both flags reach the $voice saved-recipe renderer")
                for (wav in wavs) {
                    val snip = WavReader.read(wav)
                    assertEquals(1, snip.channels, "${wav.name} stays dry and mono")
                    assertEquals(WavWriter.MPC_SAMPLE_RATE, snip.sampleRate)
                    assertTrue(snip.samples.any { kotlin.math.abs(it) > 1e-4f }, "${wav.name} is audible")
                }
                assertTrue("${presets.size} rendered" in output.toString())
                assertFalse("lands with" in output.toString(), "the cascade comes from WICK itself")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `BUBBLE preset selection preserves defaults and supports each note flag independently`() {
        val dir = kotlin.io.path.createTempDirectory("wick-cli-bubble").toFile()
        try {
            val preset = WickPresets.forVoice(WickVoice.BUBBLE)[1]
            val cases = listOf(
                emptyList<String>() to preset,
                listOf("--midi", "57") to preset.copy(midi = 57),
                listOf("--velocity", "0.4") to preset.copy(velocity = .4f),
            )
            val exported = cases.map { (flags, expected) ->
                assertEquals(0, SynthCommand.run(
                    listOf("WICK", "BUBBLE", "--preset", "2") + flags + listOf("--out", dir.path),
                    PrintStream(ByteArrayOutputStream()),
                ))
                val wav = dir.listFiles { file -> file.extension == "wav" }!!.single()
                assertTrue("Slow_Feed" in wav.name, "--preset selects the second provisional BUBBLE recipe")
                val actual = wav.readBytes()
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
                assertContentEquals(encoded, actual, "CLI preserves unsupplied fields for $flags")
                wav.delete()
                actual
            }
            assertFalse(exported[0].contentEquals(exported[1]), "the chosen MIDI override must change audio")
            assertFalse(exported[0].contentEquals(exported[2]), "the chosen velocity override must change audio")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `invalid WICK notes velocities and voices are refused before output is created`() {
        val temp = kotlin.io.path.createTempDirectory("wick-cli-invalid").toFile()
        val dir = File(temp, "not-created")
        try {
            val cases = listOf(
                "--midi" to listOf((Wick.MIN_MIDI - 1).toString(), (Wick.MAX_MIDI + 1).toString(), "60.5", "NaN"),
                "--velocity" to listOf("-0.01", "1.01", "NaN", "Infinity", "1e40"),
            )
            for ((flag, values) in cases) for (value in values) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    arrayOf("synth", "WICK", "PLUCK", flag, value, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ))
                assertTrue(flag in errors.toString(), "$flag '$value' refusal identifies the invalid flag")
                assertFalse("Exception" in errors.toString(), "CLI reports a user error")
            }
            val errors = ByteArrayOutputStream()
            assertEquals(2, Cli.run(
                arrayOf("synth", "WICK", "UNKNOWN", "--all", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors),
            ))
            assertTrue("no such engine/voice: WICK UNKNOWN" in errors.toString())
            assertFalse(dir.exists(), "invalid requests do not create the output directory")
        } finally {
            temp.deleteRecursively()
        }
    }
}
