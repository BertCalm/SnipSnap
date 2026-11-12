package com.snipsnap.synth

/**
 * Provisional references, kept out of [Presets] while SOOT is a prototype (`PatchTypes.PROTOTYPES`).
 * The first preset of every voice is the owner's round-0 pick (2026-11-12). The rest are the approved
 * layout's alternates (docs/SOOT.md), re-pointed where a pick moved the default:
 * - the kick's pick was the longer sub, so the layout's heavy kick (A05) goes longer still;
 * - the snare's pick was the hot cassette, so A12 became the round-0 coil snare at cassette .5, and
 *   then (heard as the same drum as A02) a low snare drowned in the coil;
 * - the hats' pick was the wide detune, so A13 and A14 become the round-0 narrow hats (9 cents).
 */
object SootPresets {
    private fun p(name: String, voice: SootVoice, vararg over: Pair<String, Float>) = SootPatch(name, voice, Soot.defaults(voice) + over)

    fun forVoice(voice: SootVoice): List<SootPatch> = when (voice) {
        SootVoice.KICK -> listOf(p("Sag", voice), p("Heavy", voice, "SUB" to .875f), p("Tight", voice, "SUB" to .15f), p("Dry", voice, "COIL" to 0f, "GRIME" to 0f))
        // The bench and coil snares were heard in the kit as the same drum as the hot one (2026-11-12): at
        // BENCH .9 and EDGE .5 they measured 2.0 and 0.4 dB from it (log-spectral). They now differ in
        // mechanism: the bench a clean, high metal slap; the coil a low snare drowned in the spring.
        SootVoice.SNARE -> listOf(p("Hot", voice),
            p("Coil", voice, "COIL" to 1f, "TUNE" to 0f, "EDGE" to 0f, "BENCH" to 0f, "GRIME" to .9f),
            p("Dry crack", voice, "COIL" to 0f),
            p("Bench", voice, "BENCH" to 1f, "COIL" to 0f, "TUNE" to 1f, "EDGE" to 0f, "GRIME" to 0f))
        SootVoice.HAT_CLOSED, SootVoice.HAT_OPEN -> listOf(p("Wide", voice), p("Narrow", voice, "DETUNE" to .225f), p("Pure", voice, "DETUNE" to 0f))
        else -> listOf(p("Printed", voice), p("Dry", voice, "GRIME" to 0f, *(if ("COIL" in Soot.defaults(voice) && voice != SootVoice.COIL) arrayOf("COIL" to 0f) else emptyArray())))
    }

    fun all(): List<SootPatch> = SootVoice.entries.flatMap(::forVoice)
}
