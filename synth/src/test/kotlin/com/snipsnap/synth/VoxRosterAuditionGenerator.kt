package com.snipsnap.synth

/**
 * The VOX listening page through [RosterAudition]: the cloud kit, then each
 * voice's default, every knob at its ends, TUNE at three notes, and its
 * twelve presets. Run via `./gradlew :synth:generateVoxRosterAudition`; it
 * writes testkit/vox-audition/ (gitignored) to publish as the listening
 * artifact.
 */
object VoxRosterAuditionGenerator {

    internal val SPEC = RosterAudition.Spec(
        engine = VoxPatch.ENGINE,
        title = "Vox Audition",
        tag = "FIRST LISTEN · THE VOICE ENGINE",
        guide = listOf(
            "VOX is the voice engine: eight voices, each a different way a mouth makes sound. CHOIR, ROBOT and GHOST sing a vowel through five formants; CHOIR is seven singers in stereo. BEATBOX is a mouth doing drums. THROAT is overtone singing, WRAITH is speech made of three sine tones, SWARM is a crowd in stereo, and SPEAK is a talking voice that counts one to eight.",
            "Many knobs snap: ONSET to a consonant, HIT to a drum, WHISTLE to a harmonic, WORD to a word. Their two ends are the first and last choice, not a sweep. BEATBOX's TUNE moves every hit by the same semitones, so its three TUNE clips are three tunings of a kick, not three notes.",
            "The voices and many of their knobs were built after rounds of prototype listening; the ninety-six presets were authored from the DSP and have not been heard as a set. Mark each clip KEEP, MEH or CUT. A CUT on a knob end means that end goes too far.",
        ),
        questions = listOf(
            "Which voice would you put on a pad first, and which would you never use?",
            "The knobs: which end of VOWEL, BREATH, SIZE, GLIDE, EFFORT, LOOSE, HUMAN or STUTTER goes too far, or not far enough? Are the defaults where you would start?",
            "BEATBOX files its hits as drums: would you program a beat from its kick, snares and hats next to a drum engine's?",
        ),
        voiceNotes = mapOf(
            "CHOIR" to "seven singers in sections, basses to sopranos, in stereo, A2 at the bottom of TUNE",
            "ROBOT" to "a square wave through the formants, machine-steady, E2 at the bottom of TUNE",
            "GHOST" to "a breathy, slow-vibrato singer on an \"ooh\", D3 at the bottom of TUNE",
            "BEATBOX" to "a mouth doing a drum kit; HIT picks the drum, and the app files the pads as drums (kick, snares, hats, rim), not notes",
            "THROAT" to "overtone singing: a low drone with one harmonic whistled above it, A1 at the bottom of TUNE",
            "WRAITH" to "three sine tones tracing a word's formants: ghostly speech, A1 at the bottom of TUNE",
            "SWARM" to "a crowd of two to sixteen mouths saying a word, in stereo, A1 at the bottom of TUNE",
            "SPEAK" to "a talking voice counting one to eight, speech chip to person, C2 at the bottom of TUNE",
        ),
        knobNotes = mapOf(
            "VOWEL" to ("\"aah\"" to "\"ooh\""),
            "BREATH" to ("all voice, almost no breath" to "mostly breath: the singers turn to air, WRAITH's tones to whispered bands of noise"),
            "DECAY" to ("the shortest sound: a hit, or a quick word" to "the longest: the singers hold before fading, a word slowed or a vowel stretched"),
            "SIZE" to ("a small throat: a child or a chipmunk" to "a giant's throat"),
            "GLIDE" to ("the vowel slides toward \"aah\" during the note" to "the vowel slides toward \"ooh\" during the note"),
            "ONSET" to ("no consonant: the note starts on the vowel" to "the note opens on an \"s\""),
            "HIT" to ("the kick" to "the rim, a tongue click off the palate"),
            "WHISTLE" to ("the whistle on the drone's 5th harmonic" to "the whistle on the 13th harmonic"),
            "MELODY" to ("the whistle held still" to "the whistle walking six harmonics up and back"),
            "DRONE" to ("the whistle loud over a thin drone" to "the drone's body loud under a quieter whistle"),
            "GROWL" to ("no growl" to "the full rasp an octave down"),
            "YODEL" to ("no yodel" to "the voice flipping chest to head and back"),
            "WORD" to ("the first word: why on WRAITH, hey on SWARM, one on SPEAK" to "the last word: yeah on WRAITH, aah on SWARM, eight on SPEAK"),
            "TUNED" to ("the tones glide like speech" to "the tones step onto the note's harmonics, in key"),
            "ALIEN" to ("the tones move as a mouth would" to "the tones move further than a mouth can, each with a beating twin"),
            "STUTTER" to ("the word said once" to "the most stutter: the opening grabbed again and again before the word"),
            "CROWD" to ("two mouths" to "sixteen mouths"),
            "LOOSE" to ("one word said together: a chant" to "everyone talking over each other: a murmuring room"),
            "EFFORT" to ("hushed" to "shouted"),
            "HUMAN" to ("an 80s speech chip: raw, steady and gritty" to "a person: the pitch moving through the word, the buzz rounder"),
        ),
        kit = { SynthKits.cloud() },
        kitNote = "the cloud kit as the MPC gets it: six VOX pads on A01 to A06, then GRAINS clouds made from other engines (A07 to A16)",
    )

    @JvmStatic
    fun main(args: Array<String>) = RosterAudition.main(SPEC, args)
}
