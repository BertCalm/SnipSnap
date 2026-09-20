package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/**
 * SYNTH KIT: a whole acoustic-style kit from [Skin], the counterpart to
 * [ThumpKits]'s analog one, laid out the way `AutoPlace` would place it —
 * kick A01, snare A02, hats A03/A04 choking. Plugs straight into
 * `KitAssembler.assemble`, and every pad carries its [PadRecipe] so the
 * kit that lands on disk stays editable forever.
 *
 * All eight SKIN voices appear, which is the point: it is the only place
 * in the app where SHAKER, STICK and RIDE are handed to a player without
 * them going to SYNTH and picking a voice.
 *
 * ## The macro values live in [SkinPresets], not here
 *
 * [ThumpKits] writes its macros inline because it predates its engine's
 * presets. SKIN's arrived first, so every pad below names a preset and
 * takes its values — one home for a number, and `SkinPresetsTest` already
 * proves each of those presets still classifies as its own voice, so this
 * kit inherits that guarantee rather than restating it. Renaming a preset
 * breaks this loudly (see [fromPreset]) instead of silently drifting.
 */
object SkinKits {

    /**
     * One rendered slot, its macros taken from the named preset.
     *
     * [patchName] is the pad's own name and is deliberately not the preset
     * name: a kit reads as a kit ("Low Tom", "Cross Stick"), while preset
     * names are the sound-design vocabulary of the SYNTH screen.
     */
    private fun fromPreset(
        patchName: String,
        preset: String,
        voice: SkinVoice,
        drumClass: DrumClass,
    ): ArrangedPad {
        val source = SkinPresets.forVoice(voice).firstOrNull { it.name == preset }
            ?: error(
                "SkinPresets has no '$preset' for $voice. This kit names its sounds by preset so the " +
                    "macro values have one home - if a preset was renamed, point this slot at the new " +
                    "name rather than copying its numbers back in here.",
            )
        val patch = SkinPatch(patchName, voice, source.macros)
        return ArrangedPad(patch.render(), drumClass, PadRecipe(patch).toJsonValue())
    }

    /**
     * Sixteen pads: the four-piece core, a second kick and snare, the
     * small percussion, three toms, and the ride pair on the top row.
     *
     * **RIDE is declared PERC, not HAT_OPEN, and that is deliberate.**
     * `Classifier` hears it as HAT_OPEN — `SkinTest` says so and it is a
     * real kinship, since RIDE is built from `hat()`'s own recipe. But the
     * declared class is not a description here, it is what
     * `AutoPlace.muteGroupFor` reads, and that puts HAT_CLOSED and
     * HAT_OPEN in one choke group. A ride declared HAT_OPEN would be cut
     * dead by every closed-hat hit, which is not what a ride cymbal does
     * on any kit ever made. SHAKER and STICK are PERC for the ordinary
     * reason: they have no class of their own.
     */
    fun classic(): List<ArrangedPad?> = listOf(
        fromPreset("Room Kick", "TAPE SKIN", SkinVoice.KICK, DrumClass.KICK),              // A01
        fromPreset("Wire Snare", "BRUSH WIRE", SkinVoice.SNARE, DrumClass.SNARE),          // A02
        fromPreset("Closed Hat", "SHUT BRASS", SkinVoice.HAT_CLOSED, DrumClass.HAT_CLOSED), // A03
        fromPreset("Open Hat", "LOOSE BRASS", SkinVoice.HAT_OPEN, DrumClass.HAT_OPEN),     // A04
        fromPreset("Deep Kick", "DEEP SHELL", SkinVoice.KICK, DrumClass.KICK),             // A05
        fromPreset("Tight Snare", "TIGHT SNARE", SkinVoice.SNARE, DrumClass.SNARE),        // A06
        fromPreset("Cross Stick", "DRY CLICK", SkinVoice.STICK, DrumClass.PERC),           // A07
        fromPreset("Shaker", "SOFT SEED", SkinVoice.SHAKER, DrumClass.PERC),               // A08
        fromPreset("Low Tom", "LOW WOOD", SkinVoice.TOM, DrumClass.TOM),                   // A09
        fromPreset("Mid Tom", "ROUND TOM", SkinVoice.TOM, DrumClass.TOM),                  // A10
        fromPreset("High Tom", "FAST TOM", SkinVoice.TOM, DrumClass.TOM),                  // A11
        fromPreset("Brush Snare", "SOFT CRACK", SkinVoice.SNARE, DrumClass.SNARE),         // A12
        fromPreset("Ride", "OLD BELL", SkinVoice.RIDE, DrumClass.PERC),                    // A13
        fromPreset("Ride Bell", "BRIGHT BELL", SkinVoice.RIDE, DrumClass.PERC),            // A14
        fromPreset("Long Shaker", "LONG SHAKE", SkinVoice.SHAKER, DrumClass.PERC),         // A15
        fromPreset("Rim Knock", "PEAK KNOCK", SkinVoice.STICK, DrumClass.PERC),            // A16
    )
}
