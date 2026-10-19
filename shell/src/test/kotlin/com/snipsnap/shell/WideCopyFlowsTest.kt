package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitMerge
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.BoardRecipe
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Copying a printed board must carry its editable source, not only its current WAV. */
class WideCopyFlowsTest {
    private val temp = java.nio.file.Files.createTempDirectory("wide-copy-flows").toFile()
    @AfterTest fun cleanup() { temp.deleteRecursively() }

    private data class Fixture(
        val dir: File,
        val recipe: JsonValue.Obj,
        val main: ByteArray,
        val soft: ByteArray?,
        val live: ByteArray,
    )

    private fun tone(hz: Double, gain: Double = .4) = Snip(FloatArray(2400) { i ->
        (gain * sin(2.0 * PI * hz * i / 44100.0) * exp(-i / 1000.0)).toFloat()
    }, 1, 44100)

    private fun fixture(name: String, hz: Double, layered: Boolean = false, wide: Boolean = true): Fixture {
        val dir = File(temp, name).apply { mkdirs() }
        // Both parents use the same baseline names, with different audio.
        // Their portable sources must therefore get collision-safe names.
        WavWriter.write(File(dir, "main.wav"), tone(hz))
        if (layered) WavWriter.write(File(dir, "soft.wav"), tone(hz, .15))
        val recipe = Json.parse("""{"future":"$name","opaque":{"items":[1,null,"keep exactly"]}}""") as JsonValue.Obj
        val layers = if (layered) listOf(KitLayer("soft.wav", 0, 63), KitLayer("main.wav", 64, 127)) else emptyList()
        val pad = KitPad(
            slot = 1, sampleFile = "main.wav", drumClass = DrumClass.TONAL,
            displayName = name, recipe = recipe, velocityLayers = layers,
            level = .6f, pan = .7f, attack = .1f, cutoff = .8f,
        )
        KitStore.save(Kit(name, listOf(pad)), dir)
        val main = File(dir, "main.wav").readBytes()
        val soft = File(dir, "soft.wav").takeIf { it.isFile }?.readBytes()
        if (wide) {
            val model = KitBuilderModel.open(dir)
            BoardSheet.apply(model, 1, .7f)
            model.save()
        }
        val live = File(dir, KitStore.load(dir).pad(1)!!.sampleFile).readBytes()
        return Fixture(dir, recipe, main, soft, live)
    }

    private fun files(dir: File): Map<String, List<Byte>> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).path to it.readBytes().toList() }

    private fun encoded(snip: Snip): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24)
    }.toByteArray()

    private fun assertPortable(model: KitBuilderModel, slot: Int, original: Fixture) {
        val copied = model.pad(slot)!!
        assertEquals(.7f, BoardSheet.read(copied.recipe)!!.amount)
        assertContentEquals(original.live, File(model.kitDir, copied.sampleFile).readBytes(), "kept audio is copied byte for byte")
        assertFalse(BoardSheet.canUndo(model, slot), "a copied pad begins a new editing history")
        assertContentEquals(original.main, encoded(BoardSheet.preview(model, slot, 0f)))
        val edited = BoardSheet.apply(model, slot, .35f)
        assertEquals(.35f, BoardSheet.read(edited.recipe)!!.amount)
        val removed = BoardSheet.remove(model, slot)
        assertEquals(original.recipe, removed.recipe, "opaque pre-board recipe survives import and later edits")
        assertContentEquals(original.main, File(model.kitDir, removed.sampleFile).readBytes())
        assertEquals(.6f, removed.level)
        assertEquals(.7f, removed.pan)
        assertEquals(.1f, removed.attack)
        assertEquals(.8f, removed.cutoff)
        if (original.soft != null) {
            assertEquals(listOf(0 to 63, 64 to 127), removed.velocityLayers.map { it.velStart to it.velEnd })
            assertContentEquals(original.soft, File(model.kitDir, removed.velocityLayers.first().sampleFile).readBytes())
        }
    }

    @Test
    fun `merge carries sources for both banks and rebases moved layered pads`() {
        val a = fixture("Alpha", 220.0)
        val b = fixture("Bravo", 330.0, layered = true)
        val aFiles = files(a.dir)
        val bFiles = files(b.dir)
        val dest = File(temp, "Merged")
        val merged = KitMerge.merge(a.dir, b.dir, dest, importPad = BoardSheet::importPad)
        assertNotEquals(KitStore.load(b.dir).pad(1)!!.sampleFile, merged.pad(17)!!.sampleFile)
        assertEquals(aFiles, files(a.dir), "merging leaves source A untouched")
        assertEquals(bFiles, files(b.dir), "merging leaves source B untouched")
        a.dir.deleteRecursively()
        b.dir.deleteRecursively()
        val model = KitBuilderModel.open(dest)
        assertPortable(model, 1, a)
        assertPortable(model, 17, b)
        assertNotEquals(model.pad(1)!!.sampleFile, model.pad(17)!!.sampleFile, "different baseline audio cannot collide on restore")
    }

    @Test
    fun `breeding keeps a noncrossable board with all zones and its editable source`() {
        val a = fixture("ParentA", 247.0, layered = true)
        val b = fixture("ParentB", 370.0, wide = false)
        val aFiles = files(a.dir)
        val dest = File(temp, "Child")
        val bKit = KitStore.load(b.dir)
        KitStore.save(bKit.copy(pads = bKit.pads.map {
            it.copy(recipe = PadRecipe(fx = FxChain(eq = mapOf("MID" to .6f))).toJsonValue())
        }), b.dir)
        assertTrue(Breed.crossable(KitStore.load(a.dir), KitStore.load(b.dir)).isEmpty(), "a partner's rack must not turn a printed board into a crossed capture")
        val report = Breed.breed(a.dir, b.dir, dest)
        assertTrue(1 in report.kept)
        assertTrue(report.crossed.isEmpty())
        assertEquals(2, report.kit.pad(1)!!.velocityLayers.size, "a kept WIDE pad retains every velocity zone")
        assertEquals(aFiles, files(a.dir), "the parent remains untouched")
        a.dir.deleteRecursively()
        b.dir.deleteRecursively()
        assertPortable(KitBuilderModel.open(dest), 1, a)
    }

    @Test
    fun `typed compound board recipes stay whole instead of entering the rack crosser`() {
        val a = fixture("TypedA", 196.0, wide = false)
        val b = fixture("TypedB", 294.0, wide = false)
        val typed = PadRecipe(fx = FxChain(eq = mapOf("MID" to .6f)), board = BoardRecipe(amount = .7f))
        val aKit = KitStore.load(a.dir).let { kit -> kit.copy(pads = kit.pads.map { it.copy(recipe = typed.toJsonValue()) }) }
        KitStore.save(aKit, a.dir)
        assertTrue(Breed.recipePads(aKit).isEmpty())
        assertTrue(Breed.crossable(aKit, KitStore.load(b.dir)).isEmpty())
        val result = Breed.breed(a.dir, b.dir, File(temp, "TypedChild"))
        assertEquals(listOf(1), result.kept)
        assertEquals(typed.toJsonValue(), result.kit.pad(1)!!.recipe)
    }
}
