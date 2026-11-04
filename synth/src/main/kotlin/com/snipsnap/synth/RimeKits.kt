package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/** A complete bank A: eight bass notes followed by the other eight musical recipes. */
object RimeKits {
    const val NAME = "SnipSnap Rime Kit"
    const val SOFT_VELOCITY = .3f
    const val MID_VELOCITY = .6f
    const val MAIN_VELOCITY = .8f

    fun patches(): List<RimePatch> {
        val root = RimePresets.starter(RimeVoice.LOOP)
        val walk = listOf(0 to "C2", 3 to "Eb2", 5 to "F2", 7 to "G2",
            10 to "Bb2", 12 to "C3", 15 to "Eb3", 17 to "F3")
        return walk.mapIndexed { i, (offset, note) -> root.copy(
            name = if (i == 0) root.name else "${root.name} $note", midi = root.midi + offset)
        } + RimePresets.legacyEntries().map { it.patch }.filter { it.name != root.name }
    }

    /** A separate bank keeps the accepted sixteen-pad kit reproducible. */
    fun completePatches(): List<RimePatch> {
        val finite = RimePresets.entries().map { it.patch }.filter { !Rime.isLoop(it.macros) }
        return (RimeVoice.entries.flatMap { voice -> finite.filter { it.voice == voice }.take(2) } +
            listOf(RimeVoice.REED, RimeVoice.FILIGREE, RimeVoice.CRUST, RimeVoice.VESSEL).map { voice ->
                finite.filter { it.voice == voice }[2]
            }).map { it.copy(model = Rime.MODEL_VERSION, gateSeconds = it.gateSeconds.coerceAtMost(.8f)) }
    }

    /** Native re-strikes supply timbre; the MPC velocity curve owns their level. */
    fun build(): List<ArrangedPad?> = build(patches())
    fun buildComplete(): List<ArrangedPad?> = build(completePatches())
    private fun build(patches: List<RimePatch>): List<ArrangedPad?> = patches.map { patch ->
        val recipe = PadRecipe(patch)
        val reference = recipe.render()
        ArrangedPad(reference, Rime.drumClassFor(patch.voice, patch.macros, patch.gateSeconds, patch.model),
            recipe = recipe.toJsonValue(), oneShot = true,
            softVariants = listOf(SOFT_VELOCITY, MID_VELOCITY).map { velocity ->
                Velocity.peakMatch(reference, patch.copy(velocity = velocity).render())
            }, displayName = patch.name,
            source = linkedMapOf("engine" to RimePatch.ENGINE, "model" to patch.model.toString(),
                "midi" to patch.midi.toString(), "seed" to patch.seed.toString(),
                "velocitySoft" to SOFT_VELOCITY.toString(), "velocityMid" to MID_VELOCITY.toString(),
                "velocityMain" to MAIN_VELOCITY.toString(), "velocityNormalization" to "peak-match-main"))
    }
}
