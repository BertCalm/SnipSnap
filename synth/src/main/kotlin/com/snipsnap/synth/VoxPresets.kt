package com.snipsnap.synth

/**
 * VOX's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve presets per voice (seven voices, eighty-four total). The singers spread across
 * TUNE/VOWEL/BREATH/DECAY, and since round 1 SIZE (LOW and HIGH throats)
 * and GLIDE (SPEAK BOX's "wah", HIGH SIGH and LOW MOAN sighing toward
 * "ooh"). VOWEL walks the A→E→I→O→U morph `Vox.kt` defines; BREATH
 * crossfades the throat toward filtered noise. BEATBOX's twelve walk its
 * eight HITs: three kicks, four snares (two PSH), four hats, a rim.
 * THROAT's twelve cover its three uses: drones and whistled melodies to
 * hold, short growls and whistle blips to hit, and two yodels. WRAITH's
 * walk its six words as hits, chops in key and slowed pads. SWARM's are
 * crowd shouts to hit, chants and cheers, held stadium vowels, murmuring
 * and whispering rooms as pads, and stutters.
 * Authored from that DSP, not by ear, and checked by [VoxPresetsTest]'s
 * sanity/round-trip/spread suite.
 */
object VoxPresets {

    private fun p(voice: VoxVoice, name: String, vararg macros: Pair<String, Float>) =
        VoxPatch(name, voice, macros.toMap())

    fun forVoice(voice: VoxVoice): List<VoxPatch> = when (voice) {
        VoxVoice.CHOIR -> choirPresets
        VoxVoice.ROBOT -> robotPresets
        VoxVoice.GHOST -> ghostPresets
        VoxVoice.BEATBOX -> beatboxPresets
        VoxVoice.THROAT -> throatPresets
        VoxVoice.WRAITH -> wraithPresets
        VoxVoice.SWARM -> swarmPresets
    }

    fun all(): List<VoxPatch> = VoxVoice.entries.flatMap { forVoice(it) }

    private val choirPresets = listOf(
        p(VoxVoice.CHOIR, "AAH CHOIR", "TUNE" to 0.5f, "VOWEL" to 0.05f, "BREATH" to 0.15f, "DECAY" to 0.6f),
        p(VoxVoice.CHOIR, "OOH CHOIR", "TUNE" to 0.45f, "VOWEL" to 0.95f, "BREATH" to 0.2f, "DECAY" to 0.65f),
        p(VoxVoice.CHOIR, "EH CHOIR", "TUNE" to 0.5f, "VOWEL" to 0.3f, "BREATH" to 0.1f, "DECAY" to 0.55f, "GLIDE" to 0.35f),
        p(VoxVoice.CHOIR, "BREATHY CHOIR", "TUNE" to 0.4f, "VOWEL" to 0.2f, "BREATH" to 0.6f, "DECAY" to 0.7f),
        p(VoxVoice.CHOIR, "TIGHT CHOIR", "TUNE" to 0.55f, "VOWEL" to 0.1f, "BREATH" to 0.05f, "DECAY" to 0.35f),
        p(VoxVoice.CHOIR, "HIGH CHOIR", "TUNE" to 0.7f, "VOWEL" to 0.15f, "BREATH" to 0.2f, "DECAY" to 0.5f, "SIZE" to 0.35f),
        p(VoxVoice.CHOIR, "LOW CHOIR", "TUNE" to 0.25f, "VOWEL" to 0.1f, "BREATH" to 0.1f, "DECAY" to 0.75f, "SIZE" to 0.7f),
        p(VoxVoice.CHOIR, "ANGEL PAD", "TUNE" to 0.5f, "VOWEL" to 0.05f, "BREATH" to 0.3f, "DECAY" to 0.9f),
        p(VoxVoice.CHOIR, "WIND CHOIR", "TUNE" to 0.45f, "VOWEL" to 0.5f, "BREATH" to 0.7f, "DECAY" to 0.6f),
        p(VoxVoice.CHOIR, "SHORT AAH", "TUNE" to 0.5f, "VOWEL" to 0.05f, "BREATH" to 0.1f, "DECAY" to 0.25f),
        p(VoxVoice.CHOIR, "DEEP OOH", "TUNE" to 0.2f, "VOWEL" to 0.9f, "BREATH" to 0.15f, "DECAY" to 0.8f, "SIZE" to 0.75f),
        p(VoxVoice.CHOIR, "UNISON PAD", "TUNE" to 0.3f, "VOWEL" to 0.75f, "BREATH" to 0.35f, "DECAY" to 0.85f),
    )

