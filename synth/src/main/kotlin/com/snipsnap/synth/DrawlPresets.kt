package com.snipsnap.synth

/**
 * DRAWL's factory bank: three presets per voice, each a render the owner heard from the native engine
 * and kept.
 * - The voice's default note: kept in round 1 (21 Nov 2026, "Sound great, proceed").
 * - Its long slide with full vibrato (SLIDE 1, WAVER 1): the clip the Python rounds 0c and 0d were
 *   tuned on, kept natively in round 1.
 * - Its strum ([STRUMS]): the chord picked low to high, kept in round 2 (21 Nov 2026, "All sound good").
 *
 * The first two are pinned by [DrawlNativePinsTest] as `mid` and `slide1-waver1`, the third as
 * `strum-down`. Names fit the 14 characters a pad and a user preset show.
 */
object DrawlPresets {

    /**
     * Each voice's heard strum: the chord (semitones over the played note) and the sweep from the
     * first string to the last. These are the chords the round-0 prototype page and the round-2 native page played:
     * an open A on the resonator, a power chord on the electric slide, a minor and a major chord on the
     * steels, a full six-string open D on the flat-top and a minor chord on the nylon.
     */
    val STRUMS: Map<DrawlVoice, DrawlStrum.Articulation> = mapOf(
        DrawlVoice.CONE to DrawlStrum.Articulation(listOf(0, 7, 12, 16), .06),
        DrawlVoice.BOTTLE to DrawlStrum.Articulation(listOf(0, 7, 12), .06),
        DrawlVoice.LAP to DrawlStrum.Articulation(listOf(0, 3, 7, 12), .06),
        DrawlVoice.STEEL to DrawlStrum.Articulation(listOf(0, 4, 7, 12), .06),
        DrawlVoice.FLATTOP to DrawlStrum.Articulation(listOf(0, 4, 7, 12, 16, 19), .09),
        DrawlVoice.GUT to DrawlStrum.Articulation(listOf(0, 3, 7, 12), .06),
    )

    private val longSlide = mapOf("SLIDE" to 1f, "WAVER" to 1f)

    private fun voice(voice: DrawlVoice, note: String, slide: String, strum: String): List<DrawlPatch> {
        val defaults = Drawl.defaults(voice)
        return listOf(
            DrawlPatch(note, voice, defaults),
            DrawlPatch(slide, voice, defaults + longSlide),
            DrawlPatch(strum, voice, defaults, strum = STRUMS.getValue(voice)),
        )
    }

    private val bank: Map<DrawlVoice, List<DrawlPatch>> = mapOf(
        DrawlVoice.CONE to voice(DrawlVoice.CONE, "RESO TWANG", "RESO SLIDE", "RESO STRUM"),
        DrawlVoice.BOTTLE to voice(DrawlVoice.BOTTLE, "BOTTLENECK", "BOTTLE SLIDE", "BOTTLE STRUM"),
        DrawlVoice.LAP to voice(DrawlVoice.LAP, "LAP STEEL", "LAP SLIDE", "LAP STRUM"),
        DrawlVoice.STEEL to voice(DrawlVoice.STEEL, "PEDAL STEEL", "PEDAL SLIDE", "PEDAL STRUM"),
        DrawlVoice.FLATTOP to voice(DrawlVoice.FLATTOP, "FLAT-TOP", "FLAT-TOP SLIDE", "FLAT-TOP STRUM"),
        DrawlVoice.GUT to voice(DrawlVoice.GUT, "WESTERN NYLON", "NYLON SLIDE", "NYLON STRUM"),
    )

    /** Paste complete UserPresets roster lines here; the heard bank stays fixed. */
    private val promoted: List<DrawlPatch> = listOf()

    fun forVoice(voice: DrawlVoice): List<DrawlPatch> = bank.getValue(voice) + promoted.filter { it.voice == voice }

    fun all(): List<DrawlPatch> = DrawlVoice.entries.flatMap { forVoice(it) }

    /** The bank's first preset: the resonator's default note. */
    fun default(): DrawlPatch = all().first()

    /** [voice]'s preset named [name]. */
    fun named(voice: DrawlVoice, name: String): DrawlPatch = forVoice(voice).single { it.name == name }
}
