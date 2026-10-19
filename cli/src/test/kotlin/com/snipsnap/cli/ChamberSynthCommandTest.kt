package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Chamber
import com.snipsnap.synth.ChamberConfig
import com.snipsnap.synth.ChamberDimensions
import com.snipsnap.synth.ChamberPatch
import com.snipsnap.synth.ChamberPlacementMode
import com.snipsnap.synth.ChamberPresets
import com.snipsnap.synth.ChamberVoice
import com.snipsnap.synth.Presets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChamberSynthCommandTest {
    @Test
    fun `first listen CLI finds provisional recipes and applies native performance overrides`() {
        val directory = createTempDirectory("chamber-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(listOf("CHAMBER", "HYBRID", "--all", "--midi", "63",
                "--velocity", ".35", "--seed", Long.MIN_VALUE.toString(), "--out", directory.path), PrintStream(output)))
            val wavs = requireNotNull(directory.listFiles { it.extension == "wav" }).sortedBy { it.name }
            val presets = ChamberPresets.forVoice(ChamberVoice.HYBRID)
            assertEquals(presets.size, wavs.size)
            val expected = presets.first().copy(midi = 63, velocity = .35f, seed = Long.MIN_VALUE)
            val encoded = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            assertContentEquals(encoded, wavs.first().readBytes())
            assertTrue(Presets.forVoice(ChamberPatch.ENGINE, "HYBRID").isEmpty())
            assertTrue("${presets.size} rendered into" in output.toString())
            assertFalse("lands with" in output.toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `saved room replay and note overrides retain authored geometry and exact Long seed`() {
        val directory = createTempDirectory("chamber-cli-patch").toFile()
        try {
            val patch = ChamberPatch("Authored room", ChamberVoice.AQUARIUM, Chamber.defaults(ChamberVoice.AQUARIUM),
                midi = 57, velocity = .42f, seed = 9_007_199_254_740_993L,
                configuration = ChamberConfig.aquarium().copy(dimensions = ChamberDimensions(9f, 8f, 6f),
                    splitHeight = .67f, placementMode = ChamberPlacementMode.EXPLICIT))
            val source = File(directory, "room.json").also { it.writeText(patch.toJsonText()) }
            val destination = File(directory, "wavs")
            assertEquals(0, SynthCommand.run(listOf("CHAMBER", "AQUARIUM", "--patch", source.path,
                "--midi", "67", "--velocity", ".6", "--seed", Long.MAX_VALUE.toString(),
                "--out", destination.path), PrintStream(ByteArrayOutputStream())))
            val expected = ByteArrayOutputStream().also {
                WavWriter.write(it, patch.copy(midi = 67, velocity = .6f, seed = Long.MAX_VALUE).render())
            }.toByteArray()
            assertContentEquals(expected, requireNotNull(destination.listFiles { it.extension == "wav" }).single().readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid performance and deferred held routes fail before creating output`() {
        val directory = createTempDirectory("chamber-cli-invalid").toFile()
        val destination = File(directory, "not-created")
        try {
            for (flags in listOf(
                arrayOf("--midi", "35"), arrayOf("--midi", "85"), arrayOf("--midi", "60.5"),
                arrayOf("--velocity", "NaN"), arrayOf("--velocity", "1.01"), arrayOf("--velocity", "-.01"),
                arrayOf("--seed", "1.5"), arrayOf("--seed", "9223372036854775808"),
                arrayOf("--instrument"), arrayOf("--drone"), arrayOf("--scramble", "3"),
                arrayOf("--model", "1"), arrayOf("--seconds", "2"), arrayOf("--wet"), arrayOf("--dry"),
                arrayOf("--patch", "missing.json"),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf("synth", "CHAMBER", "HYBRID", *flags, "--out", destination.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), flags.joinToString())
                assertTrue(errors.size() > 0)
                assertFalse(destination.exists(), "invalid input must not create an audition directory")
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
