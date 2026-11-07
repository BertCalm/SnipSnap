package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/** Separate dry, editable ONSET banks. Existing acceptance recipes stay on model 3. */
object OnsetKits {
    private val blends = listOf(0f, .25f, .5f, 1f)
    private val voices = listOf(OnsetVoice.KICK, OnsetVoice.SNARE, OnsetVoice.HAT_CLOSED, OnsetVoice.HAT_OPEN)

    /** A01–A04 is BLEND 0; the next rows are .25, .5 and 1, in the same voice order. */
    fun patches(): List<OnsetPatch> = blends.flatMap { blend ->
        voices.map { voice ->
            OnsetPatch("${voice.name} BLEND $blend", voice, Onset.defaults(voice) + ("BLEND" to blend), modelVersion = 3)
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

    /**
     * Seven native model-4 hybrids from the extension listening study, in its
     * original pad order. A pad carries its voice and complete macros rather
     * than a prototype setup id, so renaming it cannot change its sound.
     */
    fun extensionStudy(): List<ArrangedPad> = OnsetPresets.extensionStudy().map { patch ->
        val drumClass = when (patch.voice) {
            OnsetVoice.TOM -> DrumClass.TOM
            OnsetVoice.PERC_PLUCK, OnsetVoice.PERC_WOOD, OnsetVoice.PERC_METAL -> DrumClass.PERC
            else -> error("${patch.voice} is not an ONSET extension voice")
        }
        require(patch.modelVersion == 4) { "the extension study must retain its native model-4 recipes" }
        val recipe = PadRecipe(patch)
        ArrangedPad(
            recipe.render(), drumClass, recipe.toJsonValue(),
            level = 1f, oneShot = true, displayName = patch.name,
            source = mapOf("renderer" to "ONSET", "modelVersion" to "4"),
        )
    }
}
