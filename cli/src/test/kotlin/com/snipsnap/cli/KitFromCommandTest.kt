package com.snipsnap.cli

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.KitFromAnything
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KitFromCommandTest {

    private fun source(dir: File): File {
        val rate = 44_100
        val n = (0.4f * rate).toInt()
        var phase = 0.0
        val x = FloatArray(n) { i ->
            val t = i.toFloat() / rate
            phase += (60f + 90f * exp(-t / 0.04f)) / rate
            (sin(2.0 * PI * phase) * exp(-t / 0.15f)).toFloat()
        }
        return File(dir, "thud.wav").also { WavWriter.write(it, Snip(x, 1, rate)) }
    }

    @Test
    fun `kitfrom keeps the source, gives every pad a recipe, and --check rebuilds all sixteen bit for bit`() {
        val dir = createTempDirectory("kitfrom-cli").toFile()
        try {
            val wav = source(dir)
            val out = ByteArrayOutputStream()
            val code = Cli.run(
                arrayOf("kitfrom", wav.path, "--key", "Am", "--genre", "house", "--out", dir.path, "--name", "Thud Kit"),
                PrintStream(out), PrintStream(ByteArrayOutputStream()),
            )
            assertEquals(0, code, out.toString())
            val kitDir = File(dir, "Thud Kit")
            assertTrue(File(kitDir, KitFromAnything.SOURCE_FILE).isFile, "the kit keeps its source")
            val kit = KitStore.load(kitDir)
            assertEquals(16, kit.pads.size)
            for (pad in kit.pads) {
                val recipe = assertNotNull(KitFromAnything.Recipe.fromJsonValue(pad.recipe), "pad ${pad.slot} carries a recipe")
                assertEquals(KitFromAnything.SOURCE_FILE, recipe.sourceFile)
            }
            assertEquals(124f, kit.tempoBpm)
            assertTrue("every pad carries a recipe" in out.toString())

            val check = ByteArrayOutputStream()
            val checkCode = Cli.run(arrayOf("kitfrom", "--check", kitDir.path), PrintStream(check), PrintStream(ByteArrayOutputStream()))
            assertEquals(0, checkCode, check.toString())
            assertTrue("16 of 16 pad(s) rebuilt bit for bit" in check.toString(), check.toString())

            // and a swapped source is caught, not quietly re-rendered
            WavWriter.write(File(kitDir, KitFromAnything.SOURCE_FILE), WavReader.read(wav).let { Snip(it.samples.map { v -> v * 0.5f }.toFloatArray(), 1, it.sampleRate) })
            val swapped = ByteArrayOutputStream()
            val swappedCode = Cli.run(arrayOf("kitfrom", "--check", kitDir.path), PrintStream(swapped), PrintStream(ByteArrayOutputStream()))
            assertEquals(1, swappedCode)
            assertTrue("not the one this pad was made from" in swapped.toString(), swapped.toString())
        } finally {
            dir.deleteRecursively()
        }
    }
}
