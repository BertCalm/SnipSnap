package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.ChordStabs
import com.snipsnap.synth.Drawl
import com.snipsnap.synth.DrawlChords
import com.snipsnap.synth.DrawlPatch
import com.snipsnap.synth.DrawlPresets
import com.snipsnap.synth.DrawlVoice
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

/**
 * DRAWL's finite keys (round 4): each voice its root an octave either side, minor-third roots, three
 * touches of two takes, every sample the engine's own render bound to its recipe, through phone
 * reload and MPC 3 and MPC 2 read-back, and every chord-tool voicing played on real keys.
 */
class DrawlKeygroupMakerTest {
    private fun source(voice: DrawlVoice = DrawlVoice.LAP) = DrawlPresets.forVoice(voice).first()
    private fun fixture(spec: DrawlKeygroupMaker.Spec) = DrawlKeygroupMaker.recipes(spec).mapValues { (cell, patch) ->
        DrawlKeygroupMaker.Note(patch, Snip(FloatArray(512) { index ->
            val strength = .01f * (cell.touch + 1) + .0001f * cell.midi + .003f * cell.take
            if (index % 2 == 0) strength else -strength
        }, 1, 44_100))
    }

    @Test
    fun `every single-note factory recipe keeps its saved source at each root touch and take`() {
        val all = DrawlPresets.all().filter { it.strum == null }
        assertEquals(DrawlVoice.entries.toSet(), all.map { it.voice }.toSet())
        for (patch in all) {
            val before = patch.toJsonText()
            val spec = DrawlKeygroupMaker.Spec(patch)
            val root = Drawl.rootMidi(patch.voice)
            assertEquals((root - 12..root + 12 step 3).toList(), DrawlKeygroupMaker.roots(spec))
            val recipes = DrawlKeygroupMaker.recipes(spec)
            assertEquals(9 * DrawlKeygroupMaker.LAYERS, recipes.size)
            for ((cell, recipe) in recipes) {
                assertEquals(cell.midi, Drawl.midiFor(recipe.voice, recipe.macros.getValue("TUNE")))
                assertEquals(patch.copy(macros = patch.macros + ("TUNE" to DrawlChords.tuneFor(patch.voice, cell.midi)) +
                    ("HOLD" to spec.keyHold), velocity = DrawlKeygroupMaker.TOUCHES[cell.touch],
                    take = DrawlKeygroupMaker.TAKES[cell.take]), recipe)
                assertEquals(recipe, DrawlPatch.fromJsonText(recipe.toJsonText()))
            }
            assertEquals(before, patch.toJsonText())
        }
        assertEquals(listOf(.3f, .75f, 1f), DrawlKeygroupMaker.TOUCHES)
        assertEquals(listOf(0, 1), DrawlKeygroupMaker.TAKES)
        // HOLD is capped on keys; a recipe saved shorter keeps its own.
        val long = DrawlKeygroupMaker.Spec(source().copy(macros = source().macros + ("HOLD" to 1f)))
        assertTrue(DrawlKeygroupMaker.recipes(long).values.all { it.macros["HOLD"] == DrawlKeygroupMaker.KEY_HOLD })
        val short = DrawlKeygroupMaker.Spec(source().copy(macros = source().macros + ("HOLD" to .2f)))
        assertTrue(DrawlKeygroupMaker.recipes(short).values.all { it.macros["HOLD"] == .2f })
        // At medium touch, take 0 and a HOLD under the cap, the root's key is the saved recipe itself.
        val home = DrawlKeygroupMaker.Spec(source())
        assertEquals(source(), DrawlKeygroupMaker.recipes(home).getValue(DrawlKeygroupMaker.Cell(home.authoredMidi, 1, 0)))
    }

