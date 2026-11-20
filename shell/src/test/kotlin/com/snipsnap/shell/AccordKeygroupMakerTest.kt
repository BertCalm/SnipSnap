package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Accord
import com.snipsnap.synth.AccordChords
import com.snipsnap.synth.AccordPatch
import com.snipsnap.synth.AccordPresets
import com.snipsnap.synth.AccordVoice
import com.snipsnap.synth.ChordStabs
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

class AccordKeygroupMakerTest {
    private fun source(voice: AccordVoice = AccordVoice.BRUSH) = AccordPresets.forVoice(voice).first()
    private fun fixture(spec: AccordKeygroupMaker.Spec) = AccordKeygroupMaker.recipes(spec).mapValues { (cell, patch) ->
        AccordKeygroupMaker.Note(patch, Snip(FloatArray(512) { index ->
            val strength = .01f * (cell.touch + 1) + .0001f * cell.midi + .003f * cell.take
            if (index % 2 == 0) strength else -strength
        }, 1, 44_100))
    }

    @Test
    fun `every factory recipe keeps its complete saved source at each root touch and take`() {
        val all = AccordPresets.all()
        assertEquals(AccordVoice.entries.toSet(), all.map { it.voice }.toSet())
        for (patch in all) {
            val before = patch.toJsonText()
            val spec = AccordKeygroupMaker.Spec(patch)
            assertEquals((36..72 step 3).toList(), AccordKeygroupMaker.roots(spec))
            val recipes = AccordKeygroupMaker.recipes(spec)
            assertEquals(13 * AccordKeygroupMaker.LAYERS, recipes.size)
            for ((cell, recipe) in recipes) {
                assertEquals(patch.copy(midi = cell.midi, velocity = AccordKeygroupMaker.TOUCHES[cell.touch],
                    sourceSeconds = AccordKeygroupMaker.KEY_SECONDS), recipe)
                assertEquals(recipe, AccordPatch.fromJsonText(recipe.toJsonText()))
            }
            assertEquals(before, patch.toJsonText())
        }
        assertEquals(listOf(.3f, .75f, 1f), AccordKeygroupMaker.TOUCHES)
        assertEquals(listOf(0, 2), AccordKeygroupMaker.TAKES)
        // A recipe saved shorter than the key length keeps its own LENGTH.
        val short = AccordKeygroupMaker.Spec(source().copy(sourceSeconds = 1.5))
        assertTrue(AccordKeygroupMaker.recipes(short).values.all { it.sourceSeconds == 1.5 })
    }

