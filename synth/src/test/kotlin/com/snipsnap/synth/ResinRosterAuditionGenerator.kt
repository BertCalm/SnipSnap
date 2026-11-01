package com.snipsnap.synth

/**
 * The RESIN listening page through [RosterAudition]: each voice's default,
 * every knob at its ends, TUNE at three notes, and its factory presets, as
 * one-shots. No kit section yet: `SynthKits.resin()` lands separately, and
 * [SPEC]'s `kit` stays null until it does. Run via
 * `./gradlew :synth:generateResinRosterAudition`; it writes
 * testkit/resin-audition/ (gitignored) to publish as the listening artifact.
 */
object ResinRosterAuditionGenerator {

    internal val SPEC = RosterAudition.Spec(
        engine = ResinPatch.ENGINE,
        title = "Resin Audition",
        tag = "FIRST LISTEN · THE LADDER FILTER",
        guide = listOf(
            "RESIN is the ladder engine. Three oscillators (a saw at the note, a saw an octave down, a detuned square) feed a four-pole ladder low-pass whose feedback is soft-clipped inside the loop. The drive is the filter's own input; there is no separate saturator. An envelope sweeps the cutoff down while the note decays.",
            "BASS sits at A1, BRASS at A2 and LEAD at A3, each with two octaves of TUNE. STACK is the oscillator mixer on one knob, CONTOUR is the filter sweep's size and speed together, and CREAM is the feedback, whose top end passes the point where the ladder rings on its own.",
            "Nobody has heard these presets yet: they were authored from the DSP. BRASS's WIDE STRINGS and DARK STRINGS play here dry, without the ensemble chorus they land with in the app. These are the one-shots only, not the held instrument or the drone. Mark each clip KEEP, MEH or CUT. A CUT on a knob end means that end goes too far.",
        ),
        questions = listOf(
            "Which voice would you put on a pad first, and which would you never use?",
            "The knobs: which end of STACK, CUTOFF, CREAM, CONTOUR or DECAY goes too far, or not far enough? Are the defaults where you would start?",
            "Next to VELVET, does RESIN's filter sound like a different instrument, or the same stab with another name?",
        ),
        voiceNotes = mapOf(
            "BASS" to "the stack under a dark ladder, A1 at the bottom of TUNE",
            "LEAD" to "a full stack with more feedback, A3 at the bottom of TUNE",
            "BRASS" to "the biggest filter sweep, the wah then the snap, A2 at the bottom of TUNE",
        ),
        knobNotes = mapOf(
            "STACK" to ("one saw alone: thin" to "both saws and the detuned square: fat"),
            "CUTOFF" to ("the ladder's resting point at its lowest: a dark stab" to "the ladder's resting point wide open"),
            "CREAM" to ("no feedback: a plain four-pole low-pass" to "feedback past self-oscillation: the filter sings its own note"),
            "CONTOUR" to ("no filter sweep" to "a four-octave sweep that snaps shut fast"),
            "DECAY" to ("the shortest note, about 0.15 s to silence" to "the longest note, about 1.2 s"),
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) = RosterAudition.main(SPEC, args)
}
