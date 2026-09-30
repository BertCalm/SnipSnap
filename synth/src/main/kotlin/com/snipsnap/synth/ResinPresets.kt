package com.snipsnap.synth

/**
 * RESIN's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve per voice, thirty-six total, spread across STACK (thin → deep →
 * fat), CUTOFF, CREAM (the top sings) and CONTOUR (the wah, then the
 * snap), and two more on BRASS that land with a rack chain: WIDE STRINGS and
 * DARK STRINGS (thirty-eight in all, see [landingFor]). Authored from
 * `Resin.kt`'s DSP, not by ear, and checked by
 * [ResinPresetsTest]. Named for what they sound like — never for the
 * machine whose filter this is — per the naming rule.
 *
 * The two string-machine presets are the one place this roster is not
 * twelve: RESIN's BRASS voice is a wide saw stack through a ladder filter,
 * and the sound of a string machine is the ensemble chorus on top of that
 * ([StringMachine]). They are short, decaying pads, not a held section - the
 * voice's envelope always decays, in a little over a second at DECAY 0.85 (1.23 s).
 */
object ResinPresets {

    private fun p(voice: ResinVoice, name: String, vararg macros: Pair<String, Float>) =
        ResinPatch(name, voice, macros.toMap())

    fun forVoice(voice: ResinVoice): List<ResinPatch> = when (voice) {
        ResinVoice.BASS -> bassPresets
        ResinVoice.LEAD -> leadPresets
        ResinVoice.BRASS -> brassPresets
    }

    fun all(): List<ResinPatch> = ResinVoice.entries.flatMap { forVoice(it) }

    /**
     * The rack chain a sound made from [macros] lands with when SEND TO PAD
     * turns it into a pad, or null when it lands dry (every sound but the two
     * string machines, exactly as authored - see [StringMachine.landing]).
     */
    fun landingFor(voice: ResinVoice, macros: Map<String, Float>): FxChain? =
        if (voice == ResinVoice.BRASS) StringMachine.landing(brassPresets, brassLandings, macros) else null

    private val brassLandings: Map<String, FxChain> = mapOf(
        "WIDE STRINGS" to StringMachine.CLASSIC,
        "DARK STRINGS" to StringMachine.DARK,
    )

    private val bassPresets = listOf(
        p(ResinVoice.BASS, "DEEP CREAM", "TUNE" to 0.2f, "STACK" to 0.5f, "CUTOFF" to 0.3f, "CREAM" to 0.45f, "CONTOUR" to 0.35f, "DECAY" to 0.55f),
        p(ResinVoice.BASS, "SUB WOOD", "TUNE" to 0.1f, "STACK" to 0.55f, "CUTOFF" to 0.2f, "CREAM" to 0.2f, "CONTOUR" to 0.2f, "DECAY" to 0.6f),
        p(ResinVoice.BASS, "ROUND SUB", "TUNE" to 0.15f, "STACK" to 0.45f, "CUTOFF" to 0.25f, "CREAM" to 0.3f, "CONTOUR" to 0.25f, "DECAY" to 0.5f),
        p(ResinVoice.BASS, "WARM STACK", "TUNE" to 0.3f, "STACK" to 0.7f, "CUTOFF" to 0.35f, "CREAM" to 0.35f, "CONTOUR" to 0.4f, "DECAY" to 0.5f),
        p(ResinVoice.BASS, "FAT BOTTOM", "TUNE" to 0.25f, "STACK" to 0.95f, "CUTOFF" to 0.4f, "CREAM" to 0.3f, "CONTOUR" to 0.45f, "DECAY" to 0.45f),
        p(ResinVoice.BASS, "SLOW SWELL", "TUNE" to 0.2f, "STACK" to 0.6f, "CUTOFF" to 0.3f, "CREAM" to 0.5f, "CONTOUR" to 0.15f, "DECAY" to 0.8f),
        p(ResinVoice.BASS, "RUBBER BOOM", "TUNE" to 0.35f, "STACK" to 0.4f, "CUTOFF" to 0.45f, "CREAM" to 0.6f, "CONTOUR" to 0.7f, "DECAY" to 0.35f),
        p(ResinVoice.BASS, "HOLLOW LOW", "TUNE" to 0.3f, "STACK" to 0.85f, "CUTOFF" to 0.3f, "CREAM" to 0.55f, "CONTOUR" to 0.3f, "DECAY" to 0.5f),
        p(ResinVoice.BASS, "TIGHT KNOCK", "TUNE" to 0.4f, "STACK" to 0.3f, "CUTOFF" to 0.5f, "CREAM" to 0.4f, "CONTOUR" to 0.85f, "DECAY" to 0.2f),
        p(ResinVoice.BASS, "DARK DRONE", "TUNE" to 0.05f, "STACK" to 0.5f, "CUTOFF" to 0.15f, "CREAM" to 0.25f, "CONTOUR" to 0.1f, "DECAY" to 0.9f),
        p(ResinVoice.BASS, "SINGING LOW", "TUNE" to 0.3f, "STACK" to 0.2f, "CUTOFF" to 0.35f, "CREAM" to 0.95f, "CONTOUR" to 0.5f, "DECAY" to 0.5f),
        p(ResinVoice.BASS, "THICK BUTTER", "TUNE" to 0.25f, "STACK" to 0.8f, "CUTOFF" to 0.3f, "CREAM" to 0.65f, "CONTOUR" to 0.4f, "DECAY" to 0.55f),
    )

