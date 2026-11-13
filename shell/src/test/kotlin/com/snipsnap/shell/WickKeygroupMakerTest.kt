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
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import com.snipsnap.synth.WickPresets
import com.snipsnap.synth.WickVoice
import java.io.File
import java.util.concurrent.CancellationException
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

class WickKeygroupMakerTest {
    private fun source() = WickPresets.forVoice(WickVoice.PLUCK).first().copy(midi = 60)
    private fun fixture(spec: WickKeygroupMaker.Spec) = WickKeygroupMaker.recipes(spec).mapValues { (cell, patch) ->
        WickKeygroupMaker.Note(patch, Snip(FloatArray(512) { index ->
            val strength = .01f * (cell.touch + 1) + .0001f * cell.midi
            if (index % 2 == 0) strength else -strength
        }, 1, 44_100))
    }

    @Test
    fun `all finite factory recipes and legacy bodies retain complete source state through planning`() {
        val all = WickPresets.all()
        assertEquals(36, all.size)
        assertEquals(6, all.map { it.voice }.toSet().size)
        assertTrue(WickVoice.entries.all { WickPresets.forVoice(it).size == 6 })
        val finite = all.filterNot { Wick.isLoop(Wick.settled(it.macros, it.voice).getValue("HOLD")) }
        assertEquals(WickVoice.entries.toSet(), finite.map { it.voice }.toSet())
        val rattle = WickPresets.forVoice(WickVoice.RATTLE).first()
        val sources = finite + Wick.RattleSource.entries.map { rattle.copy(rattleSource = it) }
        for (patch in sources) {
            val before = patch.toJsonText()
            val spec = WickKeygroupMaker.Spec(patch)
            for ((cell, recipe) in WickKeygroupMaker.recipes(spec)) {
                assertEquals(patch.copy(midi = cell.midi,
                    velocity = patch.velocity * WickKeygroupMaker.TOUCHES[cell.touch]), recipe)
                assertEquals(recipe, WickPatch.fromJsonText(recipe.toJsonText()))
            }
            assertEquals(before, patch.toJsonText())
            assertTrue(patch.midi in WickKeygroupMaker.roots(spec))
        }
    }

