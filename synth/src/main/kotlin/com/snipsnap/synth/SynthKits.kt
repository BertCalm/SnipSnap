package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/**
 * The S3.5 factory kit: a *melodic* kit, where the 4×4 grid is a scale, not
 * a drum layout. This is the keys-on-pads promise from docs/SYNTH_ROADMAP.md
 * arriving early as one-shots — no keygroup export needed, because every pad
 * is just a WAV like any other. Every pad carries its [PadRecipe].
 */
object SynthKits {

    private fun pad(patch: Patch, drumClass: DrumClass, fx: FxChain? = null): ArrangedPad {
        val recipe = PadRecipe(patch, fx)
        return ArrangedPad(recipe.render(), drumClass, recipe.toJsonValue())
    }

    private fun pluck(name: String, voice: PluckVoice, semitone: Int, vararg extra: Pair<String, Float>) =
        pad(
            PluckPatch(name, voice, mapOf("TUNE" to semitone / Pluck.TUNE_SEMITONES.toFloat()) + extra),
            DrumClass.TONAL,
        )

    private fun stab(name: String, voice: TonewheelVoice, semitone: Int) =
        pad(
            TonewheelPatch(name, voice, mapOf("TUNE" to semitone / Tonewheel.TUNE_SEMITONES.toFloat())),
            DrumClass.TONAL,
        )

    /** A minor pentatonic: 0 3 5 7 10, repeating up the octaves. */
    private val PENTATONIC = intArrayOf(0, 3, 5, 7, 10, 12, 15, 17, 19, 22, 24)

    fun melodic(): List<ArrangedPad?> = listOf(
        pluck("Nylon 1", PluckVoice.NYLON, PENTATONIC[0]),        // A01 - the root
        pluck("Nylon 2", PluckVoice.NYLON, PENTATONIC[1]),        // A02
        pluck("Nylon 3", PluckVoice.NYLON, PENTATONIC[2]),        // A03
        pluck("Nylon 4", PluckVoice.NYLON, PENTATONIC[3]),        // A04
        pluck("Nylon 5", PluckVoice.NYLON, PENTATONIC[4]),        // A05
        pluck("Nylon 6", PluckVoice.NYLON, PENTATONIC[5]),        // A06
        // The kalimba's root sits an octave above the nylon's, so subtracting
        // an octave keeps one unbroken pitch line while the timbre climbs.
        pluck("Kalimba 1", PluckVoice.KALIMBA, PENTATONIC[6] - 12),  // A07 - kalimba takes over
        pluck("Kalimba 2", PluckVoice.KALIMBA, PENTATONIC[7] - 12),  // A08
        pluck("Kalimba 3", PluckVoice.KALIMBA, PENTATONIC[8] - 12),  // A09
        pluck("Kalimba 4", PluckVoice.KALIMBA, PENTATONIC[9] - 12),  // A10
        pluck("Kalimba 5", PluckVoice.KALIMBA, PENTATONIC[10] - 12), // A11
        pluck("Harp Crown", PluckVoice.HARP, 24, "DOUBLE" to 0.4f),  // A12 - harp crown (E5, the in-scale fifth)
        stab("Soul Root", TonewheelVoice.SOUL, 0),                // A13 - stabs: root
        stab("Stab Fourth", TonewheelVoice.STAB, 5),              // A14 - fourth
        stab("Stab Fifth", TonewheelVoice.STAB, 7),               // A15 - fifth
        stab("Full Octave", TonewheelVoice.FULL, 12),             // A16 - octave, everything out
    )

    /**
     * The chip kit — the roadmap's free bonus, cashed: VELVET squares and
     * THUMP/TINES drums, every pad rendered through CRUNCH via the FxChain.
     * Maximum kitsch, zero new DSP. Drums on the bottom row and a half,
     * chip-square pentatonic from A07 up. Because each pad's recipe stores
     * the patch *and* the converter chain, the whole aesthetic is one edit
     * away from being someone else's.
     */
    fun chip(): List<ArrangedPad?> {
        // One converter for the whole kit: ~9 bits, lowered hold rate, a
        // little grit. Consistent grunge is what makes it read as hardware.
        val console = FxChain(
            crunch = mapOf("BITS" to 0.75f, "RATE" to 0.6f, "TONE" to 0.65f, "GRIT" to 0.2f),
        )

        fun chipNote(n: Int, semitone: Int) = pad(
            VelvetPatch("Chip Note $n", VelvetVoice.CHIP, mapOf("TUNE" to semitone / Velvet.TUNE_SEMITONES.toFloat())),
            DrumClass.TONAL,
            console,
        )

        return listOf(
            pad(ThumpPatch("Chip Kick", ThumpVoice.KICK, mapOf("TUNE" to 0.5f, "DECAY" to 0.35f)), DrumClass.KICK, console), // A01
            pad(ThumpPatch("Chip Snare", ThumpVoice.SNARE, mapOf("SNAP" to 0.8f, "DECAY" to 0.3f)), DrumClass.SNARE, console), // A02
            pad(ThumpPatch("Chip Hat", ThumpVoice.HAT_CLOSED, emptyMap()), DrumClass.HAT_CLOSED, console),  // A03
            pad(ThumpPatch("Chip Open Hat", ThumpVoice.HAT_OPEN, mapOf("DECAY" to 0.4f)), DrumClass.HAT_OPEN, console), // A04
            pad(TinesPatch("Game Over", TinesVoice.TOY, emptyMap()), DrumClass.PERC, console),              // A05
            pad(TinesPatch("Laser", TinesVoice.ZAP, emptyMap()), DrumClass.PERC, console),                  // A06
            chipNote(1, PENTATONIC[0]), chipNote(2, PENTATONIC[1]),                                         // A07 A08
            chipNote(3, PENTATONIC[2]), chipNote(4, PENTATONIC[3]),                                         // A09 A10
            chipNote(5, PENTATONIC[4]), chipNote(6, PENTATONIC[5]),                                         // A11 A12
            chipNote(7, PENTATONIC[6]), chipNote(8, PENTATONIC[7]),                                         // A13 A14
            chipNote(9, PENTATONIC[8]), chipNote(10, PENTATONIC[9]),                                        // A15 A16
        )
    }