    @Test
    fun `finite keygroups tile each voice's two octaves within a semitone in the cycle layout and retain an off-grid root`() {
        val off = source().copy(macros = source().macros + ("TUNE" to DrawlChords.tuneFor(DrawlVoice.LAP, 63)), velocity = .5f)
        val spec = DrawlKeygroupMaker.Spec(off)
        val program = DrawlKeygroupMaker.assemble("Drawl Keys", spec, fixture(spec)).program
        assertEquals((50..74).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertTrue(program.keygroups.any { it.rootNote == 63 })
        assertTrue(program.keygroups.all { zone -> (zone.lowNote..zone.highNote).all { abs(it - zone.rootNote) <= 1 } })
        for (group in program.keygroups) {
            assertEquals(6, group.layers.size)
            assertTrue(group.cycleLayers && group.mpcVelocityCycle && group.mpcCycle)
            assertEquals(6, group.mpcLayers.size, "MPC export keeps both takes of all three touches")
            assertEquals(listOf(0..42, 0..42, 43..84, 43..84, 85..127, 85..127), group.layers.map { it.velStart..it.velEnd })
            assertTrue(group.layers.all { it.loopStartFrame == 0L })
            assertEquals((1..3).flatMap { t -> (1..2).map { r -> "Drawl_${group.rootNote}_T${t}_R$r" } },
                group.layers.map { it.sampleName })
        }
        assertEquals(.6f, program.volumeRelease)
        assertTrue(program.explicitNoteOn)
        assertFalse(program.stackZones)
        assertFalse(program.voiceMode.mono)
    }

    @Test
    fun `package keeps three touches of two takes through phone reload and MPC 3 and MPC 2 read-back`() {
        val temp = createTempDirectory("drawl-keygroups").toFile()
        try {
            val spec = DrawlKeygroupMaker.Spec(source(), releaseSeconds = .01f)
            val name = "Drawl Keys"
            val destination = File(temp, "packet")
            val assembly = DrawlKeygroupMaker.writeNewPackage(name, spec, fixture(spec), destination)
            assertEquals(54, assembly.samples.size)
            assertEquals(assembly.program, DrawlKeygroupMaker.validate(name, destination, spec))
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
            assertNull(KeyHit.resolve(instrument, 49, 1f) { samples[it]?.size?.toLong() })
            assertNull(KeyHit.resolve(instrument, 75, 1f) { samples[it]?.size?.toLong() })
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `real key samples are the engine's own takes and play every chord-tool note set`() {
        val temp = createTempDirectory("drawl-chords").toFile()
        try {
            val patch = source(DrawlVoice.FLATTOP)
            val spec = DrawlKeygroupMaker.Spec(patch)
            val notes = DrawlKeygroupMaker.render(spec)
            val root = Drawl.rootMidi(DrawlVoice.FLATTOP)
            // The samples are the engine's renders, untouched: take 1 is the saved take, take 2 another pass of the player.
            for (midi in listOf(root - 12, root, root + 12)) for (touch in 0..2) {
                val first = notes.getValue(DrawlKeygroupMaker.Cell(midi, touch, 0))
                val second = notes.getValue(DrawlKeygroupMaker.Cell(midi, touch, 1))
                assertEquals(0, first.recipe.take)
                assertEquals(first.recipe.copy(take = 1), second.recipe)
                assertContentEquals(first.recipe.render().samples, first.snip.samples)
                assertContentEquals(second.recipe.render().samples, second.snip.samples)
                assertFalse(first.snip.samples.contentEquals(second.snip.samples), "two takes are two passes")
                assertTrue(first.snip.peak() <= .99f && second.snip.peak() <= .99f)
            }
            assertTrue(notes.values.all { it.snip.frameCount <= Drawl.seconds(DrawlVoice.STEEL, DrawlKeygroupMaker.KEY_HOLD) * 44_100 + 1 },
                "keys ring out within the key HOLD")
            val packet = File(temp, "packet")
            DrawlKeygroupMaker.writeNewPackage("Drawl Chords", spec, notes, packet)
            val (sidecar, instrument) = InstrumentStore.list(packet).single()
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            val keys = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
            val chords = keys.flatMap { key -> listOf("", "m").flatMap { ChordStabs.stabs(KeySpec.parse(key + it)) } } +
                (0..11).flatMap { tonic -> ChordStabs.Mode.entries.flatMap { ChordStabs.modalStabs(tonic, it) } } +
                keys.flatMap { ChordStabs.borrowedStabs(KeySpec.parse(it)) }
            assertEquals(384 + 384 + 192, chords.size)
            for (chord in chords) {
                val voiced = DrawlChords.notes(DrawlVoice.FLATTOP, chord)
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
        val temp = createTempDirectory("drawl-bindings").toFile()
        try {
            val root = Drawl.rootMidi(DrawlVoice.LAP)
            val spec = DrawlKeygroupMaker.Spec(source(), lowMidi = root, highMidi = root)
            val packet = File(temp, "packet")
            DrawlKeygroupMaker.writeNewPackage("Drawl Bound", spec, fixture(spec), packet)
            val data = File(packet, Mpc3TrackWriter.trackDataDirName("Drawl Bound"))
            val metadata = File(data, "drawl-source-recipes.json")
            val original = metadata.readText()
            fun refusedWith(edit: (MutableMap<String, JsonValue>) -> Unit) {
                val doc = Json.parse(original).obj().toMutableMap()
                edit(doc)
                metadata.writeText(Json.write(JsonValue.Obj(doc)))
                assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.validate("Drawl Bound", packet, spec) }
                metadata.writeText(original)
            }
            fun firstRow(doc: MutableMap<String, JsonValue>, edit: (MutableMap<String, JsonValue>) -> Unit) {
                val rows = doc.getValue("samples").arr().toMutableList()
                rows[0] = JsonValue.Obj(rows.first().obj().toMutableMap().also(edit))
                doc["samples"] = JsonValue.Arr(rows)
            }
            refusedWith { doc -> firstRow(doc) { it["sha256"] = JsonValue.Str("0".repeat(64)) } }
            refusedWith { doc -> firstRow(doc) { it["take"] = JsonValue.Num(2.0) } }
            refusedWith { it["releaseSeconds"] = JsonValue.Num(1.5000000001) }
            refusedWith { it["keyHold"] = JsonValue.Num(1.0) }
            refusedWith { it["takes"] = JsonValue.Arr(listOf(JsonValue.Num(0.0), JsonValue.Num(2.0))) }
            assertEquals(6, DrawlKeygroupMaker.validate("Drawl Bound", packet, spec).keygroups.single().layers.size)
            File(packet, "Drawl Bound.xty").appendBytes(byteArrayOf(0))
            assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.validate("Drawl Bound", packet, spec) }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `strummed malformed and partial requests refuse rather than silently rewriting their source`() {
        val root = Drawl.rootMidi(DrawlVoice.LAP)
        val strum = DrawlPresets.forVoice(DrawlVoice.LAP).single { it.strum != null }
        assertFalse(DrawlPadMaker.supports(strum))
        assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.Spec(strum) }
        assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.Spec(source(), lowMidi = root - 13) }
        assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.Spec(source(), highMidi = root + 13) }
        assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.Spec(source(), lowMidi = root + 2, highMidi = root + 12) }
        assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.Spec(source(), releaseSeconds = 2f) }
        val spec = DrawlKeygroupMaker.Spec(source(), lowMidi = root, highMidi = root)
        val notes = fixture(spec)
        assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.assemble("Incomplete", spec, notes.entries.drop(1).associate { it.toPair() }) }
        val cell = notes.keys.first()
        val relabelled = notes + (cell to notes.getValue(cell).copy(recipe = source().copy(take = 3)))
        assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.assemble("Relabelled", spec, relabelled) }
        val loud = notes + (cell to notes.getValue(cell).copy(snip = Snip(FloatArray(8) { 1.5f }, 1, 44_100)))
        assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.assemble("Loud", spec, loud) }
        assertFailsWith<CancellationException> { DrawlKeygroupMaker.render(spec) { true } }
        val parent = createTempDirectory("drawl-existing").toFile()
        try {
            val packet = File(parent, "packet").apply { mkdir() }
            val old = File(packet, "keep.txt").apply { writeText("existing packet") }
            assertFailsWith<IllegalArgumentException> { DrawlKeygroupMaker.writeNewPackage("Existing", spec, notes, packet) }
            assertEquals("existing packet", old.readText())
            assertEquals(listOf("packet"), parent.listFiles()!!.map { it.name })
        } finally { parent.deleteRecursively() }
    }
}
