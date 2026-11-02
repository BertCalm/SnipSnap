package com.snipsnap.cli

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.shell.CausticPadMaker
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
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

class CausticInstrumentCommandTest {
    private fun run(args: List<String>): String {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        assertEquals(0, Cli.run(arrayOf("synth", "CAUSTIC", *args.toTypedArray()),
            PrintStream(output), PrintStream(errors)), errors.toString())
        return output.toString()
    }

    @Test fun `zero extensions keep the finite audio and positive extension edits preserve source performance`() {
        val root = createTempDirectory("caustic-cli-motion").toFile()
        try {
            val legacy = CausticPatch("SAVED ALLOY", CausticVoice.ALLOY,
                mapOf("LENGTH" to 0f, "BODY" to .6f), midi = 55, velocity = .43f, model = 1)
            val recipe = File(root, "saved.json").also { it.writeText(legacy.toJsonText()) }
            val unchanged = File(root, "zero")
            run(listOf("ALLOY", "--patch", recipe.path, "--model", "2", "--macros", "MOTION=0,HOLD=0", "--out", unchanged.path))
            assertContentEquals(ByteArrayOutputStream().also { WavWriter.write(it, legacy.render()) }.toByteArray(),
                unchanged.listFiles { it.extension == "wav" }!!.single().readBytes())
            val moved = File(root, "moved")
            run(listOf("ALLOY", "--patch", recipe.path, "--macros", "MOTION=.3,HOLD=.25", "--out", moved.path))
            val saved = CausticPatch.fromJsonText(moved.listFiles { it.name.endsWith(".patch.json") }!!.single().readText())
            assertEquals(2, saved.model)
            assertEquals(55, saved.midi)
            assertEquals(.43f, saved.velocity)
            assertEquals(.6f, saved.macros.getValue("BODY"))
            assertEquals(.3f, saved.macros.getValue("MOTION"))
            assertEquals(.25f, saved.macros.getValue("HOLD"))
            assertEquals(legacy.toJsonText(), recipe.readText())
            val wav = moved.listFiles { it.extension == "wav" }!!.single()
            assertEquals((CausticInstrument.durationSeconds(saved.voice, saved.macros, saved.model) * 44_100).toInt(),
                WavReader.read(wav).frameCount)
        } finally { root.deleteRecursively() }
    }

    @Test fun `each voice exports an explicit whole sample loop with replayable held controls`() {
        val root = createTempDirectory("caustic-cli-hold").toFile()
        try {
            for (voice in CausticVoice.entries) {
                val output = File(root, voice.name)
                run(listOf(voice.name, "--midi", "60", "--velocity", ".4", "--macros", "HOLD=1,MOTION=.3,LENGTH=0", "--out", output.path))
                val wav = output.listFiles { it.extension == "wav" }!!.single()
                val patch = CausticPatch.fromJsonText(output.listFiles { it.name.endsWith(".patch.json") }!!.single().readText())
                assertEquals(2, patch.model)
                assertEquals(voice, patch.voice)
                assertEquals(.4f, patch.velocity)
                val snip = WavReader.read(wav)
                assertEquals(SmplChunk(60, SmplChunk.Loop(0, snip.frameCount.toLong())), WavReader.readSmplStrict(wav))
                val expected = CausticInstrument.renderLoop(voice, patch.macros, patch.midi, patch.velocity, patch.model)
                val bytes = ByteArrayOutputStream().also {
                    WavWriter.write(it, expected, smpl = SmplChunk(60, SmplChunk.Loop(0, expected.frameCount.toLong())))
                }.toByteArray()
                assertContentEquals(bytes, wav.readBytes(), voice.name)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `instrument request explicitly expands an original recipe and writes playable keygroups`() {
        val root = createTempDirectory("caustic-cli-keys").toFile()
        try {
            val patch = CausticPatch("SAVED VESSEL", CausticVoice.ALLOY,
                mapOf("BODY" to .6f, "LENGTH" to 0f), midi = 60, velocity = .43f, model = 1)
            val recipe = File(root, "saved.json").also { it.writeText(patch.toJsonText()) }
            val output = File(root, "instrument")
            val text = run(listOf("ALLOY", "--patch", recipe.path, "--instrument", "--release", ".4", "--out", output.path))
            val (sidecar, instrument) = InstrumentStore.list(output).single()
            assertEquals(.4f, instrument.release)
            assertTrue(instrument.zones.all { it.loopStartFrame > 0 && it.loopStartFrame < it.frameCount - 1 })
            val xty = output.listFiles { it.extension == "xty" }!!.single()
            val data = File(output, Mpc3TrackWriter.trackDataDirName(xty.nameWithoutExtension))
            val source = CausticPatch.fromJsonText(File(data, CausticPadMaker.PATCH_FILE).readText())
            assertEquals(patch.copy(model = 2), source)
            assertEquals(patch.toJsonText(), recipe.readText())
            assertTrue(File(data, "${xty.nameWithoutExtension}.xpm").isFile)
            for (zone in instrument.zones) {
                val wav = File(sidecar.parentFile, zone.sample)
                val smpl = assertNotNull(WavReader.readSmplStrict(wav))
                assertEquals(zone.rootNote, smpl.rootNote)
                assertEquals(zone.loopStartFrame, smpl.loop?.startFrame)
                assertEquals(zone.frameCount, smpl.loop?.endFrameExclusive)
            }
            assertTrue("authored attack and repeating sustain" in text)
        } finally { root.deleteRecursively() }
    }

    @Test fun `explicit original models and invalid held arguments fail before creating output`() {
        val root = createTempDirectory("caustic-cli-held-refusal").toFile()
        try {
            val destination = File(root, "missing")
            for (flags in listOf(listOf("--model", "1", "--macros", "HOLD=1"),
                listOf("--model", "1", "--macros", "MOTION=.4"), listOf("--model", "1", "--instrument"),
                listOf("--instrument", "--attack", ".1"), listOf("--instrument", "--release", "NaN"),
                listOf("--instrument", "--release", "1.6"), listOf("--instrument", "--all"))) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "CAUSTIC", "ALLOY", *flags.toTypedArray(), "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.toString())
                assertTrue(errors.size() > 0)
                assertFalse(destination.exists(), flags.toString())
            }
        } finally { root.deleteRecursively() }
    }
}
