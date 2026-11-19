package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * Opt-in round robin for the accepted sixteen-pad TEMPEST kit ([SynthKits.tempest]): the same
 * pads in the same order, [TAKES] takes each, dealt in turn on every hit. TEMPEST has one touch,
 * so each pad is a single-zone chain of takes. Take 0 is the accepted pad, sample for sample. A
 * later take is a genuine re-render of the same recipe ([Tempest.render]'s take) that re-rolls only
 * what an analog drum machine leaves to chance between strokes ([Tempest.Takes]): every voice
 * draws fresh noise; metal's inharmonic partials and the hat's six partials start at slightly
 * different phases; the clap's later bursts land a little early or late. Kicks, snares and toms
 * keep their tuned bodies exactly, so their takes differ only in their noise. Pitch, envelopes,
 * filters, drive, the repeats and the output stage are untouched, and each take is levelled by
 * the same house stage. Hats keep mute group 1, so a closed hat still cuts an open one.
 *
 * The kit's takes are the renderer's takes in [STRIKES], screened like EROSION's: on every pad a take
 * keeps the accepted onset (within 0.25 ms), stays within 2 dB of the accepted hit's spectral shape
 * over the bands that carry it, and keeps its 90%-energy time within 0.7 to 1.4 times the accepted
 * one. Of renderer takes 1 to 24, nine passed (2, 5, 8, 9, 12, 15, 17, 21 and 23); the kit keeps
 * the first three. The rest failed only on spectral shape, every time on a snare or clap, whose
 * fresh noise moved the shape by 2.0 to 2.5 dB.
 *
 * Pads carry no PadRecipe (a single-hit recipe cannot regenerate four takes); the accepted recipe
 * rides in the pad's source as JSON beside the take rule. The accepted TEMPEST starter is unchanged.
 */
object TempestRoundRobinKits {
    const val ID = "tempest-round-robin"
    const val VERSION = 1
    const val TAKES = 4
    /** The renderer take behind each kit take, in dealing order; the first is the accepted hit. */
    val STRIKES = listOf(0, 2, 5, 8)

    /** The finished take [take] of [patch]. Pure: the same arguments give the same bytes. */
    fun render(patch: TempestPatch, take: Int): Snip {
        require(take in 0 until TAKES)
        return Tempest.render(patch.voice, patch.macros, patch.model, STRIKES[take])
    }

    /** Every take of [patch], in dealing order. */
    fun takes(patch: TempestPatch): List<Snip> = (0 until TAKES).map { render(patch, it) }

    fun build(): List<ArrangedPad> {
        val patches = TempestPresets.fullKitPatches()
        val rows = patches.parallelStream().map { takes(it) }.toList()
        return patches.zip(rows).map { (patch, row) ->
            val first = row.first()
            ArrangedPad(
                snip = first,
                drumClass = Tempest.drumClassFor(first),
                displayName = patch.name,
                takes = row.drop(1),
                source = mapOf("engine" to "TEMPEST", "role" to patch.voice.name, "model" to patch.model.toString(),
                    "factoryPreset" to patch.name,
                    "recipe" to patch.toJsonText(),
                    "roundRobin" to "$TAKES takes: take 1 is the accepted hit, later takes re-roll the noise and the machine's free-running details",
                    "rendererTakes" to STRIKES.joinToString(","),
                    "roundRobinVersion" to VERSION.toString()),
                muteGroup = if (patch.voice == TempestVoice.HAT) 1 else 0,
            )
        }
    }
}