    private val robotPresets = listOf(
        p(VoxVoice.ROBOT, "VOCODER", "TUNE" to 0.5f, "VOWEL" to 0.6f, "BREATH" to 0.05f, "DECAY" to 0.4f),
        p(VoxVoice.ROBOT, "SPEAK BOX", "TUNE" to 0.5f, "VOWEL" to 1f, "BREATH" to 0.1f, "DECAY" to 0.35f, "GLIDE" to 0f),
        p(VoxVoice.ROBOT, "ROBOT AAH", "TUNE" to 0.45f, "VOWEL" to 0.05f, "BREATH" to 0.05f, "DECAY" to 0.3f),
        p(VoxVoice.ROBOT, "ROBOT OOH", "TUNE" to 0.5f, "VOWEL" to 0.95f, "BREATH" to 0.05f, "DECAY" to 0.35f),
        p(VoxVoice.ROBOT, "METALLIC EE", "TUNE" to 0.55f, "VOWEL" to 0.35f, "BREATH" to 0.1f, "DECAY" to 0.25f),
        p(VoxVoice.ROBOT, "DROID VOICE", "TUNE" to 0.4f, "VOWEL" to 0.55f, "BREATH" to 0.15f, "DECAY" to 0.3f, "GLIDE" to 0.75f),
        p(VoxVoice.ROBOT, "TIGHT ROBOT", "TUNE" to 0.6f, "VOWEL" to 0.65f, "BREATH" to 0.02f, "DECAY" to 0.2f),
        p(VoxVoice.ROBOT, "LOW ROBOT", "TUNE" to 0.2f, "VOWEL" to 0.5f, "BREATH" to 0.1f, "DECAY" to 0.45f, "SIZE" to 0.85f),
        p(VoxVoice.ROBOT, "HIGH ROBOT", "TUNE" to 0.7f, "VOWEL" to 0.6f, "BREATH" to 0.05f, "DECAY" to 0.2f, "SIZE" to 0.15f),
        p(VoxVoice.ROBOT, "FLAT VOWEL", "TUNE" to 0.75f, "VOWEL" to 0f, "BREATH" to 0.15f, "DECAY" to 0.15f),
        p(VoxVoice.ROBOT, "BUZZY BOT", "TUNE" to 0.45f, "VOWEL" to 0.7f, "BREATH" to 0.2f, "DECAY" to 0.25f),
        p(VoxVoice.ROBOT, "SHORT BLEEP", "TUNE" to 0.55f, "VOWEL" to 0.8f, "BREATH" to 0.05f, "DECAY" to 0.1f),
    )

    private val ghostPresets = listOf(
        p(VoxVoice.GHOST, "WHISPER", "TUNE" to 0.45f, "VOWEL" to 0.85f, "BREATH" to 0.6f, "DECAY" to 0.7f),
        p(VoxVoice.GHOST, "HAUNTED OOH", "TUNE" to 0.4f, "VOWEL" to 0.6f, "BREATH" to 0.7f, "DECAY" to 0.9f, "GLIDE" to 0.7f),
        p(VoxVoice.GHOST, "AIRY AAH", "TUNE" to 0.5f, "VOWEL" to 0.1f, "BREATH" to 0.5f, "DECAY" to 0.6f),
        p(VoxVoice.GHOST, "BREATHY EE", "TUNE" to 0.55f, "VOWEL" to 0.35f, "BREATH" to 0.75f, "DECAY" to 0.55f),
        p(VoxVoice.GHOST, "FAINT VOICE", "TUNE" to 0.35f, "VOWEL" to 0.5f, "BREATH" to 0.85f, "DECAY" to 0.8f),
        p(VoxVoice.GHOST, "SPECTRAL", "TUNE" to 0.3f, "VOWEL" to 0.7f, "BREATH" to 0.65f, "DECAY" to 0.95f),
        p(VoxVoice.GHOST, "NOISY GHOST", "TUNE" to 0.5f, "VOWEL" to 0.6f, "BREATH" to 0.95f, "DECAY" to 0.5f),
        p(VoxVoice.GHOST, "LOW MOAN", "TUNE" to 0.2f, "VOWEL" to 0.8f, "BREATH" to 0.55f, "DECAY" to 0.85f, "SIZE" to 0.75f, "GLIDE" to 0.65f),
        p(VoxVoice.GHOST, "HIGH SIGH", "TUNE" to 0.65f, "VOWEL" to 0.4f, "BREATH" to 0.6f, "DECAY" to 0.4f, "GLIDE" to 0.8f),
        p(VoxVoice.GHOST, "DRY GHOST", "TUNE" to 0.45f, "VOWEL" to 0.55f, "BREATH" to 0.2f, "DECAY" to 0.5f),
        p(VoxVoice.GHOST, "SHORT GASP", "TUNE" to 0.5f, "VOWEL" to 0.3f, "BREATH" to 0.8f, "DECAY" to 0.2f),
        p(VoxVoice.GHOST, "DISTANT AAH", "TUNE" to 0.4f, "VOWEL" to 0.1f, "BREATH" to 0.5f, "DECAY" to 1f),
    )

