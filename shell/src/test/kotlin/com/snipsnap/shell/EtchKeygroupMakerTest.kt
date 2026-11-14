package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Etch
import com.snipsnap.synth.EtchPatch
import com.snipsnap.synth.EtchPresets
import com.snipsnap.synth.EtchVoice
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

/** ETCH's finite keys (round K2), on WICK's maker pattern: three touch layers per root, no loops, source-bound provenance. */
class EtchKeygroupMakerTest {
    private fun source() = EtchPresets.forVoice(EtchVoice.SAW).first().copy(midi = 60)
    private fun fixture(spec: EtchKeygroupMaker.Spec) = EtchKeygroupMaker.recipes(spec).mapValues { (cell, patch) ->
        EtchKeygroupMaker.Note(patch, Snip(FloatArray(512) { index ->
            val strength = .01f * (cell.touch + 1) + .0001f * cell.midi
            if (index % 2 == 0) strength else -strength
        }, 1, 44_100))
    }

    @Test
    fun `every finite factory recipe retains its complete source state through planning`() {
        val finite = EtchPresets.all().filterNot { Etch.isLoop(Etch.settled(it.voice, it.macros).getValue("HOLD")) }
        assertEquals(EtchVoice.entries.toSet(), finite.map { it.voice }.toSet())
        for (patch in finite) {
            val before = patch.toJsonText()
            val spec = EtchKeygroupMaker.Spec(patch)
            for ((cell, recipe) in EtchKeygroupMaker.recipes(spec)) {
                assertEquals(patch.copy(midi = cell.midi,
                    velocity = patch.velocity * EtchKeygroupMaker.TOUCHES[cell.touch]), recipe)
                assertEquals(recipe, EtchPatch.fromJsonText(recipe.toJsonText()))
            }
            assertEquals(before, patch.toJsonText())
            assertTrue(patch.midi in EtchKeygroupMaker.roots(spec))
        }
    }

