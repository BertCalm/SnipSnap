package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.ScannedChainKeysMaker
import com.snipsnap.synth.ScannedChainPatch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScannedChainKeysCommandTest {
    @Test
    fun `bad arguments refuse before creating output`() {
        val parent = Files.createTempDirectory("scanned-keys-refusal").toFile()
        try {
            val output = File(parent, "new-output")
            for (flags in listOf(listOf("PLUCK"), listOf("--release", ".2"), listOf("--unknown"))) {
                assertFailsWith<CliError> {
                    ScannedChainKeysCommand.run(flags + listOf("--out", output.path), PrintStream(ByteArrayOutputStream()))
                }
                assertFalse(output.exists())
            }
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `existing directory or ZIP requires explicit overwrite`() {
        val parent = Files.createTempDirectory("scanned-keys-existing").toFile()
        try {
            for (relative in listOf("playable/keep.txt", ScannedChainKeysCommand.ZIP_NAME)) {
                val directory = if ('/' in relative) parent else File(parent, "zip-only").apply { mkdirs() }
                val marker = File(directory, relative).apply { parentFile.mkdirs(); writeText("saved instrument") }
                assertFailsWith<CliError> {
                    ScannedChainKeysCommand.run(listOf("--out", directory.path), PrintStream(ByteArrayOutputStream()))
                }
                assertEquals("saved instrument", marker.readText())
            }
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `source file errors refuse before creating output`() {
        val parent = Files.createTempDirectory("scanned-keys-source-refusal").toFile()
        try {
            val output = File(parent, "new-output")
            val invalid = File(parent, "invalid.json").apply { writeText("{}") }
            for (source in listOf(File(parent, "missing.json"), invalid)) {
                val errors = ByteArrayOutputStream()
                assertTrue(Cli.run(arrayOf("scanned-chain-keys", "--patch", source.path, "--out", output.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)) != 0)
                assertTrue(errors.size() > 0)
                assertFalse(output.exists())
            }
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `edited source regenerates a bank with its model duration and shape retained`() {
        val parent = Files.createTempDirectory("scanned-keys-edited").toFile()
        try {
            val original = ScannedChainKeysMaker.shortPluck()
            val edited = original.copy(name = "Edited pluck", model = 1,
                macros = original.macros + ("DAMP" to .55f),
                config = original.config.copy(seconds = .25f, attackSeconds = .01f, releaseSeconds = .1f))
            val source = File(parent, "edited.json").apply { writeText(edited.toJsonText()) }
            val output = File(parent, "output")
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(arrayOf("scanned-chain-keys", "--patch", source.path, "--out", output.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors)), errors.toString())
            val playable = File(output, "playable")
            val data = File(playable, "card").listFiles()!!.single { it.isDirectory }
            assertEquals(edited, ScannedChainPatch.fromJsonText(File(data, ScannedChainKeysMaker.PATCH_FILE).readText()))
            val waves = data.listFiles()!!.filter { it.extension == "wav" }
            assertEquals(39, waves.size)
            assertTrue(waves.all { WavReader.read(it).frameCount == 11_025 })
            val instructions = File(playable, "LOADME.txt").readText()
            assertTrue("Edited pluck" in instructions)
            assertTrue("model 1" in instructions)
            assertTrue("finite 0.25 s" in instructions)
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `CLI delivers self contained finite keygroup ZIP and phone sidecar`() {
        val directory = Files.createTempDirectory("scanned-keys-export").toFile()
        try {
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(arrayOf("scanned-chain-keys", "--out", directory.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors)), errors.toString())
            val card = File(directory, "playable/card")
            val (sidecar, instrument) = InstrumentStore.list(card).single()
            assertEquals((36..72 step 3).toList(), instrument.zones.map { it.rootNote })
            assertEquals(36, instrument.zones.first().lowNote)
            assertEquals(72, instrument.zones.last().highNote)
            assertTrue(instrument.explicitNoteOn)
            assertTrue(instrument.zones.all { it.layers.size == 3 && it.layers.all { layer -> layer.loopStartFrame == 0L } })
            val waves = card.walkTopDown().filter { it.isFile && it.extension == "wav" }.toList()
            assertEquals(39, waves.size)
            for (wave in waves) {
                val snip = WavReader.read(wave)
                assertEquals(44_100, snip.sampleRate)
                assertEquals(1, snip.channels)
                assertEquals(35_280, snip.frameCount)
            }
            val data = card.listFiles()!!.single { it.isDirectory && it.name.endsWith("_[TrackData]") }
            assertTrue(File(data, "${ScannedChainKeysMaker.DEFAULT_NAME}.xpm").isFile)
            assertTrue(File(card, "${ScannedChainKeysMaker.DEFAULT_NAME}.xty").isFile)
            ZipFile(File(directory, ScannedChainKeysCommand.ZIP_NAME)).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(39, entries.count { it.name.endsWith(".wav") })
                assertTrue(entries.any { it.name == "LOADME.txt" })
                for (file in File(directory, "playable").walkTopDown().filter { it.isFile }) {
                    val path = file.relativeTo(File(directory, "playable")).invariantSeparatorsPath
                    val entry = zip.getEntry(path)
                    assertTrue(entry != null, "ZIP preserves $path")
                    assertTrue(zip.getInputStream(entry).readBytes().contentEquals(file.readBytes()), path)
                }
                assertTrue(zip.getEntry("card/${sidecar.name}") != null)
            }
        } finally { directory.deleteRecursively() }
    }
}
