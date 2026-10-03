package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A struck TERRA pad as recipe data (spec, "Data flow and compatibility";
 * "Testing", tests 4 and 5 with a striker only - R3 extends both to BEND
 * and TALK). It covers the version rule, the refusals in init and on
 * decode, copies in and out, and the striker riding through `withMacros`
 * and `Velocity`. `:shell`'s BREED, presets and replay are
 * TerraStrikerCarryTest's.
 */
class TerraPatchTest {

    private val head = TerraStrikers.head("tsnare")
    private val struck = TerraPatch("Struck", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.4f), TerraPatch.Striker(head, 0.5f, "A02"))
    private val plain = TerraPatch("Struck", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.4f))

    private fun version(p: Patch) = (p.toJsonValue().entries.getValue("version") as JsonValue.Num).value

    @Test
    fun `a struck patch writes version 2 with its striker, and a plain one version 1 without`() {
        assertEquals(2.0, version(struck))
        assertEquals(listOf("engine", "version", "name", "voice", "macros", "striker"), struck.toJsonValue().entries.keys.toList())
        val st = (struck.toJsonValue().entries.getValue("striker") as JsonValue.Obj).entries
        assertEquals(listOf("from", "hit", "head"), st.keys.toList())
        assertEquals(Fork.STRIKER_SAMPLES, st.getValue("head").arr().size)
        assertEquals(1.0, version(plain))
        assertFalse("striker" in plain.toJsonValue().entries)
        val unlabelled = struck.copy(striker = TerraPatch.Striker(head, 0.5f))
        assertFalse("from" in (unlabelled.toJsonValue().entries.getValue("striker") as JsonValue.Obj).entries, "a null label is written")
    }

    @Test
    fun `a struck patch round-trips by content and re-encodes to the same bytes`() {
        val variants = listOf(struck, struck.copy(striker = TerraPatch.Striker(head, 1f)), struck.copy(striker = TerraPatch.Striker(head, 0f, "SOUL A03")))
        for (p in variants) {
            val text = p.toJsonText()
            val back = TerraPatch.fromJsonText(text)
            assertEquals(p, back)
            assertEquals(p.hashCode(), back.hashCode())
            assertEquals(text, back.toJsonText())
            assertEquals(p, Patches.fromJsonText(text), "through the dispatcher")
            val recipe = PadRecipe(patch = p)
            val again = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, again)
            assertContentEquals(recipe.render().samples, again.render().samples)
        }
    }

    @Test
    fun `equals and hashCode compare the striker by content, label included`() {
        val twin = TerraPatch("Struck", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.4f), TerraPatch.Striker(head.copyOf(), 0.5f, "A02"))
        assertEquals(struck, twin)
        assertEquals(struck.hashCode(), twin.hashCode())
        val relabelled = struck.copy(striker = TerraPatch.Striker(head, 0.5f, "SOUL A03"))
        assertNotEquals(struck, relabelled)
        assertNotEquals(struck.toJsonText(), relabelled.toJsonText())
        assertNotEquals(struck, plain)
        assertNotEquals(struck, struck.copy(striker = TerraPatch.Striker(head, 0.75f, "A02")))
    }

    @Test
    fun `the head is copied in and out`() {
        val raw = head.copyOf()
        val s = TerraPatch.Striker(raw, 0.5f)
        raw[0] = 99f
        assertEquals(head[0], s.head[0], "a change to the array passed in reached the striker")
        s.head[1] = 99f
        assertEquals(head[1], s.head[1], "a change to the array read out reached the striker")
    }

    @Test
    fun `a broken striker is refused when it is built`() {
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(FloatArray(881), 0.5f) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(FloatArray(883), 0.5f) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(head.copyOf().also { it[3] = Float.NaN }, 0.5f) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(head.copyOf().also { it[3] = Float.POSITIVE_INFINITY }, 0.5f) }
        for (bad in listOf(Float.NaN, -0.01f, 1.01f)) assertFailsWith<IllegalArgumentException>("HIT $bad") { TerraPatch.Striker(head, bad) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(head, 0.5f, "A".repeat(TerraPatch.MAX_FROM_CHARS + 1)) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(head, 0.5f, "A\nB") }
        assertEquals("A".repeat(TerraPatch.MAX_FROM_CHARS), TerraPatch.Striker(head, 0.5f, "A".repeat(TerraPatch.MAX_FROM_CHARS)).from)
    }

    private fun edited(edit: (LinkedHashMap<String, JsonValue>) -> Unit): JsonValue {
        val obj = LinkedHashMap(struck.toJsonValue().entries)
        edit(obj)
        return JsonValue.Obj(obj)
    }

    private fun strikerEdited(edit: (LinkedHashMap<String, JsonValue>) -> Unit): JsonValue = edited { o ->
        val s = LinkedHashMap((o.getValue("striker") as JsonValue.Obj).entries)
        edit(s)
        o["striker"] = JsonValue.Obj(s)
    }

    /** A hand-edited sidecar is refused, not silently played: the door SNAP's and FORK's arrays guard (spec "Testing", test 5). */
    @Test
    fun `a broken or mislabelled struck recipe is refused on decode`() {
        val items = (struck.toJsonValue().entries.getValue("striker") as JsonValue.Obj).entries.getValue("head").arr()
        val cases = linkedMapOf(
            "a head one sample short" to strikerEdited { it["head"] = JsonValue.Arr(items.dropLast(1)) },
            "a head one sample long" to strikerEdited { it["head"] = JsonValue.Arr(items + JsonValue.Num(0.0)) },
            "a head value past float range" to strikerEdited { it["head"] = JsonValue.Arr(items.toMutableList().also { l -> l[3] = JsonValue.Num(1e300) }) },
            "a head value that is a string" to strikerEdited { it["head"] = JsonValue.Arr(items.toMutableList().also { l -> l[3] = JsonValue.Str("NaN") }) },
            "a head that is an object" to strikerEdited { it["head"] = JsonValue.Obj(emptyMap()) },
            "no head" to strikerEdited { it.remove("head") },
            "HIT above 1" to strikerEdited { it["hit"] = JsonValue.Num(1.5) },
            "HIT below 0" to strikerEdited { it["hit"] = JsonValue.Num(-0.25) },
            "no HIT" to strikerEdited { it.remove("hit") },
            "a from that is a number" to strikerEdited { it["from"] = JsonValue.Num(3.0) },
            "a from that is an object" to strikerEdited { it["from"] = JsonValue.Obj(emptyMap()) },
            "a from of 25 characters" to strikerEdited { it["from"] = JsonValue.Str("A".repeat(25)) },
            "a from with a control character" to strikerEdited { it["from"] = JsonValue.Str("A\u0001") },
            "a striker that is an array" to edited { it["striker"] = JsonValue.Arr(emptyList()) },
            "version 1 carrying a striker" to edited { it["version"] = JsonValue.Num(1.0) },
            "version 2 carrying none" to edited { it.remove("striker") },
            "version 2 with a null striker" to edited { it["striker"] = JsonValue.Null },
            "version 3" to edited { it["version"] = JsonValue.Num(3.0) },
        )
        for ((label, json) in cases) {
            assertFailsWith<JsonException>(label) { TerraPatch.fromJsonValue(json) }
            assertFailsWith<JsonException>("$label, through the dispatcher") { Patches.fromJsonValue(json) }
        }
        val plainWithNull = JsonValue.Obj(LinkedHashMap(plain.toJsonValue().entries).also { it["striker"] = JsonValue.Null })
        assertEquals(plain, TerraPatch.fromJsonValue(plainWithNull), "an explicit null striker on a plain patch reads as none")
    }

    /** What an older build runs: the shared version-1 decode refuses a struck patch by its version (`Patches.kt:85-86`). */
    @Test
    fun `the decode an older build runs refuses a struck patch by version`() {
        val thrown = assertFailsWith<JsonException> {
            Patches.decode(struck.toJsonValue(), TerraPatch.ENGINE, { n -> TerraVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                TerraPatch(name, voice, macros)
            }
        }
        assertTrue("unsupported patch version 2" in (thrown.message ?: ""), "the refusal must name the version: ${thrown.message}")
    }

    @Test
    fun `a striker renders through HIT, its label is never heard, and HIT 0 is today's pad`() {
        assertContentEquals(Terra.renderStruck(struck.voice, struck.macros, head, 0.5f).samples, struck.render().samples)
        assertFalse(struck.render().samples.contentEquals(plain.render().samples), "the striker did nothing")
        val relabelled = struck.copy(striker = TerraPatch.Striker(head, 0.5f, "SOUL A03"))
        assertContentEquals(struck.render().samples, relabelled.render().samples, "the label changed the sound")
        assertContentEquals(plain.render().samples, struck.copy(striker = TerraPatch.Striker(head, 0f)).render().samples, "HIT 0 is not today's pad")
    }

    @Test
    fun `withMacros keeps the striker`() {
        assertEquals(struck.striker, struck.withMacros(mapOf("TUNE" to 0.4f, "DECAY" to 0.8f)).striker)
    }

    /** The striker stores the hit, not its projection; the receiver's modes are recomputed at render (spec, "HIT, the design"). */
    @Test
    fun `retuning a struck pad keeps its striker and recomputes the colouring`() {
        val retuned = struck.withMacros(mapOf("TUNE" to 0.9f))
        val plainRetuned = plain.withMacros(mapOf("TUNE" to 0.9f))
        assertEquals(struck.striker, retuned.striker)
        val out = retuned.render().samples
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f }, "the retuned struck pad left the range")
        assertEquals(plainRetuned.render().samples.size, out.size, "the retuned struck pad changed length")
        assertFalse(out.contentEquals(plainRetuned.render().samples), "the retuned pad lost its striker's colour")
        val x = Terra.upsample(head)
        val before = Terra.hitLevel(TerraMeasure.bodyOf(struck.voice, struck.macros), x, 0.5f)!!
        val after = Terra.hitLevel(TerraMeasure.bodyOf(retuned.voice, retuned.macros), x, 0.5f)!!
        assertTrue(before.indices.any { !before[it].contentEquals(after[it]) }, "the colouring did not follow the new tuning")
    }

    /**
     * The spec's central data-flow claim ("Data flow and compatibility"): a
     * struck pad is an ordinary synth pad, saved verbatim in kit.json and
     * regenerated by PadRecipe.render(). This is PadRecipeTest's `the whole
     * point - a kit on disk regenerates itself from its sidecar`, with a
     * struck pad beside a plain one. The recipe's size is printed beside the
     * spec's estimate of about 31 KB for a striker (inferred there, never
     * measured).
     */
    @Test
    fun `a struck pad on disk regenerates itself from its sidecar`() {
        val dir = java.nio.file.Files.createTempDirectory("terra-struck-kit").toFile()
        try {
            val arranged = listOf(struck, plain).map { p -> ArrangedPad(p.render(), DrumClass.TOM, PadRecipe(patch = p).toJsonValue()) }
            KitAssembler.assembleArranged("Struck", arranged, dir)
            val loaded = KitStore.load(dir)
            assertEquals(2, loaded.pads.size)
            for (pad in loaded.pads) {
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe) { "pad ${pad.slot} lost its recipe" })
                assertContentEquals(arranged[pad.slot - 1].snip.samples, recipe.render().samples, "pad ${pad.slot} did not regenerate bit for bit")
            }
            val back = PadRecipe.fromJsonValue(requireNotNull(loaded.pads.first { it.slot == 1 }.recipe)).patch
            assertEquals(struck, back, "the striker did not survive kit.json")
        } finally {
            dir.deleteRecursively()
        }
        println("TERRA struck recipe: ${PadRecipe(patch = struck).toJsonText().length} characters of JSON (spec: about 31 KB for a striker, inferred, not measured)")
    }

    /** TERRA keeps the soften fallback, and the render it softens is the struck one; HIT is not a brightness override (spec, "HIT, the design", Velocity). */
    @Test
    fun `velocity layers soften the struck render, and HIT is not a brightness macro`() {
        assertNull(Velocity.brightnessSpec(struck))
        assertContentEquals(Velocity.soften(struck.render(), 0.5f).samples, Velocity.atVelocity(struck, 0.5f).samples)
        assertFalse(Velocity.atVelocity(struck, 0.5f).samples.contentEquals(Velocity.atVelocity(plain, 0.5f).samples))
        val reference = plain.render()
        val layered = Velocity.variantsAt(reference, struck)
        val unstruck = Velocity.variantsAt(reference, plain)
        assertEquals(2, layered.size)
        assertTrue(layered.indices.any { !layered[it].samples.contentEquals(unstruck[it].samples) }, "the velocity layers ignored the striker")
    }
}