    @Test
    fun `finite keygroups tile C2 to C5 within a semitone in the cycle layout and retain an off-grid root`() {
        val spec = AccordKeygroupMaker.Spec(source().copy(midi = 55, velocity = .5f))
        val program = AccordKeygroupMaker.assemble("Accord Keys", spec, fixture(spec)).program
        assertEquals((36..72).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertTrue(program.keygroups.any { it.rootNote == 55 })
        assertTrue(program.keygroups.all { zone -> (zone.lowNote..zone.highNote).all { abs(it - zone.rootNote) <= 1 } })
        for (group in program.keygroups) {
            assertEquals(6, group.layers.size)
            assertTrue(group.cycleLayers && group.mpcVelocityCycle && group.mpcCycle)
            assertEquals(6, group.mpcLayers.size, "MPC export keeps both takes of all three touches")
            assertEquals(listOf(0..42, 0..42, 43..84, 43..84, 85..127, 85..127), group.layers.map { it.velStart..it.velEnd })
            assertTrue(group.layers.all { it.loopStartFrame == 0L })
            assertEquals((1..3).flatMap { t -> (1..2).map { r -> "Accord_${group.rootNote}_T${t}_R$r" } },
                group.layers.map { it.sampleName })
        }
        assertEquals(.6f, program.volumeRelease)
        assertTrue(program.explicitNoteOn)
        assertFalse(program.stackZones)
        assertFalse(program.voiceMode.mono)
    }

    @Test
    fun `package keeps three touches of two takes through phone reload and MPC 3 and MPC 2 read-back`() {
        val temp = createTempDirectory("accord-keygroups").toFile()
        try {
            val spec = AccordKeygroupMaker.Spec(source(), releaseSeconds = .01f)
            val name = "Accord Keys"
            val destination = File(temp, "packet")
            val assembly = AccordKeygroupMaker.writeNewPackage(name, spec, fixture(spec), destination)
            assertEquals(78, assembly.samples.size)
            assertEquals(assembly.program, AccordKeygroupMaker.validate(name, destination, spec))
            val (sidecar, instrument) = InstrumentStore.list(destination).single()
            assertEquals(assembly.program, instrument.toProgram())
            val track = Mpc3Project.read(File(destination, "$name.xty"))
            val native = track.drumPrograms().single().getValue("instruments").arr()
            val legacyFile = File(destination, Mpc3TrackWriter.trackDataDirName(name) + "/$name.xpm")
            assertFalse("<SliceLoop>1</SliceLoop>" in legacyFile.readText())
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(legacyFile).getElementsByTagName("Instrument")
            assertEquals(instrument.zones.size, xml.length)
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            for ((index, zone) in instrument.zones.withIndex()) {
                val group = assembly.program.keygroups[index]
                // MPC 3: matching-window Cycle, hard window first, each window's two takes in dealing order.
                val n = native[index].obj()
                assertEquals(0, n.getValue("zonePlayTime").int(), "native matching-window Cycle")
                val layers = n.getValue("layersv").arr().map { it.obj() }.filter { it.getValue("sampleName").str().isNotEmpty() }
                val expectedNative = group.layers.chunked(2).asReversed().flatten()
                assertEquals(expectedNative.map { it.sampleName }, layers.map { it.getValue("sampleName").str() })
                assertEquals(expectedNative.map { it.velStart to it.velEnd },
                    layers.map { it.getValue("velocityStart").int() to it.getValue("velocityEnd").int() })
                // MPC 2 program: the same six layers and windows, ZonePlay Cycle (the HALOBAR layout).
                val instrumentXml = xml.item(index) as Element
                assertEquals("0", instrumentXml.getElementsByTagName("ZonePlay").item(0).textContent)
                val all = instrumentXml.getElementsByTagName("Layer")
                val filled = (0 until all.length).map { all.item(it) as Element }
                    .filter { it.getElementsByTagName("Active").item(0).textContent == "True" }
                assertEquals(group.layers.map { it.sampleName }, filled.map { it.getElementsByTagName("SampleName").item(0).textContent })
                for ((slot, layer) in filled.withIndex()) {
                    assertEquals(group.layers[slot].velStart.toString(), layer.getElementsByTagName("VelStart").item(0).textContent)
                    assertEquals(group.layers[slot].velEnd.toString(), layer.getElementsByTagName("VelEnd").item(0).textContent)
                    assertEquals(group.rootNote.toString(), layer.getElementsByTagName("RootNote").item(0).textContent)
                }
                for (layer in zone.layers) {
                    val file = File(sidecar.parentFile, layer.sample)
                    val sheet = assertNotNull(WavReader.readSmplStrict(file))
                    assertEquals(zone.rootNote, sheet.rootNote)
                    assertNull(sheet.loop)
                    val written = layers.single { it.getValue("sampleFile").str() == file.name }
                    assertEquals(0, written.getValue("sliceInfo").obj().getValue("LoopMode").int())
                }
                // Phone: each touch window deals its own two takes in turn, and a touch never borrows another's.
                for ((midiVelocity, touch) in listOf(1 to 0, 42 to 0, 43 to 1, 84 to 1, 85 to 2, 127 to 2)) {
                    val cursor = KeyHit.CycleCursor()
                    val hits = List(3) {
                        KeyHit.resolveAll(instrument, zone.rootNote, midiVelocity / 127f, cursor) { samples[it]?.size?.toLong() }.single().hit
                    }
                    assertEquals(listOf(touch * 2, touch * 2 + 1, touch * 2).map { zone.layers[it].sample }, hits.map { it.sampleFile })
                    assertTrue(hits.none { it.loops })
                    assertTrue(hits.all { it.pitchRatio == 1.0 })
                }
            }
            assertNull(KeyHit.resolve(instrument, 35, 1f) { samples[it]?.size?.toLong() })
            assertNull(KeyHit.resolve(instrument, 73, 1f) { samples[it]?.size?.toLong() })
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `real key samples are the engine's own takes and play every chord-tool note set`() {
        val temp = createTempDirectory("accord-chords").toFile()
        try {
            val patch = source(AccordVoice.TINE)
            val spec = AccordKeygroupMaker.Spec(patch)
            val notes = AccordKeygroupMaker.render(spec)
            // The samples are the engine's takes, untouched: take 1 is the plain render, take 2 another strike of it.
            for (midi in listOf(36, 48, 72)) for (touch in 0..2) {
                val recipe = patch.copy(midi = midi, velocity = AccordKeygroupMaker.TOUCHES[touch], sourceSeconds = 4.0)
                val first = notes.getValue(AccordKeygroupMaker.Cell(midi, touch, 0))
                val second = notes.getValue(AccordKeygroupMaker.Cell(midi, touch, 1))
                assertEquals(recipe, first.recipe)
                assertContentEquals(recipe.render().samples, first.snip.samples)
                assertContentEquals(Accord.renderTake(recipe, 2).samples, second.snip.samples)
                assertFalse(first.snip.samples.contentEquals(second.snip.samples), "two takes are two strikes")
                assertTrue(first.snip.peak() <= .851f && second.snip.peak() <= .851f)
            }
            assertTrue(notes.values.all { it.snip.frameCount < 8 * 44_100 }, "keys strike for four seconds, then ring out")
            val packet = File(temp, "packet")
            AccordKeygroupMaker.writeNewPackage("Accord Chords", spec, notes, packet)
            val (sidecar, instrument) = InstrumentStore.list(packet).single()
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            val keys = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
            val chords = keys.flatMap { key -> listOf("", "m").flatMap { ChordStabs.stabs(KeySpec.parse(key + it)) } } +
                (0..11).flatMap { tonic -> ChordStabs.Mode.entries.flatMap { ChordStabs.modalStabs(tonic, it) } } +
                keys.flatMap { ChordStabs.borrowedStabs(KeySpec.parse(it)) }
            assertEquals(384 + 384 + 192, chords.size)
            for (chord in chords) {
                val voiced = AccordChords.notes(chord)
                val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, samples), 44_100)
                assertTrue(voiced.all { player.noteOn(it, .65f) }, chord.name)
                assertEquals(voiced.toSet(), player.activeNotes.toSet(), chord.name)
                val audio = FloatArray(1024 * 2)
                player.render(audio, 1024)
                assertTrue(audio.any { it != 0f } && audio.all { it.isFinite() }, chord.name)
                // Key-up is the HOLD policy: the finite notes fade over the release and stop.
                voiced.forEach { player.noteOff(it) }
                val fade = FloatArray(44_100 * 2)
                player.render(fade, 44_100)
                assertTrue(player.activeNotes.isEmpty(), "${chord.name} released")
            }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `stale audio recipes takes and descriptors are refused before editable provenance is accepted`() {
        val temp = createTempDirectory("accord-bindings").toFile()
        try {
            val spec = AccordKeygroupMaker.Spec(source(), lowMidi = 48, highMidi = 48)
            val packet = File(temp, "packet")
            AccordKeygroupMaker.writeNewPackage("Accord Bound", spec, fixture(spec), packet)
            val data = File(packet, Mpc3TrackWriter.trackDataDirName("Accord Bound"))
            val metadata = File(data, "accord-source-recipes.json")
            val original = metadata.readText()
            fun refusedWith(edit: (MutableMap<String, JsonValue>) -> Unit) {
                val root = Json.parse(original).obj().toMutableMap()
                edit(root)
                metadata.writeText(Json.write(JsonValue.Obj(root)))
                assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.validate("Accord Bound", packet, spec) }
                metadata.writeText(original)
            }
            fun firstRow(root: MutableMap<String, JsonValue>, edit: (MutableMap<String, JsonValue>) -> Unit) {
                val rows = root.getValue("samples").arr().toMutableList()
                rows[0] = JsonValue.Obj(rows.first().obj().toMutableMap().also(edit))
                root["samples"] = JsonValue.Arr(rows)
            }
            refusedWith { root -> firstRow(root) { it["sha256"] = JsonValue.Str("0".repeat(64)) } }
            refusedWith { root -> firstRow(root) { it["take"] = JsonValue.Num(1.0) } }
            refusedWith { it["releaseSeconds"] = JsonValue.Num(1.5000000001) }
            refusedWith { it["keySeconds"] = JsonValue.Num(8.0) }
            refusedWith { it["takes"] = JsonValue.Arr(listOf(JsonValue.Num(0.0), JsonValue.Num(1.0))) }
            assertEquals(6, AccordKeygroupMaker.validate("Accord Bound", packet, spec).keygroups.single().layers.size)
            File(packet, "Accord Bound.xty").appendBytes(byteArrayOf(0))
            assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.validate("Accord Bound", packet, spec) }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `held malformed and partial requests refuse rather than silently rewriting their source`() {
        // HOLD is refused where it is saved: no ACCORD recipe can ask these keys for a loop.
        assertFailsWith<IllegalArgumentException> { source().copy(macros = source().macros + ("HOLD" to 1f)) }
        assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.Spec(source(), lowMidi = 35) }
        assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.Spec(source(), highMidi = 73) }
        assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.Spec(source(), lowMidi = 60, highMidi = 72) }
        assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.Spec(source(), releaseSeconds = 2f) }
        val spec = AccordKeygroupMaker.Spec(source(), lowMidi = 48, highMidi = 48)
        val notes = fixture(spec)
        assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.assemble("Incomplete", spec, notes.entries.drop(1).associate { it.toPair() }) }
        val cell = notes.keys.first()
        val relabelled = notes + (cell to notes.getValue(cell).copy(recipe = source().copy(midi = 49)))
        assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.assemble("Relabelled", spec, relabelled) }
        val loud = notes + (cell to notes.getValue(cell).copy(snip = Snip(FloatArray(8) { 1.5f }, 1, 44_100)))
        assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.assemble("Loud", spec, loud) }
        assertFailsWith<CancellationException> { AccordKeygroupMaker.render(spec) { true } }
        val parent = createTempDirectory("accord-existing").toFile()
        try {
            val packet = File(parent, "packet").apply { mkdir() }
            val old = File(packet, "keep.txt").apply { writeText("existing packet") }
            assertFailsWith<IllegalArgumentException> { AccordKeygroupMaker.writeNewPackage("Existing", spec, notes, packet) }
            assertEquals("existing packet", old.readText())
            assertEquals(listOf("packet"), parent.listFiles()!!.map { it.name })
        } finally { parent.deleteRecursively() }
    }
}
