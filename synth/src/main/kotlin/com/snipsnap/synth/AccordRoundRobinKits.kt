package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * Opt-in round robin for the kept ACCORD starter ([AccordKits.patches]): its sixteen performances,
 * each with three touches (soft .3, normal .75, hard 1, the touches heard in accord-native-1 and
 * accord-rr-1) and [Accord.TAKES] takes per touch. The ACCORD starter itself is unchanged.
 *
 * Every take is the engine's own ([Accord.renderTake]): take 1 is the plain render at that touch,
 * later takes are the same machine heard from an earlier moment (passage-offset takes), at take 1's
 * performance gain. The owner kept them by ear in accord-rr-1 (19 Nov 2026) for every voice at C3
 * at the three touches; AccordRoundRobinPinsTest pins those. ACCORD's velocity is its timbre, so
 * the zones are not peak-matched: each plays at the level the engine gives that touch.
 *
 * A pad's kept performance sits in the zone of its own touch, and that zone's first take is the kept
 * ACCORD pad, sample for sample ([build] reuses it). The softer and harder zones are the same
 * recipe at the other two touches. SOFT BRUSH, HARD BRUSH and SOFT WOOD keep their names and their
 * kept hit, in their soft or hard zone; at the other touches they play what BRUSH C3 and WOOD play.
 *
 * Grid pads carry no PadRecipe (a single performance's recipe cannot regenerate twelve takes); each
 * pad's kept recipe rides in its source as JSON beside the take rule, as TERRA ROUND ROBIN's do.
 */
object AccordRoundRobinKits {
    const val ID = "accord-round-robin"
    const val VERSION = 1

    /** The three touches, softest first: the soft, normal and hard touch every ACCORD voice was heard at. */
    val TOUCHES: List<Float> = listOf(.3f, .75f, 1f)

    /** The kept ACCORD starter's sixteen recipes, in pad order. */
    fun patches(): List<AccordPatch> = AccordKits.patches()

    /** Take [take] (0-based) of [patch] at [velocity]: the engine's take, pure in its arguments. */
    fun render(patch: AccordPatch, velocity: Float, take: Int): Snip {
        require(velocity in TOUCHES && take in 0 until Accord.TAKES)
        return Accord.renderTake(patch.copy(velocity = velocity), take)
    }

    /** The sixteen pads, soft to hard zones of four takes. The kept touch's first take is the ACCORD pad, reused. */
    fun build(): List<ArrangedPad> {
        val patches = patches()
        val kept = AccordKits.arranged()
        return patches.indices.map { i ->
            val patch = patches[i]
            val pad = kept[i]
            require(patch.velocity in TOUCHES) { "${patch.name}: kept touch ${patch.velocity} is not a zone" }
            val grid = TOUCHES.map { v ->
                (0 until Accord.TAKES).map { k -> if (v == patch.velocity && k == 0) pad.snip else render(patch, v, k) }
            }
            ArrangedPad(
                snip = grid.last().first(),
                drumClass = pad.drumClass,
                oneShot = true,
                displayName = pad.displayName,
                colorHex = pad.colorHex,
                velocityTakes = grid,
                source = mapOf("engine" to AccordPatch.ENGINE, "model" to patch.model.toString(),
                    "voice" to patch.voice.name, "rootMidi" to patch.midi.toString(),
                    "acousticProfile" to patch.acousticProfile.name, "seed" to patch.seed.toString(),
                    "initializer" to patch.initializer.name, "sourceSeconds" to patch.sourceSeconds.toString(),
                    "touch" to TOUCHES.joinToString(","), "keptTouch" to patch.velocity.toString(),
                    "recipe" to patch.toJsonText(),
                    "roundRobin" to "${Accord.TAKES} takes per touch: take 1 is the plain render, later takes replay the same machine " +
                        "from ${Accord.TAKE_STEP_CYCLES} cycles per take earlier (Accord.renderTake)",
                    "takeStartCycles" to (0 until Accord.TAKES).joinToString(",") { Accord.takeStartCycles(patch.sourceStartCycles, it).toString() },
                    "roundRobinVersion" to VERSION.toString(),
                    "playback" to "FINITE_ONE_SHOT"),
            )
        }
    }
}
