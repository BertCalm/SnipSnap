package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismGroupProfile
import com.snipsnap.synth.PrismOpeningPresets
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismVoice
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The opening belongs to the first pass; the stored loop starts inside the instrument's own WAV. */
class PrismOpeningInstrumentWorkflowTest {
    private val root = createTempDirectory("prism-opening-instrument").toFile()

    @AfterTest
    fun cleanUp() { root.deleteRecursively() }

    private fun authored(voice: PrismVoice = PrismVoice.KEY): PrismPatch {
        val opening = PrismOpeningPresets.all().first()
        return opening.copy(
            name = "MY KEPT PRISM", voice = voice,
            macros = Prism.defaults(voice) + mapOf(
                "TUNE" to (1f / Prism.TUNE_SEMITONES), "GRAIN" to .17f, "DRIFT" to .23f,
                "SHAPE" to .44f, "STRIKE" to .37f, "BODY" to .58f, "HOLD" to 1f,
            ),
            velocity = .43f,
            template = Prism.templateFor(voice).copy(
                ratioOverrides = List(Prism.PARTIALS) { it + 1f },
                amplitudeOverrides = List(Prism.PARTIALS) { .8f / (it + 1f) },
                groupProfiles = List(Prism.GROUPS) { group ->
                    PrismGroupProfile(1f + group * .11f, .9f + group * .03f,
                        group * .004f, 1 + group, .6f + group * .07f)
                },
                attackSeconds = .017f, decaySeconds = .7f, loopSeconds = 1.1f,
            ),
            motion = opening.motion!!.copy(
                morph = .12f, evolve = .68f, seconds = .18f, groupLag = .23f,
                amplitudesA = List(Prism.PARTIALS) { .6f / ((it + 1f) * (it + 1f)) },
                repeat = true, sourceId = "unavailable-source-still-replays",
            ),
        )
    }

    /** Each frame has its own address, making an accidental return to the opening visible. */
    private fun diagnosticNotes(count: Int) = List(count) { index ->
        KeyNote(Snip(FloatArray(64) { frame -> (frame + 1) / 1000f + index / 10_000f }, 1, 44_100),
            loopStartFrame = (16 + index).toLong())
    }

    @Test
    fun `complete held recipe reopens at an exact requested note without a donor or a factory reset`() {
        val patch = authored()
        val before = patch.toJsonText()
        val spec = PrismPadMaker.Spec(patch, releaseSeconds = .37f)
        val saved = Json.parse(spec.toJsonText()).obj()
        assertEquals(1, saved.getValue("version").int())
        assertEquals(1, saved.getValue("heldModel").int())
        assertEquals(patch.toJsonValue(), saved.getValue("patch"))
        val reopened = PrismPadMaker.Spec.fromJsonText(spec.toJsonText())
        assertEquals(spec, reopened, "the delivery recipe must retain both spectra, motion, source ID and the entire carrier")
        val savedMidi = Prism.midiFor(patch.voice, patch.macros.getValue("TUNE"))
        val midi = Prism.rootMidi(patch.voice) + 4
        assertEquals(Prism.rootMidi(patch.voice) + 1, savedMidi)
        assertFalse(midi in PrismPadMaker.zoneMidis(spec), "exercise a requested note between the exported roots")
        val actual = PrismPadMaker.renderZone(reopened, midi)
        val expectedMacros = patch.macros + ("TUNE" to (midi - Prism.rootMidi(patch.voice)) / Prism.TUNE_SEMITONES.toFloat())
        val expected = Prism.openingNote(patch.voice, expectedMacros, patch.velocity, patch.template, patch.motion!!)
        assertEquals(expected.loopStartFrame, actual.loopStartFrame)
        assertContentEquals(expected.snip.samples, actual.snip.samples,
            "reopening must render the stored source coefficients, not re-analyze an unavailable donor")
        assertTrue(actual.loopStartFrame in 1 until actual.snip.frameCount.toLong() - 1)
        assertTrue(actual.snip.samples.any { it != 0f }, "a silent render cannot prove source preservation")
        assertEquals(before, patch.toJsonText(), "rendering a key never rewrites the authored TUNE or motion")
    }