    @Test
    fun `finite keygroups tile the native range and retain an off-grid authored root`() {
        val spec = WickKeygroupMaker.Spec(source().copy(midi = 55, velocity = .7f))
        val program = WickKeygroupMaker.assemble("Wick Keys", spec, fixture(spec)).program
        assertEquals((24..84).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertTrue(program.keygroups.any { it.rootNote == 55 })
        assertTrue(program.keygroups.all { zone -> (zone.lowNote..zone.highNote).all { abs(it - zone.rootNote) <= 1 } })
        assertTrue(program.keygroups.all { group -> group.layers.all { it.loopStartFrame == 0L } })
        assertTrue(program.explicitNoteOn)
        assertFalse(program.stackZones)
        assertFalse(program.voiceMode.mono)
    }

    @Test
    fun `native export preserves source bindings finite sampler metadata and velocity boundaries`() {
        val temp = createTempDirectory("wick-keygroups").toFile()
        try {
            val spec = WickKeygroupMaker.Spec(source(), releaseSeconds = .01f)
            val name = "Wick Keys"
            val destination = File(temp, "packet")
            val assembly = WickKeygroupMaker.writeNewPackage(name, spec, fixture(spec), destination)
            assertEquals(63, assembly.samples.size)
            assertEquals(assembly.program, WickKeygroupMaker.validate(name, destination, spec).program)
            val (sidecar, instrument) = InstrumentStore.list(destination).single()
            assertEquals(assembly.program, instrument.toProgram())
            val track = Mpc3Project.read(File(destination, "$name.xty"))
            val native = track.drumPrograms().single().getValue("instruments").arr()
            val legacy = File(destination, Mpc3TrackWriter.trackDataDirName(name) + "/$name.xpm").readText()
            assertFalse("<SliceLoop>1</SliceLoop>" in legacy)
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            for ((index, zone) in instrument.zones.withIndex()) {
                val layers = native[index].obj().getValue("layersv").arr().map { it.obj() }
                    .filter { it.getValue("sampleName").str().isNotEmpty() }
                assertEquals(listOf(85, 43, 0), layers.map { it.getValue("velocityStart").int() })
                for (layer in zone.layers) {
                    val file = File(sidecar.parentFile, layer.sample)
                    val sheet = assertNotNull(WavReader.readSmplStrict(file))
                    assertEquals(zone.rootNote, sheet.rootNote)
                    assertNull(sheet.loop)
                    val written = layers.single { it.getValue("sampleFile").str() == file.name }
                    assertEquals(0, written.getValue("sliceInfo").obj().getValue("LoopMode").int())
                }
                for ((midiVelocity, touch) in listOf(1 to 0, 42 to 0, 43 to 1, 84 to 1, 85 to 2, 127 to 2)) {
                    val hit = assertNotNull(KeyHit.resolve(instrument, zone.rootNote, midiVelocity / 127f) { samples[it]?.size?.toLong() })
                    assertEquals(zone.layers[touch].sample, hit.sampleFile)
                    assertFalse(hit.loops)
                    assertEquals(1.0, hit.pitchRatio)
                }
            }
            assertNull(KeyHit.resolve(instrument, 23, 1f) { samples[it]?.size?.toLong() })
            assertNull(KeyHit.resolve(instrument, 85, 1f) { samples[it]?.size?.toLong() })
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `real chord-tool note sets start independent reference voices across the bounded keyboard`() {
        val temp = createTempDirectory("wick-chords").toFile()
        try {
            val spec = WickKeygroupMaker.Spec(source())
            val packet = File(temp, "packet")
            WickKeygroupMaker.writeNewPackage("Wick Chords", spec, fixture(spec), packet)
            val (sidecar, instrument) = InstrumentStore.list(packet).single()
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            var checks = 0
            for (key in listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"))
                for (suffix in listOf("", "m")) for (chord in ChordStabs.stabs(KeySpec.parse(key + suffix))) {
                    val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, samples), 44_100)
                    assertTrue(chord.notes.all { player.noteOn(it, .65f) }, chord.name)
                    assertEquals(chord.notes.toSet(), player.activeNotes.toSet(), chord.name)
                    val audio = FloatArray(256 * 2)
                    player.render(audio, 256)
                    assertTrue(audio.any { it != 0f } && audio.all { it.isFinite() })
                    checks++
                }
            assertEquals(384, checks)
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `stale audio recipes and descriptors are refused before editable provenance is accepted`() {
        val temp = createTempDirectory("wick-bindings").toFile()
        try {
            val spec = WickKeygroupMaker.Spec(source(), lowMidi = 60, highMidi = 60)
            val packet = File(temp, "packet")
            WickKeygroupMaker.writeNewPackage("Wick Bound", spec, fixture(spec), packet)
            val data = File(packet, Mpc3TrackWriter.trackDataDirName("Wick Bound"))
            val metadata = File(data, "wick-source-recipes.json")
            val original = metadata.readText()
            val root = Json.parse(original).obj().toMutableMap()
            val rows = root.getValue("samples").arr().toMutableList()
            val first = rows.first().obj().toMutableMap()
            first["sha256"] = JsonValue.Str("0".repeat(64))
            rows[0] = JsonValue.Obj(first)
            root["samples"] = JsonValue.Arr(rows)
            metadata.writeText(Json.write(JsonValue.Obj(root)))
            assertFailsWith<IllegalArgumentException> { WickKeygroupMaker.validate("Wick Bound", packet, spec) }
            metadata.writeText(original)
            val badRelease = Json.parse(original).obj().toMutableMap()
            badRelease["releaseSeconds"] = JsonValue.Num(1.5000000001)
            metadata.writeText(Json.write(JsonValue.Obj(badRelease)))
            assertFailsWith<IllegalArgumentException> { WickKeygroupMaker.validate("Wick Bound", packet, spec) }
            metadata.writeText(original)
            File(packet, "Wick Bound.xty").appendBytes(byteArrayOf(0))
            assertFailsWith<IllegalArgumentException> { WickKeygroupMaker.validate("Wick Bound", packet, spec) }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `held malformed and partial requests refuse rather than silently rewriting their source`() {
        assertFailsWith<IllegalArgumentException> { WickKeygroupMaker.Spec(source().copy(macros = source().macros + ("HOLD" to 1f))) }
        assertFailsWith<IllegalArgumentException> { WickKeygroupMaker.Spec(source().copy(velocity = 0f)) }
        assertFailsWith<IllegalArgumentException> { WickKeygroupMaker.Spec(source(), lowMidi = 23) }
        val spec = WickKeygroupMaker.Spec(source(), lowMidi = 60, highMidi = 60)
        val notes = fixture(spec)
        assertFailsWith<IllegalArgumentException> { WickKeygroupMaker.assemble("Incomplete", spec, notes.entries.drop(1).associate { it.toPair() }) }
        val cell = notes.keys.first()
        val relabelled = notes + (cell to notes.getValue(cell).copy(recipe = source().copy(midi = 61)))
        assertFailsWith<IllegalArgumentException> { WickKeygroupMaker.assemble("Relabelled", spec, relabelled) }
        assertFailsWith<CancellationException> { WickKeygroupMaker.render(spec) { true } }
        val parent = createTempDirectory("wick-existing").toFile()
        try {
            val packet = File(parent, "packet").apply { mkdir() }
            val old = File(packet, "keep.txt").apply { writeText("existing packet") }
            assertFailsWith<IllegalArgumentException> { WickKeygroupMaker.writeNewPackage("Existing", spec, notes, packet) }
            assertEquals("existing packet", old.readText())
            assertEquals(listOf("packet"), parent.listFiles()!!.map { it.name })
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `one native C4 source retains all authored controls and exact direct excitation renders`() {
        val patch = source().copy(velocity = .8f)
        val before = patch.toJsonText()
        val spec = WickKeygroupMaker.Spec(patch, lowMidi = 60, highMidi = 60)
        val notes = WickKeygroupMaker.render(spec)
        assertEquals(3, notes.size)
        for ((cell, note) in notes) {
            val expected = patch.copy(velocity = patch.velocity * WickKeygroupMaker.TOUCHES[cell.touch])
            assertEquals(expected, note.recipe)
            assertContentEquals(expected.render().samples, note.snip.samples)
        }
        assertEquals(patch, notes.getValue(WickKeygroupMaker.Cell(60, 2)).recipe)
        assertEquals(before, patch.toJsonText())
    }
}
