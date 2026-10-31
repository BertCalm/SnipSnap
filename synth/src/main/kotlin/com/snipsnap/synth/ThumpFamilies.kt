package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip

/**
 * Related SPARK hits along one expression gesture, saved as ordinary patches.
 *
 * A position is a render coordinate, not automation inside a hit: brighter and
 * more metallic settings get a shorter decay, while tuning and punch stay put.
 * Keeping every resolved macro in each patch lets its WAV be regenerated without
 * remembering the source patch or this family operation.
 */
object ThumpFamilies {
    val DEFAULT_POSITIONS: List<Float> = listOf(0f, 1f / 3f, 2f / 3f, 1f)

    /** One rendered family member and the class measured from its own audio. */
    data class Hit(
        val position: Float,
        val patch: ThumpPatch,
        val snip: Snip,
        val drumClass: DrumClass,
    )

    /**
     * Build replayable SPARK recipes in ascending expression order.
     *
     * Position 0.5 preserves the source's resolved sound. The offsets below are
     * bounded sound-design laws, not a model of a physical instrument. Clamping
     * at a control boundary is intentional: no family member can leave SPARK's
     * accepted macro ranges.
     */
    fun patches(
        base: ThumpPatch,
        positions: List<Float> = DEFAULT_POSITIONS,
    ): List<ThumpPatch> {
        require(base.voice == ThumpVoice.SPARK) { "THUMP families require a SPARK patch" }
        val steps = positions.toList()
        require(steps.size in 2..16) { "THUMP families require 2..16 positions" }
        require(steps.all { it.isFinite() && it in 0f..1f }) {
            "THUMP family positions must be finite values in 0..1"
        }
        require(steps.zipWithNext().all { (a, b) -> a < b }) {
            "THUMP family positions must be unique and strictly ascending"
        }

        // Resolve only this voice's controls. A complete map makes recipes
        // independent of which controls the source explicitly stored.
        val resolved = Thump.macrosFor(base.voice).associate { spec ->
            val value = base.macros[spec.name]?.takeIf { it.isFinite() } ?: spec.default
            spec.name to value.coerceIn(0f, 1f)
        }
        return steps.mapIndexed { index, position ->
            val offset = position - 0.5f
            val macros = resolved.toMutableMap()
            macros["METAL"] = (resolved.getValue("METAL") + 0.70f * offset).coerceIn(0f, 1f)
            macros["COLOR"] = (resolved.getValue("COLOR") + 0.70f * offset).coerceIn(0f, 1f)
            macros["DECAY"] = (resolved.getValue("DECAY") - 0.30f * offset).coerceIn(0f, 1f)
            base.copy(name = "${base.name} ${index + 1}", macros = macros)
        }
    }

    /** Render each saved recipe and classify the resulting hit, not its name. */
    fun render(
        base: ThumpPatch,
        positions: List<Float> = DEFAULT_POSITIONS,
    ): List<Hit> {
        val steps = positions.toList()
        return patches(base, steps).mapIndexed { index, patch ->
            val snip = patch.render()
            Hit(steps[index], patch, snip, Classifier.classify(snip).drumClass)
        }
    }
}
