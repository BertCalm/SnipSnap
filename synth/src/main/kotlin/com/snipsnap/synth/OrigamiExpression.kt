package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.pow

/** TOUCH also changes the upper-bank balance; LEVEL and FOLD retain their accepted responses. */
enum class OrigamiExpression { LEVEL, FOLD, TOUCH }

/**
 * A control-rate expression law over the accepted source and reflected banks.
 * Nothing moves their frequencies, start phases, root, or gesture clock. The quarter-depth
 * floor and power are authored shape choices: quiet notes retain a trace of the chosen fold,
 * while a full-strength note reaches its original depth without exceeding it.
 */
object OrigamiExpressions {
    const val MODEL_VERSION = 1
    val LAYER_VELOCITIES = listOf(.3f, .6f, 1f)

    /** Both expressive modes need independently rendered samples beneath the authored ceiling. */
    fun colorsWithVelocity(expression: OrigamiExpression): Boolean = when (expression) {
        OrigamiExpression.LEVEL -> false
        OrigamiExpression.FOLD, OrigamiExpression.TOUCH -> true
    }

    /**
     * An authored TOUCH curve, not a loudness curve: soft notes retain less upper material
     * against the same raw fundamental. The .85 factory ceiling and all stronger strikes
     * use the old FOLD path exactly. No partial can exceed the accepted source amplitude.
     */
    fun upperScale(velocity: Float): Float {
        require(velocity.isFinite() && velocity in 0f..1f) { "ORIGAMI touch velocity must be 0..1: $velocity" }
        if (velocity >= .85f) return 1f
        val amount = velocity.toDouble() / .85f.toDouble()
        return (.1 + .9 * amount * amount).toFloat()
    }

    fun depthScale(velocity: Float): Float {
        require(velocity.isFinite() && velocity in 0f..1f) { "ORIGAMI expression velocity must be 0..1: $velocity" }
        return if (velocity == 1f) 1f else (.25 + .75 * velocity.toDouble().pow(1.25)).toFloat()
    }

    /** Resolve fold depth only. TOUCH callers must retain its upper scale before this clears the mode. */
    fun appliedPatch(patch: OrigamiPatch): OrigamiPatch {
        if (patch.expression == OrigamiExpression.LEVEL) return patch
        val resolved = patch.copy(expression = OrigamiExpression.LEVEL)
        val fold = patch.resolvedMacros().getValue("FOLD")
        // These endpoints take the old arithmetic exactly, including sparse saved macros.
        if (patch.velocity == 1f || fold == 0f) return resolved
        return resolved.copy(macros = patch.macros + ("FOLD" to fold * depthScale(patch.velocity)))
    }

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) {
            throw CancellationException("ORIGAMI touch render cancelled")
        }
    }

    /**
     * Mix after the existing linear conditioning, before its single loudness stage. Rendering
     * the root separately avoids changing any frozen oscillator, source, or geometry code.
     * This also applies to SOURCE and zero FOLD: an unfolded TOUCH note still has a touch color.
     */
    internal fun renderTouchRaw(
        patch: OrigamiPatch,
        midi: Int? = patch.midi,
        diagnostic: OrigamiDiagnostic = OrigamiDiagnostic.COMBINED,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        require(patch.expression == OrigamiExpression.TOUCH)
        stop(cancelled)
        val scale = upperScale(patch.velocity)
        val resolved = appliedPatch(patch)
        val raw = OrigamiGestureRenderer.renderRaw(resolved, midi, diagnostic, cancelled)
        // Literal delegation preserves all old arithmetic at the hard endpoint and root tap.
        if (scale == 1f || diagnostic == OrigamiDiagnostic.ROOT_ONLY || patch.velocity == 0f) return raw
        if (diagnostic == OrigamiDiagnostic.FOLDED_ONLY) {
            for (i in raw.indices) {
                if (i % 4096 == 0) stop(cancelled)
                raw[i] *= scale
            }
        } else {
            val root = OrigamiGestureRenderer.renderRaw(resolved, midi, OrigamiDiagnostic.ROOT_ONLY, cancelled)
            check(root.size == raw.size)
            for (i in raw.indices) {
                if (i % 4096 == 0) stop(cancelled)
                raw[i] = root[i] + scale * (raw[i] - root[i])
            }
        }
        stop(cancelled)
        return raw
    }

    internal fun renderTouch(patch: OrigamiPatch, cancelled: () -> Boolean = { false }): Snip {
        require(patch.expression == OrigamiExpression.TOUCH)
        stop(cancelled)
        if (upperScale(patch.velocity) == 1f) {
            // Use the public accepted FOLD route, including each voice's original final stage.
            return OrigamiGestureRenderer.render(appliedPatch(patch), cancelled)
        }
        val raw = renderTouchRaw(patch, cancelled = cancelled)
        if (patch.velocity > 0f) {
            Dsp.levelTo(raw, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * patch.velocity.pow(.65f))
        }
        stop(cancelled)
        return Snip(raw, 1, Dsp.RATE)
    }

    /** A sample layer is a softer strike beneath the saved main sample's own velocity ceiling. */
    fun relativeVelocity(patch: OrigamiPatch, relative: Float): Float {
        require(relative.isFinite()) { "ORIGAMI relative velocity must be finite" }
        return patch.velocity * relative.coerceIn(0f, 1f)
    }
}
