package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Vent
import com.snipsnap.synth.VentKitPresets
import com.snipsnap.synth.VentPresets
import com.snipsnap.synth.VentVoice
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VentVoiceCommandTest {
    @Test
    fun `legacy VENT voices keep their original CLI-only recipes`() {
        val dir = createTempDirectory("vent-legacy-cli").toFile()
        try {
            for (voice in listOf(VentVoice.ROUND, VentVoice.WIRE, VentVoice.TEAR)) {
                val destination = dir.resolve(voice.name)
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(arrayOf("synth", "VENT", voice.name, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), errors.toString())
                val wav = destination.listFiles { file -> file.extension == "wav" }!!.single()
                val preset = VentPresets.forVoice(voice).single()
                assertEquals(Vent.MODEL, preset.model)
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, preset.render()) }.toByteArray()
                assertContentEquals(expected, wav.readBytes(), "$voice's historical starting point changed")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clean VENT CLI voices export the chosen note velocity and negative trajectory seed`() {
        val dir = createTempDirectory("vent-clean-cli").toFile()
        try {
            for (voice in listOf(VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME)) {
                val destination = dir.resolve(voice.name)
                val output = ByteArrayOutputStream()
                val errors = ByteArrayOutputStream()
                assertEquals(0,
                    Cli.run(
                        arrayOf("synth", "vEnT", voice.name.lowercase(), "--preset", "1", "--midi", "65",
                            "--velocity", ".4", "--seed", Int.MIN_VALUE.toString(), "--out", destination.path),
                        PrintStream(output), PrintStream(errors),
                    ), errors.toString())
                val wav = destination.listFiles { file -> file.extension == "wav" }!!.single()
                val preset = VentKitPresets.forVoice(voice).first().copy(midi = 65, velocity = .4f, seed = Int.MIN_VALUE)
                assertEquals(Vent.CLEAN_MODEL, preset.model)
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, preset.render()) }.toByteArray()
                assertContentEquals(expected, wav.readBytes(), "$voice CLI overrides must reach the deterministic render")
                assertTrue("1 rendered into" in output.toString())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
