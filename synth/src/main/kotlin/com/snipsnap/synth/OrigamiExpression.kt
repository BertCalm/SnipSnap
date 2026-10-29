package com.snipsnap.synth

import kotlin.math.pow

/** LEVEL retains the accepted dynamics; FOLD also lets hit strength choose folded color. */
enum class OrigamiExpression { LEVEL, FOLD }

/**
 * A control-rate expression law over the accepted source and reflected banks.
 * Nothing moves their frequencies, start phases, root, or gesture clock. The quarter-depth
 * floor and power are authored shape choices: quiet notes retain a trace of the chosen fold,
 * while a full-strength note reaches its original depth without exceeding it.
 */
object OrigamiExpressions {
    const val MODEL_VERSION = 1
    val LAYER_VELOCITIES = listOf(.3f, .6f, 1f)

    fun depthScale(velocity: Float): Float {
        require(velocity.isFinite() && velocity in 0f..1f) { "ORIGAMI expression velocity must be 0..1: $velocity" }
        return if (velocity == 1f) 1f else (.25 + .75 * velocity.toDouble().pow(1.25)).toFloat()
    }

    /** Resolve only for rendering. The saved recipe always keeps its authored macro values. */
    fun appliedPatch(patch: OrigamiPatch): OrigamiPatch {
        if (patch.expression == OrigamiExpression.LEVEL) return patch
        val resolved = patch.copy(expression = OrigamiExpression.LEVEL)
        val fold = patch.resolvedMacros().getValue("FOLD")
        // These endpoints take the old arithmetic exactly, including sparse saved macros.
        if (patch.velocity == 1f || fold == 0f) return resolved
        return resolved.copy(macros = patch.macros + ("FOLD" to fold * depthScale(patch.velocity)))
    }

    /** A sample layer is a softer strike beneath the saved main sample's own velocity ceiling. */
    fun relativeVelocity(patch: OrigamiPatch, relative: Float): Float {
        require(relative.isFinite()) { "ORIGAMI relative velocity must be finite" }
        return patch.velocity * relative.coerceIn(0f, 1f)
    }
}
