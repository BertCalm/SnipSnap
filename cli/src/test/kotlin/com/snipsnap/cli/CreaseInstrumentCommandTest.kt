package com.snipsnap.cli

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.shell.CreasePadMaker
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.CreasePresets
import com.snipsnap.synth.CreaseVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercise the public command through the actual phone/MPC package, including saved-patch overrides. */
class CreaseInstrumentCommandTest {
    @Test
    fun `Paper Keys authors a held instrument with explicit root velocity and release`() {
        val root = createTempDirectory("crease-paper-keys-cli").toFile()
        try {
            val dir = File(root, "instrument")
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(arrayOf("synth", "crease", "round", "--preset", "2",
                "--instrument", "--midi", "48", "--velocity", ".5", "--release", ".8", "--out", dir.path),
                PrintStream(output), PrintStream(errors)), errors.toString())
            val patch = CreasePresets.forVoice(CreaseVoice.ROUND)[1].toPatch().copy(midi = 48, velocity = .5f)
            assertEquals("Paper Keys", patch.name)
            assertInstrument(dir, patch, .8f)
            assertTrue("pressure attack and moving sustain" in output.toString())
            assertTrue("saved velocity 0.5" in output.toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a saved Ink Ribbon needs explicit model promotion and retains its other fields`() {
        val root = createTempDirectory("crease-ink-ribbon-cli").toFile()
        try {
            val original = CreasePresets.forVoice(CreaseVoice.RUBBER)[1].toPatch().copy(model = 2, midi = 69, velocity = .4f)
            assertEquals("Ink Ribbon", original.name)
            val recipe = File(root, "source.patch").also { it.writeText(original.toJsonText()) }
            val refused = File(root, "legacy-instrument")
            val errors = ByteArrayOutputStream()
            assertEquals(2, Cli.run(arrayOf("synth", "CREASE", "RUBBER", "--patch", recipe.path,
                "--instrument", "--out", refused.path), PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
            assertTrue("model 3" in errors.toString())
            assertFalse(refused.exists(), "a legacy recipe must not create an instrument or be silently upgraded")

            val dir = File(root, "promoted-instrument")
            errors.reset()
            assertEquals(0, Cli.run(arrayOf("synth", "CREASE", "RUBBER", "--patch", recipe.path,
                "--model", "3", "--instrument", "--release", ".3", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors)), errors.toString())
            assertInstrument(dir, original.copy(model = 3), .3f)
            assertEquals(original, CreasePatch.fromJsonText(recipe.readText()), "authoring does not rewrite the source recipe")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `explicit touch authors three real excitation layers with saved overrides and sample progress`() {
        val root = createTempDirectory("crease-touch-cli").toFile()
        try {
            val dir = File(root, "instrument")
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            assertEquals(0, Cli.run(arrayOf("synth", "CREASE", "ROUND", "--preset", "2",
                "--instrument", "--touch", "--midi", "24", "--velocity", ".5", "--release", ".3", "--out", dir.path),
                PrintStream(output), PrintStream(errors)), errors.toString())
            val patch = CreasePresets.forVoice(CreaseVoice.ROUND)[1].toPatch().copy(midi = 24, velocity = .5f)
            assertInstrument(dir, patch, .3f, touchLayers = true)
            val text = output.toString()
            assertTrue("layers 13..15/15" in text, "progress counts each of the five roots' three samples")
            assertTrue("touch layers: 3 per zone, 15 samples" in text)
            assertTrue("saved velocity 0.5" in text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid held options fail before an output folder is created`() {
        val root = createTempDirectory("crease-keys-cli-invalid").toFile()
        try {
            val options = listOf(
                arrayOf("--instrument", "--attack", ".1"),
                arrayOf("--instrument", "--release", "NaN"),
                arrayOf("--instrument", "--release", "Infinity"),
                arrayOf("--instrument", "--release", "never"),
                arrayOf("--instrument", "--release", ".09"),
                arrayOf("--instrument", "--release", "1.51"),
                arrayOf("--instrument", "--model", "1"),
                arrayOf("--instrument", "--model", "2"),
                arrayOf("--instrument", "--all"),
                arrayOf("--instrument", "--midi", "23"),
                arrayOf("--instrument", "--midi", "85"),
                arrayOf("--instrument", "--velocity", "NaN"),
                arrayOf("--touch"),
                arrayOf("--touch", "--drone"),
                arrayOf("--touch", "--instrument", "--model", "2"),
                arrayOf("--touch", "--instrument", "--all"),
                arrayOf("--touch", "--instrument", "--velocity", "NaN"),
                arrayOf("--touch", "--instrument", "--velocity", "1e-44"),
                arrayOf("--release", ".6"),
            )
            for ((index, flags) in options.withIndex()) {
                val dir = File(root, "refused-$index")
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "CREASE", "PINCH", *flags, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.joinToString())
                assertTrue(errors.size() > 0, "${flags.joinToString()} needs an explanation")
                assertFalse(dir.exists(), "${flags.joinToString()} wrote output before validation")
            }
            val unsupported = File(root, "other-engine")
            val errors = ByteArrayOutputStream()
            assertEquals(2, Cli.run(arrayOf("synth", "REGISTERS", "PIPE", "--instrument", "--touch", "--out", unsupported.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
            assertTrue("--touch requires CREASE --instrument" in errors.toString())
            assertFalse(unsupported.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertInstrument(dir: File, patch: CreasePatch, release: Float, touchLayers: Boolean = false) {
        val (sidecar, instrument) = InstrumentStore.list(dir).single()
        val spec = CreasePadMaker.Spec(patch, release, touchLayers)
        assertEquals(CreasePadMaker.zoneMidis(spec), instrument.zones.map { it.rootNote })
        assertEquals(release, instrument.release)
        assertTrue(instrument.zones.all { it.loopStartFrame in 1 until it.frameCount })
        assertTrue(instrument.zones.all { it.playableLayers.size == spec.layerCount })
        for (zone in instrument.zones) for (layer in zone.playableLayers) {
            val wav = File(sidecar.parentFile, layer.sample)
            assertTrue(wav.isFile)
            val sheet = requireNotNull(WavReader.readSmpl(wav))
            assertEquals(zone.rootNote, sheet.rootNote)
            assertEquals(layer.loopStartFrame, sheet.loop?.startFrame)
            assertEquals(layer.frameCount, sheet.loop?.endFrameExclusive)
        }
        val middle = instrument.zones.single { it.rootNote == patch.midi }
        for ((index, note) in CreasePadMaker.renderLayers(spec, patch.midi).withIndex()) {
            val sheet = SmplChunk(patch.midi, SmplChunk.Loop(note.loopStartFrame, note.snip.frameCount.toLong()))
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, note.snip, smpl = sheet) }.toByteArray()
            assertContentEquals(expected, File(sidecar.parentFile, middle.playableLayers[index].sample).readBytes(),
                "the exported saved-root WAV must retain the chosen model, macros and excitation")
        }
        val xty = dir.listFiles { file -> file.extension == "xty" }!!.single()
        assertTrue("\"LoopMode\": 1" in Acvs.read(xty).payloadText)
        val data = dir.listFiles { file -> file.isDirectory && file.name.endsWith("_[TrackData]") }!!.single()
        val xpm = data.listFiles { file -> file.extension == "xpm" }!!.single()
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm.readText())
    }
}
