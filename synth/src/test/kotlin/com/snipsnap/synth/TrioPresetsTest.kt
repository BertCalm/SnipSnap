package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Factory scenes remain dry, finite and regenerable in their advertised bank order. */
class TrioPresetsTest {
    @Test
    fun `sixteen scenes cover the three solo identities and four ordered drama groups`() {
        val all = TrioPresets.all()
        assertEquals(16, all.size)
        assertEquals(16, all.map { it.name }.toSet().size, "preset names must identify one recipe")
        assertEquals(all, TrioPresetGroup.entries.flatMap { TrioPresets.forGroup(it) })
        for (group in TrioPresetGroup.entries) {
            val patches = TrioPresets.forGroup(group)
            assertEquals(4, patches.size, group.name)
            assertTrue(patches.all { TrioPresets.groupFor(it) == group }, group.name)
        }

        val roots = TrioPresets.forGroup(TrioPresetGroup.ROOTS)
        assertEquals(TrioVoice.entries.toSet(), roots.map { it.voice }.toSet())
        assertTrue(roots.all { it.macros.getValue("DRAMA") == 0f }, "the roots group introduces the main players alone")
        val dramaByGroup = linkedMapOf(
            TrioPresetGroup.LOW_DRAMA to listOf(.18f, .25f, .30f, .32f),
            TrioPresetGroup.MID_DRAMA to listOf(.42f, .50f, .58f, .65f),
            TrioPresetGroup.HIGH_DRAMA to listOf(.75f, .82f, .90f, 1f),
        )
        for ((group, drama) in dramaByGroup) {
            val patches = TrioPresets.forGroup(group)
            assertTrue(patches.all { it.voice == TrioVoice.ENSEMBLE }, group.name)
            assertEquals(drama, patches.map { it.macros.getValue("DRAMA") }.sorted(), group.name)
        }

        val notes = setOf(48, 51, 53, 55, 58, 60, 63, 65, 72)
        assertEquals(notes, all.map { it.midi }.toSet(), "the bank must cover its advertised C3-C5 roots")
        for (patch in all) {
            assertTrue(patch.name.isNotBlank())
            assertEquals(Trio.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys, patch.name)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f }, patch.name)
            assertEquals(Trio.MODEL_VERSION, patch.config.model, "${patch.name} must explicitly preserve the accepted synthesis model")
            assertTrue(patch.midi in notes, "${patch.name} leaves C minor pentatonic C3-C5")
            assertTrue(patch.velocity.isFinite() && patch.velocity > 0f && patch.velocity <= 1f, patch.name)
        }
        val family = roots.single { it.voice == TrioVoice.ENSEMBLE }
        val full = TrioPresets.forGroup(TrioPresetGroup.HIGH_DRAMA).single { it.macros.getValue("DRAMA") == 1f }
        assertEquals(TrioConfig(seed = 47010, model = Trio.MODEL_VERSION), family.config, "the accepted listening anchor must retain its original performance")
        assertEquals(family.copy(name = full.name, macros = family.macros + ("DRAMA" to 1f)), full,
            "the two listening anchors must compare DRAMA without changing root, velocity or seeded gestures")
        assertEquals(all, TrioPresets.all(), "reopening the catalog must retain seeds and the complete performance")
    }

    @Test
    fun `the preset dispatcher and JSON recipes retain every complete performance`() {
        val all = TrioPresets.all()
        assertEquals(all, Presets.all().filter { it.engine == TrioPatch.ENGINE })
        for (voice in TrioVoice.entries) {
            assertEquals(all.filter { it.voice == voice }, TrioPresets.forVoice(voice))
            assertEquals(TrioPresets.forVoice(voice), Presets.forVoice(TrioPatch.ENGINE, voice.name))
        }
        assertTrue(Presets.forVoice(TrioPatch.ENGINE, "MISSING").isEmpty())
        for (patch in all) {
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name), patch.name)
            assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros), patch.name)
            val restored = assertIs<TrioPatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, restored, patch.name)
            assertEquals(patch.toJsonText(), restored.toJsonText(), patch.name)
            val recipe = PadRecipe(patch = patch)
            val replay = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, replay, patch.name)
            assertNull(replay.fx, patch.name)
            assertEquals(patch.config, assertIs<TrioPatch>(replay.patch).config, patch.name)
        }
        assertNull(Presets.byName(TrioPatch.ENGINE, TrioVoice.ENSEMBLE.name, "MISSING"))
    }

    @Test
    fun `the conversations kit renders sixteen finite tonal pads and selected recipes replay exactly`() {
        assertEquals("TRIO Conversations", TrioKits.NAME)
        val patches = TrioKits.patches()
        assertEquals(TrioPresets.all(), patches, "A01-A16 must follow the published preset groups")
        val pads = TrioKits.classic()
        val replayPads = setOf(0, 3, 7, 11, 15)
        assertEquals(16, pads.size)
        for ((i, nullable) in pads.withIndex()) {
            val label = "A${(i + 1).toString().padStart(2, '0')}"
            val pad = requireNotNull(nullable) { "$label is empty" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe) { "$label loses its editable recipe" })
            val patch = assertIs<TrioPatch>(recipe.patch, label)
            assertEquals(patches[i], patch, label)
            assertEquals(patches[i].midi, patch.midi, label)
            assertEquals(patches[i].velocity, patch.velocity, label)
            assertEquals(patches[i].config, patch.config, label)
            assertEquals(Trio.MODEL_VERSION, patch.config.model, label)
            assertEquals(patch.name, pad.displayName, "$label must retain its curated pad name")
            assertEquals(patch.name, pad.source["preset"], label)
            assertEquals(TrioPresets.groupFor(patch).name, pad.source["group"], label)
            assertNull(recipe.fx, "$label must remain dry")
            assertEquals(DrumClass.TONAL, pad.drumClass, "$label must retain melodic routing")
            val snip = pad.snip
            assertEquals(1, snip.channels, label)
            assertEquals(Dsp.RATE, snip.sampleRate, label)
            assertEquals((3.5f * Dsp.RATE).toInt(), snip.samples.size, "$label must remain a finite 3.5-second scene")
            assertTrue(snip.samples.all { it.isFinite() }, "$label contains non-finite audio")
            val rms = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
            assertTrue(rms > 1e-4, "$label is silent")
            assertTrue(snip.peak() <= .99001f, "$label exceeds the shared output ceiling")
            if (i in replayPads) {
                val saved = PadRecipe.fromJsonText(recipe.toJsonText())
                assertContentEquals(snip.samples, saved.render().samples, "$label JSON recipe changed its rendered performance")
            }
        }
    }
}
