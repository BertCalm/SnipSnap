package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip

/**
 * SYNTH KIT: render a whole playable kit from one style, laid out the way
 * [com.snipsnap.audio.AutoPlace] would place it — kick A01, snare A02, hats
 * A03/A04 choking. The result plugs straight into `KitAssembler.assemble`.
 */
object ThumpKits {

    /** One rendered slot: the sound plus the class the kit layer needs. */
    private fun slot(voice: ThumpVoice, drumClass: DrumClass, vararg macros: Pair<String, Float>) =
        Thump.render(voice, macros.toMap()) to drumClass

    /**
     * The factory default: a tight, punchy analog-style kit across 12 pads.
     * Index i lands on pad i+1; nulls are empty pads.
     */
    fun classic(): List<Pair<Snip, DrumClass>?> = listOf(
        slot(ThumpVoice.KICK, DrumClass.KICK),                                     // A01
        slot(ThumpVoice.SNARE, DrumClass.SNARE),                                   // A02
        slot(ThumpVoice.HAT_CLOSED, DrumClass.HAT_CLOSED),                         // A03
        slot(ThumpVoice.HAT_OPEN, DrumClass.HAT_OPEN),                             // A04
        slot(ThumpVoice.KICK, DrumClass.KICK, "TUNE" to 0.15f, "DECAY" to 0.7f, "DRIVE" to 0.5f), // A05
        slot(ThumpVoice.CLAP, DrumClass.CLAP),                                     // A06
        slot(ThumpVoice.RIM, DrumClass.PERC),                                      // A07
        slot(ThumpVoice.COWBELL, DrumClass.PERC),                                  // A08
        slot(ThumpVoice.TOM, DrumClass.TOM, "TUNE" to 0.2f),                       // A09
        slot(ThumpVoice.TOM, DrumClass.TOM, "TUNE" to 0.5f),                       // A10
        slot(ThumpVoice.TOM, DrumClass.TOM, "TUNE" to 0.8f),                       // A11
        slot(ThumpVoice.SNARE, DrumClass.SNARE, "SNAP" to 0.85f, "TONE" to 0.8f, "DECAY" to 0.25f), // A12
        null, null, null, null,                                                    // A13-A16
    )
}
