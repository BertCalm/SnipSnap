package com.snipsnap.synth

/**
 * The TIDE listening page through [RosterAudition]: the kit, then each
 * voice's default, every knob at its ends, TUNE at three notes, and its ten
 * presets. Run via `./gradlew :synth:generateTideRosterAudition`; it writes
 * testkit/tide-audition/ (gitignored) to publish as the listening artifact.
 */
object TideRosterAuditionGenerator {

    internal val SPEC = RosterAudition.Spec(
        engine = TidePatch.ENGINE,
        title = "Tide Audition",
        tag = "FIRST LISTEN · THE WEST COAST ENGINE",
        guide = listOf(
            "TIDE builds harmonics into a plain sine instead of cutting them out of a rich wave. The sine is phase-modulated (WARP), bent through a wavefolder (FOLD), and closed by a low-pass gate: one control that shuts the level and the filter together and lets go more slowly as it falls. That coupling is the bongo.",
            "BONGO starts at C3, DRIP at C5 with a short upward chirp, GONG at C3 with an inharmonic RATIO, FLARE at C2 with a harmonic one. On GONG and FLARE the top half of DECAY holds the gate open before it closes. WANDER nudges the fold and the decay from a seed, never the pitch, so a pad plays the same way every time.",
            "Six of these presets were played in the GLOW, edge and character revisions; the other thirty-four, and the engine's audition gate, have not been heard. Mark each clip KEEP, MEH or CUT. A CUT on a knob end means that end goes too far.",
        ),
        questions = listOf(
            "Which voice would you reach for first, and which would you never use?",
            "The knobs: which end of FOLD, WARP, GLOW, CLICK, DECAY, WANDER or RATIO goes too far, or not far enough? Are the defaults where you would start?",
            "The kit: does the bongo walk on A01 to A08 play as one hand drum up the scale, and do the DRIP, GONG and FLARE pads above it belong with it?",
        ),
        voiceNotes = mapOf(
            "BONGO" to "a gently folded sine through the gate, a struck hand drum, C3 at the bottom of TUNE",
            "DRIP" to "a short chirp up onto the note, closing fast, C5 at the bottom of TUNE",
            "GONG" to "phase modulation at an inharmonic ratio, held open by long DECAY, C3 at the bottom of TUNE",
            "FLARE" to "the most fold at a harmonic ratio, the fold blooming slower than the gate, C2 at the bottom of TUNE",
        ),
        knobNotes = mapOf(
            "FOLD" to ("no fold: a clean sine through the gate" to "the deepest fold: dense, bright harmonics"),
            "WARP" to ("no phase modulation" to "the deepest phase modulation: a growl, zapping into place at the strike"),
            "GLOW" to ("the fold and filter close with the gate: the tail darkens fast" to "the fold and filter stay open behind the level: a bright tail"),
            "CLICK" to ("no click" to "a burst of noise at the strike, the stick on the skin"),
            "DECAY" to ("the shortest note" to "the longest note; on GONG and FLARE held open before it rings out"),
            "WANDER" to ("no wander: fold, decay and WARP as set" to "fold, decay and WARP nudged by up to a quarter, and the fold drifting through the note"),
            "RATIO" to ("the lowest ratio: 1.4 on GONG, a plain 1 on FLARE" to "the highest ratio: 5.8 on GONG, 4 on FLARE"),
        ),
        kit = { SynthKits.tide() },
        kitNote = "the TIDE kit as the MPC gets it: WOOD BONGO up the minor pentatonic, then DRIP, GONG and FLARE presets",
    )

    @JvmStatic
    fun main(args: Array<String>) = RosterAudition.main(SPEC, args)
}
