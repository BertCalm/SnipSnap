package com.snipsnap.synth

/**
 * The VELVET listening page through [RosterAudition]: each voice's default,
 * every knob at its ends, TUNE at three notes, and its factory presets. No
 * kit section yet: `SynthKits.velvet()` lands separately, and [SPEC]'s `kit`
 * stays null until it does. Run via
 * `./gradlew :synth:generateVelvetRosterAudition`; it writes
 * testkit/velvet-audition/ (gitignored) to publish as the listening artifact.
 */
object VelvetRosterAuditionGenerator {

    internal val SPEC = RosterAudition.Spec(
        engine = VelvetPatch.ENGINE,
        title = "Velvet Audition",
        tag = "FIRST LISTEN · THE SUBTRACTIVE STAB",
        guide = listOf(
            "VELVET is the subtractive engine. Every voice runs the same path: two naive oscillators a hair apart plus a square an octave down, into a resonant low-pass whose cutoff an envelope sweeps down while the note decays. The filter falls faster than the note, which is the stab.",
            "BASS sits at A1, BRASS at A2, SQUELCH at E2 and CHIP at A3, each with two octaves of TUNE. The four voices share one set of knobs; what tells them apart is where those knobs start. SQUELCH starts with SQUEEZE high, CHIP with SHAPE on a narrow pulse and the filter open.",
            "Nobody has heard these presets yet: they were authored from the DSP. BRASS's STRING MACHINE and THIN STRINGS play here dry, without the ensemble chorus they land with in the app. Mark each clip KEEP, MEH or CUT. A CUT on a knob end means that end goes too far.",
        ),
        questions = listOf(
            "Which voice would you put on a pad first, and which would you never use?",
            "The knobs: which end of SHAPE, FAT, CUTOFF, SQUEEZE or DECAY goes too far, or not far enough? Are the defaults where you would start?",
            "Does SQUELCH at high SQUEEZE sound like a squelch you would play, or does the filter scream?",
        ),
        voiceNotes = mapOf(
            "BASS" to "a saw pair and a sub square under a dark filter, A1 at the bottom of TUNE",
            "BRASS" to "a wide saw pair under a half-open filter, A2 at the bottom of TUNE",
            "SQUELCH" to "a saw pair with SQUEEZE high, so the filter rings and sweeps, E2 at the bottom of TUNE",
            "CHIP" to "a narrow pulse with the filter open, A3 at the bottom of TUNE",
        ),
        knobNotes = mapOf(
            "SHAPE" to ("a plain saw" to "a narrow pulse, about 12% wide: thin and nasal"),
            "FAT" to ("the pair almost in unison, the sub square quiet" to "the widest detune and the loudest sub square"),
            "CUTOFF" to ("the filter's resting point at its lowest: a dark, muffled stab" to "the filter's resting point wide open: every harmonic through"),
            "SQUEEZE" to ("little resonance and a small sweep: a soft, plain stab" to "the filter ringing and swept hardest: the squelch"),
            "DECAY" to ("the shortest note, about 0.15 s to silence" to "the longest note, about 0.9 s"),
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) = RosterAudition.main(SPEC, args)
}
