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
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.MeridianVoice
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

class MeridianFiniteKeygroupMakerTest {
    private fun source(voice: MeridianVoice = MeridianVoice.SWEEP) = MeridianPresets.forVoice(voice)
        .first { !Meridian.isLoop(it.macros, it.model) }
    private fun fixture(spec: MeridianFiniteKeygroupMaker.Spec) = MeridianFiniteKeygroupMaker.recipes(spec).mapValues { (cell, patch) ->
        MeridianFiniteKeygroupMaker.Note(patch, Snip(FloatArray(512) { index ->
            val strength = .01f * (cell.touch + 1) + .0001f * cell.midi
            if (index % 2 == 0) strength else -strength
        }, 1, 44_100))
    }
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()
    }

    @Test fun `planning preserves all finite factory sources and exact noncentral authored performances`() {
        val factories = MeridianPresets.all()
        assertEquals(18, factories.size)
        val finite = factories.filterNot { Meridian.isLoop(it.macros, it.model) }
        assertEquals(MeridianVoice.entries.toSet(), finite.map { it.voice }.toSet())
        val legacy = source().copy(model = 1, macros = source().macros + ("HOLD" to 1f))
        for (patch in finite + legacy) {
            val edited = patch.copy(midi = 55, velocity = .73f, seedContext = -41,
                macros = patch.macros + ("TUNE" to .75f))
            val original = edited.toJsonText()
            val root = MeridianSynth.rootMidi(edited)
            assertEquals(61, root)
            val spec = MeridianFiniteKeygroupMaker.Spec(edited)
            assertTrue(root in MeridianFiniteKeygroupMaker.roots(spec))
            val recipes = MeridianFiniteKeygroupMaker.recipes(spec)
            assertEquals(66, recipes.size)
            for ((cell, recipe) in recipes) {
                assertEquals(cell.midi, MeridianSynth.rootMidi(recipe))
                assertEquals(patch.model, recipe.model)
                assertEquals(-41, recipe.seedContext)
                assertEquals(edited.velocity * MeridianFiniteKeygroupMaker.TOUCHES[cell.touch], recipe.velocity)
                assertEquals(recipe, MeridianPatch.fromJsonText(recipe.toJsonText()))
                if (cell.midi == root) assertEquals(edited.copy(velocity = recipe.velocity), recipe)
                else assertEquals(edited.copy(midi = cell.midi,
                    macros = edited.macros + ("TUNE" to .5f), velocity = recipe.velocity), recipe)
            }
            assertEquals(edited, recipes.getValue(MeridianFiniteKeygroupMaker.Cell(root, 2)))
            assertEquals(original, edited.toJsonText())
        }
        assertTrue(MeridianFiniteKeygroupMaker.supports(legacy))
        assertFalse(Meridian.isLoop(legacy.macros, legacy.model))
    }

    @Test fun `native range and velocity windows form exact bounded polyphonic finite programs`() {
        val spec = MeridianFiniteKeygroupMaker.Spec(source().copy(midi = 55))
        val program = MeridianFiniteKeygroupMaker.assemble("Meridian Keys", spec, fixture(spec)).program
        assertEquals((36..96).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertTrue(program.keygroups.any { it.rootNote == 55 })
        assertTrue(program.keygroups.all { zone -> (zone.lowNote..zone.highNote).all { abs(it - zone.rootNote) <= 1 } })
        assertTrue(program.keygroups.all { group -> group.layers.all { it.loopStartFrame == 0L } })
        assertTrue(program.explicitNoteOn)
        assertFalse(program.stackZones)
        assertFalse(program.voiceMode.mono)
        assertEquals(.35f, program.volumeRelease)
    }

    @Test fun `package descriptors actual shelf samples and native touch boundaries agree`() {
        val temp = createTempDirectory("meridian-finite-package").toFile()
        try {
            val spec = MeridianFiniteKeygroupMaker.Spec(source(), releaseSeconds = .01f)
            val name = "Meridian Keys"
            val assembly = MeridianFiniteKeygroupMaker.exportFresh(name, spec, fixture(spec), temp)
            assertEquals(63, assembly.samples.size)
            assertEquals(assembly.program, MeridianFiniteKeygroupMaker.validate(name, temp, spec).program)
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
            assertNull(KeyHit.resolve(instrument, 97, 1f) { samples[it]?.size?.toLong() })
        } finally { temp.deleteRecursively() }
    }

    @Test fun `real chord tool sets trigger independent finite voices through the bounded program`() {
        val temp = createTempDirectory("meridian-finite-chords").toFile()
        try {
            val spec = MeridianFiniteKeygroupMaker.Spec(source())
            MeridianFiniteKeygroupMaker.exportFresh("Meridian Chords", spec, fixture(spec), temp)
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
        val root = createTempDirectory("meridian-finite-rollback").toFile()
        try {
            val spec = MeridianFiniteKeygroupMaker.Spec(source(), lowMidi = 57, highMidi = 57)
            val notes = fixture(spec)
            MeridianFiniteKeygroupMaker.exportFresh("Previous Meridian", spec, notes, root)
            File(root, "unrelated.txt").writeText("keep")
            val old = snapshot(root)
            val name = "Next Meridian"
            for (entry in listOf(Mpc3TrackWriter.trackDataDirName(name), "$name.xty", "$name${InstrumentStore.SUFFIX}")) {
                assertFailsWith<IOException> {
                    MeridianFiniteKeygroupMaker.exportFresh(name, spec, notes, root,
                        InstrumentPackageAdmission.Hooks(moved = { if (it == entry) throw IOException("injected $entry") }))
                }
                assertEquals(old, snapshot(root))
                assertEquals("Previous Meridian", InstrumentStore.list(root).single().second.name)
            }
            assertFailsWith<IllegalArgumentException> { MeridianFiniteKeygroupMaker.exportFresh("Previous Meridian", spec, notes, root) }
            assertEquals(old, snapshot(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun `unsupported incomplete and substituted source packages never enter the shelf`() {
        val root = createTempDirectory("meridian-finite-corruption").toFile()
        try {
            val spec = MeridianFiniteKeygroupMaker.Spec(source(), lowMidi = 57, highMidi = 57)
            val name = "Meridian Bound"
            val dataName = Mpc3TrackWriter.trackDataDirName(name)
            fun metadata(packet: File, edit: (MutableMap<String, JsonValue>) -> Unit) {
                val f = File(packet, "$dataName/meridian-finite-source-recipes.json")
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
                "different source" to { p -> metadata(p) { it["sourcePatch"] = source().copy(seedContext = 77).toJsonValue() } },
                "different cell" to { p -> first(p) { it["recipe"] = source().copy(velocity = .99f).toJsonValue() } },
                "unknown schema" to { p -> metadata(p) { it["schema"] = JsonValue.Num(2.0) } },
                "wrong release" to { p -> metadata(p) { it["releaseSeconds"] = JsonValue.Num(1.50000000001) } },
                "changed wav" to { p -> File(p, "$dataName/Meridian_57_T1.wav").appendBytes(byteArrayOf(0)) },
                "missing wav" to { p -> File(p, "$dataName/Meridian_57_T1.wav").delete() },
                "extra wav" to { p -> File(p, "$dataName/unbound.wav").writeText("bad") },
                "native mapping" to { p -> File(p, "$name.xty").appendBytes(byteArrayOf(0)) },
                "legacy mapping" to { p -> File(p, "$dataName/$name.xpm").appendText("bad") },
                "phone mapping" to { p -> InstrumentStore.sidecar(p, name).appendText(" ") },
            )
            for ((label, corrupt) in corruptions) {
                var moved = false
                assertFails(label) { MeridianFiniteKeygroupMaker.exportFresh(name, spec, fixture(spec), root,
                    InstrumentPackageAdmission.Hooks(staged = corrupt, moved = { moved = true })) }
                assertFalse(moved, label)
                assertTrue(InstrumentStore.list(root).isEmpty(), label)
                assertTrue(root.listFiles()!!.isEmpty(), label)
            }
            println("MERIDIAN finite refused ${corruptions.size} corruptions before shelf publication")
        } finally { root.deleteRecursively() }
    }

    @Test fun `held zero strength and effective roots outside the range refuse without source migration`() {
        val held = source().copy(macros = source().macros + ("HOLD" to 1f))
        val outside = source().copy(midi = 96, macros = source().macros + ("TUNE" to 1f))
        for (patch in listOf(held, source().copy(velocity = 0f), outside)) {
            val before = patch.toJsonText()
            assertFalse(MeridianFiniteKeygroupMaker.supports(patch))
            assertFailsWith<IllegalArgumentException> { MeridianFiniteKeygroupMaker.Spec(patch) }
            assertEquals(before, patch.toJsonText())
        }
        assertFailsWith<IllegalArgumentException> { MeridianFiniteKeygroupMaker.Spec(source(), lowMidi = 35) }
        val spec = MeridianFiniteKeygroupMaker.Spec(source(), lowMidi = 57, highMidi = 57)
        assertFailsWith<IllegalArgumentException> { MeridianFiniteKeygroupMaker.assemble("Incomplete", spec, emptyMap()) }
        assertFailsWith<CancellationException> { MeridianFiniteKeygroupMaker.render(spec) { true } }
    }

    @Test fun `all six native source roots retain exact saved performance across three touches`() {
        for (voice in MeridianVoice.entries) {
            val patch = source(voice).copy(midi = 55, velocity = .8f, seedContext = -13,
                macros = source(voice).macros + ("TUNE" to .75f))
            val root = MeridianSynth.rootMidi(patch)
            val spec = MeridianFiniteKeygroupMaker.Spec(patch, lowMidi = root, highMidi = root)
            val notes = MeridianFiniteKeygroupMaker.render(spec)
            assertEquals(3, notes.size)
            for ((cell, note) in notes) {
                val expected = patch.copy(velocity = patch.velocity * MeridianFiniteKeygroupMaker.TOUCHES[cell.touch])
                assertEquals(expected, note.recipe)
                assertContentEquals(expected.render().samples, note.snip.samples)
                val pcm = note.snip.samples
                assertTrue(pcm.all { it.isFinite() && abs(it) <= 1f })
                assertTrue(abs(pcm.map { it.toDouble() }.average()) < .005)
            }
            assertEquals(patch, notes.getValue(MeridianFiniteKeygroupMaker.Cell(root, 2)).recipe)
        }
    }

    @Test fun `native finite defaults retain pitched roots at both advertised keyboard edges`() {
        for (voice in MeridianVoice.entries) for (midi in listOf(36, 96)) {
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
            println("MERIDIAN finite edge $voice MIDI$midi cents=$cents confidence=${estimate.confidence}")
        }
    }
}
