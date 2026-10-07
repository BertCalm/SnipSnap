package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorollaKitTest {
    @Test
    fun `factory kit carries fourteen retunable dry notes and two held textures`() {
        val kit = SynthKits.corolla()
        assertEquals(16, kit.size)
        for ((i, pad) in kit.withIndex()) {
            val present = requireNotNull(pad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(present.recipe))
            val patch = recipe.patch as? CorollaPatch
            assertTrue(patch != null, "pad ${i + 1} loses its Corolla recipe")
            assertEquals(null, recipe.fx, "pad ${i + 1} needs an effect to define its identity")
            assertEquals(if (i >= 14) DrumClass.LOOP else DrumClass.TONAL, present.drumClass,
                "pad ${i + 1} has the wrong playback and IN KEY routing")
            assertEquals(if (i >= 14) 1f else 0f, patch!!.macros.getValue("HOLD"))
        }
        val semitones = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        for (i in 0 until 8) {
            val patch = PadRecipe.fromJsonValue(kit[i]!!.recipe!!).patch as CorollaPatch
            assertEquals(CorollaVoice.TONGUE, patch.voice)
            assertEquals(48 + semitones[i], Corolla.midiFor(patch.voice, patch.macros.getValue("TUNE")))
            val wanted = Keys.midiHz(48 + semitones[i])
            val hz = FineTuning.measuredHz(kit[i]!!.snip, wanted, fromSec = .12f, bodySeconds = .5f)
            assertTrue(abs(FineTuning.cents(hz, wanted.toDouble())) <= 10.0, "pad ${i + 1} loses its pentatonic pitch")
        }
    }
}
