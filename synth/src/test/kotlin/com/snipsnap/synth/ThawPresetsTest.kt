package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Product contracts: the named roster, saved recipes, pitched filing, kit layout and velocity path. */
class ThawPresetsTest {

    @Test
    fun `the suggested twelve sounds name every control and round-trip every voice`() {
        val names = setOf(
            "FIRST CONTACT", "THIN ICE", "COPPER RUNNER", "SOFT MELT", "CLEAR CHANNEL", "FROZEN BRIDGE",
            "SLOW SHEET", "RETURNING FROST", "WET EDGE", "COLD CHOIR", "FINE FRACTURE", "THERMAL CYCLE",
        )
        val all = ThawPresets.all()
        assertEquals(names, all.map { it.name }.toSet())
        assertEquals(12, all.size)
        for (voice in ThawVoice.entries) {
            assertEquals(2, ThawPresets.forVoice(voice).size, "$voice has two material regions")
            assertEquals(ThawPresets.forVoice(voice), Presets.forVoice("THAW", voice.name))
        }
        assertTrue(Presets.all().containsAll(all))
        assertTrue(Presets.forVoice("THAW", "NOT_A_VOICE").isEmpty())
        for (preset in all) {
            assertEquals(Thaw.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, preset.name)
            assertTrue(preset.name.length <= 18, preset.name)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name), preset.name)
            val semitone = preset.macros.getValue("TUNE") * Thaw.TUNE_SEMITONES
            assertTrue(abs(semitone - semitone.roundToInt()) < 1e-5f, "${preset.name}: semitone tuning")
            assertEquals(preset, Patches.fromJsonText(preset.toJsonText()), preset.name)
            assertEquals(preset, Presets.byName(preset.engine, preset.voiceName, preset.name))
            assertEquals(null, Presets.landingFor(preset.engine, preset.voiceName, preset.macros))
        }
    }

    @Test
    fun `factory gestures remain pitched rather than entering drum routing`() {
        for (preset in ThawPresets.all()) {
            val filed = Thaw.drumClassFor(preset.voice, preset.macros)
            val heard = Classifier.classify(preset.render()).drumClass
            assertTrue(filed !in DRUMS, "${preset.voice}/${preset.name}: filed $filed")
            assertTrue(heard !in DRUMS, "${preset.voice}/${preset.name}: classified $heard")
            assertEquals(filed, heard, "${preset.voice}/${preset.name}: duration-aware routing")
        }
    }

    @Test
    fun `a saved material recipe regenerates through the existing effects chain`() {
        val patch = ThawPresets.forVoice(ThawVoice.RUNNER).first()
        val fx = FxChain(crunch = mapOf("BITS" to 0.40f, "RATE" to 0.25f))
        val recipe = PadRecipe(patch, fx)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        assertTrue(restored.alias, "the existing deliberate-converter metadata survives")
        assertContentEquals(fx.process(patch.render()).samples, restored.render().samples)
    }

    @Test
    fun `the dry kit carries regenerable recipes and a pentatonic pitch walk`() {
        val kit = SynthKits.thaw()
        assertEquals(16, kit.size)
        val voices = mutableSetOf<ThawVoice>()
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        for ((i, maybePad) in kit.withIndex()) {
            val pad = requireNotNull(maybePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = recipe.patch as ThawPatch
            voices += patch.voice
            assertEquals(null, recipe.fx, "A${i + 1} is dry")
            assertFalse(recipe.alias)
            assertEquals(Thaw.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            assertContentEquals(pad.snip.samples, recipe.render().samples, "A${i + 1} regenerates exactly")
            if (i < walk.size) {
                assertEquals(ThawVoice.BRITTLE, patch.voice)
                assertEquals(48 + walk[i], Thaw.midiFor(patch.voice, patch.macros.getValue("TUNE")))
            }
        }
        assertEquals(ThawVoice.entries.toSet(), voices)
        assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), kit.takeLast(2).map { it!!.drumClass })
    }

    @Test
    fun `velocity renders the runner gesture without editing the saved contact character`() {
        val patch = ThawPresets.forVoice(ThawVoice.RUNNER).first()
        val original = patch.macros.toMap()
        val soft = Velocity.atVelocity(patch, 0.25f)
        assertContentEquals(Thaw.render(patch.voice, patch.macros, velocity = 0.25f).samples, soft.samples)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples), "soft gesture changes synthesis")
        assertEquals(original, patch.macros, "the saved sound does not move")
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
