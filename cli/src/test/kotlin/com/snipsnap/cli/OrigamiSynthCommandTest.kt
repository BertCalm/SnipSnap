package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiPresets
import com.snipsnap.synth.OrigamiVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrigamiSynthCommandTest {
    private fun exported(patch: OrigamiPatch): ByteArray =
        ByteArrayOutputStream().also { WavWriter.write(it, patch.render()) }.toByteArray()

    @Test
    fun `all three paper voices route every factory preset to its exact dry WAV`() {
        val root = createTempDirectory("origami-cli-factory").toFile()
        try {
            for (voice in OrigamiVoice.entries) {
                val dir = File(root, voice.name)
                val output = ByteArrayOutputStream()
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(
                    arrayOf("synth", "oRiGaMi", voice.name.lowercase(), "--all", "--out", dir.path),
                    PrintStream(output), PrintStream(errors),
                ), errors.toString())
                val presets = OrigamiPresets.forVoice(voice)
                val wavs = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).sortedBy { it.name }
                assertEquals(2, presets.size, "$voice has two factory choices")
                assertEquals(presets.size, wavs.size, "$voice --all must render both choices")
                for ((preset, wav) in presets.zip(wavs)) {
                    assertTrue(wav.name.startsWith("ORIGAMI_${voice.name}_"), wav.name)
                    assertContentEquals(exported(preset), wav.readBytes(),
                        "${preset.name}: CLI must retain the factory model, seed, velocity and controls")
                    val actual = WavReader.read(wav)
                    assertEquals(1, actual.channels)
                    assertEquals(44_100, actual.sampleRate)
                    assertTrue(actual.peak() > 0f, "${preset.name}: routed factory audio is silent")
                }
                assertTrue("2 rendered into" in output.toString(), output.toString())
                assertFalse("lands with" in output.toString(), "ORIGAMI factory auditions are dry")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `note and velocity overrides retain the selected moving texture recipe`() {
        val root = createTempDirectory("origami-cli-overrides").toFile()
        try {
            // The second STILL preset has nonzero motion, a fixed nondefault seed and model 4.
            // Compare encoded audio so losing any of those fields cannot hide behind PCM tolerance.
            val preset = OrigamiPresets.forVoice(OrigamiVoice.STILL)[1]
            val cases = listOf(
                Triple(listOf("--midi", "48"), 48, null),
                Triple(listOf("--velocity", "0"), null, 0f),
                Triple(listOf("--midi", "72", "--velocity", ".35"), 72, .35f),
            )
            for ((index, case) in cases.withIndex()) {
                val dir = File(root, index.toString())
                val output = ByteArrayOutputStream()
                assertEquals(0, SynthCommand.run(
                    listOf("ORIGAMI", "STILL", "--preset", "2") + case.first + listOf("--out", dir.path),
                    PrintStream(output),
                ))
                val wav = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).single()
                assertEquals("ORIGAMI_STILL_01_Quiet_Paper.wav", wav.name)
                val expected = preset.copy(
                    midi = case.second ?: preset.midi,
                    velocity = case.third ?: preset.velocity,
                )
                assertContentEquals(exported(expected), wav.readBytes(),
                    "${case.first}: note overrides retain energy; velocity overrides retain note and geometry")
                if (case.third == 0f) {
                    assertTrue(WavReader.read(wav).samples.all { it == 0f }, "CLI velocity zero must be silent")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unsupported notes nonfinite velocity and held export flags refuse before writing`() {
        val root = createTempDirectory("origami-cli-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            val cases = listOf(
                listOf("--midi", (Origami.MIDI_MIN - 1).toString()),
                listOf("--midi", (Origami.MIDI_MAX + 1).toString()),
                listOf("--midi", "60.5"), listOf("--midi", "NaN"),
                listOf("--velocity", "-.1"), listOf("--velocity", "1.1"),
                listOf("--velocity", "NaN"), listOf("--velocity", "Infinity"),
                listOf("--velocity", "-Infinity"),
                listOf("--midi", "60", "--instrument"),
                listOf("--velocity", ".5", "--drone"),
            )
            for (flags in cases) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    (listOf("synth", "ORIGAMI", "FAN") + flags + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), flags.joinToString())
                assertTrue(flags.first() in errors.toString(), errors.toString())
                assertFalse(dir.exists(), "${flags.joinToString()}: invalid auditions must not create output")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