    /** HIT's eight positions, KICK to RIM. */
    private fun hit(h: VoxBeatbox.Hit) = h.ordinal / (VoxBeatbox.Hit.entries.size - 1f)

    private val beatboxPresets = listOf(
        p(VoxVoice.BEATBOX, "BOOM KICK", "HIT" to hit(VoxBeatbox.Hit.KICK), "TUNE" to 0.5f, "DECAY" to 0.5f),
        p(VoxVoice.BEATBOX, "DEEP BOOM", "HIT" to hit(VoxBeatbox.Hit.KICK), "TUNE" to 0.25f, "DECAY" to 0.75f, "SIZE" to 0.75f),
        p(VoxVoice.BEATBOX, "TIGHT KICK", "HIT" to hit(VoxBeatbox.Hit.KICK), "TUNE" to 0.6f, "DECAY" to 0.2f),
        p(VoxVoice.BEATBOX, "PF SNARE", "HIT" to hit(VoxBeatbox.Hit.PF), "DECAY" to 0.5f),
        p(VoxVoice.BEATBOX, "PSH SNARE", "HIT" to hit(VoxBeatbox.Hit.PSH), "DECAY" to 0.55f),
        p(VoxVoice.BEATBOX, "SHORT PSH", "HIT" to hit(VoxBeatbox.Hit.PSH), "DECAY" to 0.15f, "SIZE" to 0.35f),
        p(VoxVoice.BEATBOX, "K SNARE", "HIT" to hit(VoxBeatbox.Hit.K), "DECAY" to 0.5f),
        p(VoxVoice.BEATBOX, "TS HAT", "HIT" to hit(VoxBeatbox.Hit.TS), "DECAY" to 0.35f),
        p(VoxVoice.BEATBOX, "TICK HAT", "HIT" to hit(VoxBeatbox.Hit.T), "DECAY" to 0.5f),
        p(VoxVoice.BEATBOX, "OPEN TSSS", "HIT" to hit(VoxBeatbox.Hit.TSS), "DECAY" to 0.6f),
        p(VoxVoice.BEATBOX, "LONG TSSS", "HIT" to hit(VoxBeatbox.Hit.TSS), "DECAY" to 0.95f, "SIZE" to 0.6f),
        p(VoxVoice.BEATBOX, "TONGUE RIM", "HIT" to hit(VoxBeatbox.Hit.RIM), "TUNE" to 0.5f),
    )

    /** WHISTLE's position for a harmonic, 5th to 13th. */
    private fun harmonic(h: Int) = (h - VoxThroat.LOWEST_WHISTLE) / (VoxThroat.HIGHEST_WHISTLE - VoxThroat.LOWEST_WHISTLE).toFloat()