    private val leadPresets = listOf(
        p(ResinVoice.LEAD, "SOLO CREAM", "TUNE" to 0.5f, "STACK" to 0.8f, "CUTOFF" to 0.55f, "CREAM" to 0.55f, "CONTOUR" to 0.5f, "DECAY" to 0.45f),
        p(ResinVoice.LEAD, "SCREAM LEAD", "TUNE" to 0.6f, "STACK" to 0.9f, "CUTOFF" to 0.7f, "CREAM" to 0.9f, "CONTOUR" to 0.6f, "DECAY" to 0.4f),
        p(ResinVoice.LEAD, "WOOD FLUTE", "TUNE" to 0.7f, "STACK" to 0.1f, "CUTOFF" to 0.35f, "CREAM" to 0.3f, "CONTOUR" to 0.2f, "DECAY" to 0.5f),
        p(ResinVoice.LEAD, "THIN SAW", "TUNE" to 0.5f, "STACK" to 0f, "CUTOFF" to 0.8f, "CREAM" to 0.2f, "CONTOUR" to 0.3f, "DECAY" to 0.4f),
        p(ResinVoice.LEAD, "FAT LEAD", "TUNE" to 0.4f, "STACK" to 1f, "CUTOFF" to 0.5f, "CREAM" to 0.45f, "CONTOUR" to 0.45f, "DECAY" to 0.5f),
        p(ResinVoice.LEAD, "SQUARE SING", "TUNE" to 0.55f, "STACK" to 0.75f, "CUTOFF" to 0.45f, "CREAM" to 0.95f, "CONTOUR" to 0.35f, "DECAY" to 0.5f),
        p(ResinVoice.LEAD, "LASER ZAP", "TUNE" to 0.8f, "STACK" to 0.3f, "CUTOFF" to 0.9f, "CREAM" to 1f, "CONTOUR" to 1f, "DECAY" to 0.15f),
        p(ResinVoice.LEAD, "SOFT WHISTLE", "TUNE" to 0.75f, "STACK" to 0.05f, "CUTOFF" to 0.3f, "CREAM" to 0.85f, "CONTOUR" to 0.1f, "DECAY" to 0.6f),
        p(ResinVoice.LEAD, "BRIGHT STACK", "TUNE" to 0.5f, "STACK" to 0.85f, "CUTOFF" to 0.85f, "CREAM" to 0.35f, "CONTOUR" to 0.55f, "DECAY" to 0.4f),
        p(ResinVoice.LEAD, "SLOW OPENER", "TUNE" to 0.45f, "STACK" to 0.7f, "CUTOFF" to 0.25f, "CREAM" to 0.5f, "CONTOUR" to 0.05f, "DECAY" to 0.85f),
        p(ResinVoice.LEAD, "NASAL LEAD", "TUNE" to 0.55f, "STACK" to 0.6f, "CUTOFF" to 0.4f, "CREAM" to 0.7f, "CONTOUR" to 0.8f, "DECAY" to 0.35f),
        p(ResinVoice.LEAD, "GLASS SING", "TUNE" to 0.65f, "STACK" to 0.35f, "CUTOFF" to 0.6f, "CREAM" to 0.9f, "CONTOUR" to 0.65f, "DECAY" to 0.45f),
    )

