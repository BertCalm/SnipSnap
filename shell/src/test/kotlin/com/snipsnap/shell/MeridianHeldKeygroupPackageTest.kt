package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.MeridianVoice
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MeridianHeldKeygroupPackageTest {
    private fun source(voice: MeridianVoice = MeridianVoice.CHIME): MeridianPatch {
        val factories = MeridianPresets.forVoice(voice)
        // DRAW/LIFT have two finite factories. Their held counterpart keeps that
        // exact source and only selects HOLD; it is not another factory preset.
        val original = factories.firstOrNull { Meridian.isLoop(it.macros, it.model) }
            ?: factories.first().let { it.copy(macros = it.macros + ("HOLD" to 1f)) }
        return original.copy(midi = 55, velocity = .37f, seedContext = -41,
            macros = original.macros + ("TUNE" to .75f))
    }

    /** Two complete identical cycles; the loop has a real preceding continuation. */
    private fun fixture(spec: MeridianPadMaker.Spec): List<List<KeyNote>> = MeridianPadMaker.zoneMidis(spec).map { midi ->
        MeridianPadMaker.VELOCITIES.indices.map { touch ->
            KeyNote(Snip(FloatArray(1024) { index ->
                (.05 * (touch + 1) * sin(2 * PI * (index % 16) / 16)).toFloat()
            }, 1, 44_100), 512)
        }
    }
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()
    }

    @Test fun `held source and cell adaptation policies are explicit without changing the original recipe`() {
        for (voice in MeridianVoice.entries) {
            val patch = source(voice)
            val before = patch.toJsonText()
            val spec = MeridianPadMaker.Spec(patch, .73f)
            val cells = MeridianHeldKeygroupPackage.recipes(spec)
            assertEquals(39, cells.size)
            assertEquals((36..72 step 3).toList(), cells.keys.map { it.midi }.distinct())
            for ((cell, recipe) in cells) {
                assertEquals(MeridianPadMaker.melodyPatch(patch).copy(midi = cell.midi,
                    velocity = MeridianPadMaker.VELOCITIES[cell.touch]), recipe)
                assertEquals(.5f, recipe.macros.getValue("TUNE"))
                assertEquals(patch.model, recipe.model)
                assertEquals(-41, recipe.seedContext)
                assertEquals(recipe, MeridianPatch.fromJsonText(recipe.toJsonText()))
            }
            assertEquals(.37f, patch.velocity)
            assertEquals(.75f, patch.macros.getValue("TUNE"))
            assertEquals(before, patch.toJsonText())
        }
    }

    @Test fun `source bound held shelf package preserves all existing descriptor and sample bytes`() {
        val root = createTempDirectory("meridian-held-source-identity").toFile()
        try {
            val old = File(root, "old")
            val fresh = File(root, "fresh")
            val name = "Meridian Held"
            val spec = MeridianPadMaker.Spec(source(), .73f)
            val notes = fixture(spec)
            val oldProgram = MeridianPadMaker.export(name, spec, notes, old)
            val assembly = MeridianHeldKeygroupPackage.exportFresh(name, spec, notes, fresh)
            assertEquals(oldProgram, assembly.program)
            val oldFiles = snapshot(old)
            val newFiles = snapshot(fresh)
            assertEquals(oldFiles, newFiles.filterKeys { !it.endsWith(MeridianHeldKeygroupPackage.SOURCES) })
            assertEquals(oldFiles.size + 1, newFiles.size)
            assertEquals(assembly.program, MeridianHeldKeygroupPackage.validate(name, fresh, spec).program)
            val (sidecar, instrument) = InstrumentStore.list(fresh).single()
            assertEquals(fresh.canonicalFile, sidecar.parentFile.canonicalFile)
            assertEquals(oldProgram, instrument.toProgram())
            assertEquals((36..72).toList(), instrument.zones.flatMap { (it.lowNote..it.highNote).toList() })
            assertEquals(.73f, instrument.toProgram().volumeRelease)
            for (zone in instrument.zones) for (layer in zone.layers) {
                val sheet = assertNotNull(WavReader.readSmplStrict(File(fresh, layer.sample)))
                assertEquals(zone.rootNote, sheet.rootNote)
                assertEquals(SmplChunk.Loop(512, 1024), sheet.loop)
                val wav = WavReader.read(File(fresh, layer.sample))
                assertEquals(0.0, Keys.seamError(wav.samples, 512))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `original held source is retained separately from neutral tune absolute touch samples`() {
        val root = createTempDirectory("meridian-held-provenance").toFile()
        try {
            val patch = source().copy(velocity = 0f)
            val spec = MeridianPadMaker.Spec(patch, .1f)
            MeridianHeldKeygroupPackage.exportFresh("Held Policy", spec, fixture(spec), root)
            val metadata = File(root, "Held Policy_[TrackData]/${MeridianHeldKeygroupPackage.SOURCES}")
            val obj = Json.parse(metadata.readText()).obj()
            assertEquals(patch, MeridianPatch.fromJsonValue(obj.getValue("sourcePatch")))
            assertEquals("ABSOLUTE_030_065_100", obj.getValue("touchPolicy").str())
            assertEquals("NEUTRAL_AT_ABSOLUTE_NATIVE_ROOT", obj.getValue("tunePolicy").str())
            val adapted = obj.getValue("samples").arr().map {
                MeridianPatch.fromJsonValue(it.obj().getValue("recipe")) as MeridianPatch
            }
            assertEquals(setOf(.30f, .65f, 1f), adapted.map { it.velocity }.toSet())
            assertTrue(adapted.all { it.macros.getValue("TUNE") == .5f })
            assertEquals(0f, patch.velocity)
            assertEquals(.75f, patch.macros.getValue("TUNE"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `hostile held provenance loops samples and descriptors refuse before admission`() {
        val root = createTempDirectory("meridian-held-hostile").toFile()
        try {
            val spec = MeridianPadMaker.Spec(source())
            val name = "Held Bound"
            val dataName = Mpc3TrackWriter.trackDataDirName(name)
            val firstStem = "HeldBound_36_V1"
            fun metadata(packet: File, edit: (MutableMap<String, JsonValue>) -> Unit) {
                val f = File(packet, "$dataName/${MeridianHeldKeygroupPackage.SOURCES}")
                val o = Json.parse(f.readText()).obj().toMutableMap(); edit(o)
                f.writeText(Json.write(JsonValue.Obj(o)))
            }
            fun first(packet: File, edit: (MutableMap<String, JsonValue>) -> Unit) = metadata(packet) { o ->
                val rows = o.getValue("samples").arr().toMutableList()
                val row = rows.first().obj().toMutableMap(); edit(row); rows[0] = JsonValue.Obj(row)
                o["samples"] = JsonValue.Arr(rows)
            }
            val corruptions: Map<String, (File) -> Unit> = linkedMapOf(
                "forgotten root provenance" to { p -> File(p, "source.json").writeText("{}") },
                "missing binding" to { p -> metadata(p) { it["samples"] = JsonValue.Arr(it.getValue("samples").arr().dropLast(1)) } },
                "duplicate binding" to { p -> metadata(p) { val rows = it.getValue("samples").arr(); it["samples"] = JsonValue.Arr(rows.dropLast(1) + rows.first()) } },
                "bad source hash" to { p -> metadata(p) { it["sourcePatchSha256"] = JsonValue.Str("0".repeat(64)) } },
                "bad cell hash" to { p -> first(p) { it["recipeSha256"] = JsonValue.Str("0".repeat(64)) } },
                "relative touch policy" to { p -> metadata(p) { it["touchPolicy"] = JsonValue.Str("RELATIVE") } },
                "authored tune policy" to { p -> metadata(p) { it["tunePolicy"] = JsonValue.Str("KEEP_AUTHORED") } },
                "unknown source" to { p -> metadata(p) { o -> val patch = o.getValue("sourcePatch").obj().toMutableMap(); patch["future"] = JsonValue.Num(1.0); o["sourcePatch"] = JsonValue.Obj(patch) } },
                "unknown cell" to { p -> first(p) { o -> val patch = o.getValue("recipe").obj().toMutableMap(); patch["future"] = JsonValue.Num(1.0); o["recipe"] = JsonValue.Obj(patch) } },
                "wrong recipe" to { p -> first(p) { it["recipe"] = source().copy(midi = 36, velocity = .31f).toJsonValue() } },
                "wrong root" to { p -> first(p) { it["rootMidi"] = JsonValue.Num(37.0) } },
                "no preceding cycle" to { p -> first(p) { it["loopStartFrame"] = JsonValue.Num(255.0) } },
                "wrong loop start" to { p -> first(p) { it["loopStartFrame"] = JsonValue.Num(513.0) } },
                "truncated loop" to { p -> first(p) { it["loopEndFrameExclusive"] = JsonValue.Num(1023.0) } },
                "unsafe sample" to { p -> first(p) { it["sample"] = JsonValue.Str("../bad.wav") } },
                "unknown schema" to { p -> metadata(p) { it["schema"] = JsonValue.Num(2.0) } },
                "wrong release" to { p -> metadata(p) { it["releaseSeconds"] = JsonValue.Num(1.50000000001) } },
                "wrong range" to { p -> metadata(p) { it["lowMidi"] = JsonValue.Num(0.0) } },
                "changed pcm" to { p -> File(p, "$dataName/$firstStem.wav").appendBytes(byteArrayOf(0)) },
                "missing pcm" to { p -> File(p, "$dataName/$firstStem.wav").delete() },
                "extra data" to { p -> File(p, "$dataName/unbound.wav").writeText("bad") },
                "native mapping" to { p -> File(p, "$name.xty").appendBytes(byteArrayOf(0)) },
                "legacy mapping" to { p -> File(p, "$dataName/$name.xpm").appendText("bad") },
                "phone mapping" to { p -> InstrumentStore.sidecar(p, name).appendText(" ") },
            )
            for ((label, corrupt) in corruptions) {
                var moved = false
                assertFails(label) { MeridianHeldKeygroupPackage.exportFresh(name, spec, fixture(spec), root,
                    InstrumentPackageAdmission.Hooks(staged = corrupt, moved = { moved = true })) }
                assertFalse(moved, label)
                assertTrue(InstrumentStore.list(root).isEmpty(), label)
                assertTrue(root.listFiles()!!.isEmpty(), label)
            }
            println("MERIDIAN held refused ${corruptions.size} hostile packages before publication")
        } finally { root.deleteRecursively() }
    }

    @Test fun `every held publication move rolls back without changing older packages`() {
        val root = createTempDirectory("meridian-held-rollback").toFile()
        try {
            val spec = MeridianPadMaker.Spec(source())
            val notes = fixture(spec)
            MeridianHeldKeygroupPackage.exportFresh("Previous Held", spec, notes, root)
            File(root, "unrelated.txt").writeText("keep")
            val old = snapshot(root)
            val name = "Next Held"
            for (entry in listOf(Mpc3TrackWriter.trackDataDirName(name), "$name.xty", "$name${InstrumentStore.SUFFIX}")) {
                assertFailsWith<IOException> {
                    MeridianHeldKeygroupPackage.exportFresh(name, spec, notes, root,
                        InstrumentPackageAdmission.Hooks(moved = { if (it == entry) throw IOException("injected $entry") }))
                }
                assertEquals(old, snapshot(root))
                assertEquals("Previous Held", InstrumentStore.list(root).single().second.name)
            }
            assertFailsWith<IllegalArgumentException> { MeridianHeldKeygroupPackage.exportFresh("Previous Held", spec, notes, root) }
            assertEquals(old, snapshot(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun `finite and mismatched held sources refuse without silently changing their models`() {
        val finite = source().copy(macros = source().macros + ("HOLD" to 0f))
        assertFailsWith<IllegalArgumentException> { MeridianPadMaker.Spec(finite) }
        val legacy = MeridianPresets.forVoice(MeridianVoice.SWEEP).first().copy(model = 1,
            macros = source().macros + ("HOLD" to 1f))
        assertFailsWith<IllegalArgumentException> { MeridianPadMaker.Spec(legacy) }
        val spec = MeridianPadMaker.Spec(source())
        assertFailsWith<IllegalArgumentException> { MeridianHeldKeygroupPackage.assemble("Missing", spec, fixture(spec).dropLast(1)) }
        val altered = fixture(spec).toMutableList()
        altered[0] = altered[0].toMutableList().also { layers ->
            val original = layers[0]
            val broken = original.snip.samples.copyOf().also { it[1000] += .1f }
            layers[0] = original.copy(snip = Snip(broken, 1, 44_100))
        }
        assertFailsWith<IllegalArgumentException> { MeridianHeldKeygroupPackage.assemble("Broken", spec, altered) }
    }

    @Test fun `twelve original native held cells replay adapted recipes and export unchanged PCM24 loops`() {
        assertEquals(12, nativeCells(listOf(MeridianVoice.SWEEP, MeridianVoice.CLACK, MeridianVoice.CHIME, MeridianVoice.RASP)))
    }

    @Test fun `six model four native held cells retain adapted source PCM24 loops and fresh package bindings`() {
        assertEquals(6, nativeCells(listOf(MeridianVoice.DRAW, MeridianVoice.LIFT)))
    }

    private fun nativeCells(voices: List<MeridianVoice>): Int {
        val root = createTempDirectory("meridian-held-native").toFile()
        try {
            var cells = 0
            for (voice in voices) {
                val spec = MeridianPadMaker.Spec(source(voice), .73f)
                val native = MeridianPadMaker.renderZone(spec, 54)
                val recipes = MeridianHeldKeygroupPackage.recipes(spec)
                for ((touch, note) in native.withIndex()) {
                    val recipe = recipes.getValue(MeridianHeldKeygroupPackage.Cell(54, touch))
                    val replay = recipe.render()
                    assertContentEquals(note.snip.samples, replay.samples)
                    assertTrue(note.snip.samples.all { it.isFinite() && abs(it) <= 1f })
                    assertTrue(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
                    cells++
                }
                // Reuse the three real cells for synthetic other-root packaging only;
                // this verifies actual PCM/loops and descriptors, not a musical full bank.
                val mixed = fixture(spec).toMutableList()
                val index = MeridianPadMaker.zoneMidis(spec).indexOf(54)
                mixed[index] = native
                val name = "Native ${voice.name}"
                val old = File(root, "old-${voice.name}")
                val fresh = File(root, "fresh-${voice.name}")
                MeridianPadMaker.export(name, spec, mixed, old)
                val assembly = MeridianHeldKeygroupPackage.exportFresh(name, spec, mixed, fresh)
                val previous = snapshot(old)
                assertEquals(previous, snapshot(fresh).filterKeys { !it.endsWith(MeridianHeldKeygroupPackage.SOURCES) })
                val validated = MeridianHeldKeygroupPackage.validate(name, fresh, spec)
                assertEquals(assembly.program, validated.program)
                assertEquals(assembly.program, InstrumentStore.list(fresh).single().second.toProgram())
            }
            return cells
        } finally { root.deleteRecursively() }
    }
}
