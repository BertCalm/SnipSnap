package com.snipsnap.synth

/**
 * Provisional references, kept out of [Presets] while SOOT is a prototype (`PatchTypes.PROTOTYPES`).
 * The first preset of every voice is the owner's round-0 pick (2026-11-12). The rest are the approved
 * layout's alternates (docs/SOOT.md), re-pointed where a pick moved the default:
 * - the kick's pick was the longer sub, so the layout's heavy kick (A05) goes longer still;
 * - the snare's pick was the hot cassette, so A12 becomes the round-0 coil snare at cassette .5;
 * - the hats' pick was the wide detune, so A13 and A14 become the round-0 narrow hats (9 cents).
 */
object SootPresets {
    private fun p(name: String, voice: SootVoice, vararg over: Pair<String, Float>) = SootPatch(name, voice, Soot.defaults(voice) + over)

    fun forVoice(voice: SootVoice): List<SootPatch> = when (voice) {
        SootVoice.KICK -> listOf(p("Sag", voice), p("Heavy", voice, "SUB" to .875f), p("Tight", voice, "SUB" to .15f), p("Dry", voice, "COIL" to 0f, "GRIME" to 0f))
        SootVoice.SNARE -> listOf(p("Hot", voice), p("Coil", voice, "EDGE" to .5f), p("Dry crack", voice, "COIL" to 0f), p("Bench", voice, "BENCH" to .9f))
        SootVoice.HAT_CLOSED, SootVoice.HAT_OPEN -> listOf(p("Wide", voice), p("Narrow", voice, "DETUNE" to .225f), p("Pure", voice, "DETUNE" to 0f))
        else -> listOf(p("Printed", voice), p("Dry", voice, "GRIME" to 0f, *(if ("COIL" in Soot.defaults(voice) && voice != SootVoice.COIL) arrayOf("COIL" to 0f) else emptyArray())))
    }

    fun all(): List<SootPatch> = SootVoice.entries.flatMap(::forVoice)
}
