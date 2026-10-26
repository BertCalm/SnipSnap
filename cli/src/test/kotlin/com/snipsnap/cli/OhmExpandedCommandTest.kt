package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmCapture
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OhmExpandedCommandTest {
    @Test
    fun `CLI replays a complete model three source and writes its exact portable recipe`() {
        val temp = createTempDirectory("ohm-expanded-cli").toFile()
        try {
            val patch = OhmPatch("Saved stroke", Ohm.defaults(3) + ("LENGTH" to 0f), model = 3,
                options = OhmOptions(OhmExciter.CAPTURED, elder = .3f, grains = .3f,
                    capture = OhmCapture(FloatArray(2048) { if (it % 13 == 0) .6f else -.04f }, "Test stroke")),
                releaseSeconds = .6f)
            val input = File(temp, "source.patch.json").also { it.writeText(patch.toJsonText()) }
            val dir = File(temp, "output")
            assertEquals(0, SynthCommand.run(listOf("OHM", "STRING", "--patch", input.path,
                "--midi", "57", "--velocity", ".4", "--seed", "-901", "--macros", "BODY=.7", "--out", dir.path), sink()))
            val expected = patch.copy(midi = 57, velocity = .4f, seed = -901, macros = patch.macros + ("BODY" to .7f))
            val wav = dir.listFiles { f -> f.extension == "wav" }!!.single()
            val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            assertContentEquals(encoded, wav.readBytes())
            assertEquals(expected, OhmPatch.fromJsonText(File(dir, "${wav.nameWithoutExtension}.patch.json").readText()))
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
            assertEquals(3, patch.model)
            assertEquals(59, patch.midi)
            assertEquals(.8f, patch.releaseSeconds)
            assertTrue(Ohm.isHeld(patch))
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
