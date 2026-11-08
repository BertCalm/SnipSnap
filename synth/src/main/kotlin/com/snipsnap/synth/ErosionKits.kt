package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.kit.ArrangedPad

/** Accepted dry bank: eight distinct roles, with the original three native touch zones. */
object ErosionKits {
    fun contrast(): List<ArrangedPad> = ErosionBank.entries().map { entry ->
        val layers = ErosionBank.velocities.map { entry.patch.copy(velocity = it).render() }
        val hard = layers.last()
        ArrangedPad(
            snip = hard,
            drumClass = Classifier.classify(hard).drumClass,
            recipe = PadRecipe(entry.patch).toJsonValue(),
            displayName = entry.name,
            // Native touch changes the discharge; the sampler supplies the level response.
            softVariants = layers.dropLast(1).map { Velocity.peakMatch(hard, it) },
            layerNames = listOf("SOFT", "MEDIUM", "HARD"),
            source = mapOf("engine" to "EROSION", "role" to entry.role,
                "touch" to "0.35,0.65,1.0; soft zones peak-matched to hard"),
            muteGroup = 0,
        )
    }
}
