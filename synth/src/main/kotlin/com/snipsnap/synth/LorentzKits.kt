package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.kit.ArrangedPad

/**
 * LORENTZ's starter: [SynthKits.lorentz]'s sixteen pads (eight FUSE notes up C minor pentatonic from C1,
 * then four FUSE and four LORENTZ control anchors), each with three native velocity zones. A softer zone
 * is a softer contact, re-rendered and peak-matched to the hard strike; the MPC velocity curve owns its
 * level. Filed by the classifier on the hard render, as [SynthKits.lorentz] files them.
 * Held by [HeldPromotions] until the owner has heard the Kotlin render.
 */
object LorentzKits {
    const val ID = "lorentz"
    const val SOFT_VELOCITY = .3f
    const val MID_VELOCITY = .6f

    fun patches(): List<LorentzPatch> = SynthKits.lorentz().map { patchOf(checkNotNull(it)) }

    private fun patchOf(pad: ArrangedPad) = PadRecipe.fromJsonValue(checkNotNull(pad.recipe)).patch as LorentzPatch

    fun build(): List<ArrangedPad?> = SynthKits.lorentz().map { pad ->
        val p = checkNotNull(pad)
        val patch = patchOf(p)
        p.copy(softVariants = listOf(SOFT_VELOCITY, MID_VELOCITY).map { v ->
            Velocity.peakMatch(p.snip, patch.copy(velocity = v).render())
        }, source = p.source + linkedMapOf("engine" to LorentzPatch.ENGINE, "model" to patch.model.toString(),
            "velocitySoft" to SOFT_VELOCITY.toString(), "velocityMid" to MID_VELOCITY.toString(),
            "velocityMain" to patch.velocity.toString(), "velocityNormalization" to "peak-match-main"))
    }

    /** What the starter files a pad as: the classifier on its hard render, as [SynthKits.lorentz] does. */
    fun drumClassFor(patch: LorentzPatch) = Classifier.classify(patch.render()).drumClass
}
