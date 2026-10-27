package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.RegistersPresets
import com.snipsnap.synth.RegistersVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegistersCommandTest {
    @Test
    fun `CLI note and velocity overrides retain each voice factory model`() {
        val dir = createTempDirectory("registers-cli").toFile()
        try {
            for (voice in RegistersVoice.entries) {
                assertEquals(0, Cli.run(arrayOf("synth", "rEgIsTeRs", voice.name.lowercase(),
                    "--preset", "1", "--midi", "69", "--velocity", ".4", "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
                val wav = dir.listFiles { f -> f.extension == "wav" && f.name.contains(voice.name) }!!.single()
                val patch = RegistersPresets.forVoice(voice).first().copy(midi = 69, velocity = .4f)
                assertEquals(if (voice == RegistersVoice.TRANSISTOR) 6 else 3, patch.model)
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, patch.render()) }.toByteArray()
                assertTrue(expected.contentEquals(wav.readBytes()), "explicit note, energy and model survive CLI dispatch")
                assertEquals(44_100, WavReader.read(wav).sampleRate)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `CLI instrument writes nine held zones and both MPC generations`() {
        val dir = createTempDirectory("registers-instrument-cli").toFile()
        try {
            for (voice in listOf(RegistersVoice.MUSETTE, RegistersVoice.TRANSISTOR)) {
                assertEquals(if (voice == RegistersVoice.TRANSISTOR) 6 else 3,
                    RegistersPresets.forVoice(voice).first().model)
                val directory = File(dir, voice.name).apply { mkdirs() }
                val output = ByteArrayOutputStream()
                assertEquals(0, Cli.run(arrayOf("synth", "registers", voice.name.lowercase(), "--instrument", "--release", ".8", "--out", directory.path),
                    PrintStream(output), PrintStream(ByteArrayOutputStream())))
                val (sidecar, instrument) = InstrumentStore.list(directory).single()
                assertEquals((48..72 step 3).toList(), instrument.zones.map { it.rootNote })
                assertEquals(.8f, instrument.release)
                assertTrue(instrument.zones.all { it.loopStartFrame in 1 until it.frameCount })
                assertTrue(instrument.zones.all { File(sidecar.parentFile, it.sample).isFile })
                val xty = directory.listFiles { f -> f.extension == "xty" }!!.single()
                assertTrue("\"LoopMode\": 1" in Acvs.read(xty).payloadText)
                val data = directory.listFiles { f -> f.isDirectory && f.name.endsWith("_[TrackData]") }!!.single()
                assertEquals(9, data.listFiles { f -> f.extension == "wav" }!!.size)
                assertTrue("<SliceLoop>1</SliceLoop>" in data.listFiles { f -> f.extension == "xpm" }!!.single().readText())
                assertTrue("9 zones, each holds" in output.toString())
                assertTrue("authored attack retained" in output.toString())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `CLI refuses invalid registers note energy and instrument options before files are written`() {
        val dir = createTempDirectory("registers-invalid-cli").toFile()
        try {
            for (flags in listOf(
                arrayOf("--midi", "23"), arrayOf("--midi", "97"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.1"),
                arrayOf("--instrument", "--attack", ".5"),
                arrayOf("--instrument", "--release", "NaN"),
                arrayOf("--instrument", "--release", "2"),
                arrayOf("--instrument", "--all"), arrayOf("--drone"),
            )) {
                val errors = ByteArrayOutputStream()
                assertTrue(Cli.run(arrayOf("synth", "registers", "pipe", *flags, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)) != 0, flags.joinToString())
                assertTrue(errors.size() > 0)
                assertTrue(dir.listFiles()!!.isEmpty(), "invalid options write nothing: ${flags.joinToString()}")
            }
        } finally { dir.deleteRecursively() }
    }
}
