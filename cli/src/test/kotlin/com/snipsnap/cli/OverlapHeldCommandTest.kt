package com.snipsnap.cli

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Overlap
import com.snipsnap.synth.OverlapHeld
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.OverlapPlayback
import com.snipsnap.synth.OverlapTopology
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlapHeldCommandTest {
    @Test
    fun `saved held recipe replays its exact loop and exports the same instrument performance`() {
        val root = createTempDirectory("overlap-held-cli").toFile()
        try {
            val original = OverlapPatch("MY HELD MIRROR", OverlapTopology.MIRROR,
                OverlapHeld.defaults(OverlapTopology.MIRROR) + mapOf("KNOT" to .72f, "RING" to .35f),
                midi = 48, velocity = .8f, playback = OverlapPlayback.HELD, releaseSeconds = .9f)
            val source = File(root, "source.json").also { it.writeText(original.toJsonText()) }
            val expected = original.copy(midi = 60, velocity = .45f, releaseSeconds = .3f)
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            val instrumentDir = File(root, "instrument")
            assertEquals(0, Cli.run(arrayOf("synth", "OVERLAP", "MIRROR", "--patch", source.path,
                "--instrument", "--midi", "60", "--velocity", ".45", "--release", ".3",
                "--out", instrumentDir.path), PrintStream(output), PrintStream(errors)), errors.toString())
            assertEquals(original, OverlapPatch.fromJsonText(source.readText()), "export preserves its input recipe")
            val saved = File(instrumentDir, "${original.name}.patch.json")
            assertEquals(expected, OverlapPatch.fromJsonText(saved.readText()))
            val (sidecar, instrument) = InstrumentStore.list(instrumentDir).single()
            assertEquals(listOf(48, 60, 72), instrument.zones.map { it.rootNote })
            assertEquals(0, instrument.zones.first().lowNote)
            assertEquals(127, instrument.zones.last().highNote)
            assertEquals(.3f, instrument.release)
            val zone = instrument.zones.single { it.rootNote == 60 }
            val held = OverlapHeld.render(expected)
            val smpl = SmplChunk(60, SmplChunk.Loop(held.loopStartFrame.toLong(), held.loopEndExclusive.toLong()))
            val encoded = ByteArrayOutputStream().also { WavWriter.write(it, held.snip, smpl = smpl) }.toByteArray()
            val zoneWav = File(sidecar.parentFile, zone.sample)
            assertContentEquals(encoded, zoneWav.readBytes(), "note and energy overrides reach the exported source")
            assertEquals(smpl, WavReader.readSmpl(zoneWav))

            val replayDir = File(root, "replay")
            assertEquals(0, SynthCommand.run(listOf("OVERLAP", "MIRROR", "--patch", saved.path,
                "--out", replayDir.path), PrintStream(ByteArrayOutputStream())))
            val replayWav = replayDir.listFiles { it.extension == "wav" }!!.single()
            assertContentEquals(encoded, replayWav.readBytes(), "saved held replay keeps the sustain metadata")
            assertEquals(expected, OverlapPatch.fromJsonText(File(replayDir, "${replayWav.nameWithoutExtension}.patch.json").readText()))
            assertTrue("3 native reference zones" in output.toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `finite saved recipes keep finite playback and held route refuses unsupported inputs before writing`() {
        val root = createTempDirectory("overlap-cli-boundaries").toFile()
        try {
            val finite = OverlapPatch("FINITE SELF", OverlapTopology.SELF,
                Overlap.defaults(OverlapTopology.SELF, model = 1) + ("HOLD" to .1f), model = 1)
            val finiteSource = File(root, "finite.json").also { it.writeText(finite.toJsonText()) }
            val held = finite.copy(model = 2, macros = OverlapHeld.defaults(finite.topology), playback = OverlapPlayback.HELD)
            val heldSource = File(root, "held.json").also { it.writeText(held.toJsonText()) }
            val destination = File(root, "not-created")
            val cases = listOf(
                listOf("--instrument"),
                listOf("--patch", finiteSource.path, "--instrument"),
                listOf("--patch", heldSource.path, "--instrument", "--attack", ".1"),
                listOf("--patch", heldSource.path, "--instrument", "--release", "NaN"),
                listOf("--patch", heldSource.path, "--instrument", "--release", ".09"),
                listOf("--patch", heldSource.path, "--instrument", "--release", "1.51"),
                listOf("--patch", heldSource.path, "--instrument", "--all"),
                listOf("--patch", heldSource.path, "--instrument", "--midi", "85"),
            )
            for (flags in cases) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run((listOf("synth", "OVERLAP", "SELF") + flags +
                    listOf("--out", destination.path)).toTypedArray(), PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.toString())
                assertTrue(errors.size() > 0, "a refusal explains itself")
                assertFalse(destination.exists(), "invalid instrument inputs must not create output")
            }
            val replayDir = File(root, "finite")
            assertEquals(0, SynthCommand.run(listOf("OVERLAP", "SELF", "--patch", finiteSource.path,
                "--out", replayDir.path), PrintStream(ByteArrayOutputStream())))
            val wav = replayDir.listFiles { it.extension == "wav" }!!.single()
            val encoded = ByteArrayOutputStream().also { WavWriter.write(it, finite.render()) }.toByteArray()
            assertContentEquals(encoded, wav.readBytes(), "model 1 replay stays finite and unlooped")
            assertEquals(null, WavReader.readSmpl(wav))
        } finally {
            root.deleteRecursively()
        }
    }
}
