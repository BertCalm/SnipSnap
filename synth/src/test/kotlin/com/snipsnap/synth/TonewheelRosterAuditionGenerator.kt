package com.snipsnap.synth

/**
 * The TONEWHEEL listening page through [RosterAudition]: each voice's
 * default, every knob at its ends (all eight drawbars among them), TUNE at
 * three notes, and its factory presets. No kit section yet:
 * `SynthKits.tonewheel()` lands separately, and [SPEC]'s `kit` stays null
 * until it does. Run via `./gradlew :synth:generateTonewheelRosterAudition`;
 * it writes testkit/tonewheel-audition/ (gitignored) to publish as the
 * listening artifact.
 */
object TonewheelRosterAuditionGenerator {

    internal val SPEC = RosterAudition.Spec(
        engine = TonewheelPatch.ENGINE,
        title = "Tonewheel Audition",
        tag = "FIRST LISTEN · THE DRAWBAR ORGAN",
        guide = listOf(
            "TONEWHEEL is the drawbar organ. Eight sine bars at fixed harmonics, a key click, a percussion ping on the third harmonic, then a scanner chorus, DIRT into a speaker-shaped low-pass, and a rotary speaker. Every clip is one organ stab: the key held about half a second, then let go.",
            "The three voices are three registrations of the same eight bars, all from A2 at the bottom of TUNE. FULL has every bar out, SOUL is the low bars with the fundamental leading, STAB is top-heavy and starts with more PERC. BAR1 is the bar an octave under the note; BAR8 is three octaves over it.",
            "Nobody has heard these presets yet: they were authored from the DSP. The bar clips move one drawbar at a time, so some pairs will differ only a little; that is the honest size of one bar. Mark each clip KEEP, MEH or CUT. A CUT on a knob end means that end goes too far.",
        ),
        questions = listOf(
            "Which registration would you sample first, and which would you never use?",
            "The knobs: which end of PERC, WARBLE or DIRT goes too far, or not far enough? Are the defaults where you would start?",
            "The bars: does any single drawbar change the stab enough to deserve its own knob, or should the eight become fewer?",
        ),
        voiceNotes = mapOf(
            "FULL" to "every drawbar out, the most DIRT, A2 at the bottom of TUNE",
            "SOUL" to "the low bars with the fundamental leading and a little fifth, A2 at the bottom of TUNE",
            "STAB" to "bright, top-heavy bars with more PERC to cut, A2 at the bottom of TUNE",
        ),
        knobNotes = mapOf(
            "PERC" to ("no percussion ping" to "the loudest ping on the third harmonic, dying while the bars hold"),
            "WARBLE" to ("the rotary speaker braked: only the scanner chorus moves" to "the rotor at full speed: a fast tremolo and doppler"),
            "DIRT" to ("clean bars into the speaker" to "the bars pushed hardest into the drive: grit"),
            "BAR1" to ("the sub-octave bar in: no 16' weight" to "the sub-octave bar all the way out"),
            "BAR2" to ("the fundamental bar in: the note itself goes thin" to "the fundamental bar all the way out"),
            "BAR3" to ("the fifth above the note in" to "the fifth above the note all the way out"),
            "BAR4" to ("the octave bar in" to "the octave bar all the way out"),
            "BAR5" to ("the octave-and-fifth bar in" to "the octave-and-fifth bar all the way out"),
            "BAR6" to ("the two-octave bar in" to "the two-octave bar all the way out"),
            "BAR7" to ("the bar two octaves and a fifth up in" to "the bar two octaves and a fifth up all the way out"),
            "BAR8" to ("the top bar, three octaves up, in" to "the top bar all the way out: the most sparkle"),
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) = RosterAudition.main(SPEC, args)
}
