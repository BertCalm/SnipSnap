package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
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
    fun `the six moves, in the verb's own order, and STACK alone has no knob`() {
        assertEquals(listOf("STACK", "SPLICE", "SPLIT", "MORPH", "ROOM", "TRANSPLANT"), MutateSheet.MODES)
        assertEquals("WET", MutateSheet.knobFor(Mutate.Mode.ROOM)!!.label)
        assertEquals("BANDS", MutateSheet.knobFor(Mutate.Mode.TRANSPLANT)!!.label)
        assertNull(MutateSheet.knobFor(Mutate.Mode.STACK))
        assertEquals("AT", MutateSheet.knobFor(Mutate.Mode.SPLICE)!!.label)
        assertEquals("HZ", MutateSheet.knobFor(Mutate.Mode.SPLIT)!!.label)
        assertEquals("MIX", MutateSheet.knobFor(Mutate.Mode.MORPH)!!.label)
        assertFailsWith<IllegalArgumentException> { MutateSheet.modeFor("BLEND") }
    }

    @Test
    fun `knobs open at the verb's defaults, round-trip, and read in plain units`() {
        for (mode in listOf(Mutate.Mode.SPLICE, Mutate.Mode.SPLIT, Mutate.Mode.MORPH, Mutate.Mode.ROOM, Mutate.Mode.TRANSPLANT)) {
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
        val bands = MutateSheet.knobFor(Mutate.Mode.TRANSPLANT)!!
        assertEquals("16 bands", MutateSheet.label(bands, bands.default))
        assertEquals(16f, MutateSheet.value(bands, bands.defaultFraction), 0.5f)
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
    fun `a pad picked on another kit is a partner - the shelf minus this kit, its pads, the CLI's label, undo`() {
        val shelf = File(temp, "shelf-other").apply { mkdirs() }
        val mine = KitBuilderModel.create("Mine", File(shelf, "Mine"))
        mine.assign(1, tone(100.0, 0.5f), DrumClass.KICK)
        mine.save()
        val soul = KitBuilderModel.create("Soul", File(shelf, "Soul"))
        soul.assign(3, tone(2000.0, 0.4f), DrumClass.SNARE)
        soul.assign(7, tone(500.0, 0.6f), DrumClass.PERC)
        soul.save()
        File(shelf, "Broken").mkdirs().also { File(shelf, "Broken/kit.json").writeText("{ not json") }

        val others = MutateSheet.otherKits(shelf, mine.kitDir)
        assertEquals(listOf("Soul"), others.map { it.name }, "the shelf minus this kit, the broken folder skipped")
        assertEquals(listOf(3, 7), MutateSheet.padsOf(others.single()).map { it.slot })

        val partner = MutateSheet.Partner.Other("Soul", others.single().dir, 3)
        assertEquals("Soul A03", MutateSheet.name(partner))
        val before = File(mine.kitDir, mine.pad(1)!!.sampleFile).readBytes()
        val outcome = MutateSheet.apply(mine, 1, partner, Mutate.Mode.MORPH, 0.5f)
        mine.save()
        val applied = MutateSheet.read(outcome.pad.recipe)!!
        assertEquals("MORPH", applied.mode)
        assertEquals(listOf("Soul:A03"), applied.parents, "the CLI's own Kit:Pad label, as a deal's would read")
        val mutate = outcome.pad.recipe!!.entries["mutate"] as com.snipsnap.json.JsonValue.Obj
        assertEquals("Soul", (mutate.entries["otherKit"] as com.snipsnap.json.JsonValue.Str).value)
        assertTrue(!File(mine.kitDir, mine.pad(1)!!.sampleFile).readBytes().contentEquals(before), "the hit changed")

        val e = assertFailsWith<IllegalArgumentException> {
            MutateSheet.apply(mine, 1, MutateSheet.Partner.Other("Soul", others.single().dir, 9), Mutate.Mode.STACK, 0f)
        }
        assertTrue(e.message!!.contains("Soul A09"), e.message)

        MutateSheet.undo(mine, 1)
        mine.save()
        assertTrue(File(mine.kitDir, mine.pad(1)!!.sampleFile).readBytes().contentEquals(before), "undo is the original")
    }

    @Test
    fun `a file off the phone is a partner - held as a WAV under its own name, one at a time, the CLI's label, undo`() {
        val m = model("Picker")
        val hold = File(temp, "parents")
        val first = MutateSheet.hold(hold, "downloads/old take.mp3", tone(700.0, 0.4f))
        assertEquals("old take.mp3", first.label, "the name the file came with, extension and all")
        assertEquals("wav", first.file.extension)
        assertEquals(hold, first.file.parentFile)
        assertTrue(first.file.isFile, "held as a real WAV")
        assertEquals((0.4f * rate).toInt(), WavReader.read(first.file).frameCount)

        val clap = MutateSheet.hold(hold, "clap.wav", tone(1500.0, 0.3f))
        assertTrue(!first.file.exists(), "one is held at a time - the earlier pick's file goes")
        assertTrue(clap.file.isFile)
        assertEquals("clap.wav", MutateSheet.name(clap))

        val before = File(m.kitDir, m.pad(1)!!.sampleFile).readBytes()
        val outcome = MutateSheet.apply(m, 1, clap, Mutate.Mode.STACK, 0f)
        m.save()
        val applied = MutateSheet.read(outcome.pad.recipe)!!
        assertEquals("STACK", applied.mode)
        assertEquals(listOf("clap.wav"), applied.parents, "the file's own name in the lineage, as the CLI writes it")
        assertTrue(!File(m.kitDir, m.pad(1)!!.sampleFile).readBytes().contentEquals(before), "the hit changed")

        val e = assertFailsWith<IllegalArgumentException> {
            MutateSheet.hold(hold, "quiet.wav", Snip(FloatArray(rate / 10), 1, rate))
        }
        assertTrue(e.message!!.contains("silent"), e.message)

        MutateSheet.undo(m, 1)
        m.save()
        assertTrue(File(m.kitDir, m.pad(1)!!.sampleFile).readBytes().contentEquals(before), "undo is the original")
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
    fun `drift is one tap - the deal and the morph, MIX how far, read back as DRIFT`() {
        val m = model("Drft")
        model("Drft2")
        val d = MutateSheet.drift(m, 1, root = temp, seed = 2, fraction = 0.25f)
        val applied = MutateSheet.read(d.outcome.pad.recipe)!!
        assertTrue(applied.drifted)
        assertEquals("DRIFT", applied.word)
        assertEquals("MORPH", applied.mode)
        assertEquals(listOf(d.pick.label), applied.parents)
        val recipe = (d.outcome.pad.recipe!!.entries["mutate"] as com.snipsnap.json.JsonValue.Obj).entries
        assertEquals(0.25, (recipe["amount"] as com.snipsnap.json.JsonValue.Num).value, 1e-6)
        MutateSheet.undo(m, 1)
        assertNull(m.pad(1)!!.recipe)
        val empty = File(temp, "empty-drift").apply { mkdirs() }
        assertFailsWith<IllegalArgumentException> { MutateSheet.drift(m, 1, root = empty, seed = 0, fraction = 0.5f) }
    }

    @Test
    fun `read ignores every other recipe shape`() {
        val m = model("Other")
        m.eraPad(1, "tape", 0.5f)
        assertNull(MutateSheet.read(m.pad(1)!!.recipe))
        assertNull(MutateSheet.read(null))
    }
}
