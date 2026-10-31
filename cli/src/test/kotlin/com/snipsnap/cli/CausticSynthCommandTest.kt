package com.snipsnap.cli

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
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

class CausticSynthCommandTest {
    @Test
    fun `each character voice replays its own saved note and accepts native control overrides`() {
        val root = createTempDirectory("caustic-cli-voices").toFile()
        try {
            for (voice in CausticVoice.entries.filter { it != CausticVoice.ALLOY }) {
                assertEquals(1, Presets.forVoice("CAUSTIC", voice.name).size)
                val patch = CausticPatch("SAVED DIRECTION", voice,
                    mapOf("SOURCE" to .62f, "LENGTH" to 0f), midi = 55, velocity = .43f, model = 1)
                val recipe = File(root, "${voice.name}.json").also { it.writeText(patch.toJsonText()) }
                val destination = File(root, voice.name)
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(arrayOf("synth", "caustic", voice.name.lowercase(),
                    "--patch", recipe.path, "--midi", "67", "--velocity", ".29", "--model", "1",
                    "--macros", "BODY=.64,FOCUS=.31", "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), errors.toString())
                val expected = patch.copy(midi = 67, velocity = .29f,
                    macros = patch.macros + mapOf("BODY" to .64f, "FOCUS" to .31f))
                assertContentEquals(wav(expected.render()), onlyWav(destination).readBytes(), voice.name)
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `native Alloy presets render as finite dry notes without changing their saved sound`() {
        val root = createTempDirectory("caustic-cli-presets").toFile()
        try {
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "cAuStIc", "aLlOy", "--all", "--out", root.path),
                PrintStream(output), PrintStream(errors),
            ), errors.toString())
            val presets = Presets.forVoice(CausticPatch.ENGINE, CausticVoice.ALLOY.name)
            assertTrue(presets.isNotEmpty())
            val files = requireNotNull(root.listFiles { it.extension == "wav" }).sortedBy { it.name }
            assertEquals(presets.size, files.size)
            for ((patch, file) in presets.zip(files)) {
                assertTrue(patch is CausticPatch)
                assertContentEquals(wav(patch.render()), file.readBytes(), patch.name)
                val decoded = WavReader.read(file)
                assertEquals(44_100, decoded.sampleRate)
                assertEquals(1, decoded.channels)
                assertTrue(decoded.samples.all { it.isFinite() })
                assertTrue(decoded.peak() > 0f)
            }
            assertFalse("lands with" in output.toString(), "CAUSTIC auditions remain dry")
            assertFalse("holds" in output.toString(), "a long finite note must not be advertised as HOLD")
            assertTrue("${presets.size} rendered into" in output.toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `note velocity and native model overrides reach the selected Alloy patch together`() {
        val root = createTempDirectory("caustic-cli-overrides").toFile()
        try {
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(
                arrayOf("synth", "CAUSTIC", "ALLOY", "--preset", "1", "--midi", "67",
                    "--velocity", ".35", "--model", "1", "--out", root.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors),
            ), errors.toString())
            val preset = Presets.forVoice(CausticPatch.ENGINE, CausticVoice.ALLOY.name).first() as CausticPatch
            val expected = preset.copy(midi = 67, velocity = .35f, model = 1)
            assertContentEquals(wav(expected.render()), onlyWav(root).readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `saved Alloy patch keeps its note velocity and model through macro edits`() {
        val root = createTempDirectory("caustic-cli-saved").toFile()
        try {
            val patch = savedPatch()
            val recipe = File(root, "saved.json").also { it.writeText(patch.toJsonText()) }
            val destination = File(root, "edited")
            assertEquals(0, SynthCommand.run(
                listOf("CAUSTIC", "ALLOY", "--patch", recipe.path, "--model", "1",
                    "--macros", " source = .72, FOCUS=.38,BODY=.6,LENGTH=.25,HOLD=0 ", "--out", destination.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            val expected = patch.copy(macros = patch.macros + mapOf(
                "SOURCE" to .72f, "FOCUS" to .38f, "BODY" to .6f, "LENGTH" to .25f, "HOLD" to 0f,
            ))
            assertEquals(48, expected.midi)
            assertEquals(.42f, expected.velocity)
            assertEquals(1, expected.model)
            assertContentEquals(wav(expected.render()), onlyWav(destination).readBytes())

            val overridden = File(root, "overridden")
            assertEquals(0, SynthCommand.run(
                listOf("CAUSTIC", "ALLOY", "--patch", recipe.path, "--midi", "72", "--velocity", ".28",
                    "--macros", "BODY=.7,LENGTH=.25", "--out", overridden.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            assertContentEquals(wav(patch.copy(
                midi = 72, velocity = .28f, macros = patch.macros + mapOf("BODY" to .7f, "LENGTH" to .25f),
            ).render()), onlyWav(overridden).readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid pitch velocity model macros and held shapes fail before output creation`() {
        val root = createTempDirectory("caustic-cli-invalid").toFile()
        val destination = File(root, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "35"), arrayOf("--midi", "85"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "Infinity"), arrayOf("--velocity", "1.01"),
                arrayOf("--model", "0"), arrayOf("--model", "2"), arrayOf("--model", "4"), arrayOf("--model", "1.5"),
                arrayOf("--macros", "HOLD=.000001"), arrayOf("--macros", "HOLD=1"),
                arrayOf("--macros", "BODY=NaN"), arrayOf("--macros", "FOCUS=Infinity"),
                arrayOf("--macros", "BODY=.2,body=.7"), arrayOf("--macros", "UNKNOWN=.5"),
                arrayOf("--macros", "SOURCE=-.01"), arrayOf("--macros", "LENGTH=1.01"),
                arrayOf("--macros", ""), arrayOf("--macros", "FOCUS=.2,"),
                arrayOf("--instrument"), arrayOf("--drone"), arrayOf("--seconds", "1"), arrayOf("--seed", "1"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    arrayOf("synth", "CAUSTIC", "ALLOY", *flags, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), flags.joinToString())
                assertTrue(errors.size() > 0, "the refusal must explain ${flags.joinToString()}")
                assertFalse("Exception" in errors.toString())
                assertFalse(destination.exists(), "invalid ${flags.joinToString()} created output")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unsupported saved native model is refused instead of adopting a legacy audition renderer`() {
        val root = createTempDirectory("caustic-cli-model-refusal").toFile()
        val destination = File(root, "not-created")
        try {
            val text = savedPatch().toJsonText()
            val unsupported = text.replace(Regex("\"model\"\\s*:\\s*1"), "\"model\":4")
            assertTrue(text != unsupported)
            val recipe = File(root, "legacy-model.json").also { it.writeText(unsupported) }
            val errors = ByteArrayOutputStream()
            assertEquals(2, Cli.run(
                arrayOf("synth", "CAUSTIC", "ALLOY", "--patch", recipe.path, "--out", destination.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors),
            ))
            assertTrue("--patch" in errors.toString())
            assertTrue("model" in errors.toString().lowercase())
            assertFalse(destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun savedPatch() = CausticPatch(
        "SAVED ALLOY", CausticVoice.ALLOY,
        CausticInstrument.defaults(CausticVoice.ALLOY) + mapOf("BODY" to .8f, "LENGTH" to .2f),
        midi = 48, velocity = .42f, model = 1,
    )

    private fun onlyWav(directory: File): File = requireNotNull(directory.listFiles { it.extension == "wav" }).single()
    private fun wav(snip: Snip): ByteArray = ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()
}
