package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutateSheetTest {

    /**
     * DRIFT's blend, pinned.
     *
     * The card used to hand DRIFT whatever fraction the stepper held for
     * the move it was *currently* on. That move is [MutateSheet.MODES]'s
     * first entry until the user picks another, and it has no knob at all,
     * so the fraction was `0f`: the common DRIFT tap rewrote the pad's WAV
     * blending none of the neighbour in, then drew MIX at 50%.
     *
     * Each assertion below fails on a different way of reintroducing that:
     * the first if the opening move ever gains a knob (which would hide the
     * `0f`), the second and fourth if [MutateSheet.DRIFT_FRACTION] stops
     * tracking MORPH's own default, the third on a literal `0f`.
     */
    @Test
    fun `DRIFT blends by MORPH's own knob, never by the move the card opened on`() {
        val opening = MutateSheet.modeFor(MutateSheet.MODES.first())
        assertNull(
            MutateSheet.knobFor(opening),
            "the card opens on $opening, which has no knob - a fraction inherited from it is 0f",
        )

        val mix = MutateSheet.knobFor(Mutate.Mode.MORPH)
            ?: error("MORPH must have a knob: it is the one DRIFT blends with")

        assertEquals(mix.defaultFraction, MutateSheet.DRIFT_FRACTION, 1e-6f)
        assertTrue(
            MutateSheet.DRIFT_FRACTION > 0f,
            "a 0 fraction blends none of the neighbour in, which is the bug this pins",
        )
        assertEquals(
            mix.default,
            MutateSheet.value(mix, MutateSheet.DRIFT_FRACTION),
            1e-6f,
            "DRIFT's fraction must land on MIX's own default",
        )
    }

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
    fun `preview is the sound KEEP would write, for every move`() {
        // The card's whole promise: what you hear is what you get. If
        // HEAR and KEEP could compute a different splice point, a
        // different crossover or a different band count, auditioning
        // would be theatre. They read one knob mapping and one render.
        for (mode in Mutate.Mode.values()) {
            val m = model("Heard$mode")
            val partner = MutateSheet.Partner.Pad(2)
            val heard = MutateSheet.preview(m, 1, partner, mode, 0.5f)

            MutateSheet.apply(m, 1, partner, mode, 0.5f)
            val kept = WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile))

            assertEquals(heard.channels, kept.channels, "$mode: channels")
            assertEquals(heard.sampleRate, kept.sampleRate, "$mode: rate")
            assertEquals(heard.samples.size, kept.samples.size, "$mode: length")
            // Within one 24-bit step: `replaceAudio` writes through
            // `WavWriter.write`'s default depth, PCM_24, which scales by
            // 8_388_607 - so this is the file's own quantisation and
            // nothing else. A 16-bit bound would be 256x looser and would
            // let a materially different preview pass.
            var worst = 0f
            for (i in heard.samples.indices) {
                val d = Math.abs(heard.samples[i] - kept.samples[i])
                if (d > worst) worst = d
            }
            assertTrue(worst <= 2f / 8_388_607f, "$mode: heard and kept differ by $worst, more than the file's own step")
        }
    }

    @Test
    fun `preview leaves the kit exactly as it found it`() {
        val m = model("Untouched")
        val pad = m.pad(1)!!
        val wav = File(m.kitDir, pad.sampleFile)
        val before = wav.readBytes()

        repeat(3) { MutateSheet.preview(m, 1, MutateSheet.Partner.Pad(2), Mutate.Mode.MORPH, 0.75f) }

        assertTrue(before.contentEquals(wav.readBytes()), "the pad's audio moved")
        assertNull(m.pad(1)!!.recipe, "a preview left a recipe behind")
        assertNull(MutateSheet.read(m.pad(1)!!.recipe), "a preview read back as mutated")
        assertTrue(m.binContents().isEmpty(), "a preview put something in the bin")
    }

    @Test
    fun `preview refuses what keeping it would refuse`() {
        // A move the keep would decline must not be audible first: the
        // player would hear a sound the card then refuses to give them.
        val m = model("Refused")
        assertFailsWith<IllegalArgumentException>("a pad can't be its own parent") {
            MutateSheet.preview(m, 1, MutateSheet.Partner.Pad(1), Mutate.Mode.MORPH, 0.5f)
        }
        assertFailsWith<IllegalArgumentException>("no pad on that slot") {
            MutateSheet.preview(m, 7, MutateSheet.Partner.Pad(2), Mutate.Mode.MORPH, 0.5f)
        }
        // The two the WRITE refuses, which a preview skipped until review
        // caught it: a round-robin chain is several files pretending to be
        // one pad, and a velocity-layered pad has more than one sound to
        // replace. Heard-then-refused is worse than never heard.
        val chained = model("Chained")
        Robin.apply(chained, 2, takes = 2)
        val why = assertFailsWith<IllegalArgumentException> {
            MutateSheet.preview(chained, 2, MutateSheet.Partner.Pad(1), Mutate.Mode.MORPH, 0.5f)
        }.message
        assertTrue(why != null && "round-robin" in why, "said: $why")
        // And it is the same refusal the keep gives, not a lookalike.
        val kept = assertFailsWith<IllegalArgumentException> {
            MutateSheet.apply(chained, 2, MutateSheet.Partner.Pad(1), Mutate.Mode.MORPH, 0.5f)
        }.message
        assertEquals(kept, why, "HEAR and KEEP refuse in different words")
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

    // ---------- BECOME ----------

    @Test
    fun `BECOME is MORPH's second knob - linear 0 to 2000 ms, OFF at rest, 50 ms steps`() {
        val b = MutateSheet.BECOME
        assertEquals("BECOME", b.label)
        assertEquals(0f, b.lo)
        assertEquals(2000f, b.hi)
        assertEquals(0f, b.default)
        assertEquals(0f, b.defaultFraction)
        assertEquals(b, MutateSheet.becomeFor(Mutate.Mode.MORPH))
        for (mode in Mutate.Mode.values().filter { it != Mutate.Mode.MORPH }) {
            assertNull(MutateSheet.becomeFor(mode), "$mode has no BECOME")
        }
        assertEquals("MIX", MutateSheet.knobFor(Mutate.Mode.MORPH)!!.label, "BECOME is a second knob, not MORPH's first")
        // The phone snaps the stepper to 1/40 (PadSheetScreen's onKnobChange): every step is 50 ms.
        for (step in 0..40) assertEquals(50 * step, MutateSheet.value(b, step / 40f).roundToInt(), "step $step")
        assertEquals(0f, MutateSheet.value(b, Float.NaN), "a fraction that is not a number reads as OFF")
        assertEquals("OFF", MutateSheet.label(b, 0f))
        assertEquals("50 ms", MutateSheet.label(b, 50f))
        assertEquals("500 ms", MutateSheet.label(b, MutateSheet.value(b, 0.25f)))
        assertEquals("2000 ms", MutateSheet.label(b, 2000f))
    }

    @Test
    fun `HEAR is KEEP with BECOME on, and BECOME rides the recipe`() {
        for (f in listOf(0f, 0.25f, 1f)) {
            val m = model("Become${(f * 100).roundToInt()}")
            val partner = MutateSheet.Partner.Pad(2)
            val heard = MutateSheet.preview(m, 1, partner, Mutate.Mode.MORPH, 0.5f, f)
            val outcome = MutateSheet.apply(m, 1, partner, Mutate.Mode.MORPH, 0.5f, f)
            val kept = WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile))
            assertEquals(heard.channels, kept.channels, "BECOME fraction $f: channels")
            assertEquals(heard.samples.size, kept.samples.size, "BECOME fraction $f: length")
            var worst = 0f
            for (i in heard.samples.indices) worst = maxOf(worst, Math.abs(heard.samples[i] - kept.samples[i]))
            assertTrue(worst <= 2f / 8_388_607f, "BECOME fraction $f: heard and kept differ by $worst, more than the file's own step")
            val mutate = (outcome.pad.recipe!!.entries["mutate"] as JsonValue.Obj).entries
            val applied = MutateSheet.read(outcome.pad.recipe)!!
            val ms = (f * Mutate.MAX_BECOME_MS).roundToInt()
            if (ms == 0) {
                assertTrue("become" !in mutate, "BECOME OFF writes no key: ${mutate.keys}")
                assertEquals(0, applied.becomeMs)
                assertEquals("MORPH", applied.word)
            } else {
                assertEquals(ms.toDouble(), (mutate["become"] as JsonValue.Num).value)
                assertEquals(ms, applied.becomeMs)
                assertEquals("BECOME", applied.word)
            }
        }
    }

    @Test
    fun `a BECOME left dialled does nothing to a move that is not MORPH`() {
        // The card keeps one BECOME value across move switches; only MORPH may read it.
        for (mode in Mutate.Mode.values().filter { it != Mutate.Mode.MORPH }) {
            val m = model("Stale$mode")
            val partner = MutateSheet.Partner.Pad(2)
            val plain = MutateSheet.preview(m, 1, partner, mode, 0.5f, 0f)
            val stale = MutateSheet.preview(m, 1, partner, mode, 0.5f, 0.5f)
            assertContentEquals(plain.samples, stale.samples, "$mode heard a BECOME it does not read")
            val outcome = MutateSheet.apply(m, 1, partner, mode, 0.5f, 0.5f)
            val mutate = (outcome.pad.recipe!!.entries["mutate"] as JsonValue.Obj).entries
            assertTrue("become" !in mutate, "$mode wrote a BECOME it does not read: ${mutate.keys}")
            assertEquals(mode.name, MutateSheet.read(outcome.pad.recipe)!!.word)
        }
    }

    @Test
    fun `a ramped pad reads BECOME everywhere the move label goes, and a hostile become reads as none`() {
        fun recipe(mode: String, become: JsonValue?, drift: Boolean = false): JsonValue.Obj {
            val m = linkedMapOf<String, JsonValue>(
                "mode" to JsonValue.Str(mode),
                "with" to JsonValue.Arr(listOf(JsonValue.Str("Soul:A03"))),
            )
            if (drift) m["drift"] = JsonValue.Bool(true)
            if (become != null) m["become"] = become
            return JsonValue.Obj(linkedMapOf<String, JsonValue>("mutate" to JsonValue.Obj(m)))
        }
        val ramped = recipe("morph", JsonValue.Num(400.0))
        val applied = MutateSheet.read(ramped)!!
        assertEquals(400, applied.becomeMs)
        assertEquals("MORPH", applied.mode)
        assertEquals("BECOME", applied.word)
        // The strip, the takes diff and the replay refusal all read the move label (Decision 3).
        assertEquals("BECOME × SOUL A03", PadSheetBoxes.mutate(applied))
        assertEquals("MUTATED: BECOME", KitDiff.recipeName(ramped))
        assertEquals(
            RecipeReplay.Plan.Refused("MUTATE (BECOME WITH SOUL A03) NEEDS ITS PARTNER - NOT CARRIED."),
            RecipeReplay.plan(ramped),
        )

        val hostile = listOf(
            JsonValue.Str("400"), JsonValue.Num(Double.NaN), JsonValue.Num(Double.POSITIVE_INFINITY),
            JsonValue.Num(-5.0), JsonValue.Num(0.0), JsonValue.Num(1e9), JsonValue.Bool(true), JsonValue.Null,
        )
        for (h in hostile) {
            val a = MutateSheet.read(recipe("morph", h))!!
            assertEquals(0, a.becomeMs, "become = $h")
            assertEquals("MORPH", a.word, "become = $h")
        }
        val onSplice = MutateSheet.read(recipe("splice", JsonValue.Num(400.0)))!!
        assertEquals(0, onSplice.becomeMs, "a ramp on SPLICE is no ramp")
        assertEquals("SPLICE", onSplice.word)
        // DRIFT never takes BECOME; a hand-written ramp on a drift still reads DRIFT, the drift's word coming first.
        assertEquals("DRIFT", MutateSheet.read(recipe("morph", JsonValue.Num(400.0), drift = true))!!.word)
        // Built positionally, as PadSheetBoxesTest.kt:48-50 builds it, a plain MORPH still reads MORPH.
        assertEquals("MORPH", MutateSheet.Applied("MORPH", listOf("Soul:A03")).word)
    }

    // ---------- BECOME's card row (A1b) ----------

    /**
     * What the MUTATE card's second row leans on, pinned at the sheet.
     *
     * The row opens on `BECOME.defaultFraction` and DRIFT puts it back
     * there (ConventionTest's BECOME laws read both off the screen), so
     * that fraction must read OFF. ConventionTest's row law holds the
     * screen's snap to exactly the text `(f * 40f).roundToInt() / 40f`,
     * and this sweep proves that text only ever lands on whole 50 ms
     * steps, the first of which clears one 23 ms analysis window. The
     * label lives here and not in `Copy`, where PersonalityTest's shout
     * law cannot see it, so this holds it to the house style instead.
     * BECOME's own test above already pins the default, OFF and the
     * 50/500/2000 ms readouts; this does not restate them.
     */
    @Test
    fun `the BECOME row opens OFF, lands on 50 ms steps wherever a thumb lets go, and shouts its label`() {
        val b = MutateSheet.BECOME
        assertEquals(
            "OFF",
            MutateSheet.label(b, MutateSheet.value(b, b.defaultFraction)),
            "the row's holder opens on BECOME.defaultFraction and DRIFT puts it back there, so that fraction must read OFF",
        )

        // The screen's snap. ConventionTest's BECOME row law asserts the screen's expression is exactly this text,
        // so this sweep is a proof about the phone's snap, not about a private copy of it.
        fun snap(f: Float): Float = (f * 40f).roundToInt() / 40f
        val readouts = mutableListOf<String>()
        for (i in 0..1000) {
            val thumb = i / 1000f
            val ms = MutateSheet.value(b, snap(thumb))
            assertEquals(0, ms.roundToInt() % 50, "a thumb let go at $thumb landed on $ms ms, between steps")
            val text = MutateSheet.label(b, ms)
            // The lowercase unit is this card's own precedent (AT reads "40 ms", pinned above), not a slip.
            assertTrue(text == "OFF" || Regex("""[1-9]\d* ms""").matches(text), "a thumb at $thumb reads '$text'")
            readouts += text
        }
        assertEquals("50 ms", readouts.first { it != "OFF" }, "the first step a thumb reaches above OFF")
        assertEquals("2000 ms", readouts.last(), "a thumb let go at the far end")

        assertEquals(b.label.uppercase(), b.label, "a card label shouts")
        // A coarse proxy only. The label font is Silkscreen (TapeTheme.kt:59), which is proportional, and
        // BECOME's M is wider than any letter of ATTACK, so a letter count cannot see a pixel overflow of the
        // 44 dp label column. The phone check (line 3, "BECOME is not cut off") is the real gate; this
        // catches only a longer word.
        assertTrue(b.label.length <= "ATTACK".length, "'${b.label}' has more letters than ATTACK, the label column's longest word")
    }

    /**
     * DRIFT is a flat morph: the sheet's own door never hands it a ramp.
     * That is what makes the card's reset of BECOME to OFF on a DRIFT tap
     * a true readout rather than a guess (`Mutate.drift` is pinned the
     * same way in BecomeTest; this pins the door the phone calls).
     */
    @Test
    fun `a DRIFT from the card carries no BECOME, so the row's reset to OFF tells the truth`() {
        val m = model("Dbec")
        model("Dbec2")
        val d = MutateSheet.drift(m, 1, root = temp, seed = 2, fraction = 0.5f)
        val mutate = (d.outcome.pad.recipe!!.entries["mutate"] as JsonValue.Obj).entries
        assertTrue("become" !in mutate, "DRIFT wrote a ramp: ${mutate.keys}")
        val applied = MutateSheet.read(d.outcome.pad.recipe)!!
        assertEquals(0, applied.becomeMs, "the drifted pad reads back a ramp, so the card's OFF would be a lie")
        assertEquals("DRIFT", applied.word, "the drifted pad reads back as ${applied.word}, not DRIFT")
    }

    // ---------- honest refusals (the redesign's round M1) ----------

    /**
     * The pre-check every MUTATE door runs before it launches: a pad that
     * can never mutate says so before the card asks for a partner. A pad
     * cannot be layered and chained at once (`KitPad` refuses a chain with
     * velocity layers), so the order between those two is the code's own
     * and not testable here; each is pinned alone.
     */
    @Test
    fun `a door refuses a layered or chained pad first, and asks for a partner only when it needs one`() {
        val m = model("Pre")
        val plain = m.pad(1)!!
        assertEquals(MutateSheet.Refusal.NoPartner, MutateSheet.refusalBefore(plain, null, needsPartner = true))
        assertNull(MutateSheet.refusalBefore(plain, MutateSheet.Partner.Pad(2), needsPartner = true))
        assertNull(MutateSheet.refusalBefore(plain, null, needsPartner = false), "DRIFT and ROULETTE pick their own partner")

        m.addGhostLayers(2)
        val layered = m.pad(2)!!
        assertEquals(MutateSheet.Refusal.Layered, MutateSheet.refusalBefore(layered, null, needsPartner = true))
        assertEquals(MutateSheet.Refusal.Layered, MutateSheet.refusalBefore(layered, null, needsPartner = false))

        val chainedKit = model("PreChain")
        Robin.apply(chainedKit, 2, takes = 2)
        val chained = chainedKit.pad(2)!!
        assertEquals(MutateSheet.Refusal.Chained, MutateSheet.refusalBefore(chained, null, needsPartner = true))
        assertEquals(
            MutateSheet.Refusal.Chained,
            MutateSheet.refusalBefore(chained, null, needsPartner = false),
            "a DRIFT on a chained pad says it is chained, never that the shelf is empty",
        )

        assertEquals(Copy.MUTATE_PICK_PARTNER, MutateSheet.Refusal.NoPartner.line)
        assertEquals(Copy.MUTATE_LAYERED, MutateSheet.Refusal.Layered.line)
        assertEquals(Copy.MUTATE_CHAINED, MutateSheet.Refusal.Chained.line)
        assertEquals(Copy.CRATE_EMPTY, MutateSheet.Refusal.ShelfEmpty.line)
        assertEquals(Copy.ROULETTE_ONLY_COPIES, MutateSheet.Refusal.OnlyCopies.line)
        assertEquals(Copy.MUTATE_PARTNER_GONE, MutateSheet.Refusal.PartnerGone.line)
    }

    /**
     * ROULETTE and DRIFT used to turn every IllegalArgumentException into
     * "the shelf is empty". The typed refusal says which of the two things
     * the crate actually found, and anything else is a real failure.
     */
    @Test
    fun `ROULETTE says whether the shelf was empty or held only doubles, and nothing else reads as either`() {
        val alone = File(temp, "alone").apply { mkdirs() }
        val solo = KitBuilderModel.create("Solo", File(alone, "Solo"))
        solo.assign(1, tone(100.0, 0.5f), DrumClass.KICK)
        solo.save()
        val empty = assertFailsWith<Mutate.RouletteRefused> { MutateSheet.deal(solo, 1, root = alone, seed = 0) }
        assertEquals(Mutate.RouletteRefused.Kind.EMPTY, empty.kind)
        assertEquals("the crate under $alone has nothing to spin for", empty.message, "the CLI prints this message; it must not change")
        assertEquals(MutateSheet.Refusal.ShelfEmpty, MutateSheet.refusalOf(empty))

        val twins = File(temp, "twins").apply { mkdirs() }
        val one = KitBuilderModel.create("One", File(twins, "One"))
        one.assign(1, tone(100.0, 0.5f), DrumClass.KICK)
        one.save()
        val two = KitBuilderModel.create("Two", File(twins, "Two"))
        two.assign(1, tone(100.0, 0.5f), DrumClass.KICK)
        two.save()
        val doubles = assertFailsWith<Mutate.RouletteRefused> { MutateSheet.deal(one, 1, root = twins, seed = 0) }
        assertEquals(Mutate.RouletteRefused.Kind.ONLY_COPIES, doubles.kind)
        assertEquals("every sound in the crate is this pad's double - spin --wild instead", doubles.message)
        assertEquals(MutateSheet.Refusal.OnlyCopies, MutateSheet.refusalOf(doubles))
        val drift = assertFailsWith<Mutate.RouletteRefused> { MutateSheet.drift(one, 1, root = twins, seed = 0, fraction = 0.5f) }
        assertEquals(MutateSheet.Refusal.OnlyCopies, MutateSheet.refusalOf(drift))

        assertNull(MutateSheet.refusalOf(IllegalArgumentException("pad 2 is a round-robin chain - `robin --undo` before rewriting it")))
        assertNull(MutateSheet.refusalOf(java.io.IOException("disk full")))
        // The bug itself: a chained pad that slipped past the pre-check reaches the rewrite's own gate, and
        // that refusal is a real failure, never "the shelf is empty".
        val chained = model("DriftChain")
        model("DriftChain2")
        Robin.apply(chained, 2, takes = 2)
        val gate = assertFailsWith<IllegalArgumentException> { MutateSheet.drift(chained, 2, root = temp, seed = 0, fraction = 0.5f) }
        assertNull(MutateSheet.refusalOf(gate), "a chained pad's DRIFT read as ${MutateSheet.refusalOf(gate)}: ${gate.message}")
        assertContains(gate.message!!, "round-robin")
    }

    /**
     * A partner that is no longer there says so, for every kind, checked
     * before anything is read: otherwise the read throws an IOException
     * and the card can only say "TRY AGAIN", which never helps. One test
     * per kind, so a failure names the kind that broke; they share this
     * shelf and [assertGone].
     */
    private fun goneShelf(): Pair<File, KitBuilderModel> {
        val shelf = File(temp, "gone").apply { mkdirs() }
        val mine = KitBuilderModel.create("Mine", File(shelf, "Mine"))
        mine.assign(1, tone(100.0, 0.5f), DrumClass.KICK)
        mine.assign(2, tone(3000.0, 0.4f), DrumClass.SNARE)
        mine.save()
        return shelf to mine
    }

    private fun otherKit(shelf: File, name: String): KitBuilderModel = KitBuilderModel.create(name, File(shelf, name)).also {
        it.assign(3, tone(2000.0, 0.4f), DrumClass.SNARE)
        it.save()
    }

    /** A WAV that was written to [shelf] and then deleted: a partner file that is gone. */
    private fun goneWav(shelf: File, name: String): File = File(shelf, name).also { f ->
        f.outputStream().use { com.snipsnap.audio.WavWriter.write(it, tone(700.0, 0.3f)) }
        f.delete()
    }

    /** [partner] is gone: both `preview` and `apply` throw [MutateSheet.PartnerGone], which reads as its refusal. */
    private fun assertGone(mine: KitBuilderModel, partner: MutateSheet.Partner) {
        val e = assertFailsWith<MutateSheet.PartnerGone> { MutateSheet.preview(mine, 1, partner, Mutate.Mode.STACK, 0f) }
        assertEquals(MutateSheet.Refusal.PartnerGone, MutateSheet.refusalOf(e))
        assertFailsWith<MutateSheet.PartnerGone> { MutateSheet.apply(mine, 1, partner, Mutate.Mode.STACK, 0f) }
    }

    @Test
    fun `a gone partner says so - a deleted pad on this kit`() {
        val (_, mine) = goneShelf()
        mine.clear(2)
        assertGone(mine, MutateSheet.Partner.Pad(2))
    }

    @Test
    fun `a gone partner says so - a deleted pad on another kit`() {
        val (shelf, mine) = goneShelf()
        val soul = otherKit(shelf, "Soul")
        soul.clear(3)
        soul.save()
        assertGone(mine, MutateSheet.Partner.Other("Soul", soul.kitDir, 3))
    }

    @Test
    fun `a gone partner says so - another kit's folder deleted`() {
        val (shelf, mine) = goneShelf()
        val funk = otherKit(shelf, "Funk")
        funk.kitDir.deleteRecursively()
        assertGone(mine, MutateSheet.Partner.Other("Funk", funk.kitDir, 3))
    }

    @Test
    fun `a gone partner says so - another kit's kit json deleted`() {
        val (shelf, mine) = goneShelf()
        val jazz = otherKit(shelf, "Jazz")
        File(jazz.kitDir, com.snipsnap.kit.KitStore.FILE_NAME).delete()
        assertGone(mine, MutateSheet.Partner.Other("Jazz", jazz.kitDir, 3))
    }

    @Test
    fun `a gone partner says so - a ROULETTE pick's file deleted`() {
        val (shelf, mine) = goneShelf()
        assertGone(mine, MutateSheet.Partner.Deal("Soul:A03", goneWav(shelf, "pick.wav"), 1))
    }

    @Test
    fun `a gone partner says so - a kept room's file deleted`() {
        val (shelf, mine) = goneShelf()
        assertGone(mine, MutateSheet.Partner.Room("FUNK ROOM", goneWav(shelf, "room.wav")))
    }

    @Test
    fun `a gone partner says so - a held file deleted`() {
        val (shelf, mine) = goneShelf()
        assertGone(mine, MutateSheet.Partner.Wav("clap.wav", goneWav(shelf, "clap.wav")))
    }

    @Test
    fun `a partner file that is there but will not decode is a real failure, not a gone partner`() {
        val (shelf, mine) = goneShelf()
        val broken = File(shelf, "broken.wav").apply { writeText("not a wav") }
        val e = assertFailsWith<Exception> { MutateSheet.preview(mine, 1, MutateSheet.Partner.Wav("broken.wav", broken), Mutate.Mode.STACK, 0f) }
        assertTrue(e !is MutateSheet.PartnerGone, "a file that is there but will not decode is a real failure, not a gone partner")
        assertNull(MutateSheet.refusalOf(e))
    }

    /**
     * The pad's own file gone (a file manager, a sync) is not a gone
     * partner: telling the player to pick another partner would fail the
     * same way. The read's failure is a real one, null here, so the card
     * says `HEAR FAILED. TRY AGAIN.` (or KEEP's).
     */
    @Test
    fun `the pad's own file gone is a real failure, never a gone partner`() {
        val (_, mine) = goneShelf()
        File(mine.kitDir, mine.pad(1)!!.sampleFile).delete()
        for ((what, read) in listOf<Pair<String, () -> Unit>>(
            "preview" to { MutateSheet.preview(mine, 1, MutateSheet.Partner.Pad(2), Mutate.Mode.STACK, 0f) },
            "apply" to { MutateSheet.apply(mine, 1, MutateSheet.Partner.Pad(2), Mutate.Mode.STACK, 0f) },
        )) {
            val e = assertFailsWith<Exception>(what) { read() }
            assertTrue(e !is MutateSheet.PartnerGone, "$what: the pad's own file gone read as a gone partner")
            assertNull(MutateSheet.refusalOf(e), "$what: the pad's own file gone read as ${MutateSheet.refusalOf(e)}")
        }
    }

    @Test
    fun `UNDO says why it has nothing to do, and is quiet only when it can undo`() {
        val applied = MutateSheet.Applied("SPLICE", listOf("Kit:A02"))
        assertEquals(Copy.UNDO_NOTHING, MutateSheet.undoRefusal(null, binned = true))
        assertEquals(Copy.UNDO_NOTHING, MutateSheet.undoRefusal(null, binned = false))
        assertEquals(Copy.UNDO_NOT_BINNED, MutateSheet.undoRefusal(applied, binned = false))
        assertNull(MutateSheet.undoRefusal(applied, binned = true))
    }
}
