package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/** Sixteen finite audition candidates, with two softer native timbre layers. */
object FiredBronzeKits {
    const val NAME = "SnipSnap Fired Bronze Study"

    fun study(): List<ArrangedPad> = FiredBronze.Part.entries.map { part ->
        val recipe = FiredBronze.Recipe(part)
        val hard = recipe.render()
        ArrangedPad(hard, part.drumClass, level = part.gain,
            softVariants = listOf(.3f, .65f).map { v ->
                // MPC supplies velocity gain. Store matched timbre layers, as
                // the house velocity exporter does, to avoid double attenuation.
                Velocity.peakMatch(hard, recipe.copy(velocity = v).render())
            }, displayName = part.label, muteGroup = if (part in listOf(
                FiredBronze.Part.CLOSED_HAT, FiredBronze.Part.OPEN_HAT)) 1 else 0,
            source = mapOf("study" to "FIRED_BRONZE", "version" to FiredBronze.VERSION.toString(),
                "recipe" to "recipes/${part.name.lowercase()}_amp.json"))
    }
}