    @Test
    fun `finite keygroups tile the native range and retain an off-grid authored root`() {
        val spec = EtchKeygroupMaker.Spec(source().copy(midi = 43, velocity = .7f))
        val program = EtchKeygroupMaker.assemble("Etch Keys", spec, fixture(spec)).program
        assertEquals((24..60).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertTrue(program.keygroups.any { it.rootNote == 43 })
        assertTrue(program.keygroups.all { zone -> (zone.lowNote..zone.highNote).all { abs(it - zone.rootNote) <= 1 } })
        assertTrue(program.keygroups.all { group -> group.layers.all { it.loopStartFrame == 0L } })
        assertTrue(program.explicitNoteOn)
        assertFalse(program.stackZones)
        assertFalse(program.voiceMode.mono)
    }

    @Test
    fun `native export preserves source bindings finite sampler metadata and velocity boundaries`() {
        val temp = createTempDirectory("etch-keygroups").toFile()
        try {
            val spec = EtchKeygroupMaker.Spec(source(), releaseSeconds = .01f)
            val name = "Etch Keys"
            val destination = File(temp, "packet")
            val assembly = EtchKeygroupMaker.writeNewPackage(name, spec, fixture(spec), destination)
            assertEquals(39, assembly.samples.size) // 13 minor-third roots from C1 to C4, three touches each
            assertEquals(assembly.program, EtchKeygroupMaker.validate(name, destination, spec).program)
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
            assertNull(KeyHit.resolve(instrument, 61, 1f) { samples[it]?.size?.toLong() })
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `chords inside the bass register start independent voices`() {
        val temp = createTempDirectory("etch-chords").toFile()
        try {
            val spec = EtchKeygroupMaker.Spec(source())
            val packet = File(temp, "packet")
            EtchKeygroupMaker.writeNewPackage("Etch Chords", spec, fixture(spec), packet)
            val (sidecar, instrument) = InstrumentStore.list(packet).single()
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            // The chord tool voices around G3 to F#4, above ETCH's C4 ceiling, so these are bass-register chords by hand.
            val chords = listOf(listOf(24, 31, 36), listOf(36, 39, 43), listOf(41, 45, 48), listOf(48, 51, 55, 58), listOf(53, 57, 60))
            for (chord in chords) {
                val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, samples), 44_100)
                assertTrue(chord.all { player.noteOn(it, .65f) }, "$chord")
                assertEquals(chord.toSet(), player.activeNotes.toSet(), "$chord")
                val audio = FloatArray(256 * 2)
                player.render(audio, 256)
                assertTrue(audio.any { it != 0f } && audio.all { it.isFinite() })
            }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `stale audio recipes and descriptors are refused before editable provenance is accepted`() {
        val temp = createTempDirectory("etch-bindings").toFile()
        try {
            val spec = EtchKeygroupMaker.Spec(source(), lowMidi = 60, highMidi = 60)
            val packet = File(temp, "packet")
            EtchKeygroupMaker.writeNewPackage("Etch Bound", spec, fixture(spec), packet)
            val data = File(packet, Mpc3TrackWriter.trackDataDirName("Etch Bound"))
            val metadata = File(data, "etch-source-recipes.json")
            val original = metadata.readText()
            val root = Json.parse(original).obj().toMutableMap()
            val rows = root.getValue("samples").arr().toMutableList()
            val first = rows.first().obj().toMutableMap()
            first["sha256"] = JsonValue.Str("0".repeat(64))
            rows[0] = JsonValue.Obj(first)
            root["samples"] = JsonValue.Arr(rows)
            metadata.writeText(Json.write(JsonValue.Obj(root)))
            assertFailsWith<IllegalArgumentException> { EtchKeygroupMaker.validate("Etch Bound", packet, spec) }
            metadata.writeText(original)
            val badRelease = Json.parse(original).obj().toMutableMap()
            badRelease["releaseSeconds"] = JsonValue.Num(1.5000000001)
            metadata.writeText(Json.write(JsonValue.Obj(badRelease)))
            assertFailsWith<IllegalArgumentException> { EtchKeygroupMaker.validate("Etch Bound", packet, spec) }
            metadata.writeText(original)
            File(packet, "Etch Bound.xty").appendBytes(byteArrayOf(0))
            assertFailsWith<IllegalArgumentException> { EtchKeygroupMaker.validate("Etch Bound", packet, spec) }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `held malformed and partial requests refuse rather than silently rewriting their source`() {
        assertFailsWith<IllegalArgumentException> { EtchKeygroupMaker.Spec(source().copy(macros = source().macros + ("HOLD" to 1f))) }
        assertFailsWith<IllegalArgumentException> { EtchKeygroupMaker.Spec(source().copy(velocity = 0f)) }
        assertFailsWith<IllegalArgumentException> { EtchKeygroupMaker.Spec(source(), lowMidi = 23) }
        val spec = EtchKeygroupMaker.Spec(source(), lowMidi = 60, highMidi = 60)
        val notes = fixture(spec)
        assertFailsWith<IllegalArgumentException> { EtchKeygroupMaker.assemble("Incomplete", spec, notes.entries.drop(1).associate { it.toPair() }) }
        val cell = notes.keys.first()
        val relabelled = notes + (cell to notes.getValue(cell).copy(recipe = source().copy(midi = 59)))
        assertFailsWith<IllegalArgumentException> { EtchKeygroupMaker.assemble("Relabelled", spec, relabelled) }
        assertFailsWith<CancellationException> { EtchKeygroupMaker.render(spec) { true } }
        val parent = createTempDirectory("etch-existing").toFile()
        try {
            val packet = File(parent, "packet").apply { mkdir() }
            val old = File(packet, "keep.txt").apply { writeText("existing packet") }
            assertFailsWith<IllegalArgumentException> { EtchKeygroupMaker.writeNewPackage("Existing", spec, notes, packet) }
            assertEquals("existing packet", old.readText())
            assertEquals(listOf("packet"), parent.listFiles()!!.map { it.name })
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `one C4 source retains all authored controls and exact direct excitation renders`() {
        val patch = source().copy(velocity = .8f)
        val before = patch.toJsonText()
        val spec = EtchKeygroupMaker.Spec(patch, lowMidi = 60, highMidi = 60)
        val notes = EtchKeygroupMaker.render(spec)
        assertEquals(3, notes.size)
        for ((cell, note) in notes) {
            val expected = patch.copy(velocity = patch.velocity * EtchKeygroupMaker.TOUCHES[cell.touch])
            assertEquals(expected, note.recipe)
            assertContentEquals(expected.render().samples, note.snip.samples)
        }
        assertEquals(patch, notes.getValue(EtchKeygroupMaker.Cell(60, 2)).recipe)
        assertEquals(before, patch.toJsonText())
    }
}
