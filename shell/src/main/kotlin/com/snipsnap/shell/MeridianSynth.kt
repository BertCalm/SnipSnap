package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianKits
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.MeridianVoice
import com.snipsnap.synth.PadRecipe
import kotlin.random.Random

/** Meridian's bench behavior, shared by the Android screen and JVM workflow tests. */
object MeridianSynth {
    fun factory(voice: MeridianVoice = MeridianVoice.SWEEP): List<MeridianPatch> =
        MeridianPresets.forVoice(voice)

    /** Keep the accepted factory performance, including its saved noise context. */
    fun defaultPatch(voice: MeridianVoice = MeridianVoice.SWEEP): MeridianPatch = factory(voice).first()

    fun defaults(voice: MeridianVoice = MeridianVoice.SWEEP): Map<String, Float> = defaultPatch(voice).macros

    fun scramble(patch: MeridianPatch, random: Random, temperature: Float = .35f): MeridianPatch =
        patch.copy(macros = Meridian.scramble(patch.voice, random, temperature, near = patch))

    fun withMidi(patch: MeridianPatch, midi: Int): MeridianPatch = patch.copy(midi = midi)
    fun withVelocity(patch: MeridianPatch, velocity: Float): MeridianPatch = patch.copy(velocity = velocity)
    fun render(patch: MeridianPatch): Snip = patch.render()

    fun rootMidi(patch: MeridianPatch): Int = Meridian.midiFor(patch.voice, patch.macros["TUNE"] ?: .5f, patch.midi)
    fun isHeld(patch: MeridianPatch): Boolean = Meridian.isLoop(patch.macros, patch.model)
    fun holdLabel(patch: MeridianPatch): String = if (isHeld(patch)) "FIXED LOOP" else "FINITE"
    fun holdCaption(patch: MeridianPatch): String = if (isHeld(patch))
        "The swing repeats at one fixed alignment." else "A finite swing, followed by the remaining ring."

    data class PadLanding(
        val recipe: PadRecipe,
        val knownRootMidi: Int,
        val source: Map<String, String>,
    ) {
        val drumClass: DrumClass get() = DrumClass.TONAL
        val oneShot: Boolean get() = true
        fun render(): Snip = recipe.render()
    }

    /**
     * SEND TO PAD always delivers a finite sample. Its saved ending is separate
     * from the source's real held transport, so reopening and replaying a pad
     * retains that ending without changing the patch's HOLD setting or model.
     * A recalled recipe keeps its complete rack, board and treatment metadata.
     */
    fun padLanding(patch: MeridianPatch, originalRecipe: PadRecipe? = null): PadLanding {
        val held = isHeld(patch)
        val original = originalRecipe?.copy(patch = patch) ?: PadRecipe(patch)
        val fade = if (held) maxOf(original.terminalFadeMs ?: 0f, MeridianKits.HELD_CAPTURE_FADE_MS)
            else original.terminalFadeMs
        val recipe = original.copy(terminalFadeMs = fade)
        val root = rootMidi(patch)
        return PadLanding(recipe, root, linkedMapOf(
            "engine" to patch.engine, "model" to patch.model.toString(),
            "nativeMidi" to patch.midi.toString(), "rootMidi" to root.toString(),
            "rootNote" to Scales.nameOf(root), "rootOrigin" to "authored",
            "velocity" to patch.velocity.toString(), "seedContext" to patch.seedContext.toString(),
            "playback" to if (held) "FINITE_HELD_CAPTURE" else "FINITE_ONE_SHOT",
            "terminalFadeMs" to (recipe.terminalFadeMs?.toString() ?: "0"),
        ))
    }
}
