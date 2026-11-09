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
        val snip = patch.render()
        // Two softer strikes under the hard one (StrikeVelocity), so the MPC
        // switches sound, not just level, with how hard the pad is hit.
        return ArrangedPad(snip, drumClass, PadRecipe(patch).toJsonValue(), softVariants = Velocity.variantsAt(snip, patch, count = 2))
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
        // Bayan Drag: the BAYAN voice (TerraFitted, 2026-11-08), fitted to the owner's bayan reference: a ghe on
        // 92 Hz, the heel letting go from 1.6x (BEND 1). The owner: "Both work" for the fall and the held ghe (BEND 0).
        // Classified TOM, as the classifier hears it: a real ghe's partials (267-741 Hz) put its centroid
        // past a kick's, where the old 65 Hz membrane pad was a KICK.
        slot("Bayan Drag", TerraVoice.BAYAN, DrumClass.TOM, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "BEND" to 1f), // A02
        // Djembe Bass: the DJEMBE voice's bass stroke (TerraFitted, 2026-11-08), fitted to the owner's djembe reference,
        // then round 10's B: a deeper bowl (58 Hz), a bigger boom and the palm's thump. Classified TOM, as the
        // classifier hears it (centroid 128 Hz, low share 0.823, just under a kick's 0.85 at that centroid).
        slot("Djembe Bass", TerraVoice.DJEMBE, DrumClass.TOM, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "STROKE" to 0f, "THUMP" to 0.5f), // A03
        // Cajon Low Port: 60Hz, hardness 0.20, CAVITY 0.70, BUZZ 0.15 ("slight snare rattle").
        slot("Cajon Low", TerraVoice.RESONANT_CAVITY, DrumClass.KICK, "TUNE" to 0.1516f, "FORCE" to 0.20f, "CAVITY" to 0.70f, "BUZZ" to 0.15f), // A04
        // Dholak Bass: the DHOLAK voice (TerraFitted, 2026-11-08), fitted to the owner's dholak reference: 95 Hz falling
        // from 1.55x (round 9's B, 7.6 semitones), the treble head ringing in sympathy.
        slot("Dholak Bass", TerraVoice.DHOLAK, DrumClass.TOM, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "DROP" to 0.5f, "TREBLE" to 0.5f), // A05
        // Dumbek Doum: the DOUM voice (TerraHands, 2026-11-08): the Dumbek Tek's goblet struck in its centre, its air at
        // 110 Hz answering the head's net volume (round 5's A). Classified PERC, as the classifier hears it
        // (centroid 200 Hz, low share 0.478): the 340 Hz head carries more of it than the air does.
        slot("Dumbek Doum", TerraVoice.DOUM, DrumClass.PERC, "TUNE" to 0.483f, "DECAY" to 0.5f, "FORCE" to 0.4f, "AIR" to 0.5f, "SHELL" to 0.5f), // A06
        // Cajon Slap: a box. The spec's TUNED_BAR slap shared the balafon's bar modes and had no low end
        // (heard 2026-11-06 as "very similar" to Balafon Key); its RESONANT_CAVITY rebuild the same day
        // had the bass but read "too much like a drum machine rimshot". Now the BOX voice (TerraBox) at
        // the Python prototype the owner picked ("C (deeper box)", the rimshot "Gone"): a birch plate's
        // 98 modes from 75 Hz struck near its top edge, the port's air at 85 Hz, the wires at half. Its
        // macros are spelled out (BOX's defaults) so the pad never moves with them. Classified SNARE, as
        // the wires lift the high band past half (0.54); a cajón's slap is its backbeat.
        slot("Cajon Slap", TerraVoice.BOX, DrumClass.SNARE, "TUNE" to 0.4f, "DECAY" to 0.5f, "FORCE" to 0.8f, "POS" to 0.875f, "CAVITY" to 0.3f, "BUZZ" to 0.5f), // A07
        // Djembe Open: the DJEMBE voice's open tone (TerraFitted, 2026-11-08), fitted to the owner's djembe reference,
        // then round 9's B: the head's notes ringing 1.8x longer.
        slot("Djembe Open", TerraVoice.DJEMBE, DrumClass.PERC, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "STROKE" to 1f, "THUMP" to 0.5f), // A08
        // Tabla Dayan (Tin): D4/293.6Hz, a rim strike. The DAYAN voice (TerraHands), from the owner's listening rounds (2026-11-07):
        // round 3's open ring "C but tighter, more forceful snap", then the third of three settings heard in
        // the app: a harder finger (FORCE 0.9) and more snap (CLICK 0.65). Its macros are spelled out so the
        // pad never moves with the voice's defaults.
        slot("Tabla Tin", TerraVoice.DAYAN, DrumClass.PERC, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.9f, "OPEN" to 1f, "CLICK" to 0.65f), // A09
        // Tabla Tun: the TUN voice (TerraFitted, 2026-11-08), fitted to three tuns in an open tabla dataset
        // (CC BY 4.0, measured only): the dayan's note at D4, the Tin's fundamental, ringing 2.55 s, and the
        // fingertip's knock 15 ms after it. The owner's pick, round 1's A ("as played").
        slot("Tabla Tun", TerraVoice.TUN, DrumClass.PERC, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "KNOCK" to 0.5f, "LAG" to 0.5f), // A10
        // Dumbek Tek: a hard fingertip at the rim. The DARBUKA voice (TerraHands, 2026-11-07): round 3's B, a ceramic goblet with more ring.
        slot("Dumbek Tek", TerraVoice.DARBUKA, DrumClass.PERC, "TUNE" to 0.483f, "DECAY" to 0.5f, "FORCE" to 0.92f, "POS" to 0.875f, "SHELL" to 0.5f, "SNAP" to 0.5f), // A11
        // Djembe Slap: the SLAP voice (TerraHands, 2026-11-07): round 2's A, "full body", the palm then the fingers over the bowl's air.
        slot("Djembe Slap", TerraVoice.SLAP, DrumClass.PERC, "TUNE" to 0.53f, "DECAY" to 0.5f, "FORCE" to 0.95f, "POS" to 0.875f, "BOWL" to 0.5f, "SMACK" to 0.5f), // A12
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