    private val throatPresets = listOf(
        p(VoxVoice.THROAT, "STEPPE DRONE", "WHISTLE" to harmonic(8), "MELODY" to 0.5f, "DRONE" to 0.5f, "DECAY" to 0.8f),
        p(VoxVoice.THROAT, "WHISTLE SONG", "WHISTLE" to harmonic(8), "MELODY" to 1f, "DRONE" to 0.1f, "DECAY" to 0.9f),
        p(VoxVoice.THROAT, "SKY WHISTLE", "TUNE" to 0.6f, "WHISTLE" to harmonic(12), "MELODY" to 0f, "DRONE" to 0.2f, "DECAY" to 0.85f),
        p(VoxVoice.THROAT, "BELLY DRONE", "TUNE" to 0.35f, "WHISTLE" to harmonic(6), "MELODY" to 0.2f, "DRONE" to 1f, "DECAY" to 0.9f),
        p(VoxVoice.THROAT, "LOW GROWL", "TUNE" to 0.3f, "WHISTLE" to harmonic(8), "MELODY" to 0.35f, "DRONE" to 0.8f, "GROWL" to 1f, "DECAY" to 0.8f),
        p(VoxVoice.THROAT, "NIGHT CHANT", "TUNE" to 0.25f, "WHISTLE" to harmonic(9), "MELODY" to 0.5f, "DRONE" to 0.7f, "GROWL" to 0.5f, "DECAY" to 1f),
        p(VoxVoice.THROAT, "GROWL STAB", "TUNE" to 0f, "WHISTLE" to harmonic(8), "MELODY" to 0f, "DRONE" to 1f, "GROWL" to 1f, "DECAY" to 0.15f),
        p(VoxVoice.THROAT, "WHISTLE BLIP", "WHISTLE" to harmonic(12), "MELODY" to 0.35f, "DRONE" to 0f, "DECAY" to 0.1f),
        p(VoxVoice.THROAT, "RASP HIT", "TUNE" to 0.4f, "WHISTLE" to harmonic(10), "MELODY" to 0f, "DRONE" to 0.7f, "GROWL" to 0.6f, "DECAY" to 0.25f),
        p(VoxVoice.THROAT, "MOUNTAIN CALL", "TUNE" to 0.7f, "WHISTLE" to harmonic(6), "MELODY" to 0f, "DRONE" to 0.6f, "YODEL" to 0.5f, "DECAY" to 0.85f),
        p(VoxVoice.THROAT, "YODEL RUN", "TUNE" to 0.7f, "WHISTLE" to harmonic(6), "MELODY" to 0f, "DRONE" to 0.9f, "YODEL" to 1f, "DECAY" to 0.9f),
        p(VoxVoice.THROAT, "THROAT PAD", "TUNE" to 0.1f, "WHISTLE" to harmonic(8), "MELODY" to 0.8f, "DRONE" to 0.7f, "GROWL" to 0.25f, "DECAY" to 1f),
    )

    /** WORD's position for a word. */
    private fun word(w: VoxWraith.Word) = w.ordinal / (VoxWraith.Word.entries.size - 1f)

    private val wraithPresets = listOf(
        p(VoxVoice.WRAITH, "FREE SPEECH", "WORD" to word(VoxWraith.Word.WHY), "DECAY" to 0.45f, "BREATH" to 0.1f),
        p(VoxVoice.WRAITH, "WHY WHISPER", "WORD" to word(VoxWraith.Word.WHY), "DECAY" to 0.55f, "BREATH" to 0.6f),
        p(VoxVoice.WRAITH, "HELLO PAD", "TUNE" to 0.25f, "WORD" to word(VoxWraith.Word.HELLO), "DECAY" to 1f, "TUNED" to 1f, "ALIEN" to 0.3f, "BREATH" to 0.35f),
        p(VoxVoice.WRAITH, "SEANCE", "WORD" to word(VoxWraith.Word.HELLO), "DECAY" to 0.9f, "BREATH" to 0.8f),
        p(VoxVoice.WRAITH, "NO NO NO", "WORD" to word(VoxWraith.Word.NO), "DECAY" to 0.3f, "STUTTER" to 0.75f),
        p(VoxVoice.WRAITH, "STUTTER WHY", "WORD" to word(VoxWraith.Word.WHY), "DECAY" to 0.25f, "TUNED" to 1f, "STUTTER" to 1f),
        p(VoxVoice.WRAITH, "YEAH HIT", "TUNE" to 0.7f, "WORD" to word(VoxWraith.Word.YEAH), "DECAY" to 0.1f, "TUNED" to 1f),
        p(VoxVoice.WRAITH, "YOU CHOP", "WORD" to word(VoxWraith.Word.YOU), "DECAY" to 0.3f, "TUNED" to 1f),
        p(VoxVoice.WRAITH, "WOW ALIEN", "WORD" to word(VoxWraith.Word.WOW), "DECAY" to 0.5f, "ALIEN" to 1f),
        p(VoxVoice.WRAITH, "MACHINE DREAM", "WORD" to word(VoxWraith.Word.YEAH), "DECAY" to 0.8f, "TUNED" to 1f, "ALIEN" to 0.6f),
        p(VoxVoice.WRAITH, "LOW WAIL", "TUNE" to 0.1f, "WORD" to word(VoxWraith.Word.WOW), "DECAY" to 1f, "TUNED" to 0.5f),
        p(VoxVoice.WRAITH, "HALF TUNED", "WORD" to word(VoxWraith.Word.HELLO), "DECAY" to 0.6f, "TUNED" to 0.5f),
    )

