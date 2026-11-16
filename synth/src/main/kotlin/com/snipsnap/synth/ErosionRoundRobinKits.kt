package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * Opt-in round robin for the accepted EROSION contrast bank: the eight pads, three native touches,
 * [TAKES] takes per touch. Take 0 is the accepted kit's zone sample, sample for sample. A later take
 * is a genuine re-render of the same recipe at the same touch ([Erosion.render]'s take): the first
 * discharge keeps its onset and energy, and the scheduler re-seeds after it, so what follows (the
 * spacing and size of the later packets, the collapse delays) lands differently. Controls, kernels,
 * filters and the output stage are untouched, and each take is levelled by the same house stage.
 * Soft and medium zones are peak-matched to their own take's hard hit, as the accepted kit does.
 * The kit's takes are the renderer's takes in [STRIKES]: of renderer takes 1 to 7, 1, 4, 6 and 7
 * moved some pad's 90%-energy time outside 0.7 to 1.4 times the accepted hit (RASP's take 1 has a
 * late burst at 239 ms against 66), so they are left out.
 *
 * Grid pads carry no PadRecipe (a single-hit recipe cannot regenerate four takes); the accepted
 * recipe rides in the pad's source as JSON beside the take rule. The accepted EROSION starter is unchanged.
 */
object ErosionRoundRobinKits {
    const val ID = "erosion-round-robin"
    const val VERSION = 1
    const val TAKES = 4
    /** The renderer take behind each kit take, in dealing order; the first is the accepted hit. */
    val STRIKES = listOf(0, 2, 3, 5)

    /** The finished zone sample for one recipe, touch and take. Pure: the same arguments give the same bytes. */
    fun render(patch: ErosionPatch, velocity: Float, take: Int): Snip {
        require(velocity in ErosionBank.velocities && take in 0 until TAKES)
        val strike = STRIKES[take]
        val hard = Erosion.render(patch.voice, patch.macros, 1f, strike)
        return if (velocity == 1f) hard else Velocity.peakMatch(hard, Erosion.render(patch.voice, patch.macros, velocity, strike))
    }

    /** Soft-to-hard zones, each with its [TAKES] takes in order; every hard take renders once. */
    fun grid(patch: ErosionPatch): List<List<Snip>> {
        val hards = STRIKES.map { strike -> Erosion.render(patch.voice, patch.macros, 1f, strike) }
        return ErosionBank.velocities.map { v ->
            if (v == 1f) hards
            else hards.mapIndexed { take, hard -> Velocity.peakMatch(hard, Erosion.render(patch.voice, patch.macros, v, STRIKES[take])) }
        }
    }

    fun build(): List<ArrangedPad> {
        val entries = ErosionBank.entries()
        val grids = entries.parallelStream().map { grid(it.patch) }.toList()
        return entries.zip(grids).map { (entry, grid) ->
            val hard = grid.last().first()
            ArrangedPad(
                snip = hard,
                drumClass = Classifier.classify(hard).drumClass,
                displayName = entry.name,
                velocityTakes = grid,
                source = mapOf("engine" to "EROSION", "role" to entry.role,
                    "touch" to "0.35,0.65,1.0; soft zones peak-matched to their own take's hard hit",
                    "recipe" to entry.patch.toJsonText(),
                    "roundRobin" to "$TAKES takes per touch: take 1 is the accepted hit, later takes re-seed the discharges after the first",
                    "rendererTakes" to STRIKES.joinToString(","),
                    "roundRobinVersion" to VERSION.toString()),
                muteGroup = 0,
            )
        }
    }
}
