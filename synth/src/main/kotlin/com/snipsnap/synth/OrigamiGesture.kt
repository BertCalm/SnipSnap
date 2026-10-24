package com.snipsnap.synth

import kotlin.math.pow

/** A once-only change in source/reflected balance, separate from the frozen geometric model. */
enum class OrigamiGesture { STEADY, BLOOM, UNFOLD }

/** The six existing controls describe either the original axis motion or an explicit fold gesture. */
object OrigamiGestures {
    const val MODEL_VERSION = 1
    const val GEOMETRY_MODEL = 4

    /** Let STILL's soft attack speak before its color starts moving. These are authored timings. */
    fun startSeconds(voice: OrigamiVoice): Double = when (voice) {
        OrigamiVoice.CRANE -> .003
        OrigamiVoice.FAN -> .04
        OrigamiVoice.STILL -> .35
    }

    /** In BLOOM/UNFOLD only, MOTION walks perceptually spaced fast-to-slow durations. */
    fun durationSeconds(voice: OrigamiVoice, motion: Float): Double {
        val (fast, slow) = when (voice) {
            OrigamiVoice.CRANE -> .035 to .50
            OrigamiVoice.FAN -> .12 to 1.20
            OrigamiVoice.STILL -> .30 to 1.80
        }
        val amount = (motion.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f).toDouble()
        return fast * (slow / fast).pow(amount)
    }

    /** Resolve once outside a render loop; the immutable envelope allocates nothing per sample. */
    fun envelope(patch: OrigamiPatch): OrigamiFoldEnvelope {
        val macros = patch.resolvedMacros()
        return OrigamiFoldEnvelope(patch.gesture, macros.getValue("FOLD").toDouble(),
            if (patch.gesture == OrigamiGesture.STEADY) 0.0 else startSeconds(patch.voice),
            if (patch.gesture == OrigamiGesture.STEADY) 0.0 else durationSeconds(patch.voice, macros.getValue("MOTION")))
    }

    fun endSeconds(patch: OrigamiPatch): Double = envelope(patch).endSeconds
    fun foldAt(patch: OrigamiPatch, timeSeconds: Double): Double = envelope(patch).depthAt(timeSeconds)

    /** The held body starts after the gesture and never restarts it at a loop wrap. */
    fun settledPatch(patch: OrigamiPatch): OrigamiPatch {
        if (patch.gesture == OrigamiGesture.STEADY) return patch
        val fold = if (patch.gesture == OrigamiGesture.BLOOM) patch.resolvedMacros().getValue("FOLD") else 0f
        return patch.copy(gesture = OrigamiGesture.STEADY,
            macros = patch.resolvedMacros() + mapOf("MOTION" to 0f, "FOLD" to fold))
    }
}

/** Complementary source/wet shares preserve the anchor and total amplitude budget. */
data class OrigamiFoldEnvelope internal constructor(
    val gesture: OrigamiGesture,
    val maximumFold: Double,
    val startSeconds: Double,
    val durationSeconds: Double,
) {
    val endSeconds: Double get() = startSeconds + durationSeconds

    fun depthAt(timeSeconds: Double): Double {
        require(timeSeconds.isFinite() && timeSeconds >= 0.0) { "ORIGAMI gesture time must be finite and nonnegative" }
        if (gesture == OrigamiGesture.STEADY || maximumFold == 0.0) return maximumFold
        val x = ((timeSeconds - startSeconds) / durationSeconds).coerceIn(0.0, 1.0)
        // Zero first and second derivatives at both ends keep the color contour from adding an edge.
        val amount = (x * x * x * (x * (x * 6.0 - 15.0) + 10.0)).coerceIn(0.0, 1.0)
        return maximumFold * if (gesture == OrigamiGesture.BLOOM) amount else 1.0 - amount
    }
}
