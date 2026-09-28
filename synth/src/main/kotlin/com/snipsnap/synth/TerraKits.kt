package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/**
 * TERRA KIT: the world-percussion factory kit from
 * `TERRA_World_Percussion_Synth_Spec.md` S5 - all four topologies (udu/cajón,
 * djembe/dholak/dumbek/tabla, agogô, balafon) across sixteen pads. Plugs
 * straight into `KitAssembler.assemble`, and every pad carries its
 * [PadRecipe] so the kit that lands on disk stays editable forever.
 *
 * Macros are written inline, the same way [ThumpKits] does for a first kit
 * predating its own presets file (see that object's own KDoc) - TERRA has
 * no `TerraPresets.kt` yet, so there is nowhere else for these numbers to
 * live. TUNE/DROOP values are S5's Hz/depth figures solved back through
 * this engine's macro maps (`Dsp.expMap`/`Dsp.lin`, the forward maps live in
 * each topology's own render function in [Terra]); FORCE, POS, CAVITY and
 * BUZZ take S5's own 0..1 figures directly.
 *
 * **Most of these sixteen pads read as PERC, and that's the classifier
 * being honest, not wrong.** `Classifier`'s five named families (KICK,
 * SNARE, HAT, TOM, CLAP) are a Western drum kit's vocabulary; a tabla's
 * ringing tone, an agogô's forged bell and a balafon's buzzing bar aren't
 * any of those, any more than SkinKits' RIDE/SHAKER/STICK trio is - PERC is
 * the shelf for "a real, distinct sound with no kit-vocabulary name," not a
 * failure to classify. Only the deepest, most fundamental-heavy hits clear
 * KICK's or TOM's low-band/centroid gates - measured per pad, not assumed
 * from the family.
 */
object TerraKits {

    /** One rendered slot: audio, class, and the recipe that regenerates it. */
    private fun slot(
        patchName: String,
        voice: TerraVoice,
        drumClass: DrumClass,
        vararg macros: Pair<String, Float>,
    ): ArrangedPad {
        val patch = TerraPatch(patchName, voice, macros.toMap())
        return ArrangedPad(patch.render(), drumClass, PadRecipe(patch).toJsonValue())
    }

