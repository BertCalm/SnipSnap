package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Oven
import com.snipsnap.synth.OvenPresets
import com.snipsnap.synth.OvenVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OvenSynthCommandTest {
    @Test
    fun `all four Oven articulations retain their saved controls and model with note and touch overrides`() {
        val root = createTempDirectory("oven-cli").toFile()
        try {
            for ((index, voice) in OvenVoice.entries.withIndex()) {
                val source = OvenPresets.forVoice(voice).last()
                val number = OvenPresets.forVoice(voice).size
                val midi = listOf(Oven.MIDI_MIN, Oven.MIDI_MAX, 65, 72)[index]
                // FORGED exercises a note-only override, retaining its authored strike velocity.
                val velocity = if (voice == OvenVoice.FORGED) source.velocity else .35f
                val touchFlag = if (voice == OvenVoice.FORGED) emptyList() else listOf("--velocity", ".35")
                val destination = File(root, voice.name)
                val messages = ByteArrayOutputStream()
                assertEquals(0, SynthCommand.run(
                    listOf("oVeN", voice.name.lowercase(), "--preset", "$number", "--midi", "$midi",
                        "--out", destination.path) + touchFlag,
                    PrintStream(messages),
                ))
                val wav = requireNotNull(destination.listFiles { it.extension == "wav" }).single()
                val expected = ByteArrayOutputStream().also {
                    WavWriter.write(it, source.copy(midi = midi, velocity = velocity).render())
                }.toByteArray()
                assertContentEquals(expected, wav.readBytes(),
                    "$voice must render the selected model and six controls with only requested fields changed")
                val audio = WavReader.read(wav)
                assertEquals(1, audio.channels)
                assertEquals(44_100, audio.sampleRate)
                assertTrue(audio.peak() > 0f)
                assertTrue(audio.durationSeconds <= Oven.MAX_SECONDS + .001f)
                assertTrue("1 rendered into" in messages.toString())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Oven velocity-only auditions retain the authored root and omitted flags preserve the complete preset`() {
        val root = createTempDirectory("oven-cli-native").toFile()
        try {
            // Soft Hearth's saved MIDI 57 and articulated tail differ from the default C4 strike.
            val source = OvenPresets.forVoice(OvenVoice.FELT).single { it.name == "Soft Hearth" }
            val number = OvenPresets.forVoice(OvenVoice.FELT).indexOf(source) + 1
            for (velocity in listOf<Float?>(null, .4f)) {
                val flags = velocity?.let { listOf("--velocity", "$it") } ?: emptyList()
                val destination = File(root, if (velocity == null) "native" else "touch")
                assertEquals(0, SynthCommand.run(
                    listOf("OVEN", "FELT", "--preset", "$number", "--out", destination.path) + flags,
                    PrintStream(ByteArrayOutputStream()),
                ))
                val expected = ByteArrayOutputStream().also {
                    WavWriter.write(it, source.copy(velocity = velocity ?: source.velocity).render())
                }.toByteArray()
                val wav = requireNotNull(destination.listFiles { it.extension == "wav" }).single()
                assertContentEquals(expected, wav.readBytes(), "an omitted MIDI flag must keep the saved root")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid Oven auditions fail with a CLI error before creating the output folder`() {
        val root = createTempDirectory("oven-cli-invalid").toFile()
        val destination = File(root, "not-created")
        try {
            for (flags in listOf(
                listOf("--midi", "23"), listOf("--midi", "97"), listOf("--midi", "60.5"),
                listOf("--midi", "2147483648"), listOf("--velocity", "NaN"),
                listOf("--velocity", "Infinity"), listOf("--velocity", "-.01"), listOf("--velocity", "1.01"),
                listOf("--midi", "60", "--instrument"), listOf("--velocity", ".5", "--drone"),
            )) {
                val errors = ByteArrayOutputStream()
                val code = Cli.run(
                    (listOf("synth", "OVEN", "GRAND", "--out", destination.path) + flags).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                )
                assertEquals(2, code, flags.joinToString())
                assertTrue(flags.first() in errors.toString(), "the refusal must identify the invalid option")
                assertFalse("Exception" in errors.toString())
                assertFalse(destination.exists(), "an invalid request created an output folder")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
