package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.CreasePresets
import com.snipsnap.synth.CreaseTouchKeys
import com.snipsnap.synth.CreaseVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Check the actual dual-generation package and the same key map used by the phone. */
class CreasePadMakerTest {
    private val dest = java.nio.file.Files.createTempDirectory("crease-keys").toFile()

    @AfterTest fun cleanUp() { dest.deleteRecursively() }

    private companion object {
        val patch = CreasePatch("CREASE KEYS", CreaseVoice.ROUND,
            Crease.defaults(CreaseVoice.ROUND) + mapOf("FOLD" to .25f, "PRESSURE" to .65f,
                "DRAG" to .22f, "RELAX" to .25f, "HOLD" to 0f),
            midi = 48, velocity = .65f, model = 3)
        val spec = CreasePadMaker.Spec(patch, releaseSeconds = .4f)
        val notes: List<KeyNote> by lazy {
            CreasePadMaker.zoneMidis(spec).map { CreasePadMaker.renderZone(spec, it) }
        }
        val paper = CreasePresets.patches().single { it.name == "Paper Keys" }
        val touchSpec = CreasePadMaker.Spec(paper, releaseSeconds = .4f, touchLayers = true)
        val touchNotes: List<List<KeyNote>> by lazy {
            CreasePadMaker.zoneMidis(touchSpec).map { CreasePadMaker.renderLayers(touchSpec, it) }
        }
    }

    @Test
    fun `saved root and boundary roots tile only their sampled keyboard span`() {
        assertEquals((36..60 step 3).toList(), CreasePadMaker.zoneMidis(spec))
        // Genuine audio is unnecessary for testing the keyboard's range and file-name contract.
        val fixture = KeyNote(Snip(floatArrayOf(0f, .2f, 0f, -.2f, 0f, .2f, 0f, -.2f), 1, Crease.RATE), 4L)
        for (root in listOf(24, 25, 48, 83, 84)) {
            val edge = CreasePadMaker.Spec(patch.copy(midi = root))
            val roots = CreasePadMaker.zoneMidis(edge)
            val (program, samples) = CreasePadMaker.assemble("CREASE EDGE", edge, roots.map { fixture })
            assertTrue(root in roots, "the saved root is a directly rendered zone")
            assertEquals(roots.distinct().sorted(), roots)
            assertEquals(maxOf(24, root - 12), roots.first())
            assertEquals(minOf(84, root + 12), roots.last())
            assertEquals(roots, program.keygroups.map { it.rootNote })
            assertEquals(roots.size, samples.size, "sharp and natural root names cannot overwrite a WAV")
            assertEquals((roots.first()..roots.last()).toList(),
                program.keygroups.flatMap { (it.lowNote..it.highNote).toList() }, "no keyboard gaps or overlap")
            assertTrue(program.keygroups.all { zone ->
                zone.rootNote in zone.lowNote..zone.highNote &&
                    (zone.lowNote..zone.highNote).all { abs(it - zone.rootNote) <= 1 }
            })
        }
    }

