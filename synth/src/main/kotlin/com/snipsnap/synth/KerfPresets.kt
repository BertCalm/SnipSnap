package com.snipsnap.synth

/**
 * Provisional references, kept out of [Presets] while KERF is a prototype (`PatchTypes.PROTOTYPES`).
 *
 * Each preset is one hit of its voice's pool ([Kerf.pool]), named for what it measures (2026-11-20, the prototype's
 * clean renders: length, spectral centroid, the share below 150 Hz). A preset is only a hit that KERF's own labeller
 * ([KerfFit.label]) files as its kind when rendered by the engine (2026-11-20). The rest stay on HIT: the loops' hats are
 * dark, so half the hat pool labels as SNARE, and kit A's loud open hats are cymbals over a kick or snare. The owner
 * heard all 27 on 2026-11-21 ("Sounds good").
 */
object KerfPresets {
    /** A preset on pool hit [index] of [voice], with the other macros at their defaults unless [over] moves them. */
    fun hit(name: String, voice: KerfVoice, index: Int, vararg over: Pair<String, Float>) =
        KerfPatch(name, voice, Kerf.defaults(voice) + ("HIT" to Kerf.hitFor(voice, index)) + over)

    fun forVoice(voice: KerfVoice): List<KerfPatch> = when (voice) {
        KerfVoice.KICK -> listOf(
            hit("Round", voice, 0), hit("Short thud", voice, 2), hit("Long boom", voice, 10),
            hit("Punch", voice, 12), hit("Knock", voice, 14), hit("Dusty", voice, 4),
        )
        KerfVoice.SNARE -> listOf(
            hit("Crack", voice, 4), hit("Fat", voice, 1), hit("Tight", voice, 7),
            hit("Room", voice, 9), hit("Rattle", voice, 13), hit("Bright", voice, 8),
        )
        KerfVoice.GHOST -> listOf(hit("Soft", voice, 0), hit("Tap", voice, 2), hit("Brush", voice, 5))
        KerfVoice.HAT -> listOf(hit("Tick", voice, 4), hit("Chick", voice, 1), hit("Wash", voice, 10), hit("Dark", voice, 9))
        KerfVoice.HAT_OPEN -> listOf(hit("Open", voice, 3), hit("Sizzle", voice, 7), hit("Long", voice, 5), hit("Splash", voice, 9))
        KerfVoice.PERC -> listOf(hit("Clack", voice, 0), hit("Stack", voice, 1), hit("Crash", voice, 3), hit("Rim", voice, 4))
    }

    fun all(): List<KerfPatch> = KerfVoice.entries.flatMap(::forVoice)
}
