package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPresets
import com.snipsnap.synth.WickVoice
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WickPadMakerTest {
    private fun spec() = WickPadMaker.Spec(WickPresets.forVoice(WickVoice.RATTLE).first().copy(
        midi = 55, velocity = .37f, rattleSource = Wick.RattleSource.HOLLOW_REED))

    private fun notes(spec: WickPadMaker.Spec): List<List<KeyNote>> = WickPadMaker.zoneMidis(spec).map { midi ->
        WickKeygroupMaker.TOUCHES.indices.map { touch ->
            KeyNote(Snip(FloatArray(64) { i -> if (i % 2 == 0) .01f * (touch + 1) else -.0001f * midi }, 1, 44_100), 0L)
        }
    }

    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()
    }

    @Test
    fun `admission places complete source-bound packages directly on the real instrument shelf`() {
        val root = createTempDirectory("wick-shelf").toFile()
        try {
            File(root, "unrelated.txt").writeText("keep")
            val spec = spec()
            val original = spec.patch.toJsonText()
            val program = WickPadMaker.export("Wick Native", spec, notes(spec), root)
            val (sidecar, instrument) = InstrumentStore.list(root).single()
            assertEquals(root.canonicalFile, sidecar.parentFile.canonicalFile)
            assertEquals(program, instrument.toProgram())
            assertEquals(66, instrument.sampleFiles.size)
            assertEquals(program, WickKeygroupMaker.validate("Wick Native", root, spec.keys).program)
            assertEquals("keep", File(root, "unrelated.txt").readText())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".instrument-admission-") })
            val cells = WickPadMaker.cells(spec, notes(spec))
            assertEquals(WickKeygroupMaker.recipes(spec.keys), cells.mapValues { it.value.recipe })
            assertTrue(cells.values.all { it.recipe.rattleSource == Wick.RattleSource.HOLLOW_REED })
            assertEquals(original, spec.patch.toJsonText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `failed fresh publications preserve existing instruments and unrelated siblings at every move`() {
        val root = createTempDirectory("wick-admission-rollback").toFile()
        try {
            val spec = spec()
            val notes = notes(spec)
            WickPadMaker.export("Previous Wick", spec, notes, root)
            File(root, "unrelated.txt").writeText("keep")
            val old = snapshot(root)
            val target = "Next Wick"
            for (entry in listOf(Mpc3TrackWriter.trackDataDirName(target), "$target.xty", "$target${InstrumentStore.SUFFIX}")) {
                val failure = assertFailsWith<IOException> {
                    WickPadMaker.export(target, spec, notes, root,
                        InstrumentPackageAdmission.Hooks(moved = { if (it == entry) throw IOException("injected $entry") }))
                }
                assertEquals("injected $entry", failure.message)
                assertEquals(old, snapshot(root))
                assertEquals("Previous Wick", InstrumentStore.list(root).single().second.name)
                assertFalse(root.listFiles()!!.any { it.name.startsWith(".instrument-admission-") })
            }
            assertFailsWith<IllegalArgumentException> { WickPadMaker.export("Previous Wick", spec, notes, root) }
            assertEquals(old, snapshot(root))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `undeclared provenance and unsupported recipe fields refuse before any shelf entry appears`() {
        val root = createTempDirectory("wick-provenance").toFile()
        try {
            val spec = spec()
            val notes = notes(spec)
            val name = "Wick Bound"
            val dataName = Mpc3TrackWriter.trackDataDirName(name)
            val corruptions: List<(File) -> Unit> = listOf(
                { packet -> File(packet, "forgotten-recipe.json").writeText("{}") },
                { packet ->
                    val file = File(packet, "$dataName/wick-source-recipes.json")
                    val obj = Json.parse(file.readText()).obj().toMutableMap()
                    val patch = obj.getValue("sourcePatch").obj().toMutableMap()
                    patch["futureRenderer"] = JsonValue.Num(2.0)
                    obj["sourcePatch"] = JsonValue.Obj(patch)
                    file.writeText(Json.write(JsonValue.Obj(obj)))
                },
                { packet ->
                    val file = File(packet, "$dataName/wick-source-recipes.json")
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
                    WickPadMaker.export(name, spec, notes, root,
                        InstrumentPackageAdmission.Hooks(staged = corrupt, moved = { moved = true }))
                }
                assertFalse(moved)
                assertTrue(InstrumentStore.list(root).isEmpty())
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `held source cancellation and looped adapter notes are explicit refusals`() {
        val spec = spec()
        val held = spec.patch.copy(macros = spec.patch.macros + ("HOLD" to 1f))
        val before = held.toJsonText()
        assertFalse(WickPadMaker.supports(held))
        assertFailsWith<IllegalArgumentException> { WickPadMaker.Spec(held) }
        assertEquals(before, held.toJsonText())
        assertFailsWith<CancellationException> { WickPadMaker.renderZone(spec, 55) { true } }
        val bad = notes(spec).toMutableList()
        bad[0] = bad[0].toMutableList().also { it[0] = it[0].copy(loopStartFrame = 2L) }
        assertFailsWith<IllegalArgumentException> { WickPadMaker.cells(spec, bad) }
    }
}
