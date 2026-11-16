package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/** Eight C-minor-pentatonic bass notes, then six source voices and the two accepted routes. */
object BristleKits {
    const val NAME = "SnipSnap Bristle Kit"
    val velocities = listOf(.25f, .65f, 1f)
    const val TAKES = 2

    fun patches(): List<BristlePatch> {
        val root = BristlePresets.forVoice(Bristle.Route.A).single()
        val notes = listOf(24,27,29,31,34,36,39,41)
        return notes.map { root.copy(name = "Filtered ${com.snipsnap.audio.Scales.nameOf(it)}", midi = it) } +
            (Bristle.musicalVoices + Bristle.anchors).map { BristlePresets.forVoice(it).single() }
    }

    /** Remove sampled touch gain; the pad's live velocity curve supplies that gain once. */
    fun grid(patch: BristlePatch): List<List<Snip>> = velocities.map { velocity ->
        (0 until TAKES).map { take ->
            val snip = patch.copy(velocity = patch.velocity * velocity, take = (patch.take + take) % TAKES).render()
            snip.copy(samples = FloatArray(snip.samples.size) { snip.samples[it] / velocity })
        }
    }

    fun build(): List<ArrangedPad> = patches().map { patch ->
        val grid = grid(patch)
        ArrangedPad(grid.last().first(), DrumClass.TONAL, recipe = PadRecipe(patch).toJsonValue(),
            displayName = patch.name, velocityTakes = grid, source = mapOf(
                "engine" to BristlePatch.ENGINE, "model" to patch.model.toString(),
                "voice" to patch.voice.name, "midi" to patch.midi.toString(),
                "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                "normalization" to "remove-touch-gain", "articulation" to "finite"))
    }
}
