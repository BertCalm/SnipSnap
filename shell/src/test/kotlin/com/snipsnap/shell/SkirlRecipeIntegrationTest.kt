package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Skirl
import com.snipsnap.synth.SkirlPatch
import com.snipsnap.synth.SkirlVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkirlRecipeIntegrationTest {
    @Test
    fun `spread reads the authored note including relative tune and supported range`() {
        val silent = Snip(FloatArray(441), channels = 1, sampleRate = 44100)
        for (voice in SkirlVoice.entries) for (midi in listOf(36, 57, 84)) {
            for ((tune, offset) in listOf(0f to -12, .5f to 0, 1f to 12)) {
                val patch = SkirlPatch("Authored root", voice, mapOf("TUNE" to tune), midi = midi)
                assertEquals((midi + offset).coerceIn(Skirl.MIDI_MIN, Skirl.MIDI_MAX).toFloat(), Spread.detect(silent, patch))
            }
            val centered = SkirlPatch("Centered root", voice, emptyMap(), midi = midi)
            assertEquals(midi.toFloat(), Spread.detect(silent, centered))
        }
    }

    @Test
    fun `pasteable listening recipe preserves root performance model and escaped name`() {
        val patch = SkirlPatch("RIM \"answer\" \\ \$lead\nnext", SkirlVoice.WHIRR,
            mapOf("EDGE" to .73125f, "PLATE" to .321f), midi = 67, velocity = .375f)
        val line = UserPresets.rosterLine(patch)
        val call = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.removeSuffix(",")))
        assertEquals("fromJsonText", call.name)
        val receiver = assertIs<KotlinLiteral.Call>(call.receiver)
        assertEquals("SkirlPatch", receiver.name)
        assertTrue(receiver.positional.isEmpty() && receiver.named.isEmpty() && call.named.isEmpty())
        val recipe = assertIs<KotlinLiteral.Str>(call.positional.single()).value
        assertEquals(patch, SkirlPatch.fromJsonText(recipe))
        assertEquals("SkirlPresets.kt", UserPresets.rosterFile(patch))
    }
}