    /**
     * The atmosphere kit: VOX choirs on the bottom rows, GRAINS clouds
     * above — the first kit whose sounds come from granulating *other
     * sounds*, which in the app means granulating your captures. GRAINS
     * pads carry no recipe yet (a cloud's recipe needs a source-file
     * reference, which is the app layer's kit-folder job); VOX pads are
     * fully editable like every other synth pad.
     */
    fun cloud(): List<ArrangedPad?> {
        fun vox(name: String, voice: VoxVoice, semitone: Int) = pad(
            VoxPatch(name, voice, mapOf("TUNE" to semitone / Vox.TUNE_SEMITONES.toFloat())),
            DrumClass.TONAL,
        )

        fun cloudOf(source: com.snipsnap.audio.Snip, cls: DrumClass, seconds: Float, seed: Int, vararg macros: Pair<String, Float>) =
            ArrangedPad(Grains.render(source, macros.toMap(), seconds = seconds, seed = seed), cls)

        val bell = Tines.render(TinesVoice.BELL, mapOf("DECAY" to 0.9f))
        val chime = Tines.render(TinesVoice.CHIME, mapOf("DECAY" to 0.8f))
        val brass = Velvet.render(VelvetVoice.BRASS, mapOf("DECAY" to 0.9f))
        val koto = Pluck.render(PluckVoice.KOTO, mapOf("DAMP" to 0.2f))
        val choirSrc = Vox.render(VoxVoice.CHOIR, mapOf("DECAY" to 1f))

        return listOf(
            vox("Choir Root", VoxVoice.CHOIR, 0),                                    // A01
            vox("Choir Fourth", VoxVoice.CHOIR, 5),                                  // A02
            vox("Choir Fifth", VoxVoice.CHOIR, 7),                                   // A03
            vox("Robot Root", VoxVoice.ROBOT, 0),                                    // A04
            vox("Ghost Low", VoxVoice.GHOST, 0),                                     // A05
            vox("Ghost High", VoxVoice.GHOST, 12),                                   // A06
            cloudOf(bell, DrumClass.PERC, 1.2f, 21, "SIZE" to 0.3f, "DRIFT" to 0.4f),      // A07 bell scatter
            cloudOf(chime, DrumClass.PERC, 1.2f, 22, "SIZE" to 0.2f, "SHINE" to 0.5f),     // A08 glass stutter
            cloudOf(koto, DrumClass.PERC, 1.2f, 23, "SIZE" to 0.35f, "DRIFT" to 0.6f),     // A09 koto scatter
            cloudOf(brass, DrumClass.TONAL, 1.4f, 24, "SIZE" to 0.8f, "SMEAR" to 0.8f),    // A10 brass smear
            cloudOf(choirSrc, DrumClass.TONAL, 1.4f, 25, "SIZE" to 0.9f, "PITCH" to 0.25f), // A11 choir under
            cloudOf(bell, DrumClass.TONAL, 1.4f, 26, "SIZE" to 0.7f, "PITCH" to 0.75f, "SHINE" to 0.6f), // A12 bell above
            cloudOf(choirSrc, DrumClass.LOOP, 2.5f, 27, "SIZE" to 1f, "SMEAR" to 1f, "PITCH" to 0.25f),  // A13 choir drone
            cloudOf(brass, DrumClass.LOOP, 2.5f, 28, "SIZE" to 1f, "SMEAR" to 0.9f, "DRIFT" to 0.3f),    // A14 brass pad
            cloudOf(bell, DrumClass.LOOP, 2.5f, 29, "SIZE" to 0.9f, "SHINE" to 0.8f, "DRIFT" to 0.5f),   // A15 shimmer wash
            cloudOf(chime, DrumClass.LOOP, 2.5f, 30, "SIZE" to 0.6f, "PITCH" to 0.3f, "DRIFT" to 0.7f),  // A16 deep glass
        )
    }
}
