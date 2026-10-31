package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavReader
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmCapture
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OhmExpandedCommandTest {
    @Test
    fun `CLI replays each complete expanded source and writes its exact portable recipe`() {
        val temp = createTempDirectory("ohm-expanded-cli").toFile()
        try {
            for (model in 3..4) {
                val patch = OhmPatch("Saved stroke", Ohm.defaults(model) + ("LENGTH" to 0f), model = model,
                    options = OhmOptions(OhmExciter.CAPTURED, elder = .3f, grains = .3f,
                        capture = OhmCapture(FloatArray(2048) { if (it % 13 == 0) .6f else -.04f }, "Test stroke")),
                    releaseSeconds = .6f)
                val input = File(temp, "source$model.patch.json").also { it.writeText(patch.toJsonText()) }
                val dir = File(temp, "output$model")
                assertEquals(0, SynthCommand.run(listOf("OHM", "STRING", "--patch", input.path,
                    "--midi", "57", "--velocity", ".4", "--seed", "-901", "--macros", "BODY=.7,HOLD=0", "--out", dir.path), sink()))
                val expected = patch.copy(midi = 57, velocity = .4f, seed = -901, macros = patch.macros + mapOf("BODY" to .7f, "HOLD" to 0f))
                val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
                assertContentEquals(encoded, wav.readBytes())
                assertEquals(expected, OhmPatch.fromJsonText(File(dir, "${wav.nameWithoutExtension}.patch.json").readText()))
            }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `instrument CLI promotes a finite recipe into native held zones with saved release`() {
        val temp = createTempDirectory("ohm-instrument-cli").toFile()
        try {
            assertEquals(0, SynthCommand.run(listOf("OHM", "STRING", "--preset", "4", "--instrument",
                "--midi", "59", "--release", ".8", "--out", temp.path), sink()))
            assertEquals(1, temp.listFiles { f -> f.extension == "xty" }!!.size)
            val patchFile = temp.listFiles { f -> f.name.endsWith(".patch.json") }!!.single()
            val patch = OhmPatch.fromJsonText(patchFile.readText())
            assertEquals(4, patch.model)
            assertEquals(59, patch.midi)
            assertEquals(.8f, patch.releaseSeconds)
            assertTrue(Ohm.isHeld(patch))
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `instrument CLI preserves each saved expanded renderer source and performance`() {
        val temp = createTempDirectory("ohm-saved-instrument-cli").toFile()
        try {
            val capture = OhmCapture(FloatArray(4_410) { i -> (.3 * sin(2 * PI * 173 * i / 44_100)).toFloat() }, "Saved tap")
            for (model in 3..4) {
                val patch = OhmPatch("Saved instrument $model", Ohm.defaults(model) + ("LENGTH" to 0f),
                    midi = 57, velocity = .6f, seed = -71, model = model,
                    options = OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture), releaseSeconds = .7f)
                val input = File(temp, "source$model.patch.json").also { it.writeText(patch.toJsonText()) }
                val dir = File(temp, "instrument$model")
                assertEquals(0, SynthCommand.run(listOf("OHM", "STRING", "--patch", input.path,
                    "--instrument", "--out", dir.path), sink()))
                val saved = dir.listFiles { file -> file.name.endsWith(".patch.json") }!!.single()
                assertEquals(patch.copy(macros = patch.macros + ("HOLD" to 1f)), OhmPatch.fromJsonText(saved.readText()))
                assertEquals(1, dir.listFiles { file -> file.extension == "xty" }!!.size)
            }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `explicit model three remains available while saved expanded recipes refuse downgrade`() {
        val temp = createTempDirectory("ohm-model-choice-cli").toFile()
        try {
            val explicit = File(temp, "explicit-three")
            assertEquals(0, SynthCommand.run(listOf("OHM", "STRING", "--preset", "1", "--model", "3",
                "--macros", "LENGTH=0,HOLD=0", "--out", explicit.path), sink()))
            assertEquals(3, OhmPatch.fromJsonText(explicit.listFiles { file -> file.name.endsWith(".patch.json") }!!.single().readText()).model)
            val held = File(temp, "new-held-note")
            assertEquals(0, SynthCommand.run(listOf("OHM", "STRING", "--preset", "1",
                "--macros", "LENGTH=0,HOLD=1", "--out", held.path), sink()))
            val heldPatch = OhmPatch.fromJsonText(held.listFiles { file -> file.name.endsWith(".patch.json") }!!.single().readText())
            assertEquals(4, heldPatch.model, "new HOLD requests must use the current expanded renderer")
            assertTrue(Ohm.isHeld(heldPatch))
            val heldWav = held.listFiles { file -> file.extension == "wav" }!!.single()
            assertTrue(assertNotNull(assertNotNull(WavReader.readSmpl(heldWav)).loop).startFrame > 0)
            val patch = OhmPatch("Current source", Ohm.defaults(4), model = 4)
            val input = File(temp, "source.patch.json").also { it.writeText(patch.toJsonText()) }
            for (model in 1..3) {
                val absent = File(temp, "downgrade$model")
                assertEquals(2, Cli.run(arrayOf("synth", "OHM", "STRING", "--patch", input.path,
                    "--model", model.toString(), "--out", absent.path), sink(), sink()))
                assertFalse(absent.exists())
            }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `invalid expanded states fail before creating an output directory`() {
        val temp = createTempDirectory("ohm-expanded-invalid").toFile()
        try {
            val dest = File(temp, "absent")
            for (flags in listOf(listOf("--model", "9"), listOf("--model", "3", "--macros", "HOLD=NaN"),
                listOf("--instrument", "--model", "2"), listOf("--instrument", "--release", "NaN"),
                listOf("--instrument", "--attack", ".2"))) {
                val args = (listOf("synth", "OHM", "STRING") + flags + listOf("--out", dest.path)).toTypedArray()
                assertEquals(2, Cli.run(args, sink(), sink()), flags.toString())
                assertFalse(dest.exists())
            }
        } finally { temp.deleteRecursively() }
    }

    private fun sink() = PrintStream(ByteArrayOutputStream())
}
