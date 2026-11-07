package com.snipsnap.cli

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.ContourPadMaker
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourPresets
import com.snipsnap.synth.ContourTable
import com.snipsnap.synth.ContourVoice
import com.snipsnap.synth.OpenSkyPresets
import com.snipsnap.synth.OpenSkyVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContourSynthCommandTest {
    @Test
    fun `downloaded Contour recipes replay exactly and retain their structure through overrides`() {
        val root = kotlin.io.path.createTempDirectory("contour-cli-patch").toFile()
        try {
            val preset = ContourPresets.forVoice(ContourVoice.KEY).first()
            val patch = preset.copy(
                name = "MY SOURCE",
                macros = preset.macros + mapOf("BODY" to .3f, "EDGE" to .65f, "RESO" to .6f),
                config = preset.config.copy(
                    bodyTable = ContourTable.FORMANT, edgeTable = ContourTable.ODD_EVEN,
                    edgeRatio = 1.5f, bodyLevel = .62f, edgeLevel = .48f,
                    ampAttack = .12f, ampDecay = .22f, ampSustain = .35f,
                    ampRelease = .4f, sustainSeconds = .2f,
                ),
                velocity = .6f,
            )
            val recipe = File(root, "patch.json").also { it.writeText(patch.toJsonText()) }
            val exact = File(root, "exact")
            assertEquals(0, SynthCommand.run(
                listOf("CONTOUR", "KEY", "--patch", recipe.path, "--out", exact.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            assertRenderedPatch(patch, exact)

            val edited = File(root, "edited")
            assertEquals(0, SynthCommand.run(
                listOf("CONTOUR", "KEY", "--patch", recipe.path, "--midi", "72", "--velocity", ".3",
                    "--macros", " body = .2, EDGE=.8,ATTACK=.25,DECAY=.7,RATE=.75,ENV=.65 ", "--out", edited.path),
                PrintStream(ByteArrayOutputStream()),
            ))
            assertRenderedPatch(patch.copy(
                macros = patch.macros + mapOf("TUNE" to .75f, "BODY" to .2f, "EDGE" to .8f,
                    "ATTACK" to .25f, "DECAY" to .7f, "RATE" to .75f, "ENV" to .65f),
                velocity = .3f,
            ), edited)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Contour macro overrides apply to selected and all complete factory recipes`() {
        val root = kotlin.io.path.createTempDirectory("contour-cli-macros").toFile()
        try {
            val presets = ContourPresets.forVoice(ContourVoice.KEY)
            for ((label, selection, expected) in listOf(
                Triple("one", listOf("--preset", "2"), listOf(presets[1])),
                Triple("all", listOf("--all"), presets),
            )) {
                val dir = File(root, label)
                assertEquals(0, SynthCommand.run(
                    listOf("CONTOUR", "KEY") + selection + listOf("--macros=TUNE=.75,BODY=.2,EDGE=.8,RESO=.65", "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()),
                ))
                val wavs = requireNotNull(dir.listFiles { it.extension == "wav" }).sortedBy { it.name }
                assertEquals(expected.size, wavs.size)
                for ((patch, wav) in expected.zip(wavs)) {
                    val edited = patch.copy(macros = patch.macros +
                        mapOf("TUNE" to .75f, "BODY" to .2f, "EDGE" to .8f, "RESO" to .65f))
                    val bytes = ByteArrayOutputStream().also { WavWriter.write(it, edited.render()) }
                    assertContentEquals(bytes.toByteArray(), wav.readBytes(), "$label: ${patch.name}")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Contour auditions retain structural presets and excitation velocity at the requested note`() {
        val dir = kotlin.io.path.createTempDirectory("contour-cli").toFile()
        try {
            val output = ByteArrayOutputStream()
            val presets = ContourPresets.forVoice(ContourVoice.KEY)
            assertEquals(0, SynthCommand.run(
                listOf("CONTOUR", "KEY", "--all", "--midi", "72", "--velocity", ".3", "--out", dir.path),
                PrintStream(output),
            ))
            val wavs = requireNotNull(dir.listFiles { file -> file.extension == "wav" }).sortedBy { it.name }
            assertEquals(presets.size, wavs.size)
            for ((preset, wav) in presets.zip(wavs)) {
                // C5 is one octave above KEY's C4 root: the centered four-octave range maps it to .75.
                val expected = preset.copy(macros = preset.macros + ("TUNE" to .75f), velocity = .3f).render()
                val bytes = ByteArrayOutputStream().also { WavWriter.write(it, expected) }
                assertContentEquals(bytes.toByteArray(), wav.readBytes(), "${preset.name}: table pair and envelope survive CLI overrides")
                val actual = WavReader.read(wav)
                assertEquals(1, actual.channels)
                assertEquals(44_100, actual.sampleRate)
                assertTrue(actual.samples.all { it.isFinite() })
                assertTrue(actual.samples.any { kotlin.math.abs(it) > 1e-4f })
            }
            assertFalse("lands with" in output.toString(), "CONTOUR auditions are dry")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Contour note range and finite velocity are checked before any output is written`() {
        val root = kotlin.io.path.createTempDirectory("contour-cli-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            val low = ContourEngine.rootMidi(ContourVoice.BASS) - ContourEngine.TUNE_SEMITONES
            val high = ContourEngine.rootMidi(ContourVoice.BASS) + ContourEngine.TUNE_SEMITONES
            for ((flag, value) in listOf(
                "--midi" to (low - 1).toString(), "--midi" to (high + 1).toString(),
                "--midi" to "36.5", "--velocity" to "NaN", "--velocity" to "Infinity", "--velocity" to "1.01",
            )) {
                val err = ByteArrayOutputStream()
                assertEquals(2, Cli.run(
                    arrayOf("synth", "CONTOUR", "BASS", flag, value, "--out", dir.path),
                    PrintStream(ByteArrayOutputStream()), PrintStream(err),
                ))
                assertTrue(flag in err.toString(), err.toString())
            }
            assertFalse(dir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Contour macro errors and ambiguous pitch are rejected before creating output`() {
        val root = kotlin.io.path.createTempDirectory("contour-cli-macro-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            for (flags in listOf(
                listOf("--macros", ""), listOf("--macros", "BODY"), listOf("--macros", "BODY=.2=3"),
                listOf("--macros", "BODY=.2,"), listOf("--macros", ",BODY=.2"),
                listOf("--macros", "BODY=.2,body=.8"), listOf("--macros", "NOT_A_CONTROL=.5"),
                listOf("--macros", "BODY=NaN"), listOf("--macros", "EDGE=Infinity"),
                listOf("--macros", "ATTACK=-.01"), listOf("--macros", "DECAY=1.01"),
                listOf("--macros", "RATE="), listOf("--macros", "RESO=loud"),
                listOf("--macros", "ENV=.2", "--macros=BODY=.8"),
                listOf("--macros", "TUNE=.75", "--midi", "72"),
            )) {
                val errors = ByteArrayOutputStream()
                val args = listOf("synth", "CONTOUR", "KEY") + flags + listOf("--out", dir.path)
                assertEquals(2, Cli.run(args.toTypedArray(), PrintStream(ByteArrayOutputStream()), PrintStream(errors)), "$flags: $errors")
                assertTrue("--macros" in errors.toString(), "$flags: $errors")
                assertFalse("Exception" in errors.toString(), "$flags: $errors")
            }
            val errors = ByteArrayOutputStream()
            assertEquals(2, Cli.run(arrayOf("synth", "OPENSKY", "HALO", "--macros", "BODY=.2", "--out", dir.path),
                PrintStream(ByteArrayOutputStream()), PrintStream(errors)))
            assertTrue("--macros" in errors.toString())
            assertFalse(dir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Contour patch identity selection and malformed files are rejected before creating output`() {
        val root = kotlin.io.path.createTempDirectory("contour-cli-patch-refusals").toFile()
        val dir = File(root, "not-created")
        try {
            val recipe = File(root, "contour.json").also {
                it.writeText(ContourPresets.forVoice(ContourVoice.KEY).first().toJsonText())
            }
            val skyRecipe = File(root, "opensky.json").also {
                it.writeText(OpenSkyPresets.forVoice(OpenSkyVoice.HALO).first().toJsonText())
            }
            val malformed = File(root, "broken.json").also { it.writeText("{broken") }
            for (args in listOf(
                listOf("CONTOUR", "BASS", "--patch", recipe.path),
                listOf("CONTOUR", "KEY", "--patch", recipe.path, "--all"),
                listOf("CONTOUR", "KEY", "--patch", recipe.path, "--preset", "1"),
                listOf("CONTOUR", "KEY", "--patch", skyRecipe.path),
                listOf("OPENSKY", "HALO", "--patch", recipe.path),
                listOf("CONTOUR", "KEY", "--patch", malformed.path),
                listOf("CONTOUR", "KEY", "--patch", File(root, "missing.json").path),
            )) {
                val errors = ByteArrayOutputStream()
                assertEquals(2, Cli.run((listOf("synth") + args + listOf("--out", dir.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()), PrintStream(errors)), "$args: $errors")
                assertTrue("--patch" in errors.toString(), "$args: $errors")
                assertFalse("Exception" in errors.toString(), "$args: $errors")
            }
            assertFalse(dir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a complete Contour recipe becomes a seventeen zone instrument with macro and energy edits`() {
        val root = kotlin.io.path.createTempDirectory("contour-cli-instrument").toFile()
        try {
            val source = ContourPresets.forVoice(ContourVoice.KEY).first().copy(
                name = "MY CONTOUR",
                macros = ContourEngine.defaults(ContourVoice.KEY) + mapOf("TUNE" to .75f, "HOLD" to 0f),
                config = ContourPresets.forVoice(ContourVoice.KEY).first().config.copy(
                    bodyTable = ContourTable.FORMANT, edgeTable = ContourTable.ODD_EVEN,
                    edgeRatio = 1.5f, bodyLevel = .62f, edgeLevel = .48f, loopSeconds = 1f,
                ),
                velocity = .7f,
            )
            val recipe = File(root, "recipe.json").also { it.writeText(source.toJsonText()) }
            val directory = File(root, "instrument")
            val output = ByteArrayOutputStream()
            assertEquals(0, SynthCommand.run(listOf("CONTOUR", "KEY", "--patch", recipe.path,
                "--instrument", "--release", ".8", "--velocity", ".35",
                "--macros", "BODY=.2,EDGE=.8,RATE=.65", "--out", directory.path), PrintStream(output)))
            val (sidecar, instrument) = InstrumentStore.list(directory).single()
            val midis = (36..84 step 3).toList()
            assertEquals(midis, instrument.zones.map { it.rootNote }, "keyboard zones replace saved TUNE")
            assertEquals(.8f, instrument.release)
            assertEquals(0, instrument.zones.first().lowNote)
            assertEquals(127, instrument.zones.last().highNote)
            assertTrue(instrument.zones.all { it.loopStartFrame in 1 until it.frameCount })
            assertEquals(17, instrument.sampleFiles.size)
            assertEquals(1, requireNotNull(directory.listFiles { it.extension == "xty" }).size)
            assertEquals(1, directory.walkTopDown().count { it.extension == "xpm" })
            val expectedPatch = source.copy(macros = source.macros +
                mapOf("BODY" to .2f, "EDGE" to .8f, "RATE" to .65f), velocity = .35f)
            val spec = ContourPadMaker.Spec(expectedPatch, .8f)
            for (index in listOf(0, 8, 16)) {
                val midi = midis[index]
                val zone = instrument.zones[index]
                val note = ContourPadMaker.renderZone(spec, midi)
                val sheet = SmplChunk(midi, SmplChunk.Loop(note.loopStartFrame, note.snip.frameCount.toLong()))
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, note.snip, smpl = sheet) }
                val wav = File(sidecar.parentFile, zone.sample)
                assertContentEquals(expected.toByteArray(), wav.readBytes(), "MIDI $midi keeps saved tables and edited excitation")
                assertEquals(1, WavReader.read(wav).channels)
            }
            assertTrue("17 zones, settled sustain (finite attack omitted)" in output.toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Contour instrument rejects ineffective edits and invalid release before output is created`() {
        val root = kotlin.io.path.createTempDirectory("contour-cli-instrument-refusals").toFile()
        val directory = File(root, "not-created")
        try {
            val cases = listOf(
                "--attack" to listOf("--attack", ".2"),
                "--midi" to listOf("--midi", "60"),
                "--macros" to listOf("--macros", "TUNE=.75"),
                "--macros" to listOf("--macros", "HOLD=1"),
                "--all" to listOf("--all"),
                "--velocity" to listOf("--velocity", "NaN"),
                "--velocity" to listOf("--velocity", "1.01"),
            ) + listOf("NaN", "Infinity", "0", "-.1", "1.51", "long", "").map {
                "--release" to listOf("--release", it)
            }
            for ((flag, flags) in cases) {
                val errors = ByteArrayOutputStream()
                val args = listOf("synth", "CONTOUR", "KEY", "--instrument") + flags + listOf("--out", directory.path)
                assertEquals(2, Cli.run(args.toTypedArray(), PrintStream(ByteArrayOutputStream()), PrintStream(errors)), "$flags: $errors")
                assertTrue(flag in errors.toString(), "$flags: $errors")
                assertFalse("Exception" in errors.toString(), "$flags: $errors")
                assertFalse(directory.exists(), "$flags must fail before any output is created")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertRenderedPatch(patch: ContourPatch, dir: File) {
        val wav = requireNotNull(dir.listFiles { it.extension == "wav" }).single()
        val expected = ByteArrayOutputStream().also { WavWriter.write(it, patch.render()) }
        assertContentEquals(expected.toByteArray(), wav.readBytes(), "complete saved source must survive CLI playback")
    }
}