    @Test
    fun `WAV sampler metadata both MPC generations and phone sidecar retain roots loops and release`() {
        val before = patch.toJsonText()
        val program = CreasePadMaker.export(patch.name, spec, notes, dest)
        val data = File(dest, Mpc3TrackWriter.trackDataDirName(patch.name))
        val xpm = File(data, "${patch.name}.xpm").readText()
        val xty = Acvs.read(File(dest, "${patch.name}.xty")).payloadText
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm)
        assertTrue("<VolumeAttack>0.000000</VolumeAttack>" in xpm, "the WAV already owns its onset")
        assertTrue("<VolumeRelease>0.400000</VolumeRelease>" in xpm)
        assertTrue("\"LoopMode\": 1" in xty)
        val envelopes = objects(Json.parse(xty)).mapNotNull { it["ampEnvelope"]?.obj() }
            .take(notes.size).toList()
        assertEquals(notes.size, envelopes.size)
        for (envelope in envelopes) {
            assertEquals(spec.releaseSeconds.toDouble(), envelope.getValue("Release").obj().getValue("value0").num())
            assertFalse(envelope.getValue("OneShot").obj().getValue("value0").bool())
        }
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        assertEquals(spec.releaseSeconds, instrument.release)
        assertEquals(CreasePadMaker.zoneMidis(spec), instrument.zones.map { it.rootNote })
        assertEquals(0, instrument.zones.first().lowNote)
        assertEquals(127, instrument.zones.last().highNote)
        val spanned = program.fullKeyboard()
        instrument.zones.forEachIndexed { index, zone ->
            assertEquals(spanned.keygroups[index].lowNote, zone.lowNote)
            assertEquals(spanned.keygroups[index].highNote, zone.highNote)
            assertEquals(notes[index].loopStartFrame, zone.loopStartFrame)
            val wav = File(sidecar.parentFile, zone.sample)
            val audio = WavReader.read(wav)
            val sheet = assertNotNull(WavReader.readSmpl(wav))
            val loop = assertNotNull(sheet.loop)
            assertEquals(zone.rootNote, sheet.rootNote)
            assertEquals(zone.loopStartFrame, loop.startFrame)
            assertEquals(zone.frameCount, loop.endFrameExclusive, "the player wraps at the sample's exclusive end")
            assertEquals(notes[index].snip.frameCount.toLong(), zone.frameCount)
            assertEquals(Crease.RATE, audio.sampleRate)
            assertEquals(1, audio.channels)
            assertTrue(audio.samples.all { it.isFinite() })
            assertTrue(audio.samples.indices.all { abs(audio.samples[it] - notes[index].snip.samples[it]) < 2e-7f },
                "authored model, excitation and macro audio survives PCM24 export")
            assertTrue(Keys.seamError(audio.samples, zone.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            assertTrue("<SliceLoopStart>${zone.loopStartFrame}</SliceLoopStart>" in xpm)
            assertTrue("\"LoopStart\": ${zone.loopStartFrame}" in xty)
        }
        assertEquals(before, patch.toJsonText(), "making an instrument leaves the complete saved recipe intact")
        assertEquals(0f, patch.macros.getValue("HOLD"))
        // The outer zones stretch to the keyboard's ends, so keys past the authored span land in them.
        assertEquals(instrument.zones.first(), instrument.zoneFor(35))
        assertEquals(instrument.zones.last(), instrument.zoneFor(61))
    }

    @Test
    fun `export readback holds at the played pitch at 48 kHz then releases on note off`() {
        CreasePadMaker.export(patch.name, spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        val loaded = InstrumentEngine.Loaded(instrument, Crease.RATE,
            instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples })
        val player = InstrumentEngine(loaded, 48_000)
        assertTrue(player.noteOn(49, .8f))
        val seconds = notes.maxOf { it.snip.durationSeconds } + 1f
        val held = render(player, seconds, 48_000)
        assertTrue(rms(held.samples, held.samples.size - 48_000, held.samples.size) > .001,
            "the note keeps sounding after the exported file has ended")
        val detected = assertNotNull(Pitch.detect(held, fromSec = seconds - .6f, windowSec = .4f))
        val cents = abs(1200 * ln(detected.hz / Keys.midiHz(49).toDouble()) / ln(2.0))
        assertTrue(cents < 5, "the transposed held key is $cents cents from its requested pitch")
        player.noteOff(49)
        val release = render(player, instrument.release + .15f, 48_000)
        assertTrue(release.samples.takeLast(960).all { it == 0f })
        assertTrue(player.activeNotes.isEmpty())

        // The saved excitation is baked once. A key touch adds only the existing linear player gain.
        val soft = InstrumentEngine(loaded, Crease.RATE).also { assertTrue(it.noteOn(48, .25f)) }
        val hard = InstrumentEngine(loaded, Crease.RATE).also { assertTrue(it.noteOn(48, 1f)) }
        val a = render(soft, .3f, Crease.RATE).samples
        val b = render(hard, .3f, Crease.RATE).samples
        assertTrue(a.indices.all { abs(a[it] - .25f * b[it]) < 1e-7f })
    }

    @Test
    fun `preview preserves the full arrival and held pass then demonstrates the selected release`() {
        val root = notes[CreasePadMaker.zoneMidis(spec).indexOf(patch.midi)]
        val source = root.snip.samples.copyOf()
        val noteOff = root.snip.frameCount * 2 - root.loopStartFrame.toInt()
        val shortSpec = spec.copy(releaseSeconds = .1f)
        val longSpec = spec.copy(releaseSeconds = .7f)
        val short = CreasePadMaker.preview(shortSpec)
        val long = CreasePadMaker.preview(longSpec)
        for ((setting, preview) in listOf(shortSpec to short, longSpec to long)) {
            val releaseFrames = (setting.releaseSeconds * root.snip.sampleRate).roundToInt()
            assertEquals(noteOff + releaseFrames, preview.frameCount, "RELEASE adds its selected duration")
            assertContentEquals(source, preview.samples.copyOf(root.snip.frameCount), "the complete authored prefix is unchanged")
            assertContentEquals(source.copyOfRange(root.loopStartFrame.toInt(), source.size),
                preview.samples.copyOfRange(root.snip.frameCount, noteOff), "one extra held pass remains unmodified")
            assertEquals(source[root.loopStartFrame.toInt()], preview.samples[noteOff], "key-up continues the next loop")
            assertEquals(0f, preview.samples.last(), "the audition reaches exact silence instead of cutting a nonzero sample")
            val window = releaseFrames / 8
            assertTrue(rms(preview.samples, preview.samples.size - window, preview.samples.size) <
                rms(preview.samples, noteOff, noteOff + window) * .35,
                "the late released tail is quiet relative to the start of the fade")
        }
        val shortReleaseFrames = short.frameCount - noteOff
        val from = noteOff + shortReleaseFrames * 3 / 4
        assertTrue(rms(long.samples, from, short.frameCount) > rms(short.samples, from, short.frameCount) * 2,
            "the longer release retains more level at the same post-key-up time")
        assertTrue(long.frameCount > short.frameCount)
        assertContentEquals(source, root.snip.samples, "preview never fades the reusable exported KeyNote in place")
        val after = CreasePadMaker.renderZone(spec, patch.midi)
        assertEquals(root.loopStartFrame, after.loopStartFrame)
        assertContentEquals(source, after.snip.samples, "subsequent exports retain the exact authored source")
    }

    @Test
    fun `legacy recipes invalid packages and cancelled zones publish no partial instrument`() {
        assertTrue(CreasePadMaker.supports(patch))
        for (model in listOf(1, 2)) {
            val legacy = patch.copy(model = model)
            assertFalse(CreasePadMaker.supports(legacy))
            assertFailsWith<IllegalArgumentException> { CreasePadMaker.Spec(legacy) }
            assertEquals(model, legacy.model, "a builder refusal never upgrades an old recipe")
        }
        for (release in listOf(.01f, 1.6f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { CreasePadMaker.Spec(patch, release) }
        }
        assertEquals(.1f, CreasePadMaker.spec(patch, 0f).releaseSeconds)
        assertEquals(1.5f, CreasePadMaker.spec(patch, 1f).releaseSeconds)
        assertEquals("0.40 s", CreasePadMaker.secondsLabel(.4f))
        assertFailsWith<CancellationException> { CreasePadMaker.renderZone(spec, patch.midi) { true } }
        var checks = 0
        assertFailsWith<CancellationException> { CreasePadMaker.renderZone(spec, patch.midi) { ++checks > 4 } }
        assertTrue(checks > 4, "cancellation is polled inside the held render")
        assertFailsWith<IllegalArgumentException> { CreasePadMaker.renderZone(spec, 35) }
        val untouched = File(dest, "invalid")
        val invalid = notes.toMutableList().also { it[0] = it[0].copy(loopStartFrame = 0L) }
        assertFailsWith<IllegalArgumentException> { CreasePadMaker.export("INVALID", spec, invalid, untouched) }
        assertFalse(untouched.exists(), "all zones are validated before the package writer creates a directory")
    }

    @Test
    fun `default single layer retains the approved package audio names and all metadata bytes`() {
        // SHA256s from the published 3fb2c43c Paper Keys instrument, not this implementation. The nine WAVs are
        // still the published bytes. The sidecar, the .xty and the .xpm were re-pinned when every export began
        // spanning the keyboard and carrying a voice mode: they differ from the published ones in the outer zone
        // edges (0 and 127) AND the voice-mode fields (the sidecar's voiceMode, the .xty's monophonic/poliphony
        // pair, the .xpm's program-level voice tags).
        // The sidecar now writes explicitNoteOn:false too. Removing just that default field must recover the
        // preceding sidecar hash, and both versions must still describe the same instrument.
        val expected = mapOf(
            "Crease Paper Keys.instrument.json" to "4123f312c6f2ef3376e86bd30252aed2634b87ea87c414da37162d2da88d30d1",
            "Crease Paper Keys.xty" to "7914f2da97444a4af472330faba18114a9ea4962bf4ca5ddf66f8c74ca7583f3",
            "Crease Paper Keys_[TrackData]/Crease Paper Keys.xpm" to "7a46aac454783287fbcd92e38f8368b0b45ac8844599abe9b4795b80bdb28391",
            "Crease Paper Keys_[TrackData]/CreasePaperKeys_A3.wav" to "973f4979c6dba53b72e8de565aba6717cebc64d79e957d155cb65d0ac888f680",
            "Crease Paper Keys_[TrackData]/CreasePaperKeys_A4.wav" to "bd9a81541cea931a6052954a891236bd86e6e7a6209144d8bb40d4d15fe17103",
            "Crease Paper Keys_[TrackData]/CreasePaperKeys_C3.wav" to "a22d0f9005f0b45feff46f87a4d5b4ffc00b7cc4cf9a72aa1df455624c7de90e",
            "Crease Paper Keys_[TrackData]/CreasePaperKeys_C4.wav" to "ee96b1e51ed50f77beb2bc903f5e976f30f3dd2a3adb6ab87ea5b652f55fa8ab",
            "Crease Paper Keys_[TrackData]/CreasePaperKeys_C5.wav" to "b904c0d0660023c3dbd53c0cb03567171327282bfe28a6ebdc2769c7f1ff2ee8",
            "Crease Paper Keys_[TrackData]/CreasePaperKeys_D#3.wav" to "e25645ad5df66d7d51214626ae542ecfb20df52796998622adfff941bc0741e6",
            "Crease Paper Keys_[TrackData]/CreasePaperKeys_D#4.wav" to "25f223b081b512e620d21577ccfe7736423fe4446899c3acd85d15475e208344",
            "Crease Paper Keys_[TrackData]/CreasePaperKeys_F#3.wav" to "5c6961701c2d59a2676d833bf260bbfbfe48fc4a1b0b9ea1d7db2a063cab5e70",
            "Crease Paper Keys_[TrackData]/CreasePaperKeys_F#4.wav" to "0c4ac53bca6efc50c279bf09db4503e4aa91ddfbb0455d6374c5ebc143388d20",
        )
        val legacy = CreasePadMaker.Spec(paper)
        assertFalse(legacy.touchLayers)
        assertEquals(1, legacy.layerCount)
        val rendered = CreasePadMaker.zoneMidis(legacy).map { CreasePadMaker.renderLayers(legacy, it) }
        CreasePadMaker.exportLayers("Crease Paper Keys", legacy, rendered, dest)
        val actual = dest.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dest).invariantSeparatorsPath to sha(it) }
        assertEquals(expected, actual, "no --touch must retain every original package byte")
        val sidecar = File(dest, "Crease Paper Keys.instrument.json")
        val text = sidecar.readText()
        val defaultField = "  \"explicitNoteOn\": false,\n"
        assertEquals(1, text.lineSequence().count { it == defaultField.trimEnd() })
        val previous = File.createTempFile("crease-legacy-sidecar-", ".instrument.json")
        try {
            previous.writeText(text.replaceFirst(defaultField, ""))
            assertEquals("cccac73fbbaef101cd5cac7017b8cd6c86c63c47cf16623c105d32ed5e1a1992", sha(previous))
            assertEquals(InstrumentStore.read(previous), InstrumentStore.read(sidecar),
                "an explicitly false note-on flag must preserve the earlier instrument's meaning")
        } finally {
            previous.delete()
        }
    }

