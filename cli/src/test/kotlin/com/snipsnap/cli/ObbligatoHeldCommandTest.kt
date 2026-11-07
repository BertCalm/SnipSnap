package com.snipsnap.cli

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoHeld
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.ObbligatoPlayback
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The CLI must preserve the same held recipe and loop the phone package plays. */
class ObbligatoHeldCommandTest {
    private fun source() = ObbligatoPatch("HELD DUET", Obbligato.defaults() + ("HOLD" to 1f),
        playback = ObbligatoPlayback.HELD)

    @Test
    fun `a saved held audition retains playing overrides and real WAV sustain metadata`() {
        val root = createTempDirectory("obbligato-held-cli").toFile()
        try {
            val original = source()
            val text = original.toJsonText()
            val input = File(root, "source.patch").also { it.writeText(text) }
            val destination = File(root, "audition")
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(arrayOf("synth", "OBBLIGATO", "AIR_REED", "--patch", input.path,
                "--midi", "67", "--velocity", ".6", "--out", destination.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors)), errors.toString())
            val expected = original.copy(midi = 67, velocity = .6f)
            val wav = destination.listFiles { it.extension == "wav" }!!.single()
            val saved = File(destination, "${wav.nameWithoutExtension}.patch")
            assertEquals(expected, ObbligatoPatch.fromJsonText(saved.readText()))
            assertEquals(text, input.readText(), "held audition rewrites its input recipe")
            val capture = ObbligatoHeld.render(expected)
            val metadata = SmplChunk(67, SmplChunk.Loop(capture.loopStartFrame.toLong(), capture.loopEndExclusive.toLong()))
            assertEquals(metadata, WavReader.readSmplStrict(wav))
            val encoded = ByteArrayOutputStream().also {
                WavWriter.write(it, capture.snip, smpl = metadata)
            }.toByteArray()
            assertContentEquals(encoded, wav.readBytes(), "CLI held output differs from the saved source capture")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `held instrument export keeps thirteen bounded native roots and its saved sampler release`() {
        val root = createTempDirectory("obbligato-held-instrument-cli").toFile()
        try {
            val original = source()
            val input = File(root, "source.patch").also { it.writeText(original.toJsonText()) }
            val destination = File(root, "instrument")
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(arrayOf("synth", "OBBLIGATO", "AIR_REED", "--patch", input.path,
                "--instrument", "--release", ".43", "--out", destination.path),
                PrintStream(output), PrintStream(errors)), errors.toString())
            assertEquals(original.copy(releaseSeconds = .43f),
                ObbligatoPatch.fromJsonText(File(destination, "${original.name}.patch.json").readText()))
            val (sidecar, instrument) = InstrumentStore.list(destination).single()
            assertEquals((48..84 step 3).toList(), instrument.zones.map { it.rootNote })
            assertEquals(48, instrument.zones.first().lowNote)
            assertEquals(84, instrument.zones.last().highNote)
            assertEquals(.43f, instrument.release)
            for (zone in instrument.zones) {
                assertTrue(zone.loopStartFrame > 0 && zone.frameCount > zone.loopStartFrame)
                val wav = File(sidecar.parentFile, zone.sample)
                assertEquals(SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount)),
                    WavReader.readSmplStrict(wav))
            }
            assertTrue("13 native reference zones" in output.toString())
        } finally {
            root.deleteRecursively()
        }
    }
}
