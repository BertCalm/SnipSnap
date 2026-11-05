package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/** The accepted duet on four relationship rows and four explicit notes. */
object ObbligatoKits {
    const val NAME = "SnipSnap Obbligato Kit"
    const val MODEL = Obbligato.MODEL
    const val MAIN_VELOCITY = .8f
    const val SOFT_VELOCITY = .3f
    const val MID_VELOCITY = .6f
    const val SEED = 0

    private val notes = listOf(60 to "C4", 63 to "Eb4", 65 to "F4", 67 to "G4")
    private val relationships = listOf("Harmony", "Play", "Fight", "Protect")

    /** A01–A04 Harmony, A05–A08 Play, A09–A12 Fight, A13–A16 Protect. */
    fun patches(): List<ObbligatoPatch> {
        val presets = ObbligatoPresets.all().associateBy { it.name }
        return relationships.flatMap { relationship ->
            val preset = presets.getValue(relationship)
            notes.map { (midi, note) ->
                preset.copy(name = "$relationship $note", midi = midi,
                    velocity = MAIN_VELOCITY, seed = SEED, model = MODEL)
            }
        }
    }

    /**
     * Finite, dry one-shots with three native velocity zones. The main recipe retains
     * the accepted .8 strike. Soft layers re-render the same note, relationship, seed
     * and model at .3 and .6, then use the standard peak match: hardware velocity owns
     * level. Their saved strike values and matching rule travel in pad provenance.
     */
    fun build(): List<ArrangedPad?> = patches().map { patch ->
        val recipe = PadRecipe(patch)
        val reference = recipe.render()
        ArrangedPad(
            snip = reference,
            drumClass = Obbligato.drumClassFor(),
            recipe = recipe.toJsonValue(),
            oneShot = true,
            softVariants = listOf(SOFT_VELOCITY, MID_VELOCITY).map { velocity ->
                Velocity.peakMatch(reference, Velocity.atVelocity(patch, velocity))
            },
            source = linkedMapOf(
                "engine" to ObbligatoPatch.ENGINE,
                "model" to patch.model.toString(),
                "midi" to patch.midi.toString(),
                "seed" to patch.seed.toString(),
                "velocitySoft" to SOFT_VELOCITY.toString(),
                "velocityMid" to MID_VELOCITY.toString(),
                "velocityMain" to MAIN_VELOCITY.toString(),
                "velocityNormalization" to "peak-match-main",
            ),
            displayName = patch.name,
        )
    }
}
