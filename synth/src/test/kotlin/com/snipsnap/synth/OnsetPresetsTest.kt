package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Factory recipes are listening-study snapshots, including their renderer versions. */
class OnsetPresetsTest {
    @Test
    fun `original twelve keep their names complete settings and model three recipes`() {
        data class Legacy(val voice: OnsetVoice, val names: List<String>, val values: List<Float>)
        val expected = listOf(
            Legacy(OnsetVoice.KICK, listOf("PULSE KICK", "HYBRID KICK", "MEMBRANE KICK"),
                listOf(.35f, .45f, .5f, .5f, .6f, .5f)),
            Legacy(OnsetVoice.SNARE, listOf("MEMBRANE SNARE", "HYBRID SNARE", "FM SNARE"),
                listOf(.4f, .5f, .5f, .6f, .6f, .5f)),
            Legacy(OnsetVoice.HAT_CLOSED, listOf("SQUARE CLOSED", "HYBRID CLOSED", "RESONANT CL"),
                listOf(.5f, .3f, .5f, .35f, .45f, .5f)),
            Legacy(OnsetVoice.HAT_OPEN, listOf("SQUARE OPEN", "HYBRID OPEN", "RESONANT OPEN"),
                listOf(.5f, .55f, .5f, .35f, .45f, .5f)),
        ).flatMap { family ->
            listOf(0f, .5f, 1f).zip(family.names) { blend, name ->
                OnsetPatch(name, family.voice, MACROS.zip(family.values).toMap() + ("BLEND" to blend),
                    modelVersion = 3)
            }
        }
        val actual = OnsetPresets.all().take(12)
        assertEquals(expected, actual)
        assertEquals(expected.map { it.toJsonText() }, actual.map { it.toJsonText() })
        for (preset in actual) {
            assertContentEquals(Onset.render(preset.voice, preset.macros, modelVersion = 3).samples,
                preset.render().samples, preset.name)
        }
    }

    @Test
    fun `extension kit selects the seven exact study nine midpoint snapshots in order`() {
        val study = OnsetPresets.extensionStudy()
        assertEquals(listOf("FLOOR HYBRID", "MID HYBRID", "HIGH HYBRID", "DRY HYBRID",
            "WIRE HYBRID", "WOOD HYBRID", "METAL HYBRID"), study.map { it.name })
        assertEquals(listOf(OnsetVoice.TOM, OnsetVoice.TOM, OnsetVoice.TOM,
            OnsetVoice.PERC_PLUCK, OnsetVoice.PERC_PLUCK, OnsetVoice.PERC_WOOD,
            OnsetVoice.PERC_METAL), study.map { it.voice })
        // IEEE-754 snapshots taken from Study 009's parameter document, independently
        // of current renderer defaults. A decimal rounding or macro-order change matters.
        val bits = listOf(
            listOf(0x3e215b8c, 0x3f0f5c29, 0x3f000000, 0x3ec28f5c, 0x3f1eb852, 0x3eb851ec),
            listOf(0x3ef0ef78, 0x3eeb851f, 0x3f000000, 0x3ee147ae, 0x3f147ae1, 0x3ee147ae),
            listOf(0x3f49ebe8, 0x3eb851ec, 0x3f000000, 0x3f000000, 0x3f000000, 0x3f051eb8),
            listOf(0x3ea28171, 0x3e8f5c29, 0x3f000000, 0x3ea3d70a, 0x3e75c28f, 0x3eae147b),
            listOf(0x3f4eaa8d, 0x3f1eb852, 0x3f000000, 0x3f0a3d71, 0x3f3851ec, 0x3f428f5c),
            listOf(0x3e58bdc1, 0x3e3851ec, 0x3f000000, 0x3ecccccd, 0x3e3851ec, 0x3eae147b),
            listOf(0x3f43378b, 0x3f3851ec, 0x3f000000, 0x3ef5c28f, 0x3f5c28f6, 0x3f3d70a4),
        )
        for ((patch, expected) in study.zip(bits)) {
            assertEquals(4, patch.modelVersion, patch.name)
            assertEquals(MACROS, patch.macros.keys.toList(), patch.name)
            assertEquals(expected, MACROS.map { patch.macros.getValue(it).toBits() }, patch.name)
        }
    }

    @Test
    fun `factory exposes only the seven studied extension triplets after the legacy twelve`() {
        val all = OnsetPresets.all()
        assertEquals(33, all.size)
        val extension = all.drop(12)
        assertEquals(mapOf(OnsetVoice.TOM to 9, OnsetVoice.PERC_PLUCK to 6,
            OnsetVoice.PERC_WOOD to 3, OnsetVoice.PERC_METAL to 3),
            extension.groupingBy { it.voice }.eachCount())
        assertEquals(OnsetPresets.extensionStudy(), extension.chunked(3).map { it[1] })
        for (triplet in extension.chunked(3)) {
            assertEquals(listOf(0f, .5f, 1f), triplet.map { it.macros.getValue("BLEND") })
            assertEquals(1, triplet.map { it.macros - "BLEND" }.distinct().size)
            assertTrue(triplet.all { it.modelVersion == 4 })
        }
        for (voice in OnsetVoice.entries) {
            assertEquals(all.filter { it.voice == voice }, Presets.forVoice("ONSET", voice.name))
        }
    }

    @Test
    fun `every factory recipe round trips and renders finite unnormalized audio`() {
        for (preset in OnsetPresets.all()) {
            val restored = OnsetPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, preset.name)
            assertEquals(preset, Patches.fromJsonText(preset.toJsonText()), preset.name)
            val recipe = PadRecipe(preset)
            val reopened = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, reopened, preset.name)
            val rendered = preset.render()
            assertTrue(rendered.frameCount > 0, preset.name)
            assertTrue(rendered.samples.all { it.isFinite() && it in -1f..1f }, preset.name)
            assertTrue(rendered.peak() > .001f, "${preset.name} is silent")
            assertContentEquals(rendered.samples, reopened.render().samples, preset.name)
        }
    }

    @Test
    fun `factory names are short uppercase unique and free of maker names`() {
        val presets = OnsetPresets.all()
        assertEquals(presets.size, presets.map { it.name }.distinct().size)
        for (preset in presets) {
            assertTrue(preset.name.length <= 14, preset.name)
            assertEquals(preset.name.uppercase(), preset.name)
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name), preset.name)
        }
    }

    private companion object {
        val MACROS = listOf("TUNE", "DECAY", "BLEND", "SNAP", "BODY", "EDGE")
    }
}
