package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import kotlin.math.abs
import kotlin.test.*

class LorentzPresetsTest {
    @Test
    fun `both voice banks expose six distinct editable control anchors`() {
        assertEquals(12, LorentzPresets.all().size)
        assertEquals(LorentzPresets.all(), Presets.all().filter { it.engine == LorentzPatch.ENGINE })
        for (voice in LorentzVoice.entries) {
            val bank = LorentzPresets.forVoice(voice)
            assertEquals(6, bank.size)
            assertEquals(bank, Presets.forVoice(LorentzPatch.ENGINE, voice.name))
            assertEquals(bank.size, bank.map { it.macros }.toSet().size)
            assertEquals(bank.size, bank.map { it.name }.toSet().size)
            for (p in bank) {
                assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name))
                assertEquals(p, Patches.fromJsonText(p.toJsonText()))
                assertEquals(Lorentz.defaults(voice).keys, p.macros.keys)
            }
        }
    }
    @Test
    fun `factory anchors render bounded full level audio with actual classification`() {
        for (patch in LorentzPresets.all()) {
            val audio = patch.render()
            assertTrue(audio.samples.all { it.isFinite() && abs(it) <= .990001f })
            assertTrue(abs(audio.samples.average()) < .005)
            assertTrue(Loudness.of(audio) >= Dsp.MELODIC_LOUDNESS_TARGET * .9 || audio.peak() >= .95f)
            assertEquals(Classifier.classify(audio).drumClass, Lorentz.drumClassFor(patch.voice, patch.macros))
        }
    }
    @Test
    fun `starter pads are sixteen finite dry recipes with exact recall`() {
        val kit = SynthKits.lorentz()
        assertEquals(16, kit.size)
        for (pad in kit) {
            val p = requireNotNull(pad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(p.recipe))
            assertIs<LorentzPatch>(recipe.patch)
            assertNull(recipe.fx)
            // Rendering again must reproduce the same WAV source, not just the recipe text.
            assertContentEquals(recipe.render().samples, p.snip.samples)
        }
    }
}
