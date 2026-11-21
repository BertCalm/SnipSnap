package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * Opt-in round robin for the CONSORT starter ([SynthKits.consortPatches]): its sixteen pads, each with
 * three touches (soft .3, the engine's default .7, hard 1) and two takes a touch ([TAKES]). The CONSORT
 * starter itself is unchanged.
 *
 * CONSORT's velocity is breath: a harder touch blows harder (brighter, faster to speak), and at the hard
 * touch the FLUTE cracks to its octave and the reeds growl, the overblow the owner asked for. So the
 * zones are not peak-matched: each plays at the level the engine gives that touch, levelled as every
 * render is. A take is the players again, another way of playing the note ([Consort.TAKES]) with fresh
 * humanization: engine takes 0 and 2, the keys' pair ([ConsortKeys.TAKES]), the two furthest apart in
 * attack and vibrato onset. Two takes, not four, as ACCORD TAKES deals: four would double a kit of
 * sections to about 50 MB of PCM24.
 *
 * A pad's default-touch zone's first take is the CONSORT pad, sample for sample ([build] reuses it).
 * Grid pads carry no PadRecipe (one recipe cannot regenerate six takes); each pad's recipe rides in
 * its source as JSON beside the take rule.
 */
object ConsortRoundRobinKits {
    const val ID = "consort-round-robin"
    const val VERSION = 1

    /** The three touches, softest first. The middle one is every CONSORT pad's own. */
    val TOUCHES: List<Float> = ConsortKeys.TOUCHES

    /** The engine takes each touch deals, in order. */
    val TAKES: List<Int> = ConsortKeys.TAKES

    fun patches(): List<ConsortPatch> = SynthKits.consortPatches()

    /** Engine take [take] (one of [TAKES]) of [patch] at [velocity]: pure in its arguments. */
    fun render(patch: ConsortPatch, velocity: Float, take: Int): Snip {
        require(velocity in TOUCHES && take in TAKES)
        return patch.copy(velocity = velocity, take = take).render()
    }

    /** The sixteen pads, soft to hard zones of two takes. The default touch's first take is the CONSORT pad, reused. */
    fun build(): List<ArrangedPad> {
        val patches = patches()
        val kept = SynthKits.consort()
        return patches.indices.map { i ->
            val patch = patches[i]
            val pad = requireNotNull(kept[i])
            require(patch.velocity in TOUCHES && patch.take == 0) { "${patch.name}: kept touch ${patch.velocity} is not a zone" }
            val grid = TOUCHES.map { v ->
                TAKES.map { k -> if (v == patch.velocity && k == 0) pad.snip else render(patch, v, k) }
            }
            ArrangedPad(
                snip = grid.last().first(),
                drumClass = pad.drumClass,
                oneShot = true,
                displayName = patch.name,
                velocityTakes = grid,
                source = mapOf("engine" to ConsortPatch.ENGINE, "voice" to patch.voice.name,
                    "touch" to TOUCHES.joinToString(","), "keptTouch" to patch.velocity.toString(),
                    "recipe" to patch.toJsonText(),
                    "roundRobin" to "${TAKES.size} takes per touch: engine takes ${TAKES.joinToString(" and ")} " +
                        "(Consort.TAKES ways ${TAKES.joinToString(" and ") { Consort.TAKES[it].name }}, fresh humanization each)",
                    "roundRobinVersion" to VERSION.toString(),
                    "playback" to "FINITE_ONE_SHOT"),
            )
        }
    }
}
