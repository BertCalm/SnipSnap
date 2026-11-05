package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.kit.ArrangedPad

/**
 * The first Meridian pad bank: a C-minor-pentatonic walk followed by eight
 * saved material journeys. Drum-program exports play every pad once. Held
 * presets are finite captures here; their genuine sustained instruments are
 * exported separately through the supported keygroup path.
 */
object MeridianKits {
    const val NAME = "SnipSnap Meridian Kit"
    const val ARCHIVE_NAME = "SnipSnap-Meridian-Kit.zip"
    const val HELD_CAPTURE_FADE_MS = 20f
    const val HELD_RELEASE_SECONDS = .12f

    private val walkMidis = listOf(60, 63, 65, 67, 70, 72, 75, 77)
    private val walkVelocities = listOf(.70f, .50f, .75f, .60f, .82f, .55f, .72f, .85f)

    /** A01-A08 use native notes, not sampler retuning, so swing timing stays fixed. */
    fun patches(): List<MeridianPatch> {
        val presets = MeridianPresets.all()
        require(presets.size == 8) { "the Meridian starter kit requires eight saved presets" }
        val finite = presets.filterNot { Meridian.isLoop(it.macros, it.model) }
        val held = presets.filter { Meridian.isLoop(it.macros, it.model) }
        require(finite.isNotEmpty()) { "the Meridian scale walk requires a finite preset" }
        require(presets.all { it.model == Meridian.MODEL_VERSION }) { "the kit preserves the current saved renderer model" }
        val first = finite.first()
        val walk = walkMidis.mapIndexed { index, midi ->
            first.copy(name = "${first.name} ${Scales.nameOf(midi)}", midi = midi, velocity = walkVelocities[index])
        }
        return walk + finite + held
    }

    /** The fade belongs to this finite pad capture, leaving the held source recipe intact. */
    fun sourceRecipe(patch: MeridianPatch): PadRecipe = PadRecipe(
        patch = patch,
        terminalFadeMs = if (Meridian.isLoop(patch.macros, patch.model)) HELD_CAPTURE_FADE_MS else null,
    )

    /** Sixteen dry native renders. LOOP filing never implies a drum-program sustain loop. */
    fun build(): List<ArrangedPad?> = patches().mapIndexed { index, patch ->
        val held = Meridian.isLoop(patch.macros, patch.model)
        val recipe = sourceRecipe(patch)
        val midi = Meridian.midiFor(patch.voice, patch.macros["TUNE"] ?: .5f, patch.midi)
        ArrangedPad(
            snip = recipe.render(),
            drumClass = Meridian.drumClassFor(patch.voice, patch.macros, patch.model),
            recipe = recipe.toJsonValue(),
            oneShot = true,
            displayName = patch.name,
            source = linkedMapOf(
                "engine" to MeridianPatch.ENGINE,
                "model" to patch.model.toString(),
                "midi" to midi.toString(),
                "velocity" to patch.velocity.toString(),
                "seedContext" to patch.seedContext.toString(),
                "row" to if (index < 8) "MINOR_PENTATONIC" else "PRESETS",
                "playback" to if (held) "FINITE_HELD_CAPTURE" else "FINITE_ONE_SHOT",
                "terminalFadeMs" to (recipe.terminalFadeMs?.toString() ?: "0"),
            ),
        )
    }
}
