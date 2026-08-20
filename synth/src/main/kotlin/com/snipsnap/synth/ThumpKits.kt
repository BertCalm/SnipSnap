package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/**
 * SYNTH KIT: render a whole playable kit from one style, laid out the way
 * [com.snipsnap.audio.AutoPlace] would place it — kick A01, snare A02, hats
 * A03/A04 choking. The result plugs straight into `KitAssembler.assemble`,
 * and every pad carries its [PadRecipe], so the kit that lands on disk
 * stays editable forever.
 */
object ThumpKits {

    /** One rendered slot: audio, class, and the recipe that regenerates it. */
    private fun slot(
        patchName: String,
        voice: ThumpVoice,
        drumClass: DrumClass,
        vararg macros: Pair<String, Float>,
    ): ArrangedPad {
        val patch = ThumpPatch(patchName, voice, macros.toMap())
        return ArrangedPad(patch.render(), drumClass, PadRecipe(patch).toJsonValue())
    }

    private fun tines(
        patchName: String,
        voice: TinesVoice,
        drumClass: DrumClass,
        vararg macros: Pair<String, Float>,
    ): ArrangedPad {
        val patch = TinesPatch(patchName, voice, macros.toMap())
        return ArrangedPad(patch.render(), drumClass, PadRecipe(patch).toJsonValue())
    }

    /**
     * The factory default: a tight, punchy analog-style kit on the first 12
     * pads, TINES metal on the top row — one kit from two engines, which is
     * the S3 promise. Index i lands on pad i+1.
     */
    fun classic(): List<ArrangedPad?> = listOf(
        slot("Factory Kick", ThumpVoice.KICK, DrumClass.KICK),                     // A01
        slot("Factory Snare", ThumpVoice.SNARE, DrumClass.SNARE),                  // A02
        slot("Factory Hat", ThumpVoice.HAT_CLOSED, DrumClass.HAT_CLOSED),          // A03
        slot("Factory Open Hat", ThumpVoice.HAT_OPEN, DrumClass.HAT_OPEN),         // A04
        slot("Basement Kick", ThumpVoice.KICK, DrumClass.KICK, "TUNE" to 0.15f, "DECAY" to 0.7f, "DRIVE" to 0.5f), // A05
        slot("Factory Clap", ThumpVoice.CLAP, DrumClass.CLAP),                     // A06
        slot("Factory Rim", ThumpVoice.RIM, DrumClass.PERC),                       // A07
        slot("Factory Cowbell", ThumpVoice.COWBELL, DrumClass.PERC),               // A08
        slot("Low Tom", ThumpVoice.TOM, DrumClass.TOM, "TUNE" to 0.2f),            // A09
        slot("Mid Tom", ThumpVoice.TOM, DrumClass.TOM, "TUNE" to 0.5f),            // A10
        slot("High Tom", ThumpVoice.TOM, DrumClass.TOM, "TUNE" to 0.8f),           // A11
        slot("Tight Snare", ThumpVoice.SNARE, DrumClass.SNARE, "SNAP" to 0.85f, "TONE" to 0.8f, "DECAY" to 0.25f), // A12
        tines("Factory Zap", TinesVoice.ZAP, DrumClass.PERC),                      // A13
        tines("Factory Block", TinesVoice.BLOCK, DrumClass.PERC),                  // A14
        tines("Factory Chime", TinesVoice.CHIME, DrumClass.TONAL),                 // A15
        tines("Long Bell", TinesVoice.BELL, DrumClass.TONAL, "DECAY" to 0.7f),     // A16
    )
}