    /** WORD's position for one of SWARM's words. */
    private fun say(w: VoxSwarm.Word) = w.ordinal / (VoxSwarm.Word.entries.size - 1f)

    private val swarmPresets = listOf(
        p(VoxVoice.SWARM, "HEY SHOUT", "WORD" to say(VoxSwarm.Word.HEY), "CROWD" to 1f, "LOOSE" to 0.12f, "EFFORT" to 1f, "DECAY" to 0.1f),
        p(VoxVoice.SWARM, "HO SHOUT", "WORD" to say(VoxSwarm.Word.HO), "CROWD" to 1f, "LOOSE" to 0.12f, "EFFORT" to 1f, "DECAY" to 0.1f),
        p(VoxVoice.SWARM, "HUH HIT", "WORD" to say(VoxSwarm.Word.HUH), "CROWD" to 1f, "LOOSE" to 0.1f, "EFFORT" to 0.9f, "DECAY" to 0.05f),
        p(VoxVoice.SWARM, "HEY CHANT", "WORD" to say(VoxSwarm.Word.HEY), "CROWD" to 0.75f, "LOOSE" to 0.1f, "EFFORT" to 0.75f, "DECAY" to 0.3f),
        p(VoxVoice.SWARM, "HO CHANT", "WORD" to say(VoxSwarm.Word.HO), "CROWD" to 0.75f, "LOOSE" to 0.1f, "EFFORT" to 0.75f, "DECAY" to 0.3f),
        p(VoxVoice.SWARM, "YEAH CHEER", "WORD" to say(VoxSwarm.Word.YEAH), "CROWD" to 1f, "LOOSE" to 0.35f, "EFFORT" to 0.9f, "DECAY" to 0.45f),
        p(VoxVoice.SWARM, "STADIUM OOH", "WORD" to say(VoxSwarm.Word.OOH), "CROWD" to 1f, "LOOSE" to 0.3f, "EFFORT" to 0.7f, "DECAY" to 0.9f),
        p(VoxVoice.SWARM, "CROWD AAH", "WORD" to say(VoxSwarm.Word.AAH), "CROWD" to 0.8f, "LOOSE" to 0.25f, "EFFORT" to 0.55f, "DECAY" to 0.85f),
        p(VoxVoice.SWARM, "MURMUR ROOM", "CROWD" to 1f, "LOOSE" to 1f, "EFFORT" to 0.5f, "DECAY" to 1f),
        p(VoxVoice.SWARM, "WHISPER ROOM", "CROWD" to 0.9f, "LOOSE" to 1f, "EFFORT" to 0f, "DECAY" to 1f),
        p(VoxVoice.SWARM, "SMALL TALK", "CROWD" to 0.2f, "LOOSE" to 1f, "EFFORT" to 0.45f, "DECAY" to 0.9f),
        p(VoxVoice.SWARM, "STUTTER HEY", "WORD" to say(VoxSwarm.Word.HEY), "CROWD" to 0.9f, "LOOSE" to 0f, "EFFORT" to 0.8f, "DECAY" to 0.25f, "STUTTER" to 0.75f),
    )
}
