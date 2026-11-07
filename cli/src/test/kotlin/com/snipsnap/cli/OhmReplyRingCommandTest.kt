package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import com.snipsnap.synth.OhmPresets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OhmReplyRingCommandTest {
    @Test
    fun `new controls select approved defaults while an unqualified command retains model two`() {
        val root = createTempDirectory("ohm-reply-cli").toFile()
        try {
            val legacy = File(root, "legacy")
            run(listOf("--out", legacy.path))
            assertEquals(OhmPresets.all().first(), saved(legacy))
            val explicit = File(root, "explicit")
            run(listOf("--model", "5", "--out", explicit.path))
            assertEquals(Ohm.replyRingPatch(), saved(explicit))
            val edited = File(root, "edited")
            run(listOf("--ohm-reply", ".2", "--ohm-ring", ".8", "--midi", "57", "--velocity", ".4",
                "--seed", "-71", "--macros", "BODY=.7", "--out", edited.path))
            val base = Ohm.replyRingPatch()
            val expected = base.copy(macros = base.macros + ("BODY" to .7f), midi = 57,
                velocity = .4f, seed = -71, reply = .2f, ring = .8f)
            assertEquals(expected, saved(edited))
            val wav = edited.listFiles { f -> f.extension == "wav" }!!.single()
            val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            assertContentEquals(encoded, wav.readBytes())
            assertEquals(7 * WavWriter.MPC_SAMPLE_RATE, WavReader.read(wav).frameCount)
            assertEquals(null, WavReader.readSmpl(wav)?.loop)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `saved reply ring recipes preserve their complete performance and all filters the approved family`() {
        val root = createTempDirectory("ohm-reply-replay").toFile()
        try {
            val base = Ohm.replyRingPatch("Saved conversation")
            val patch = base.copy(macros = base.macros + mapOf("TOUCH" to .23f, "COMMUNE" to .62f),
                midi = 63, velocity = .51f, seed = Int.MIN_VALUE, reply = .19f, ring = .83f, releaseSeconds = 1.2f)
            val input = File(root, "source.patch.json").also { it.writeText(patch.toJsonText()) }
            val out = File(root, "replay")
            run(listOf("--patch", input.path, "--ohm-ring", ".6", "--out", out.path))
            assertEquals(patch.copy(ring = .6f), saved(out))
            val all = File(root, "family")
            run(listOf("--all", "--model", "5", "--ohm-reply", ".25", "--out", all.path))
            val saved = all.listFiles { f -> f.name.endsWith(".patch.json") }!!.sortedBy { it.name }
                .map { OhmPatch.fromJsonText(it.readText()) }
            assertEquals(OhmPresets.all().filter { it.model == 5 }.map { it.copy(reply = .25f) }, saved)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `finite instrument CLI preserves model five release and authored controls`() {
        val root = createTempDirectory("ohm-reply-instrument-cli").toFile()
        try {
            run(listOf("--preset", "8", "--instrument", "--release", ".12", "--out", root.path))
            val patch = saved(root)
            assertEquals(OhmPresets.all()[7].copy(releaseSeconds = .12f), patch)
            assertFalse("HOLD" in patch.macros)
            assertEquals(1, root.listFiles { f -> f.extension == "xty" }!!.size)
            val waves = root.walkTopDown().filter { it.extension == "wav" }.toList()
            assertEquals(9, waves.size)
            assertTrue(waves.all { WavReader.read(it).frameCount == 7 * WavWriter.MPC_SAMPLE_RATE &&
                assertNotNull(WavReader.readSmpl(it)).loop == null })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `incompatible reply ring edits fail before creating output`() {
        val root = createTempDirectory("ohm-reply-errors").toFile()
        try {
            val patch = File(root, "new.patch.json").also { it.writeText(Ohm.replyRingPatch().toJsonText()) }
            val source = File(root, "source.patch.json").also { it.writeText(OhmPatch("Bow", Ohm.defaults(4),
                model = 4, options = OhmOptions(exciter = OhmExciter.BOW)).toJsonText()) }
            val cases = listOf(
                listOf("--ohm-reply", "NaN") to "finite",
                listOf("--ohm-ring", "1.1") to "0..1",
                listOf("--ohm-ring", ".2", "--ohm-ring", ".3") to "once",
                listOf("--model", "4", "--ohm-reply", ".2") to "model 5",
                listOf("--model", "5", "--macros", "HOLD=1") to "finite",
                listOf("--model", "5", "--preset", "5") to "presets 6..10",
                listOf("--patch", patch.path, "--model", "4") to "downgraded",
                listOf("--patch", source.path, "--model", "5") to "source settings",
            )
            for ((index, case) in cases.withIndex()) {
                val dest = File(root, "absent$index")
                val error = ByteArrayOutputStream()
                assertEquals(2, Cli.run((listOf("synth", "OHM", "STRING") + case.first +
                    listOf("--out", dest.path)).toTypedArray(), sink(), PrintStream(error)), case.first.toString())
                assertTrue(error.toString().contains(case.second), error.toString())
                assertFalse(dest.exists())
            }
            val wrongEngine = File(root, "wrong-engine")
            val error = ByteArrayOutputStream()
            assertEquals(2, Cli.run(arrayOf("synth", "RESIN", "BRASS", "--ohm-reply", ".5", "--out", wrongEngine.path),
                sink(), PrintStream(error)))
            assertTrue(error.toString().contains("only supported by OHM"), error.toString())
            assertFalse(wrongEngine.exists())
        } finally { root.deleteRecursively() }
    }

    private fun run(flags: List<String>) = assertEquals(0, SynthCommand.run(listOf("OHM", "STRING") + flags, sink()))
    private fun saved(dir: File): OhmPatch = OhmPatch.fromJsonText(dir.listFiles { f ->
        f.name.endsWith(".patch.json") }!!.single().readText())
    private fun sink() = PrintStream(ByteArrayOutputStream())
}