    private val brassPresets = listOf(
        p(ResinVoice.BRASS, "BIG BRASS", "TUNE" to 0.4f, "STACK" to 0.7f, "CUTOFF" to 0.4f, "CREAM" to 0.3f, "CONTOUR" to 0.75f, "DECAY" to 0.5f),
        p(ResinVoice.BRASS, "SOFT HORN", "TUNE" to 0.35f, "STACK" to 0.5f, "CUTOFF" to 0.3f, "CREAM" to 0.2f, "CONTOUR" to 0.45f, "DECAY" to 0.55f),
        p(ResinVoice.BRASS, "WAH STAB", "TUNE" to 0.45f, "STACK" to 0.65f, "CUTOFF" to 0.35f, "CREAM" to 0.6f, "CONTOUR" to 0.95f, "DECAY" to 0.3f),
        p(ResinVoice.BRASS, "THIN REED", "TUNE" to 0.6f, "STACK" to 0.15f, "CUTOFF" to 0.5f, "CREAM" to 0.5f, "CONTOUR" to 0.5f, "DECAY" to 0.4f),
        p(ResinVoice.BRASS, "DARK HORN", "TUNE" to 0.3f, "STACK" to 0.6f, "CUTOFF" to 0.2f, "CREAM" to 0.25f, "CONTOUR" to 0.4f, "DECAY" to 0.6f),
        p(ResinVoice.BRASS, "FANFARE", "TUNE" to 0.55f, "STACK" to 0.8f, "CUTOFF" to 0.55f, "CREAM" to 0.35f, "CONTOUR" to 0.7f, "DECAY" to 0.4f),
        p(ResinVoice.BRASS, "PUNCHY STAB", "TUNE" to 0.4f, "STACK" to 0.75f, "CUTOFF" to 0.45f, "CREAM" to 0.4f, "CONTOUR" to 0.9f, "DECAY" to 0.2f),
        p(ResinVoice.BRASS, "CREAM HORN", "TUNE" to 0.4f, "STACK" to 0.55f, "CUTOFF" to 0.35f, "CREAM" to 0.7f, "CONTOUR" to 0.6f, "DECAY" to 0.5f),
        p(ResinVoice.BRASS, "WIDE SECTION", "TUNE" to 0.35f, "STACK" to 1f, "CUTOFF" to 0.4f, "CREAM" to 0.3f, "CONTOUR" to 0.55f, "DECAY" to 0.55f),
        p(ResinVoice.BRASS, "QUICK BLAT", "TUNE" to 0.5f, "STACK" to 0.6f, "CUTOFF" to 0.5f, "CREAM" to 0.45f, "CONTOUR" to 1f, "DECAY" to 0.15f),
        p(ResinVoice.BRASS, "MELLOW WAH", "TUNE" to 0.3f, "STACK" to 0.4f, "CUTOFF" to 0.25f, "CREAM" to 0.55f, "CONTOUR" to 0.65f, "DECAY" to 0.65f),
        p(ResinVoice.BRASS, "SINGING HORN", "TUNE" to 0.45f, "STACK" to 0.45f, "CUTOFF" to 0.35f, "CREAM" to 0.95f, "CONTOUR" to 0.7f, "DECAY" to 0.45f),
        // The string machine's two: the full stack with the wah and the snap out (CONTOUR near 0), left to ring; the rack's ENSEMBLE does the rest.
        p(ResinVoice.BRASS, "WIDE STRINGS", "TUNE" to 0.5f, "STACK" to 0.9f, "CUTOFF" to 0.7f, "CREAM" to 0.1f, "CONTOUR" to 0.05f, "DECAY" to 0.85f),
        p(ResinVoice.BRASS, "DARK STRINGS", "TUNE" to 0.3f, "STACK" to 0.8f, "CUTOFF" to 0.4f, "CREAM" to 0.15f, "CONTOUR" to 0.1f, "DECAY" to 0.85f),
    )
}
