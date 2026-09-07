package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutateSheetTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("mutate-sheet").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    private fun tone(hz: Double, seconds: Float): Snip =
        Snip(
            FloatArray((seconds * rate).toInt()) { i ->
                (0.5 * Math.sin(2.0 * Math.PI * hz * i / rate) * Math.exp(-i / (0.4 * rate))).toFloat()
            },
            1, rate,
        )

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, tone(100.0, 0.5f), DrumClass.KICK)
        m.assign(2, tone(3000.0, 0.8f), DrumClass.SNARE)
        m.assign(5, tone(800.0, 0.3f), DrumClass.PERC)
        m.save()
        return m
    }

    @Test
    fun `the four moves, in the verb's own order, and STACK alone has no knob`() {
        assertEquals(listOf("STACK", "SPLICE", "SPLIT", "MORPH"), MutateSheet.MODES)
        assertNull(MutateSheet.knobFor(Mutate.Mode.STACK))
        assertEquals("AT", MutateSheet.knobFor(Mutate.Mode.SPLICE)!!.label)
        assertEquals("HZ", MutateSheet.knobFor(Mutate.Mode.SPLIT)!!.label)
        assertEquals("MIX", MutateSheet.knobFor(Mutate.Mode.MORPH)!!.label)
        assertFailsWith<IllegalArgumentException> { MutateSheet.modeFor("BLEND") }
    }

    @Test
    fun `knobs open at the verb's defaults, round-trip, and read in plain units`() {
        for (mode in listOf(Mutate.Mode.SPLICE, Mutate.Mode.SPLIT, Mutate.Mode.MORPH)) {
            val k = MutateSheet.knobFor(mode)!!
            val f = MutateSheet.fraction(k, k.default)
            assertEquals(k.default, MutateSheet.value(k, f), 1e-2f, "${k.label} round-trips its default")
            assertEquals(k.lo, MutateSheet.value(k, 0f), 1e-3f)
            assertEquals(k.hi, MutateSheet.value(k, 1f), 1e-1f)
        }
        val at = MutateSheet.knobFor(Mutate.Mode.SPLICE)!!
        assertEquals("40 ms", MutateSheet.label(at, 40f))
        val hz = MutateSheet.knobFor(Mutate.Mode.SPLIT)!!
        assertEquals("200 Hz", MutateSheet.label(hz, 200f))
        assertEquals("1.2k", MutateSheet.label(hz, 1200f))
        // Exponential: halfway on the stepper is the geometric middle, where the ear lives.
        assertEquals(Math.sqrt(40.0 * 8000.0).toFloat(), MutateSheet.value(hz, 0.5f), 1f)
        val mix = MutateSheet.knobFor(Mutate.Mode.MORPH)!!
        assertEquals("50%", MutateSheet.label(mix, 0.5f))
    }

    @Test
    fun `pad tags cross banks and partners never include the pad itself`() {
        assertEquals("A01", MutateSheet.padTag(1))
        assertEquals("A16", MutateSheet.padTag(16))
        assertEquals("B01", MutateSheet.padTag(17))
        val m = model("Partners")
        assertEquals(listOf(2, 5), MutateSheet.partners(m.kit, 1).map { it.slot })
        assertEquals(listOf(1, 5), MutateSheet.partners(m.kit, 2).map { it.slot })
        assertFailsWith<IllegalArgumentException> {
            MutateSheet.apply(m, 1, MutateSheet.Partner.Pad(1), Mutate.Mode.STACK, 0f)
        }
    }

    @Test
    fun `a pad partner mutates through the verb's own door - recipe, provenance, undo`() {
        val m = model("Sheet")
        val before = File(m.kitDir, m.pad(1)!!.sampleFile).readBytes()
        assertNull(MutateSheet.read(m.pad(1)!!.recipe))

        val outcome = MutateSheet.apply(m, 1, MutateSheet.Partner.Pad(2), Mutate.Mode.SPLICE, 0.5f)
        m.save()
        val applied = MutateSheet.read(outcome.pad.recipe)
        assertEquals("SPLICE", applied!!.mode)
        assertEquals(listOf("Sheet:A02"), applied.parents, "the CLI's own Kit:Pad label, so lineage reads the same")
        assertEquals("Sheet:A02", outcome.pad.source["mutatedWith"])
        assertTrue(!File(m.kitDir, m.pad(1)!!.sampleFile).readBytes().contentEquals(before), "the hit changed")
        val recipeAt = ((outcome.pad.recipe!!.entries["mutate"] as com.snipsnap.json.JsonValue.Obj).entries["at"] as com.snipsnap.json.JsonValue.Num).value
        assertEquals(MutateSheet.value(MutateSheet.knobFor(Mutate.Mode.SPLICE)!!, 0.5f).toDouble(), recipeAt, 1.0)

        MutateSheet.undo(m, 1)
        m.save()
        assertNull(MutateSheet.read(m.pad(1)!!.recipe))
        assertTrue(File(m.kitDir, m.pad(1)!!.sampleFile).readBytes().contentEquals(before), "the original is back byte-identical")
    }

    @Test
    fun `roulette deals off the shelf, seeded, and records its seed`() {
        val m = model("Spin")
        model("Spin2")
        val deal = MutateSheet.deal(m, 1, root = temp, seed = 3)
        assertEquals(deal, MutateSheet.deal(m, 1, root = temp, seed = 3), "same seed, same deal")
        assertEquals(3, deal.seed)
        assertEquals(deal.label, MutateSheet.name(deal))

        val outcome = MutateSheet.apply(m, 1, deal, Mutate.Mode.STACK, 0f)
        val recipe = (outcome.pad.recipe!!.entries["mutate"] as com.snipsnap.json.JsonValue.Obj).entries
        val roulette = (recipe["roulette"] as com.snipsnap.json.JsonValue.Obj).entries
        assertEquals(3.0, (roulette["seed"] as com.snipsnap.json.JsonValue.Num).value)
        assertEquals(listOf(deal.label), MutateSheet.read(outcome.pad.recipe)!!.parents)

        val empty = File(temp, "empty").apply { mkdirs() }
        assertFailsWith<IllegalArgumentException> { MutateSheet.deal(m, 1, root = empty, seed = 0) }
    }

    @Test
    fun `read ignores every other recipe shape`() {
        val m = model("Other")
        m.eraPad(1, "tape", 0.5f)
        assertNull(MutateSheet.read(m.pad(1)!!.recipe))
        assertNull(MutateSheet.read(null))
    }
}
