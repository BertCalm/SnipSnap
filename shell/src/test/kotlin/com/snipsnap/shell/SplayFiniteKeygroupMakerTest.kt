package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.ChordStabs
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Splay
import com.snipsnap.synth.SplayPatch
import com.snipsnap.synth.SplayFactoryPresets
import com.snipsnap.synth.SplayAcceptedBank
import com.snipsnap.synth.SplayVoice
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplayFiniteKeygroupMakerTest {
    private fun source(voice: SplayVoice = SplayVoice.TONGUE) = SplayFactoryPresets.forVoice(voice)
        .first { ((it.macros["HOLD"] ?: 0f) == 0f && it.model in 1..2) }
    private fun fixture(spec: SplayFiniteKeygroupMaker.Spec) = SplayFiniteKeygroupMaker.recipes(spec).mapValues { (cell, patch) ->
        SplayFiniteKeygroupMaker.Note(patch, Snip(FloatArray(512) { index ->
            val strength = .01f * (cell.touch + 1) + .0001f * cell.midi
            if (index % 2 == 0) strength else -strength
        }, 1, 44_100))
    }
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()
    }

    @Test fun `planning preserves five one voice factories and exact saved models at absolute MIDI roots`() {
        val factories = SplayFactoryPresets.all()
        assertEquals(5, factories.size)
        assertEquals(SplayAcceptedBank.entries.map { it.patch }, SplayFiniteKeygroupMaker.factorySpecs().values.map { it.patch })
        val finite = factories.filter { it.model in 1..2 && (it.macros["HOLD"] ?: 0f) == 0f }
        assertEquals(SplayVoice.entries.toSet(), finite.map { it.voice }.toSet())
        val legacy = source().copy(model = 1, macros = emptyMap())
        for (patch in finite + legacy) {
            val edited = patch.copy(midi = 55, velocity = .73f, seed = -41)
            val original = edited.toJsonText()
            val root = edited.midi
            assertEquals(55, root)
            val spec = SplayFiniteKeygroupMaker.Spec(edited)
            assertTrue(root in SplayFiniteKeygroupMaker.roots(spec))
            val recipes = SplayFiniteKeygroupMaker.recipes(spec)
            assertEquals(54, recipes.size)
            for ((cell, recipe) in recipes) {
                assertEquals(cell.midi, recipe.midi)
                assertEquals(patch.model, recipe.model)
                assertEquals(-41, recipe.seed)
                assertEquals(edited.velocity * SplayFiniteKeygroupMaker.TOUCHES[cell.touch], recipe.velocity)
                assertEquals(recipe, SplayPatch.fromJsonText(recipe.toJsonText()))
                if (cell.midi == root) assertEquals(edited.copy(velocity = recipe.velocity), recipe)
                else assertEquals(edited.copy(midi = cell.midi, velocity = recipe.velocity), recipe)
            }
            assertEquals(edited, recipes.getValue(SplayFiniteKeygroupMaker.Cell(root, 2)))
            assertEquals(original, edited.toJsonText())
        }
        assertTrue(SplayFiniteKeygroupMaker.supports(legacy))
        assertFalse((legacy.macros["HOLD"] ?: 0f) > 0f)
    }

    @Test fun `native range and velocity windows form exact bounded polyphonic finite programs`() {
        val spec = SplayFiniteKeygroupMaker.Spec(source().copy(midi = 55))
        val program = SplayFiniteKeygroupMaker.assemble("Splay Keys", spec, fixture(spec)).program
        assertEquals((36..84).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertTrue(program.keygroups.any { it.rootNote == 55 })
        assertTrue(program.keygroups.all { zone -> (zone.lowNote..zone.highNote).all { abs(it - zone.rootNote) <= 1 } })
        assertTrue(program.keygroups.all { group -> group.layers.all { it.loopStartFrame == 0L } })
        assertTrue(program.explicitNoteOn)
        assertFalse(program.stackZones)
        assertFalse(program.voiceMode.mono)
        assertEquals(.35f, program.volumeRelease)
    }

    @Test fun `package descriptors actual shelf samples and native touch boundaries agree`() {
        val temp = createTempDirectory("splay-finite-package").toFile()
        try {
            val spec = SplayFiniteKeygroupMaker.Spec(source(), releaseSeconds = .01f)
            val name = "Splay Keys"
            val assembly = SplayFiniteKeygroupMaker.exportFresh(name, spec, fixture(spec), temp)
            assertEquals(51, assembly.samples.size)
            assertEquals(assembly.program, SplayFiniteKeygroupMaker.validate(name, temp, spec).program)
            val (sidecar, instrument) = InstrumentStore.list(temp).single()
            assertEquals(temp.canonicalFile, sidecar.parentFile.canonicalFile)
            assertEquals(assembly.program, instrument.toProgram())
            val native = Mpc3Project.read(File(temp, "$name.xty")).drumPrograms().single().getValue("instruments").arr()
            val data = File(temp, Mpc3TrackWriter.trackDataDirName(name))
            val legacy = File(data, "$name.xpm").readText()
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
            assertNull(KeyHit.resolve(instrument, 35, 1f) { samples[it]?.size?.toLong() })
            assertNull(KeyHit.resolve(instrument, 85, 1f) { samples[it]?.size?.toLong() })
        } finally { temp.deleteRecursively() }
    }

    @Test fun `real chord tool sets trigger independent finite voices through the bounded program`() {
        val temp = createTempDirectory("splay-finite-chords").toFile()
        try {
            val spec = SplayFiniteKeygroupMaker.Spec(source())
            SplayFiniteKeygroupMaker.exportFresh("Splay Chords", spec, fixture(spec), temp)
            val (sidecar, instrument) = InstrumentStore.list(temp).single()
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

    @Test fun `every failed admission move preserves older instruments and unrelated bytes`() {
        val root = createTempDirectory("splay-finite-rollback").toFile()
        try {
            val spec = SplayFiniteKeygroupMaker.Spec(source(), lowMidi = 60, highMidi = 60)
            val notes = fixture(spec)
            SplayFiniteKeygroupMaker.exportFresh("Previous Splay", spec, notes, root)
            File(root, "unrelated.txt").writeText("keep")
            val old = snapshot(root)
            val name = "Next Splay"
            for (entry in listOf(Mpc3TrackWriter.trackDataDirName(name), "$name.xty", "$name${InstrumentStore.SUFFIX}")) {
                assertFailsWith<IOException> {
                    SplayFiniteKeygroupMaker.exportFresh(name, spec, notes, root,
                        InstrumentPackageAdmission.Hooks(moved = { if (it == entry) throw IOException("injected $entry") }))
                }
                assertEquals(old, snapshot(root))
                assertEquals("Previous Splay", InstrumentStore.list(root).single().second.name)
            }
            assertFailsWith<IllegalArgumentException> { SplayFiniteKeygroupMaker.exportFresh("Previous Splay", spec, notes, root) }
            assertEquals(old, snapshot(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun `unsupported incomplete and substituted source packages never enter the shelf`() {
        val root = createTempDirectory("splay-finite-corruption").toFile()
        try {
            val spec = SplayFiniteKeygroupMaker.Spec(source(), lowMidi = 60, highMidi = 60)
            val name = "Splay Bound"
            val dataName = Mpc3TrackWriter.trackDataDirName(name)
            fun metadata(packet: File, edit: (MutableMap<String, JsonValue>) -> Unit) {
                val f = File(packet, "$dataName/splay-finite-source-recipes.json")
                val o = Json.parse(f.readText()).obj().toMutableMap(); edit(o)
                f.writeText(Json.write(JsonValue.Obj(o)))
            }
            fun first(packet: File, edit: (MutableMap<String, JsonValue>) -> Unit) = metadata(packet) { o ->
                val rows = o.getValue("samples").arr().toMutableList()
                val row = rows.first().obj().toMutableMap(); edit(row); rows[0] = JsonValue.Obj(row)
                o["samples"] = JsonValue.Arr(rows)
            }
            val corruptions: Map<String, (File) -> Unit> = linkedMapOf(
                "unbound root provenance" to { p -> File(p, "source.json").writeText("{}") },
                "missing binding" to { p -> metadata(p) { it["samples"] = JsonValue.Arr(it.getValue("samples").arr().dropLast(1)) } },
                "duplicate binding" to { p -> metadata(p) { val rows = it.getValue("samples").arr(); it["samples"] = JsonValue.Arr(rows.dropLast(1) + rows.first()) } },
                "bad source hash" to { p -> metadata(p) { it["sourcePatchSha256"] = JsonValue.Str("0".repeat(64)) } },
                "bad recipe hash" to { p -> first(p) { it["recipeSha256"] = JsonValue.Str("0".repeat(64)) } },
                "unsafe path" to { p -> first(p) { it["sample"] = JsonValue.Str("../bad.wav") } },
                "unknown source" to { p -> metadata(p) { o -> val patch = o.getValue("sourcePatch").obj().toMutableMap(); patch["future"] = JsonValue.Num(1.0); o["sourcePatch"] = JsonValue.Obj(patch) } },
                "unknown recipe" to { p -> first(p) { o -> val patch = o.getValue("recipe").obj().toMutableMap(); patch["future"] = JsonValue.Num(1.0); o["recipe"] = JsonValue.Obj(patch) } },
                "different source" to { p -> metadata(p) { it["sourcePatch"] = source().copy(seed = 77).toJsonValue() } },
                "different cell" to { p -> first(p) { it["recipe"] = source().copy(velocity = .99f).toJsonValue() } },
                "unknown schema" to { p -> metadata(p) { it["schema"] = JsonValue.Num(2.0) } },
                "wrong release" to { p -> metadata(p) { it["releaseSeconds"] = JsonValue.Num(1.50000000001) } },
                "changed wav" to { p -> File(p, "$dataName/Splay_60_T1.wav").appendBytes(byteArrayOf(0)) },
                "missing wav" to { p -> File(p, "$dataName/Splay_60_T1.wav").delete() },
                "extra wav" to { p -> File(p, "$dataName/unbound.wav").writeText("bad") },
                "native mapping" to { p -> File(p, "$name.xty").appendBytes(byteArrayOf(0)) },
                "legacy mapping" to { p -> File(p, "$dataName/$name.xpm").appendText("bad") },
                "phone mapping" to { p -> InstrumentStore.sidecar(p, name).appendText(" ") },
            )
            for ((label, corrupt) in corruptions) {
                var moved = false
                assertFails(label) { SplayFiniteKeygroupMaker.exportFresh(name, spec, fixture(spec), root,
                    InstrumentPackageAdmission.Hooks(staged = corrupt, moved = { moved = true })) }
                assertFalse(moved, label)
                assertTrue(InstrumentStore.list(root).isEmpty(), label)
                assertTrue(root.listFiles()!!.isEmpty(), label)
            }
            println("SPLAY finite refused ${corruptions.size} corruptions before shelf publication")
        } finally { root.deleteRecursively() }
    }

    @Test fun unsupportedFiniteSourcesAndInvalidRangesRefuseWithoutMigration() {
        val zero = source().copy(velocity = 0f)
        val before = zero.toJsonText()
        assertFalse(SplayFiniteKeygroupMaker.supports(zero))
        assertFailsWith<IllegalArgumentException> { SplayFiniteKeygroupMaker.Spec(zero) }
        assertEquals(before, zero.toJsonText())
        assertFailsWith<IllegalArgumentException> { source().copy(macros = source().macros + ("HOLD" to 1f)) }
        assertFailsWith<IllegalArgumentException> { source().copy(model = 3) }
        assertFailsWith<IllegalArgumentException> { SplayFiniteKeygroupMaker.Spec(source(), lowMidi = 35) }
        assertFailsWith<IllegalArgumentException> { SplayFiniteKeygroupMaker.Spec(source().copy(midi = 84), highMidi = 83) }
        val spec = SplayFiniteKeygroupMaker.Spec(source(), lowMidi = 60, highMidi = 60)
        assertFailsWith<IllegalArgumentException> { SplayFiniteKeygroupMaker.assemble("Incomplete", spec, emptyMap()) }
        assertFailsWith<CancellationException> { SplayFiniteKeygroupMaker.render(spec) { true } }
    }

    @Test fun `native authored TONGUE root retains exact saved performance across three touches`() {
        for (voice in SplayVoice.entries) {
            val patch = source(voice).copy(midi = 55, velocity = .8f, seed = -13)
            val root = patch.midi
            val spec = SplayFiniteKeygroupMaker.Spec(patch, lowMidi = root, highMidi = root)
            val notes = SplayFiniteKeygroupMaker.render(spec)
            assertEquals(3, notes.size)
            for ((cell, note) in notes) {
                val expected = patch.copy(velocity = patch.velocity * SplayFiniteKeygroupMaker.TOUCHES[cell.touch])
                assertEquals(expected, note.recipe)
                assertContentEquals(expected.render().samples, note.snip.samples)
                val pcm = note.snip.samples
                assertTrue(pcm.all { it.isFinite() && abs(it) <= 1f })
                assertTrue(abs(pcm.map { it.toDouble() }.average()) < .005)
            }
            assertEquals(patch, notes.getValue(SplayFiniteKeygroupMaker.Cell(root, 2)).recipe)
        }
    }

    @Test fun `native finite defaults retain pitched roots at both advertised keyboard edges`() {
        for (voice in SplayVoice.entries) for (midi in listOf(36, 84)) {
            val patch = source(voice).copy(midi = midi, velocity = .65f)
            val snip = patch.render()
            assertTrue(snip.samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(abs(snip.samples.map { it.toDouble() }.average()) < .005)
            // Pitch.detect's upper bound is1200Hz. Reinterpret unchanged frames at
            // half rate for C7, then restore Hz; time windows address the same frames.
            val divisor = if (midi > 84) 2 else 1
            val analysis = Snip(snip.samples, 1, snip.sampleRate / divisor)
            val estimate = assertNotNull(Pitch.detect(analysis, .18f * divisor, .35f * divisor))
            val hz = estimate.hz * divisor
            val cents = 1200 * ln(hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
            assertTrue(estimate.confidence >= .3f, "$voice/$midi confidence ${estimate.confidence}")
            assertTrue(abs(cents) < 50, "$voice/$midi root error $cents")
            println("SPLAY finite edge $voice MIDI$midi cents=$cents confidence=${estimate.confidence}")
        }
    }
}
