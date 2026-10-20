package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/** The dry, editable four-voice ONSET acceptance kit. No audition matching or effects. */
object OnsetKits {
    private val blends = listOf(0f, .25f, .5f, 1f)
    private val voices = listOf(OnsetVoice.KICK, OnsetVoice.SNARE, OnsetVoice.HAT_CLOSED, OnsetVoice.HAT_OPEN)

    /** A01–A04 is BLEND 0; the next rows are .25, .5 and 1, in the same voice order. */
    fun patches(): List<OnsetPatch> = blends.flatMap { blend ->
        voices.map { voice ->
            OnsetPatch("${voice.name} BLEND $blend", voice, Onset.defaults(voice) + ("BLEND" to blend))
        }
    }

    /** Normal kit assembly supplies hat mute group 1 and persists each complete recipe. */
    fun acceptance(): List<ArrangedPad> = patches().map { patch ->
        val recipe = PadRecipe(patch)
        ArrangedPad(
            recipe.render(), DrumClass.valueOf(patch.voice.name), recipe.toJsonValue(),
            level = 1f, oneShot = true, displayName = patch.name,
        )
    }
}