    @Test
    fun `package keeps the opening offset in both MPC generations sampler sheets and phone playback`() {
        val spec = PrismPadMaker.Spec(authored(), releaseSeconds = .23f)
        val roots = PrismPadMaker.zoneMidis(spec)
        val notes = diagnosticNotes(roots.size)
        val name = "PRISM OPENING"
        val program = PrismPadMaker.export(name, spec, notes, root)
        assertTrue(program.explicitNoteOn, "the new instrument explicitly sustains while a key is held")
        assertEquals(spec, PrismPadMaker.Spec.fromJsonText(PrismPadMaker.recipeFile(root, name).readText()),
            "the package keeps its full source and delivery recipe beside the playable files")
        val (sidecar, instrument) = InstrumentStore.list(root).single()
        assertEquals(roots, instrument.zones.map { it.rootNote })
        assertEquals(spec.releaseSeconds, instrument.release)
        val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val xml = factory.newDocumentBuilder().parse(File(data, "$name.xpm"))
            .getElementsByTagName("Instrument")
        assertEquals(9, xml.length)
        val native = Json.parse(Acvs.read(File(root, "$name.xty")).payloadText).obj()
            .getValue("data").obj().getValue("program").obj().getValue("drum").obj()
            .getValue("instruments").arr()
        val audio = instrument.zones.mapIndexed { index, zone ->
            val group = program.keygroups[index]
            val layer = group.layers.single()
            val file = File(sidecar.parentFile, zone.sample)
            val sheet = SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount))
            assertEquals(notes[index].loopStartFrame, zone.loopStartFrame)
            assertEquals(notes[index].snip.frameCount.toLong(), zone.frameCount)
            assertEquals(sheet, WavReader.readSmplStrict(file))
            val expectedWav = ByteArrayOutputStream().also { WavWriter.write(it, notes[index].snip, smpl = sheet) }
            assertContentEquals(expectedWav.toByteArray(), file.readBytes(), "packaging must not alter the opening or sustain audio")
            val x = xml.item(index) as Element
            val xLayer = x.getElementsByTagName("Layer").item(0) as Element
            assertEquals(zone.lowNote, text(x, "LowNote").toInt())
            assertEquals(zone.highNote, text(x, "HighNote").toInt())
            assertEquals("2", text(x, "TriggerMode"))
            assertEquals(spec.releaseSeconds, text(x, "VolumeRelease").toFloat())
            assertEquals(layer.sampleName, text(xLayer, "SampleName"))
            assertEquals(zone.rootNote, text(xLayer, "RootNote").toInt())
            assertEquals(zone.loopStartFrame, text(xLayer, "SliceLoopStart").toLong())
            assertEquals(zone.frameCount, text(xLayer, "SliceEnd").toLong())
            assertEquals("1", text(xLayer, "SliceLoop"))
            val n = native[index].obj()
            assertEquals(2, n.getValue("triggerMode").int(), "the key sustains until note-off")
            val nLayer = n.getValue("layersv").arr().first().obj()
            val slice = nLayer.getValue("sliceInfo").obj()
            assertEquals(zone.rootNote, nLayer.getValue("rootNote").int())
            assertEquals(zone.loopStartFrame, slice.getValue("LoopStart").long())
            assertEquals(zone.frameCount, slice.getValue("End").long())
            assertEquals(1, slice.getValue("LoopMode").int())
            assertEquals(spec.releaseSeconds.toDouble(), n.getValue("synthSection").obj()
                .getValue("ampEnvelope").obj().getValue("Release").obj().getValue("value0").num())
            WavReader.read(file).samples
        }

        val loaded = InstrumentEngine.Loaded(instrument, 44_100, audio)
        val player = InstrumentEngine(loaded, 44_100)
        val soft = InstrumentEngine(loaded, 44_100)
        val center = instrument.zones[4]
        val source = audio[4]
        val loopStart = center.loopStartFrame.toInt()
        val period = source.size - loopStart
        assertTrue(player.noteOn(center.rootNote))
        assertTrue(soft.noteOn(center.rootNote, .5f))
        val frames = source.size + period * 2 + 7
        val heard = render(player, frames)
        val quieter = render(soft, frames)
        for (frame in 0 until frames) {
            val address = if (frame < source.size) frame else loopStart + (frame - source.size) % period
            val expected = source[address] * InstrumentEngine.VOICE_LEVEL
            assertEquals(expected, heard[frame * 2], 1e-7f,
                "frame $frame must play opening once, then return to sustain frame $address")
            assertEquals(expected, heard[frame * 2 + 1], 1e-7f)
            assertEquals(expected * .5f, quieter[frame * 2], 1e-7f,
                "a key's playing velocity supplies one host gain after the saved source velocity")
        }
        assertEquals(listOf(center.rootNote), player.activeNotes)
        player.noteOff(center.rootNote)
        val release = render(player, ((spec.releaseSeconds + .1f) * 44_100).toInt())
        assertTrue(release.takeLast(882).all { it == 0f }, "the repeated sustain must stop after the saved note-off release")
        assertTrue(player.activeNotes.isEmpty())
    }

    @Test
    fun `nine native roots tile each voices own two octave register without changing its authored note`() {
        for (voice in PrismVoice.entries) {
            val spec = PrismPadMaker.Spec(authored(voice))
            val low = Prism.rootMidi(voice)
            val roots = (low..low + Prism.TUNE_SEMITONES step 3).toList()
            assertEquals(roots, PrismPadMaker.zoneMidis(spec))
            val notes = diagnosticNotes(roots.size)
            val (program, samples) = PrismPadMaker.assemble("PRISM ${voice.name}", spec, notes)
            assertEquals(roots, program.keygroups.map { it.rootNote })
            assertEquals((low..low + Prism.TUNE_SEMITONES).toList(),
                program.keygroups.flatMap { (it.lowNote..it.highNote).toList() }, "zones must tile without gaps or overlap")
            assertEquals(9, samples.size)
            program.keygroups.forEachIndexed { index, group ->
                val layer = group.layers.single()
                assertEquals(0, layer.velStart)
                assertEquals(127, layer.velEnd)
                assertEquals(notes[index].loopStartFrame, layer.loopStartFrame)
                assertContentEquals(notes[index].snip.samples, samples.getValue(layer.sampleName).samples)
            }
            assertEquals(low + 1, Prism.midiFor(voice, spec.patch.macros.getValue("TUNE")))
        }
    }

    @Test
    fun `unsupported source recipes malformed zones and cancelled work leave no partial package`() {
        val patch = authored()
        val unsupported = listOf(
            patch.copy(motion = null),
            patch.copy(macros = patch.macros + ("HOLD" to .8f)),
            patch.copy(macros = patch.macros + ("HOLD" to .8f), motion = patch.motion!!.copy(repeat = false)),
        )
        for (source in unsupported) {
            assertFalse(PrismPadMaker.supports(source))
            val refused = assertFailsWith<IllegalArgumentException> { PrismPadMaker.Spec(source) }
            assertTrue(refused.message.orEmpty().contains("PRISM"), "a source refusal must identify the PRISM instrument contract")
        }
        val spec = PrismPadMaker.Spec(patch)
        assertFailsWith<CancellationException> { PrismPadMaker.renderZone(spec, Prism.rootMidi(patch.voice)) { true } }
        for (midi in listOf(Prism.rootMidi(patch.voice) - 1, Prism.rootMidi(patch.voice) + Prism.TUNE_SEMITONES + 1)) {
            assertFailsWith<IllegalArgumentException> { PrismPadMaker.renderZone(spec, midi) }
        }
        val valid = diagnosticNotes(9)
        val invalid = listOf(
            valid.take(8),
            valid.toMutableList().also { it[0] = valid[0].copy(loopStartFrame = 0) },
            valid.toMutableList().also { it[0] = valid[0].copy(loopStartFrame = 63) },
            valid.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(64), 2, 44_100), 16) },
            valid.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(64), 1, 48_000), 16) },
            valid.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(64) { Float.NaN }, 1, 44_100), 16) },
            valid.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(64) { 1.01f }, 1, 44_100), 16) },
        )
        for (notes in invalid) assertFailsWith<IllegalArgumentException> {
            PrismPadMaker.export("BAD PRISM", spec, notes, root)
        }
        for (name in listOf("../escape", "/tmp/escape", "x".repeat(OneNote.MAX_NAME_LENGTH + 1))) {
            assertFailsWith<IllegalArgumentException> { PrismPadMaker.export(name, spec, valid, root) }
        }
        assertTrue(root.listFiles()!!.isEmpty(), "validation and cancellation must finish before a package is written")
        PrismPadMaker.export("KEPT PRISM", spec, valid, root)
        val before = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }
        assertFailsWith<DestinationExists> { PrismPadMaker.export("KEPT PRISM", spec, valid, root) }
        before.forEach { (path, bytes) -> assertContentEquals(bytes, File(root, path).readBytes()) }
    }

    @Test
    fun `delivery recall refuses unknown versions and invalid release instead of changing the source`() {
        val spec = PrismPadMaker.Spec(authored(), .37f)
        val fields = Json.parse(spec.toJsonText()).obj()
        for (field in listOf("version", "heldModel")) {
            assertFailsWith<JsonException> {
                PrismPadMaker.Spec.fromJsonText(Json.write(JsonValue.Obj(fields + (field to JsonValue.Num(99.0)))))
            }
        }
        for (seconds in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 0f, 2f)) {
            assertFailsWith<IllegalArgumentException> { PrismPadMaker.Spec(spec.patch, seconds) }
        }
        for (fraction in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f, 0f, .5f, 1f, 2f)) {
            val stepped = PrismPadMaker.spec(spec.patch, fraction)
            assertTrue(stepped.releaseSeconds.isFinite())
            assertTrue(stepped.releaseSeconds in PrismPadMaker.RELEASE_MIN_SECONDS..PrismPadMaker.RELEASE_MAX_SECONDS)
            assertEquals(spec.patch, stepped.patch)
        }
        assertEquals("0.30 s", PrismPadMaker.secondsLabel(.3f))
    }

    private fun text(parent: Element, tag: String): String = parent.getElementsByTagName(tag).item(0).textContent.trim()

    private fun render(engine: InstrumentEngine, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        engine.render(out, frames)
        return out
    }
}