    /**
     * Sixteen pads, laid out the way S5's own table orders them: the two
     * cavity/membrane bass pads and the hand-drum core first, the tabla and
     * dumbek pair in the middle, then the two struck-body topologies
     * (agogô, balafon) on the top row.
     */
    fun classic(): List<ArrangedPad?> = listOf(
        // Udu Low Whoomp: 55Hz, hardness 0.10, droop 0.05, CAVITY 0.90 ("deep air push").
        slot("Udu Whoomp", TerraVoice.RESONANT_CAVITY, DrumClass.KICK, "TUNE" to 0.1058f, "FORCE" to 0.10f, "DROOP" to 0.0769f, "CAVITY" to 0.90f), // A01
        // Bayan Heel Drag: 65Hz, hardness 0.25, droop 0.55 (a bayan's own heel-pressure pitch sweep).
        slot("Bayan Drag", TerraVoice.COMPOUND_MEMBRANE, DrumClass.KICK, "TUNE" to 0.0803f, "FORCE" to 0.25f, "DROOP" to 0.8462f), // A02
        // Djembe Bass: 73Hz, hardness 0.30, droop 0.12, POS 0.05 ("warm thump") - measured KICK: see TerraTest's own note on why.
        slot("Djembe Bass", TerraVoice.COMPOUND_MEMBRANE, DrumClass.KICK, "TUNE" to 0.1362f, "FORCE" to 0.30f, "POS" to 0.05f, "DROOP" to 0.1846f), // A03
        // Cajon Low Port: 60Hz, hardness 0.20, CAVITY 0.70, BUZZ 0.15 ("slight snare rattle").
        slot("Cajon Low", TerraVoice.RESONANT_CAVITY, DrumClass.KICK, "TUNE" to 0.1516f, "FORCE" to 0.20f, "CAVITY" to 0.70f, "BUZZ" to 0.15f), // A04
        // Dholak Bass: 98Hz, hardness 0.35, droop 0.35 ("heavy dynamic pitch sag").
        slot("Dholak Bass", TerraVoice.COMPOUND_MEMBRANE, DrumClass.TOM, "TUNE" to 0.2779f, "FORCE" to 0.35f, "DROOP" to 0.5385f), // A05
        // Dumbek Doum: 110Hz, hardness 0.40, droop 0.18 ("full center mass displacement") - measured TOM: centroidHz=125.4, lowRatio=0.642, same honest bass-family reasoning as Djembe Bass above.
        slot("Dumbek Doum", TerraVoice.COMPOUND_MEMBRANE, DrumClass.TOM, "TUNE" to 0.3333f, "FORCE" to 0.40f, "DROOP" to 0.2769f), // A06
        // Cajon Corner Slap: TUNED_BAR, 220Hz, hardness 0.85, BUZZ 0.75 ("high rattleAmount, fast decay").
        slot("Cajon Slap", TerraVoice.TUNED_BAR, DrumClass.PERC, "TUNE" to 0.2514f, "FORCE" to 0.85f, "BUZZ" to 0.75f), // A07
        // Djembe Open Tone: 245Hz, hardness 0.50, droop 0.05, POS 0.5 ("harmonic mode resonance").
        slot("Djembe Open", TerraVoice.COMPOUND_MEMBRANE, DrumClass.PERC, "TUNE" to 0.7185f, "FORCE" to 0.50f, "POS" to 0.5f, "DROOP" to 0.0769f), // A08
        // Tabla Dayan (Tin): D4/293.6Hz, hardness 0.80, droop 0.02, POS 0.95 ("high-edge rim strike").
        slot("Tabla Tin", TerraVoice.COMPOUND_MEMBRANE, DrumClass.PERC, "TUNE" to 0.8057f, "FORCE" to 0.80f, "POS" to 0.95f, "DROOP" to 0.0308f), // A09
        // Tabla Dayan (Tun): same D4 fundamental as Tin, hardness 0.45, center strike ("resonant open center strike").
        slot("Tabla Tun", TerraVoice.COMPOUND_MEMBRANE, DrumClass.PERC, "TUNE" to 0.8057f, "FORCE" to 0.45f, "POS" to 0.05f), // A10
        // Dumbek Tek: 440Hz, hardness 0.92, POS 0.95 ("hard fingertip on aluminum rim").
        slot("Dumbek Tek", TerraVoice.COMPOUND_MEMBRANE, DrumClass.PERC, "TUNE" to 1.0f, "FORCE" to 0.92f, "POS" to 0.95f), // A11
        // Djembe Sharp Slap: 370Hz, hardness 0.95, droop 0.20, POS 0.9, fast decay ("60ms rim slap") - DECAY 0 is this
        // engine's own floor (80ms), the closest it comes; see Terra.DECAY_MIN_SECONDS.
        slot("Djembe Slap", TerraVoice.COMPOUND_MEMBRANE, DrumClass.PERC, "TUNE" to 0.9167f, "FORCE" to 0.95f, "POS" to 0.9f, "DROOP" to 0.3077f, "DECAY" to 0f), // A12
        // Agogo Low Bell: D5/587.3Hz, hardness 0.85 ("forged iron conical modes").
        slot("Agogo Low", TerraVoice.CONICAL_BELL, DrumClass.PERC, "TUNE" to 0.2508f, "FORCE" to 0.85f), // A13
        // Agogo High Bell: A5/880Hz, hardness 0.90 ("upper bell tone, perfect 5th up").
        slot("Agogo High", TerraVoice.CONICAL_BELL, DrumClass.PERC, "TUNE" to 0.8807f, "FORCE" to 0.90f), // A14
        // Agogo Clack: 740Hz, hardness 0.95, CLACK 20ms/30ms=0.6667 ("InterlockClackMs = 20ms").
        slot("Agogo Clack", TerraVoice.CONICAL_BELL, DrumClass.PERC, "TUNE" to 0.6108f, "FORCE" to 0.95f, "CLACK" to 0.6667f), // A15
        // Balafon Key Gourd: E4/329.6Hz, hardness 0.70, BUZZ 0.60 ("spider-egg membrane buzz on wooden bar").
        slot("Balafon Key", TerraVoice.TUNED_BAR, DrumClass.PERC, "TUNE" to 0.7573f, "FORCE" to 0.70f, "BUZZ" to 0.60f), // A16
    )
}
