package com.snipsnap.synth

/**
 * DRAWL's provisional recipes: for each voice, the two settings the owner heard and kept in the
 * Python prototype (rounds 0 to 0d, 21 Nov 2026): the voice's default note, and its long slide
 * with full vibrato (SLIDE 1, WAVER 1), the clip the round-0c and 0d notes were about. They are
 * kept recipes of the prototype, not chosen one by one on the Kotlin render, so they stay outside
 * the factory roster ([Presets]) until the owner hears the native engine: recipes written without
 * listening are disposable.
 */
object DrawlPresets {

    private fun kept(voice: DrawlVoice, name: String, macros: Map<String, Float> = emptyMap()) =
        DrawlPatch(name, voice, Drawl.defaults(voice) + macros)

    private val longSlide = mapOf("SLIDE" to 1f, "WAVER" to 1f)

    private val recipes: Map<DrawlVoice, List<DrawlPatch>> = mapOf(
        DrawlVoice.CONE to listOf(kept(DrawlVoice.CONE, "RESONATOR TWANG"), kept(DrawlVoice.CONE, "RESONATOR LONG SLIDE", longSlide)),
        DrawlVoice.BOTTLE to listOf(kept(DrawlVoice.BOTTLE, "BOTTLENECK WHINE"), kept(DrawlVoice.BOTTLE, "BOTTLENECK LONG SLIDE", longSlide)),
        DrawlVoice.LAP to listOf(kept(DrawlVoice.LAP, "LAP STEEL"), kept(DrawlVoice.LAP, "LAP STEEL LONG SLIDE", longSlide)),
        DrawlVoice.STEEL to listOf(kept(DrawlVoice.STEEL, "PEDAL STEEL SWELL"), kept(DrawlVoice.STEEL, "PEDAL STEEL WIDE", longSlide)),
        DrawlVoice.FLATTOP to listOf(kept(DrawlVoice.FLATTOP, "COUNTRY FLAT-TOP"), kept(DrawlVoice.FLATTOP, "FLAT-TOP FRET SLIDE", longSlide)),
        DrawlVoice.GUT to listOf(kept(DrawlVoice.GUT, "WESTERN NYLON"), kept(DrawlVoice.GUT, "NYLON SLIDE AND SHAKE", longSlide)),
    )

    fun forVoice(voice: DrawlVoice): List<DrawlPatch> = recipes.getValue(voice)

    fun all(): List<DrawlPatch> = DrawlVoice.entries.flatMap { forVoice(it) }
}