    @Test
    fun `touch export retains every layer window root independent loop and common sustain level`() {
        val before = paper.toJsonText()
        val program = CreasePadMaker.exportLayers("TOUCH KEYS", touchSpec, touchNotes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        assertEquals(3, touchSpec.layerCount)
        assertEquals(27, instrument.sampleFiles.size)
        val data = File(dest, Mpc3TrackWriter.trackDataDirName("TOUCH KEYS"))
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(data, "TOUCH KEYS.xpm"))
        val xmlNodes = xml.getElementsByTagName("Layer")
        val xmlLayers = (0 until xmlNodes.length).map { xmlNodes.item(it) as Element }.filter {
            it.getElementsByTagName("SampleName").item(0).textContent.isNotEmpty()
        }.associateBy { it.getElementsByTagName("SampleName").item(0).textContent }
        val payload = Json.parse(Acvs.read(File(dest, "TOUCH KEYS.xty")).payloadText)
        val mpcLayers = objects(payload).filter { "sliceInfo" in it && it["sampleName"]?.str()?.isNotEmpty() == true }.toList()
        assertEquals(27, xmlLayers.size)
        assertEquals(27, mpcLayers.size)
        val mpcByName = mpcLayers.associateBy { it.getValue("sampleName").str() }
        assertEquals(program.keygroups.first().layers.asReversed().map { it.sampleName },
            mpcLayers.take(3).map { it.getValue("sampleName").str() }, "MPC3 layers descend hard-first")
        for ((zoneIndex, zone) in instrument.zones.withIndex()) {
            assertEquals(CreaseTouchKeys.VELOCITY_WINDOWS, zone.layers.map { it.velStart..it.velEnd })
            assertEquals(zone.layers.last().sample, zone.sample, "the legacy headline is the hard layer")
            val levels = zone.layers.mapIndexed { layerIndex, layer ->
                assertTrue(layer.sample.endsWith("_v${layerIndex + 1}.wav"))
                val source = touchNotes[zoneIndex][layerIndex]
                val wav = File(sidecar.parentFile, layer.sample)
                val decoded = WavReader.read(wav)
                assertEquals(source.snip.frameCount.toLong(), layer.frameCount)
                assertEquals(source.loopStartFrame, layer.loopStartFrame)
                assertEquals(com.snipsnap.audio.SmplChunk(zone.rootNote,
                    com.snipsnap.audio.SmplChunk.Loop(layer.loopStartFrame, layer.frameCount)), WavReader.readSmpl(wav))
                assertTrue(decoded.samples.all { it.isFinite() && abs(it) <= .991f })
                assertTrue(decoded.samples.indices.all { abs(decoded.samples[it] - source.snip.samples[it]) < 2e-7f })
                assertTrue(Keys.seamError(decoded.samples, layer.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
                val stem = wav.nameWithoutExtension
                val x = xmlLayers.getValue(stem)
                fun xmlText(name: String) = x.getElementsByTagName(name).item(0).textContent
                assertEquals(layer.velStart.toString(), xmlText("VelStart"))
                assertEquals(layer.velEnd.toString(), xmlText("VelEnd"))
                assertEquals(zone.rootNote.toString(), xmlText("RootNote"))
                assertEquals(layer.loopStartFrame.toString(), xmlText("SliceLoopStart"))
                val m = mpcByName.getValue(stem)
                assertEquals(layer.velStart, m.getValue("velocityStart").int())
                assertEquals(layer.velEnd, m.getValue("velocityEnd").int())
                assertEquals(zone.rootNote, m.getValue("rootNote").int())
                assertEquals(layer.loopStartFrame, m.getValue("sliceInfo").obj().getValue("LoopStart").long())
                Loudness.of(decoded.samples.copyOfRange(layer.loopStartFrame.toInt(), decoded.samples.size), 1, Crease.RATE).toDouble()
            }
            val spread = 20 * log10(levels.max() / levels.min())
            assertTrue(spread <= .25, "MIDI${zone.rootNote} baked steady level steps $spread dB")
            println("CREASE TOUCH EXPORT MIDI${zone.rootNote} steadySpreadDb=$spread")
        }
        assertEquals(before, paper.toJsonText(), "touch authoring keeps the saved hard-touch anchor and macros")
    }

    @Test
    fun `actual layered playback changes spectrum and avoids extra level steps at MIDI window boundaries`() {
        CreasePadMaker.exportLayers("TOUCH PLAYER", touchSpec, touchNotes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
        val loaded = InstrumentEngine.Loaded(instrument, Crease.RATE, samples)
        val zone = instrument.zones.single { it.rootNote == paper.midi }
        for ((midiVelocity, layerIndex) in listOf(1 to 0, 42 to 0, 43 to 1, 84 to 1, 85 to 2, 127 to 2)) {
            val touch = midiVelocity / 127f
            val hit = assertNotNull(KeyHit.resolve(instrument, paper.midi, touch) { samples[it]?.size?.toLong() })
            assertEquals(zone.layers[layerIndex].sample, hit.sampleFile, "MIDI velocity$midiVelocity")
            assertEquals(touch * InstrumentEngine.VOICE_LEVEL, hit.gain)
        }
        val loopFrames = zone.layers.last().frameCount.toInt() - zone.layers.last().loopStartFrame.toInt()
        val start = zone.layers.maxOf { it.loopStartFrame.toInt() }
        val played = listOf(32, 42, 43, 84, 85, 127).associateWith { midiVelocity ->
            val player = InstrumentEngine(loaded, Crease.RATE)
            assertTrue(player.noteOn(paper.midi, midiVelocity / 127f))
            val audio = FloatArray((start + loopFrames) * 2)
            player.render(audio, start + loopFrames)
            assertTrue(audio.all { abs(it) < .9f }, "reference soft clip must not disguise level switching")
            FloatArray(loopFrames) { audio[(start + it) * 2] }
        }
        fun level(velocity: Int) = Loudness.of(played.getValue(velocity), 1, Crease.RATE).toDouble()
        for ((low, high) in listOf(42 to 43, 84 to 85)) {
            val actual = 20 * log10(level(high) / level(low))
            val ideal = 20 * log10(high.toDouble() / low)
            assertTrue(abs(actual - ideal) <= .35, "$low->$high adds a baked level step: $actual vs $ideal dB")
            println("CREASE TOUCH CROSSOVER $low->$high actualDb=$actual linearIdealDb=$ideal")
        }
        // A held pressure gesture moves through a whole period. The first 250 ms alone
        // measured 0.0492423 in the initial probe; retain it and sample the other fixed
        // quarter phases rather than choosing a louder-looking window after rendering.
        val phases = listOf(0.0, .25, .5, .75)
        val distances = phases.map { fraction ->
            val at = (fraction * loopFrames).toInt()
            val a = harmonicPower(played.getValue(32), Keys.midiHz(paper.midi).toDouble(), at)
            val b = harmonicPower(played.getValue(127), Keys.midiHz(paper.midi).toDouble(), at)
            a.indices.sumOf { abs(a[it] - b[it]) } / 2
        }
        println("CREASE TOUCH PLAYER phases=$phases softHardHarmonicTv=$distances " +
            "mean=${distances.average()} max=${distances.max()}")
        assertTrue(distances.average() >= .05,
            "actual soft/hard playback has little harmonic redistribution across the held gesture: $distances")
        assertTrue(distances.max() >= .075,
            "the actual held gesture never reaches substantial soft/hard contrast: $distances")
    }

    @Test
    fun `touch mode refuses single layer export and incomplete banks before writing`() {
        val subnormal = paper.copy(velocity = Float.MIN_VALUE)
        assertFalse(CreasePadMaker.Spec(subnormal).touchLayers, "the default keeps its existing patch domain")
        assertFailsWith<IllegalArgumentException> { CreasePadMaker.Spec(subnormal, touchLayers = true) }
        assertTrue(CreasePadMaker.Spec(paper.copy(velocity = CreaseTouchKeys.MIN_POSITIVE_ANCHOR), touchLayers = true).touchLayers)
        val refused = File(dest, "refused")
        assertFailsWith<IllegalArgumentException> { CreasePadMaker.export("NO LOSS", touchSpec, notes, refused) }
        assertFalse(refused.exists())
        val fixture = KeyNote(Snip(floatArrayOf(0f, .2f, 0f, -.2f, 0f, .2f, 0f, -.2f), 1, Crease.RATE), 4L)
        val incomplete = CreasePadMaker.zoneMidis(touchSpec).map { listOf(fixture, fixture) }
        assertFailsWith<IllegalArgumentException> { CreasePadMaker.exportLayers("NO LOSS", touchSpec, incomplete, refused) }
        assertFalse(refused.exists())
        assertFailsWith<CancellationException> { CreasePadMaker.renderLayers(touchSpec, paper.midi) { true } }
        assertFailsWith<CancellationException> { CreasePadMaker.preview(touchSpec) { true } }
    }

    @Test
    fun `touch preview plays three short presses and each selected release without changing source samples`() {
        val source = touchNotes[CreasePadMaker.zoneMidis(touchSpec).indexOf(paper.midi)]
        val before = source.map { it.snip.samples.copyOf() }
        val heard = CreasePadMaker.preview(touchSpec)
        assertEquals(2, heard.channels)
        val held = (Crease.RATE * .9f).roundToInt()
        val tail = (Crease.RATE * (touchSpec.releaseSeconds + .12f)).roundToInt()
        assertEquals((held + tail) * 3, heard.frameCount)
        for (i in 0 until 3) {
            val from = i * (held + tail) * 2
            assertTrue(rms(heard.samples, from, from + held * 2) > .001)
            val end = (i + 1) * (held + tail) * 2
            assertTrue(heard.samples.copyOfRange(end - 256, end).all { it == 0f }, "each press completes its selected release")
        }
        before.zip(source).forEach { (saved, note) -> assertContentEquals(saved, note.snip.samples) }
    }

    private fun objects(value: JsonValue): Sequence<Map<String, JsonValue>> = sequence {
        when (value) {
            is JsonValue.Obj -> {
                yield(value.entries)
                value.entries.values.forEach { yieldAll(objects(it)) }
            }
            is JsonValue.Arr -> value.items.forEach { yieldAll(objects(it)) }
            else -> Unit
        }
    }

    private fun render(player: InstrumentEngine, seconds: Float, rate: Int): Snip {
        val frames = (seconds * rate).toInt()
        val samples = FloatArray(frames * 2)
        player.render(samples, frames)
        return Snip(samples, channels = 2, sampleRate = rate)
    }

    private fun rms(samples: FloatArray, start: Int, end: Int): Double =
        sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start))

    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun harmonicPower(samples: FloatArray, hz: Double, start: Int): DoubleArray {
        val size = 65_536
        val frames = Crease.RATE / 4
        require(start >= 0 && start + frames <= samples.size)
        val window = FloatArray(size)
        for (i in 0 until frames) window[i] = (samples[start + i] * (.5 - .5 * cos(2 * Math.PI * i / (frames - 1)))).toFloat()
        val magnitude = Fft.magnitudeSpectrum(window, size)
        val powers = DoubleArray(32)
        for (h in powers.indices) for (bin in 1 until magnitude.size) {
            if (abs(bin * Crease.RATE.toDouble() / size - hz * (h + 1)) <= 12)
                powers[h] += magnitude[bin].toDouble() * magnitude[bin]
        }
        val total = powers.sum().coerceAtLeast(1e-30)
        return DoubleArray(powers.size) { powers[it] / total }
    }
}
