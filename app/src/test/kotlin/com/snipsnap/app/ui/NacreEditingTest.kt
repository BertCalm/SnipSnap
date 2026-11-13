package com.snipsnap.app.ui

import com.snipsnap.synth.Nacre
import com.snipsnap.synth.NacrePatch
import com.snipsnap.synth.NacrePresets
import com.snipsnap.synth.NacreVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure phone-editor state checks; these do not render or replace the kept audio witnesses. */
class NacreEditingTest {
    @Test
    fun partialContactRecipesResolveVisibleControlsWithoutChangingSourceIdentity() {
        val patch = NacrePatch("Partial contact", NacreVoice.PEARL, mapOf("DUET" to .61f),
            midi = 57, velocity = .37f, seed = Long.MIN_VALUE + 19, model = 4)
        val controls = NacreEditing.controls(patch)
        assertEquals(0f, controls.getValue("CONTACT"))
        assertEquals(.61f, controls.getValue("DUET"))
        assertEquals(Nacre.defaults(patch.voice, 4).keys, controls.keys)
        assertEquals(listOf("TOUCH", "DUET", "CURRENT", "VENUE", "BLOOM", "CONTACT"),
            NacreEditing.macrosFor(patch).map { it.name })
        assertTrue(NacreEditing.macrosFor(patch).all { it.name in controls })
        val edited = Patches.edited(patch, "Edited partial", controls + ("CONTACT" to .62f)) as NacrePatch
        assertEquals(patch.copy(name = "Edited partial", macros = controls + ("CONTACT" to .62f)), edited)
        assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
        assertEquals(mapOf("DUET" to .61f), patch.macros)
    }

    @Test
    fun keptContactRecipesProvideTheirSavedSliderValues() {
        val kept = NacrePresets.all().filter { it.model == 4 }
        assertEquals(listOf(.35f, .8f), kept.map { it.macros.getValue("CONTACT") })
        for (patch in kept) {
            assertEquals(patch.macros, NacreEditing.controls(patch))
            assertEquals(patch.macros.getValue("CONTACT"), NacreEditing.controls(patch).getValue("CONTACT"))
            assertFalse(NacreEditing.macrosFor(patch).any { it.name == "HOLD" })
            assertEquals(0f, NacreEditing.controls(patch).getValue("HOLD"))
        }
    }

    @Test
    fun scrambleRetainsContactAndAllControlsAcrossSavedRecipeRoundTrip() {
        val patch = NacrePresets.all().last().copy(midi = 55, velocity = .43f,
            seed = Long.MAX_VALUE - 23)
        // The existing finite gesture scramble knows the original six controls only.
        val generated = Nacre.defaults(patch.voice) + mapOf("TOUCH" to .42f, "DUET" to .73f, "BLOOM" to .64f)
        val scrambled = NacreEditing.scrambledControls(patch, generated)
        assertEquals(.8f, scrambled.getValue("CONTACT"))
        assertEquals(generated, scrambled - "CONTACT")
        assertEquals(Nacre.defaults(patch.voice, 4).keys, scrambled.keys)
        val edited = Patches.edited(patch, "Scrambled contact", scrambled) as NacrePatch
        assertEquals(patch.copy(name = "Scrambled contact", macros = scrambled), edited)
        assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
        val zero = patch.copy(macros = mapOf("DUET" to .2f))
        assertEquals(0f, NacreEditing.scrambledControls(zero, generated).getValue("CONTACT"))
    }

    @Test
    fun legacyControlsReturnAfterContactAndNeverAcquireIgnoredContact() {
        val contact = NacrePresets.all().last()
        assertTrue(NacreEditing.macrosFor(contact).any { it.name == "CONTACT" })
        for (model in 1..3) {
            val legacy = NacrePatch("Saved legacy $model", NacreVoice.PEARL,
                mapOf("DUET" to .49f), seed = Long.MIN_VALUE + model, model = model)
            assertEquals(Nacre.defaults(legacy.voice, model) + legacy.macros, NacreEditing.controls(legacy))
            assertFalse("CONTACT" in NacreEditing.controls(legacy))
            assertFalse(NacreEditing.macrosFor(legacy).any { it.name == "CONTACT" || it.name == "HOLD" })
            assertEquals(Nacre.defaults(legacy.voice), NacreEditing.scrambledControls(legacy, Nacre.defaults(legacy.voice)))
            assertEquals(legacy, Patches.fromJsonText(legacy.toJsonText()))
        }
        assertEquals(.8f, NacreEditing.controls(contact).getValue("CONTACT"))
    }
}
