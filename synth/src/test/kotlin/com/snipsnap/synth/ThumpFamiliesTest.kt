package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThumpFamiliesTest {
    private fun base(macros: Map<String, Float> = emptyMap()) =
        ThumpPatch("Expression", ThumpVoice.SPARK, macros)

    @Test
    fun `default family is four ordered editable snapshots with stable tuning and punch`() {
        val source = base(mapOf("TUNE" to .37f, "PUNCH" to .6f))
        val original = source.macros.toMap()
        val family = ThumpFamilies.patches(source)
        assertEquals(listOf(0f, 1f / 3f, 2f / 3f, 1f), ThumpFamilies.DEFAULT_POSITIONS)
        assertEquals(4, family.size)
        assertEquals(original, source.macros, "making related hits changed the source recipe")
        assertEquals(listOf("Expression 1", "Expression 2", "Expression 3", "Expression 4"),
            family.map { it.name })
        for (patch in family) {
            assertEquals(ThumpVoice.SPARK, patch.voice)
            assertEquals(.37f, patch.macros.getValue("TUNE"))
            assertEquals(.6f, patch.macros.getValue("PUNCH"))
            assertEquals(Thump.defaults(ThumpVoice.SPARK).keys, patch.macros.keys)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            assertEquals(patch, ThumpPatch.fromJsonText(patch.toJsonText()))
        }
        assertTrue(family.zipWithNext().all { (a, b) ->
            a.macros.getValue("METAL") < b.macros.getValue("METAL") &&
                a.macros.getValue("COLOR") < b.macros.getValue("COLOR") &&
                a.macros.getValue("DECAY") > b.macros.getValue("DECAY")
        }, "family order lost the coordinated bright-short gesture")
    }

    @Test
    fun `the center reproduces the source and endpoints have deliberate bounded offsets`() {
        val source = base(mapOf("METAL" to .5f, "COLOR" to .5f, "DECAY" to .5f))
        val family = ThumpFamilies.patches(source, listOf(0f, .5f, 1f))
        val expected = listOf(
            mapOf("METAL" to .15f, "COLOR" to .15f, "DECAY" to .65f),
            mapOf("METAL" to .5f, "COLOR" to .5f, "DECAY" to .5f),
            mapOf("METAL" to .85f, "COLOR" to .85f, "DECAY" to .35f),
        )
        for ((patch, controls) in family.zip(expected)) for ((name, value) in controls) {
            assertEquals(value, patch.macros.getValue(name), 1e-6f, "$name lost the expression law")
        }
        assertContentEquals(source.render().samples, family[1].render().samples,
            "the middle coordinate must preserve the actual source sound")

        val clipped = ThumpFamilies.patches(base(mapOf("METAL" to .9f, "COLOR" to .1f,
            "DECAY" to .95f)), listOf(0f, 1f))
        assertEquals(0f, clipped[0].macros.getValue("COLOR"))
        assertEquals(1f, clipped[0].macros.getValue("DECAY"))
        assertEquals(1f, clipped[1].macros.getValue("METAL"))
    }

    @Test
    fun `saved family members replay independently and retain the class of their actual audio`() {
        val source = base(mapOf("TUNE" to .55f, "METAL" to .5f, "COLOR" to .5f))
        val positions = listOf(0f, .5f, 1f)
        val first = ThumpFamilies.render(source, positions)
        val second = ThumpFamilies.render(source, positions)
        assertEquals(positions, first.map { it.position })
        assertEquals(first.map { it.patch }, second.map { it.patch })
        for ((hit, repeated) in first.zip(second)) {
            assertContentEquals(hit.snip.samples, repeated.snip.samples)
            val replay = PadRecipe.fromJsonText(PadRecipe(hit.patch).toJsonText()).render()
            assertContentEquals(hit.snip.samples, replay.samples,
                "a family member depends on unsaved source or expression state")
            assertEquals(Classifier.classify(hit.snip).drumClass, hit.drumClass,
                "family class was guessed from its source or name")
        }
        assertTrue(first.zipWithNext().all { (a, b) -> !a.snip.samples.contentEquals(b.snip.samples) },
            "adjacent expression coordinates produced identical audio")
    }

    @Test
    fun `family snapshots do not share the caller's mutable macro map`() {
        val controls = mutableMapOf("TUNE" to .4f, "PUNCH" to .3f)
        val source = base(controls)
        val family = ThumpFamilies.patches(source)
        val saved = family.map { it.toJsonText() }
        controls["TUNE"] = .9f
        assertEquals(saved, family.map { it.toJsonText() },
            "later editing the source silently rewrote existing family members")
    }

    @Test
    fun `invalid expression lists and other voices are refused before rendering`() {
        assertFailsWith<IllegalArgumentException> {
            ThumpFamilies.patches(ThumpPatch("Tom", ThumpVoice.TOM, emptyMap()))
        }
        val invalid = listOf(
            emptyList(), listOf(.5f), List(17) { it / 16f },
            listOf(0f, .5f, .5f, 1f), listOf(1f, 0f),
            listOf(-.1f, 1f), listOf(0f, 1.1f),
            listOf(0f, Float.NaN), listOf(0f, Float.POSITIVE_INFINITY),
        )
        for (positions in invalid) assertFailsWith<IllegalArgumentException>("positions=$positions") {
            ThumpFamilies.patches(base(), positions)
        }
        assertEquals(2, ThumpFamilies.patches(base(), listOf(0f, 1f)).size)
        assertEquals(16, ThumpFamilies.patches(base(), List(16) { it / 15f }).size)
    }
}
