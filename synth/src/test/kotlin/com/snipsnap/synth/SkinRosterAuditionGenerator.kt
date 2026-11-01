package com.snipsnap.synth

/**
 * The SKIN listening page through [RosterAudition]: the classic kit, then
 * each voice's default, every knob at its ends, TUNE at three tunings (SKIN
 * is drums: three head or plate tunings, not three notes), and its twenty
 * presets. Run via `./gradlew :synth:generateSkinRosterAudition`; it writes
 * testkit/skin-audition/ (gitignored) to publish as the listening artifact.
 */
object SkinRosterAuditionGenerator {

    internal val SPEC = RosterAudition.Spec(
        engine = SkinPatch.ENGINE,
        title = "Skin Audition",
        tag = "FIRST LISTEN · THE ACOUSTIC-STYLE KIT",
        guide = listOf(
            "SKIN is the second drum engine, and where THUMP builds its hits from oscillators, SKIN builds them from what a struck object does. KICK, SNARE, TOM and STICK are sums of decaying sine partials at inharmonic ratios, the higher ones dying first. The hats and RIDE are continuous noise through a bank of tuned resonant filters. SHAKER is noise through one wide band.",
            "Every voice has PUNCH, the shared transient shaper, which reshapes the attack at the same loudness. The other knobs each open a constant the voice used to hardcode, and each default renders that constant exactly. TUNE here is three tunings of the drum, from slack to tight, not three notes.",
            "Nobody has heard these presets yet: they were authored from the DSP and held inside the regions the drum classifier still files as their own class. Listen on speakers that reach the kick. Mark each clip KEEP, MEH or CUT. A CUT on a knob end means that end goes too far.",
        ),
        questions = listOf(
            "Which drums would you keep in a kit, and which sound like a synth pretending to be a drum?",
            "The knobs: which end of CLICK, DROP, SNAP, RATTLE, SIZZLE, RING, SWELL, GRAIN or BODY goes too far, or not far enough? Are the defaults where you would start?",
            "The kit: does it play as one room of drums from A01 to A16, and is the ride right to ring on over the closed hat rather than be choked by it?",
        ),
        voiceNotes = mapOf(
            "KICK" to "a struck membrane: a low partial and two inharmonic overtones, with a beater click",
            "SNARE" to "a two-partial head against high-passed noise wires, mixed by SNAP",
            "HAT_CLOSED" to "noise through six tuned resonant filters, short",
            "HAT_OPEN" to "the same six-filter bank, left to ring longer",
            "TOM" to "a shell partial and one overtone, pitched between the kick and the snare",
            "RIDE" to "noise through eight tuned filters ringing long, the wash; the kit files it as PERC so the closed hat does not choke it",
            "SHAKER" to "noise through one wide, non-resonant band: beads, no tone, and no TUNE",
            "STICK" to "a single high partial, the cross-stick click",
        ),
        knobNotes = mapOf(
            "DECAY" to ("the shortest ring" to "the longest ring"),
            "TONE" to (
                "darker: the kick and tom overtones down, the hat and ride modes packed close, the shaker's band at 3.5 kHz" to
                    "brighter: the kick and tom overtones up, the hat and ride modes spread wide, the shaker's band at 9 kHz"
                ),
            "PUNCH" to ("no transient shaping: the hit as the body makes it" to "the hardest attack boost, at the same loudness"),
            "CLICK" to (
                "no strike noise at all: no beater on the kick, no stick on the tom, no crack on the stick" to
                    "the hardest strike noise: a bright plastic beater on the kick, a stick on the tom, a wood crack on the stick"
                ),
            "DROP" to ("no pitch dip: the head starts on its note" to "the head starts sharp and settles: a fast thud on the kick, a heard glide on the tom"),
            "SNAP" to ("all head and no wires: a pitched thud the classifier files as PERC" to "all wires and no head"),
            "RATTLE" to ("the wires choked short with the head" to "the wires hissing long after the head"),
            "SIZZLE" to (
                "the snare's wires dark and papery; the ride with no rivets" to
                    "the snare's wires a thin 8 kHz hiss; the ride's rivets buzzing over the wash"
                ),
            "RING" to ("the hat's filters wide: one trashy band" to "the hat's filters narrow: ringing metal"),
            "SWELL" to ("a 1 ms attack: a tapped shaker" to "an 80 ms attack: a shaker thrown forward, the shh"),
            "GRAIN" to ("smooth noise" to "separate beads you can hear as seeds"),
            "BODY" to ("a click only" to "a low shell partial under the click: a knock on a drum"),
        ),
        kit = { SkinKits.classic() },
        kitNote = "the classic kit as the MPC gets it: the four-piece core, a second kick and snare, small percussion, three toms and the rides",
    )

    @JvmStatic
    fun main(args: Array<String>) = RosterAudition.main(SPEC, args)
}
