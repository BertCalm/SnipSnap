package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import com.snipsnap.kit.ArrangedPad

/** The two heard bass articulations on the same E-minor-pentatonic pad walk. */
object UprightKits {
    const val MAIN_VELOCITY = .65f
    private val semitones = listOf(0, 3, 5, 7, 10, 12, 15, 17)

    /** A01–A08 Round; A09–A16 Muted. Only pitch and the heard DAMP endpoint differ. */
    fun patches(): List<PluckPatch> = listOf("Round" to .18f, "Muted" to 1f).flatMap { (name, damp) ->
        semitones.map { semitone ->
            val midi = 28 + semitone
            PluckPatch("$name ${Scales.nameOf(midi)}", PluckVoice.UPRIGHT,
                Pluck.defaults(PluckVoice.UPRIGHT) + mapOf(
                    "TUNE" to semitone / Pluck.TUNE_SEMITONES.toFloat(), "DAMP" to damp,
                ), velocity = MAIN_VELOCITY)
        }
    }

    /** Long natural decays remain finite, tonal one-shots; no loop or drum choke is implied. */
    fun classic(): List<ArrangedPad?> = patches().map { patch ->
        val recipe = PadRecipe(patch)
        val midi = 28 + Math.round(patch.macros.getValue("TUNE") * Pluck.TUNE_SEMITONES)
        ArrangedPad(recipe.render(), DrumClass.TONAL, recipe.toJsonValue(),
            oneShot = true, displayName = patch.name,
            source = mapOf("engine" to "PLUCK", "voice" to "UPRIGHT", "rootMidi" to midi.toString(),
                "rootNote" to Scales.nameOf(midi), "rootOrigin" to "authored",
                "velocity" to patch.velocity.toString()))
    }
}
