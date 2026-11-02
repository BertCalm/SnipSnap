package com.snipsnap.synth

/**
 * The FATHOM listening page through [RosterAudition]: the starter kit, then each
 * voice's default, every knob at its ends, TUNE at three notes, and its twelve
 * presets. Run via `./gradlew :synth:generateFathomAudition`; it writes
 * testkit/fathom-audition/ (gitignored) to publish as the listening artifact.
 */
object FathomAuditionGenerator {

    internal val SPEC = RosterAudition.Spec(
        engine = FathomPatch.ENGINE,
        title = "Fathom Audition",
        tag = "FIRST LISTEN · THE BASS SLOT",
        guide = listOf(
            "FATHOM is the bass engine. Every voice runs the same path: a source, then DRIVE, then a resonant low-pass, then the amp envelope. DRIVE sits before the filter on purpose, so the filter shapes the harmonics the drive makes.",
            "DEEP is a sine sub with a short pitch blip at the attack (SWEEP). GRIND is two detuned saws left to beat over a long tail (SPREAD). GLASS is two-operator FM tuned low, with RATIO snapped to five safe ratios. GLIDE slides up into the note on every voice.",
            "Nobody has heard these presets yet: they were authored from the DSP. Listen on speakers or headphones that reach below 60 Hz, or the DEEP clips will read as almost silent. Mark each clip KEEP, MEH or CUT. A CUT on a knob end means that end goes too far.",
        ),
        questions = listOf(
            "Which voice would you reach for first on an MPC bass track, and which would you never use?",
            "The knobs: which end of DRIVE, CUTOFF, DECAY, GLIDE, SWEEP, SPREAD or RATIO goes too far, or not far enough? Are the defaults where you would start?",
            "The kit: does the DEEP walk keep its weight up the row, and do the eight presets on A09 to A16 sound like eight different basses?",
        ),
        voiceNotes = mapOf(
            "DEEP" to "a sine sub with a pitch blip at the attack, E1 at the bottom of TUNE",
            "GRIND" to "two detuned saws beating over a long tail, A1 at the bottom of TUNE",
            "GLASS" to "low two-operator FM with a snapped ratio, A1 at the bottom of TUNE",
        ),
        knobNotes = mapOf(
            "GLIDE" to ("no slide: the note starts on pitch" to "the longest upward slide into the note"),
            "DRIVE" to ("clean: the source straight into the filter" to "hard saturation before the filter, more harmonics to shape"),
            "CUTOFF" to ("the filter nearly closed: a dark thud" to "the filter open: every harmonic the drive made"),
            "DECAY" to ("the shortest note" to "the longest tail"),
            "SWEEP" to ("no attack blip: a pure sine onset" to "the biggest pitch blip at the attack"),
            "SPREAD" to ("the two saws in tune: no beating" to "the widest detune: fast beating"),
            "RATIO" to ("sub-octave ratio 0.5: a soft, hollow FM" to "ratio 3: the metallic twelfth"),
        ),
        kit = { SynthKits.fathom() },
        kitNote = "the bass kit as the MPC gets it: the DEEP walk, then GRIND and GLASS presets",
    )

    @JvmStatic
    fun main(args: Array<String>) = RosterAudition.main(SPEC, args)
}
