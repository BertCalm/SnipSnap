package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip

/**
 * The S3.5 factory kit: a *melodic* kit, where the 4×4 grid is a scale, not
 * a drum layout. This is the keys-on-pads promise from docs/SYNTH_ROADMAP.md
 * arriving early as one-shots — no keygroup export needed, because every pad
 * is just a WAV like any other.
 *
 * Layout (root bottom-left, ascending left→right bottom→top, per the SCALE
 * layout convention): A01–A12 walk two octaves of A minor pentatonic on
 * PLUCK — nylon low, kalimba above, one harp on top — and A13–A16 are
 * TONEWHEEL stabs on the root, fourth, fifth and octave.
 */
object SynthKits {

    private fun pluck(voice: PluckVoice, semitone: Int, vararg extra: Pair<String, Float>): Pair<Snip, DrumClass> =
        Pluck.render(
            voice,
            mapOf("TUNE" to semitone / Pluck.TUNE_SEMITONES.toFloat()) + extra,
        ) to DrumClass.TONAL

    private fun stab(voice: TonewheelVoice, semitone: Int): Pair<Snip, DrumClass> =
        Tonewheel.render(
            voice,
            mapOf("TUNE" to semitone / Tonewheel.TUNE_SEMITONES.toFloat()),
        ) to DrumClass.TONAL

    /** A minor pentatonic: 0 3 5 7 10, repeating up the octaves. */
    private val PENTATONIC = intArrayOf(0, 3, 5, 7, 10, 12, 15, 17, 19, 22, 24)

    fun melodic(): List<Pair<Snip, DrumClass>?> = listOf(
        pluck(PluckVoice.NYLON, PENTATONIC[0]),                 // A01 - the root
        pluck(PluckVoice.NYLON, PENTATONIC[1]),                 // A02
        pluck(PluckVoice.NYLON, PENTATONIC[2]),                 // A03
        pluck(PluckVoice.NYLON, PENTATONIC[3]),                 // A04
        pluck(PluckVoice.NYLON, PENTATONIC[4]),                 // A05
        pluck(PluckVoice.NYLON, PENTATONIC[5]),                 // A06
        // The kalimba's root sits an octave above the nylon's, so subtracting
        // an octave keeps one unbroken pitch line while the timbre climbs.
        pluck(PluckVoice.KALIMBA, PENTATONIC[6] - 12),          // A07 - kalimba takes over
        pluck(PluckVoice.KALIMBA, PENTATONIC[7] - 12),          // A08
        pluck(PluckVoice.KALIMBA, PENTATONIC[8] - 12),          // A09
        pluck(PluckVoice.KALIMBA, PENTATONIC[9] - 12),          // A10
        pluck(PluckVoice.KALIMBA, PENTATONIC[10] - 12),         // A11
        pluck(PluckVoice.HARP, 24, "DOUBLE" to 0.4f),           // A12 - harp crown (E5, the in-scale fifth)
        stab(TonewheelVoice.SOUL, 0),                           // A13 - stabs: root
        stab(TonewheelVoice.STAB, 5),                           // A14 - fourth
        stab(TonewheelVoice.STAB, 7),                           // A15 - fifth
        stab(TonewheelVoice.FULL, 12),                          // A16 - octave, everything out
    )
}
