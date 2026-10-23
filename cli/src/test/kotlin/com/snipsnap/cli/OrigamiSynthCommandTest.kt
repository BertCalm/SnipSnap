package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.SmplChunk
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.shell.OrigamiPadMaker
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiPresets
import com.snipsnap.synth.OrigamiVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrigamiSynthCommandTest {
    private fun exported(patch: OrigamiPatch): ByteArray =
        ByteArrayOutputStream().also { WavWriter.write(it, patch.render()) }.toByteArray()

    @Test
    fun `all three paper voices route every factory preset to its exact dry WAV`() {
        val root = createTempDirectory("origami-cli-factory").toFile()
        try {
            for (voice in OrigamiVoice.entries) {
                val dir = File(root, voice.name)
                val output = ByteArrayOutputStream()
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(
                    arrayOf("synth", "oRiGaMi", voice.name.lowercase(), "--all", "--out", dir.path),
                    PrintStream(output), PrintStream(errors),
                ), errors.toString())
                val presets = OrigamiPresets.forVoice(voice)
                val wavs = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).sortedBy { it.name }
                assertEquals(2, presets.size, "$voice has two factory choices")
                assertEquals(presets.size, wavs.size, "$voice --all must render both choices")
                for ((preset, wav) in presets.zip(wavs)) {
                    assertTrue(wav.name.startsWith("ORIGAMI_${voice.name}_"), wav.name)
                    assertContentEquals(exported(preset), wav.readBytes(),
                        "${preset.name}: CLI must retain the factory model, seed, velocity and controls")
                    val actual = WavReader.read(wav)
                    assertEquals(1, actual.channels)
                    assertEquals(44_100, actual.sampleRate)
                    assertTrue(actual.peak() > 0f, "${preset.name}: routed factory audio is silent")
                }
                assertTrue("2 rendered into" in output.toString(), output.toString())
                assertFalse("lands with" in output.toString(), "ORIGAMI factory auditions are dry")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `note and velocity overrides retain the selected moving texture recipe`() {
        val root = createTempDirectory("origami-cli-overrides").toFile()
        try {
            // The second STILL preset has nonzero motion, a fixed nondefault seed and model 4.
            // Compare encoded audio so losing any of those fields cannot hide behind PCM tolerance.
            val preset = OrigamiPresets.forVoice(OrigamiVoice.STILL)[1]
            val cases = listOf(
                Triple(listOf("--midi", "48"), 48, null),
                Triple(listOf("--velocity", "0"), null, 0f),
                Triple(listOf("--midi", "72", "--velocity", ".35"), 72, .35f),
            )
            for ((index, case) in cases.withIndex()) {
                val dir = File(root, index.toString())
                val output = ByteArrayOutputStream()
                assertEquals(0, SynthCommand.run(
                    listOf("ORIGAMI", "STILL", "--preset", "2") + case.first + listOf("--out", dir.path),
                    PrintStream(output),
                ))
                val wav = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).single()
                assertEquals("ORIGAMI_STILL_01_Quiet_Paper.wav", wav.name)
                val expected = preset.copy(
                    midi = case.second ?: preset.midi,
                    velocity = case.third ?: preset.velocity,
                )
                assertContentEquals(exported(expected), wav.readBytes(),
                    "${case.first}: note overrides retain energy; velocity overrides retain note and geometry")
                if (case.third == 0f) {
                    assertTrue(WavReader.read(wav).samples.all { it == 0f }, "CLI velocity zero must be silent")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unsupported notes nonfinite velocity and drone flags refuse before writing`() {
        val root = createTempDirectory("origami-cli-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            val cases = listOf(
                listOf("--midi", (Origami.MIDI_MIN - 1).toString()),
                listOf("--midi", (Origami.MIDI_MAX + 1).toString()),
                listOf("--midi", "60.5"), listOf("--midi", "NaN"),
                listOf("--velocity", "-.1"), listOf("--velocity", "1.1"),
                listOf("--velocity", "NaN"), listOf("--velocity", "Infinity"),
                listOf("--velocity", "-Infinity"),
                listOf("--attack", ".1"), listOf("--release", ".6"),
                listOf("--drone"),
            )
            for (flags in cases) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    (listOf("synth", "ORIGAMI", "FAN") + flags + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), flags.joinToString())
                assertTrue(flags.first() in errors.toString(), errors.toString())
                assertFalse(dir.exists(), "${flags.joinToString()}: invalid auditions must not create output")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `FAN and STILL instruments retain their recipes in nine held zones and both MPC formats`() {
        val root = createTempDirectory("origami-cli-instruments").toFile()
        try {
            for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) {
                val dir = File(root, voice.name)
                val preset = OrigamiPresets.forVoice(voice)[1]
                val saved = if (voice == OrigamiVoice.STILL) preset.copy(
                    name = "HELD PAPER", midi = 48, velocity = .7f, seed = Long.MIN_VALUE + 37L,
                    macros = preset.macros + mapOf("TUNE" to .1f, "CREASE" to .38f,
                        "TURN" to .62f, "WARP" to .44f, "MOTION" to .45f),
                ) else preset
                val selection = if (voice == OrigamiVoice.STILL) {
                    val file = File(root, "saved-still.patch").apply { writeText(saved.toJsonText()) }
                    listOf("--patch", file.path)
                } else listOf("--preset", "2")
                val release = if (voice == OrigamiVoice.STILL) .8f else .6f
                val releaseFlags = if (voice == OrigamiVoice.STILL) listOf("--release", ".8") else emptyList()
                val output = ByteArrayOutputStream()
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(
                    (listOf("synth", "ORIGAMI", voice.name) + selection + listOf(
                        "--instrument", "--midi", "72", "--velocity", ".4", "--out", dir.path,
                    ) + releaseFlags).toTypedArray(), PrintStream(output), PrintStream(errors),
                ), errors.toString())

                val (sidecar, instrument) = InstrumentStore.list(dir).single()
                assertEquals((48..72 step 3).toList(), instrument.zones.map { it.rootNote })
                assertEquals(48, instrument.zones.first().lowNote)
                assertEquals(72, instrument.zones.last().highNote)
                assertEquals(release, instrument.release)
                assertTrue(instrument.zones.all { it.loopStartFrame in 1 until it.frameCount })
                assertTrue(instrument.zones.all { File(sidecar.parentFile, it.sample).isFile })
                val xty = requireNotNull(dir.listFiles { file -> file.extension == "xty" }).single()
                assertTrue("\"LoopMode\": 1" in Acvs.read(xty).payloadText)
                val data = requireNotNull(dir.listFiles { file -> file.isDirectory && file.name.endsWith("_[TrackData]") }).single()
                assertEquals(9, requireNotNull(data.listFiles { file -> file.extension == "wav" }).size)
                val xpm = requireNotNull(data.listFiles { file -> file.extension == "xpm" }).single()
                assertTrue("<SliceLoop>1</SliceLoop>" in xpm.readText())

                // Saved MIDI 72 and dormant TUNE must not replace the C4 sampled zone.
                val spec = OrigamiPadMaker.Spec(saved.copy(midi = 72, velocity = .4f), releaseSeconds = release)
                assertEquals(spec, OrigamiPadMaker.Spec.fromJsonText(
                    OrigamiPadMaker.recipeFile(dir, instrument.name).readText(),
                ), "instrument replay must retain all six controls, note, velocity, model and 64-bit seed")
                val expected = OrigamiPadMaker.renderZone(spec, 60)
                val center = instrument.zones.single { it.rootNote == 60 }
                val expectedBytes = ByteArrayOutputStream().also {
                    WavWriter.write(it, expected.snip, smpl = SmplChunk(
                        60, SmplChunk.Loop(expected.loopStartFrame, expected.snip.frameCount.toLong()),
                    ))
                }.toByteArray()
                assertContentEquals(expectedBytes, File(sidecar.parentFile, center.sample).readBytes(),
                    "$voice held CLI must render the complete recipe at the zone's MIDI note")
                assertTrue("9 zones, each holds" in output.toString())
                assertTrue("authored attack retained" in output.toString())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `held instruments reject CRANE legacy recipes and incompatible controls before writing`() {
        val root = createTempDirectory("origami-cli-instrument-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            val cases = listOf(
                listOf("--instrument", "--attack", ".1") to "--attack",
                listOf("--instrument", "--all") to "--all",
                listOf("--instrument", "--drone") to "--drone",
            ) + listOf(".09", "1.51", "NaN", "Infinity", "bad").map {
                listOf("--instrument", "--release", it) to "--release"
            }
            for ((flags, rejectedFlag) in cases) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    (listOf("synth", "ORIGAMI", "FAN") + flags + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), flags.joinToString())
                assertTrue(rejectedFlag in errors.toString(), errors.toString())
                assertFalse(dir.exists(), "invalid held options must not create output")
            }
            val unsupported = listOf(OrigamiPresets.forVoice(OrigamiVoice.CRANE).first()) +
                (1..3).map { model -> OrigamiPresets.forVoice(OrigamiVoice.FAN).first().copy(model = model) }
            for (patch in unsupported) {
                val file = File(root, "unsupported.patch").apply { writeText(patch.toJsonText()) }
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf(
                    "synth", "ORIGAMI", patch.voiceName, "--patch", file.path,
                    "--instrument", "--out", dir.path,
                ), PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
                assertTrue("--instrument" in errors.toString(), errors.toString())
                assertTrue("model-4 FAN" in errors.toString(), errors.toString())
                assertFalse(dir.exists(), "unsupported voices/models must not create output")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `saved legacy patch remains a finite audition without the instrument flag`() {
        val root = createTempDirectory("origami-cli-saved-finite").toFile()
        try {
            val patch = OrigamiPresets.forVoice(OrigamiVoice.FAN)[1].copy(
                name = "LEGACY PAPER", model = 2, midi = 48, velocity = .45f, seed = Long.MIN_VALUE + 17L,
            )
            val saved = File(root, "legacy.patch").apply { writeText(patch.toJsonText()) }
            val dir = File(root, "audio")
            assertEquals(0, SynthCommand.run(listOf(
                "ORIGAMI", "FAN", "--patch", saved.path, "--out", dir.path,
            ), PrintStream(ByteArrayOutputStream())))
            val wav = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).single()
            assertContentEquals(exported(patch), wav.readBytes(), "held support must not upgrade finite saved recipes")
            assertTrue(InstrumentStore.list(dir).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
