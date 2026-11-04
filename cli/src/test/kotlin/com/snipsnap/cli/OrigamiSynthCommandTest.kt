package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.SmplChunk
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.shell.OrigamiPadMaker
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiExpression
import com.snipsnap.synth.OrigamiGesture
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
                assertEquals(4, presets.size, "$voice has two reference and two performance choices")
                assertEquals(presets.size, wavs.size, "$voice --all must render every choice")
                for ((preset, wav) in presets.zip(wavs)) {
                    assertTrue(wav.name.startsWith("ORIGAMI_${voice.name}_"), wav.name)
                    assertContentEquals(exported(preset), wav.readBytes(),
                        "${preset.name}: CLI must retain the factory model, seed, velocity, gesture, expression and controls")
                    val actual = WavReader.read(wav)
                    assertEquals(1, actual.channels)
                    assertEquals(44_100, actual.sampleRate)
                    assertTrue(actual.peak() > 0f, "${preset.name}: routed factory audio is silent")
                }
                assertTrue("${presets.size} rendered into" in output.toString(), output.toString())
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
    fun `fold gestures and six control overrides retain complete finite recipes and replay exactly`() {
        val root = createTempDirectory("origami-cli-gestures").toFile()
        try {
            for (voice in OrigamiVoice.entries) {
                val source = OrigamiPresets.forVoice(voice)[1].copy(
                    name = "SAVED ${voice.name}", midi = 57, velocity = .73f, seed = Long.MIN_VALUE + 93L,
                    macros = mapOf("TUNE" to .1f, "CREASE" to .37f, "FOLD" to .64f,
                        "TURN" to .62f, "WARP" to .44f, "MOTION" to .45f),
                )
                val gesture = if (voice == OrigamiVoice.FAN) OrigamiGesture.UNFOLD else OrigamiGesture.BLOOM
                val overrides = if (voice == OrigamiVoice.CRANE) mapOf(
                    "TUNE" to .75f, "CREASE" to .29f, "FOLD" to .71f,
                    "TURN" to .34f, "WARP" to .58f, "MOTION" to .28f,
                ) else mapOf("FOLD" to .71f, "MOTION" to .28f)
                val saved = File(root, "${voice.name}.patch").apply { writeText(source.toJsonText()) }
                val dir = File(root, voice.name)
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(arrayOf(
                    "synth", "ORIGAMI", voice.name, "--patch", saved.path,
                    "--gesture", if (gesture == OrigamiGesture.BLOOM) "bLoOm" else "uNfOlD",
                    "--macros", overrides.entries.joinToString(",") { "${it.key.lowercase()}=${it.value}" },
                    "--velocity", ".37", "--out", dir.path,
                ), PrintStream(ByteArrayOutputStream()), PrintStream(errors)), errors.toString())
                val expected = source.copy(
                    gesture = gesture, macros = source.macros + overrides, velocity = .37f,
                    midi = overrides["TUNE"]?.let { Origami.midiFor(voice, it) } ?: source.midi,
                )
                val wav = requireNotNull(dir.listFiles { it.extension == "wav" }).single()
                assertContentEquals(exported(expected), wav.readBytes(),
                    "$voice gesture and control edits must retain model, authored pitch, geometry and all seed bits")

                if (voice == OrigamiVoice.FAN) {
                    val replay = File(root, "gesture.patch").apply { writeText(expected.toJsonText()) }
                    for (steady in listOf(false, true)) {
                        val replayDir = File(root, "replay-$steady")
                        val flags = if (steady) listOf("--gesture", "sTeAdY") else emptyList()
                        assertEquals(0, SynthCommand.run(
                            listOf("ORIGAMI", "FAN", "--patch", replay.path, "--out", replayDir.path) + flags,
                            PrintStream(ByteArrayOutputStream()),
                        ))
                        val replayWav = requireNotNull(replayDir.listFiles { it.extension == "wav" }).single()
                        val replayPatch = if (steady) expected.copy(gesture = OrigamiGesture.STEADY) else expected
                        assertContentEquals(exported(replayPatch), replayWav.readBytes(),
                            "saved gestures must replay without flags, and explicit STEADY must disable them")
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `fold expression preserves every gesture and complete saved recipe through finite replay`() {
        val root = createTempDirectory("origami-cli-expression").toFile()
        try {
            for (gesture in OrigamiGesture.entries) {
                val source = OrigamiPresets.forVoice(OrigamiVoice.FAN)[1].copy(
                    name = "SAVED ${gesture.name}", gesture = gesture, midi = 57,
                    velocity = .73f, seed = Long.MIN_VALUE + 93L,
                    macros = mapOf("TUNE" to .1f, "CREASE" to .37f, "FOLD" to .64f,
                        "TURN" to .62f, "WARP" to .44f, "MOTION" to .45f),
                )
                val saved = File(root, "${gesture.name}.patch").apply { writeText(source.toJsonText()) }
                val dir = File(root, gesture.name)
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(arrayOf(
                    "synth", "ORIGAMI", "FAN", "--patch", saved.path,
                    "--expression", "fOlD", "--velocity", ".37",
                    "--macros", "FOLD=.71,MOTION=.28", "--out", dir.path,
                ), PrintStream(ByteArrayOutputStream()), PrintStream(errors)), errors.toString())
                val expected = source.copy(
                    expression = OrigamiExpression.FOLD, velocity = .37f,
                    macros = source.macros + mapOf("FOLD" to .71f, "MOTION" to .28f),
                )
                assertEquals(source, OrigamiPatch.fromJsonText(saved.readText()), "CLI must not rewrite its input recipe")
                val wav = requireNotNull(dir.listFiles { it.extension == "wav" }).single()
                assertContentEquals(exported(expected), wav.readBytes(),
                    "$gesture expression must retain its gesture, note, model, seed and six authored controls")

                val replay = File(root, "${gesture.name}-expression.patch").apply { writeText(expected.toJsonText()) }
                assertEquals(expected, OrigamiPatch.fromJsonText(replay.readText()))
                val replayDir = File(root, "${gesture.name}-replay")
                assertEquals(0, SynthCommand.run(listOf(
                    "ORIGAMI", "FAN", "--patch", replay.path, "--out", replayDir.path,
                ), PrintStream(ByteArrayOutputStream())))
                val replayWav = requireNotNull(replayDir.listFiles { it.extension == "wav" }).single()
                assertContentEquals(wav.readBytes(), replayWav.readBytes(),
                    "saved $gesture expression must replay without an --expression flag")

                if (gesture == OrigamiGesture.STEADY) {
                    val levelDir = File(root, "explicit-level")
                    assertEquals(0, SynthCommand.run(listOf(
                        "ORIGAMI", "FAN", "--patch", replay.path, "--expression", "lEvEl", "--out", levelDir.path,
                    ), PrintStream(ByteArrayOutputStream())))
                    val levelWav = requireNotNull(levelDir.listFiles { it.extension == "wav" }).single()
                    assertContentEquals(exported(expected.copy(expression = OrigamiExpression.LEVEL)), levelWav.readBytes(),
                        "explicit LEVEL must disable saved fold expression without replacing its recipe")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid foreign and legacy expression requests refuse before writing`() {
        val root = createTempDirectory("origami-cli-expression-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            for (value in listOf("BRIGHT", "NaN", "")) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf(
                    "synth", "ORIGAMI", "FAN", "--expression", value, "--out", dir.path,
                ), PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
                assertTrue("--expression" in errors.toString(), errors.toString())
                assertTrue("LEVEL|FOLD" in errors.toString(), errors.toString())
                assertFalse(dir.exists(), "invalid expression must not create output")
            }
            for (value in listOf("LEVEL", "FOLD")) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf(
                    "synth", "OPENSKY", "HALO", "--expression", value, "--out", dir.path,
                ), PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
                assertTrue("--expression" in errors.toString(), errors.toString())
                assertTrue("ORIGAMI" in errors.toString(), errors.toString())
                assertFalse(dir.exists(), "foreign expression must not create output")
            }
            for (model in 1..3) {
                val source = OrigamiPresets.forVoice(OrigamiVoice.FAN).first().copy(model = model)
                val saved = File(root, "legacy-$model.patch").apply { writeText(source.toJsonText()) }
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(arrayOf(
                    "synth", "ORIGAMI", "FAN", "--patch", saved.path,
                    "--expression", "FOLD", "--out", dir.path,
                ), PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
                assertTrue("--expression" in errors.toString(), errors.toString())
                assertTrue("model 4" in errors.toString(), errors.toString())
                assertFalse(dir.exists(), "expression must not silently upgrade legacy model $model")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid gestures and control edits refuse before creating output`() {
        val root = createTempDirectory("origami-cli-gesture-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            val cases = listOf(
                listOf("--gesture", "HOLD") to "--gesture",
                listOf("--macros", "HOLD=.5") to "--macros",
                listOf("--macros", "FOLD=.2,fold=.8") to "--macros",
                listOf("--macros", "MOTION=.2", "--macros", "FOLD=.8") to "--macros",
                listOf("--macros", "FOLD") to "--macros",
                listOf("--macros", "FOLD=.2=bad") to "--macros",
                listOf("--macros", "FOLD=.2,") to "--macros",
                listOf("--macros", "TUNE=.3", "--midi", "60") to "--macros",
                listOf("--gesture", "BLOOM", "--motion", ".5") to "--motion",
            ) + listOf("-.1", "1.1", "NaN", "Infinity", "-Infinity").map {
                listOf("--gesture", "UNFOLD", "--macros", "MOTION=$it") to "--macros"
            }
            for ((flags, flag) in cases) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    (listOf("synth", "ORIGAMI", "FAN") + flags + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors),
                ), flags.joinToString())
                assertTrue(flag in errors.toString(), errors.toString())
                assertFalse(dir.exists(), "invalid gesture/control options must not create output")
            }
            val foreignErrors = ByteArrayOutputStream()
            assertEquals(2, Cli.run(arrayOf(
                "synth", "OPENSKY", "HALO", "--gesture", "STEADY", "--out", dir.path,
            ), PrintStream(ByteArrayOutputStream()), PrintStream(foreignErrors)))
            assertTrue("--gesture" in foreignErrors.toString(), foreignErrors.toString())
            assertTrue("ORIGAMI" in foreignErrors.toString(), foreignErrors.toString())
            assertFalse(dir.exists())

            for (model in 1..3) {
                val legacy = OrigamiPresets.forVoice(OrigamiVoice.FAN).first().copy(model = model)
                val saved = File(root, "legacy-$model.patch").apply { writeText(legacy.toJsonText()) }
                for (gesture in listOf("BLOOM", "UNFOLD")) {
                    val errors = ByteArrayOutputStream()
                    assertEquals(2, Cli.run(arrayOf(
                        "synth", "ORIGAMI", "FAN", "--patch", saved.path,
                        "--gesture", gesture, "--out", dir.path,
                    ), PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
                    assertTrue("--gesture" in errors.toString(), errors.toString())
                    assertTrue("model 4" in errors.toString(), errors.toString())
                    assertFalse(dir.exists(), "new gestures must not upgrade legacy model $model")
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
                val gestureFlags = if (voice == OrigamiVoice.FAN) listOf(
                    "--gesture", "uNfOlD", "--expression", "fOlD", "--macros", "fOlD=.73,mOtIoN=.4",
                ) else emptyList()
                val output = ByteArrayOutputStream()
                val errors = ByteArrayOutputStream()
                assertEquals(0, Cli.run(
                    (listOf("synth", "ORIGAMI", voice.name) + selection + listOf(
                        "--instrument", "--midi", "72", "--velocity", ".4", "--out", dir.path,
                    ) + releaseFlags + gestureFlags).toTypedArray(), PrintStream(output), PrintStream(errors),
                ), errors.toString())

                val (sidecar, instrument) = InstrumentStore.list(dir).single()
                assertEquals((48..72 step 3).toList(), instrument.zones.map { it.rootNote })
                assertEquals(0, instrument.zones.first().lowNote)
                assertEquals(127, instrument.zones.last().highNote)
                assertEquals(release, instrument.release)
                val layerCount = if (voice == OrigamiVoice.FAN) 3 else 1
                assertEquals(9 * layerCount, instrument.sampleFiles.size)
                for (zone in instrument.zones) {
                    val layers = zone.playableLayers
                    assertEquals(layerCount, layers.size)
                    assertEquals(if (layerCount > 1) 1 else 0, layers.first().velStart)
                    assertEquals(127, layers.last().velEnd)
                    assertTrue(layers.zipWithNext().all { (a, b) -> a.velEnd + 1 == b.velStart },
                        "velocity layers must cover positive MIDI velocities without gaps or overlap")
                    for (layer in layers) {
                        val file = File(sidecar.parentFile, layer.sample)
                        val smpl = requireNotNull(WavReader.readSmpl(file))
                        assertEquals(zone.rootNote, smpl.rootNote)
                        assertEquals(layer.loopStartFrame, smpl.loop?.startFrame)
                        assertEquals(layer.frameCount, smpl.loop?.endFrameExclusive)
                    }
                }
                assertTrue(instrument.zones.all { it.loopStartFrame in 1 until it.frameCount })
                assertTrue(instrument.zones.all { File(sidecar.parentFile, it.sample).isFile })
                val xty = requireNotNull(dir.listFiles { file -> file.extension == "xty" }).single()
                assertTrue("\"LoopMode\": 1" in Acvs.read(xty).payloadText)
                val data = requireNotNull(dir.listFiles { file -> file.isDirectory && file.name.endsWith("_[TrackData]") }).single()
                assertEquals(9 * layerCount, requireNotNull(data.listFiles { file -> file.extension == "wav" }).size)
                val xpm = requireNotNull(data.listFiles { file -> file.extension == "xpm" }).single()
                assertTrue("<SliceLoop>1</SliceLoop>" in xpm.readText())

                // Saved MIDI 72 and dormant TUNE must not replace the C4 sampled zone.
                val edited = if (voice == OrigamiVoice.FAN) saved.copy(
                    gesture = OrigamiGesture.UNFOLD, expression = OrigamiExpression.FOLD,
                    macros = saved.macros + mapOf("FOLD" to .73f, "MOTION" to .4f),
                ) else saved
                val spec = OrigamiPadMaker.Spec(edited.copy(midi = 72, velocity = .4f), releaseSeconds = release)
                assertEquals(spec, OrigamiPadMaker.Spec.fromJsonText(
                    OrigamiPadMaker.recipeFile(dir, instrument.name).readText(),
                ), "instrument replay must retain expression, gesture, six controls, note, velocity, model and 64-bit seed")
                val center = instrument.zones.single { it.rootNote == 60 }
                val expectedNotes = if (layerCount == 1) listOf(OrigamiPadMaker.renderZone(spec, 60))
                    else OrigamiPadMaker.renderVelocityZone(spec, 60)
                assertEquals(layerCount, expectedNotes.size)
                for ((index, expected) in expectedNotes.withIndex()) {
                    val expectedBytes = ByteArrayOutputStream().also {
                        WavWriter.write(it, expected.snip, smpl = SmplChunk(
                            60, SmplChunk.Loop(expected.loopStartFrame, expected.snip.frameCount.toLong()),
                        ))
                    }.toByteArray()
                    assertContentEquals(expectedBytes, File(sidecar.parentFile, center.playableLayers[index].sample).readBytes(),
                        "$voice held CLI layer $index must render the complete recipe at the zone's MIDI note")
                }
                assertTrue("9 zones, each holds" in output.toString())
                assertTrue("authored attack retained" in output.toString())
                if (layerCount > 1) {
                    assertTrue("layers 25..27/27" in output.toString())
                    assertTrue("expression layers: 3 per zone, 27 samples" in output.toString())
                }
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
            assertFalse("\"gesture\"" in patch.toJsonText(), "STEADY must keep legacy JSON unchanged")
            assertFalse("\"expression\"" in patch.toJsonText(), "LEVEL must keep legacy JSON unchanged")
            for (steady in listOf(false, true)) {
                val dir = File(root, "audio-$steady")
                val flags = if (steady) listOf("--gesture", "STEADY", "--expression", "LEVEL") else emptyList()
                assertEquals(0, SynthCommand.run(listOf(
                    "ORIGAMI", "FAN", "--patch", saved.path, "--out", dir.path,
                ) + flags, PrintStream(ByteArrayOutputStream())))
                val wav = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).single()
                assertContentEquals(exported(patch), wav.readBytes(), "held and gesture support must not upgrade finite saved recipes")
                assertTrue(InstrumentStore.list(dir).isEmpty())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
