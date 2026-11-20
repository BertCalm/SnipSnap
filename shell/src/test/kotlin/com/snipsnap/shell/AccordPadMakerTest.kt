package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Accord
import com.snipsnap.synth.AccordPresets
import com.snipsnap.synth.AccordVoice
import com.snipsnap.synth.KeyNote
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccordPadMakerTest {
    private fun spec() = AccordPadMaker.Spec(AccordPresets.forVoice(AccordVoice.REED).first().copy(midi = 55, velocity = .37f))

    private fun notes(spec: AccordPadMaker.Spec): List<List<KeyNote>> = AccordPadMaker.zoneMidis(spec).map { midi ->
        List(AccordKeygroupMaker.LAYERS) { layer ->
            KeyNote(Snip(FloatArray(64) { i -> if (i % 2 == 0) .01f * (layer + 1) else -.0001f * midi }, 1, 44_100), 0L)
        }
    }

    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()
    }

    @Test
    fun `admission places complete source-bound packages directly on the real instrument shelf`() {
        val root = createTempDirectory("accord-shelf").toFile()
        try {
            File(root, "unrelated.txt").writeText("keep")
            val spec = spec()
            val original = spec.patch.toJsonText()
            assertEquals(14, AccordPadMaker.zoneMidis(spec).size, "thirteen minor-third roots and the saved G3")
            val program = AccordPadMaker.export("Accord Native", spec, notes(spec), root)
            val (sidecar, instrument) = InstrumentStore.list(root).single()
            assertEquals(root.canonicalFile, sidecar.parentFile.canonicalFile)
            assertEquals(program, instrument.toProgram())
            assertEquals(84, instrument.sampleFiles.size)
            assertEquals(program, AccordKeygroupMaker.validate("Accord Native", root, spec.keys))
            assertEquals("keep", File(root, "unrelated.txt").readText())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".instrument-admission-") })
            val cells = AccordPadMaker.cells(spec, notes(spec))
            assertEquals(AccordKeygroupMaker.recipes(spec.keys), cells.mapValues { it.value.recipe })
            assertTrue(cells.values.all { it.recipe.voice == AccordVoice.REED })
            assertEquals(original, spec.patch.toJsonText())
            assertEquals("Finite notes, no loop. C2–C5 · three touch layers · two takes per touch · 84 samples. Let go to fade.",
                AccordPadMaker.instrumentNote(spec))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a rendered root is three touches of two engine takes and the preview is its medium key`() {
        val spec = AccordPadMaker.Spec(AccordPresets.forVoice(AccordVoice.WOOD).first().copy(sourceSeconds = .75))
        val zone = AccordPadMaker.renderZone(spec, 48)
        assertEquals(6, zone.size)
        assertTrue(zone.all { it.loopStartFrame == 0L })
        for ((layer, note) in zone.withIndex()) {
            val recipe = spec.patch.copy(velocity = AccordKeygroupMaker.TOUCHES[layer / 2])
            assertContentEquals(Accord.renderTake(recipe, AccordKeygroupMaker.TAKES[layer % 2]).samples, note.snip.samples)
        }
        assertContentEquals(zone[2].snip.samples, AccordPadMaker.preview(spec).samples)
    }

    @Test
    fun `failed fresh publications preserve existing instruments and unrelated siblings at every move`() {
        val root = createTempDirectory("accord-admission-rollback").toFile()
        try {
            val spec = spec()
            val notes = notes(spec)
            AccordPadMaker.export("Previous Accord", spec, notes, root)
            File(root, "unrelated.txt").writeText("keep")
            val old = snapshot(root)
            val target = "Next Accord"
            for (entry in listOf(Mpc3TrackWriter.trackDataDirName(target), "$target.xty", "$target${InstrumentStore.SUFFIX}")) {
                val failure = assertFailsWith<IOException> {
                    AccordPadMaker.export(target, spec, notes, root,
                        InstrumentPackageAdmission.Hooks(moved = { if (it == entry) throw IOException("injected $entry") }))
                }
                assertEquals("injected $entry", failure.message)
                assertEquals(old, snapshot(root))
                assertEquals("Previous Accord", InstrumentStore.list(root).single().second.name)
                assertFalse(root.listFiles()!!.any { it.name.startsWith(".instrument-admission-") })
            }
            assertFailsWith<IllegalArgumentException> { AccordPadMaker.export("Previous Accord", spec, notes, root) }
            assertEquals(old, snapshot(root))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `undeclared provenance and unsupported recipe fields refuse before any shelf entry appears`() {
        val root = createTempDirectory("accord-provenance").toFile()
        try {
            val spec = spec()
            val notes = notes(spec)
            val name = "Accord Bound"
            val dataName = Mpc3TrackWriter.trackDataDirName(name)
            val corruptions: List<(File) -> Unit> = listOf(
                { packet -> File(packet, "forgotten-recipe.json").writeText("{}") },
                { packet ->
                    val file = File(packet, "$dataName/accord-source-recipes.json")
                    val obj = Json.parse(file.readText()).obj().toMutableMap()
                    val patch = obj.getValue("sourcePatch").obj().toMutableMap()
                    patch["futureRenderer"] = JsonValue.Num(2.0)
                    obj["sourcePatch"] = JsonValue.Obj(patch)
                    file.writeText(Json.write(JsonValue.Obj(obj)))
                },
                { packet ->
                    val file = File(packet, "$dataName/accord-source-recipes.json")
                    val obj = Json.parse(file.readText()).obj().toMutableMap()
                    val rows = obj.getValue("samples").arr().toMutableList()
                    val binding = rows.first().obj().toMutableMap()
                    val patch = binding.getValue("recipe").obj().toMutableMap()
                    patch["futureRenderer"] = JsonValue.Num(2.0)
                    binding["recipe"] = JsonValue.Obj(patch)
                    rows[0] = JsonValue.Obj(binding)
                    obj["samples"] = JsonValue.Arr(rows)
                    file.writeText(Json.write(JsonValue.Obj(obj)))
                },
            )
            for (corrupt in corruptions) {
                var moved = false
                assertFailsWith<IllegalArgumentException> {
                    AccordPadMaker.export(name, spec, notes, root,
                        InstrumentPackageAdmission.Hooks(staged = corrupt, moved = { moved = true }))
                }
                assertFalse(moved)
                assertTrue(InstrumentStore.list(root).isEmpty())
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `cancellation and looped or short adapter notes are explicit refusals`() {
        val spec = spec()
        assertTrue(AccordPadMaker.supports(spec.patch))
        assertFailsWith<CancellationException> { AccordPadMaker.renderZone(spec, 55) { true } }
        assertFailsWith<CancellationException> { AccordPadMaker.preview(spec) { true } }
        assertFailsWith<IllegalArgumentException> { AccordPadMaker.renderZone(spec, 56) }
        val bad = notes(spec).toMutableList()
        bad[0] = bad[0].toMutableList().also { it[0] = it[0].copy(loopStartFrame = 2L) }
        assertFailsWith<IllegalArgumentException> { AccordPadMaker.cells(spec, bad) }
        val short = notes(spec).toMutableList()
        short[0] = short[0].dropLast(1)
        assertFailsWith<IllegalArgumentException> { AccordPadMaker.cells(spec, short) }
    }
}
