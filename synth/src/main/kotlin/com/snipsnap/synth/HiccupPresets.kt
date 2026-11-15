package com.snipsnap.synth

/**
 * The settings are authored from the design spec's macro contract, not picked by ear, and filed in the
 * factory bank through [Presets]. The owner heard them one by one on 2026-11-15 ("Everything sounds good").
 * Twenty-one hits, at least two on every voice. Each is [Hiccup.defaults] with the six macros set, struck
 * full at take 0, the plain render the picker also plays. Names are mixed case, as SOOT's are.
 */
object HiccupPresets {
    /** Paste complete UserPresets roster lines here; the authored hits stay fixed. */
    private val promoted: List<HiccupPatch> = listOf()

    /** The take every preset is filed at: take 0, the plain render. */
    const val TAKE = 0

    private fun p(
        voice: HiccupVoice, name: String,
        tune: Float, decay: Float, toy: Float, fault: Float, chop: Float, crush: Float,
    ) = HiccupPatch(
        name, voice,
        Hiccup.defaults(voice) + mapOf("TUNE" to tune, "DECAY" to decay, "TOY" to toy, "FAULT" to fault, "CHOP" to chop, "CRUSH" to crush),
        velocity = 1f, take = TAKE,
    )

    private val authored: List<HiccupPatch> = listOf(
        p(HiccupVoice.KICK, "Clean Thump", .45f, .50f, .10f, .00f, .50f, .00f),
        p(HiccupVoice.KICK, "Lurch", .40f, .60f, .15f, .35f, .30f, .20f),
        p(HiccupVoice.KICK, "Buzz Kick", .50f, .50f, .30f, .80f, .85f, .50f),
        p(HiccupVoice.KICK, "Candy Donk", .80f, .30f, .80f, .20f, .60f, .25f),
        p(HiccupVoice.SNARE, "Dry Crack", .50f, .45f, .20f, .00f, .50f, .05f),
        p(HiccupVoice.SNARE, "Trip", .45f, .55f, .25f, .45f, .40f, .35f),
        p(HiccupVoice.SNARE, "Rewind", .55f, .60f, .30f, .90f, .20f, .50f),
        p(HiccupVoice.SNARE, "Sparkle", .90f, .20f, 1.0f, .85f, .95f, .50f),
        p(HiccupVoice.CLAP, "Stagger", .45f, .55f, .20f, .40f, .35f, .30f),
        p(HiccupVoice.CLAP, "Glitter", .90f, .20f, .90f, .90f, .95f, .60f),
        p(HiccupVoice.HAT_CLOSED, "Tick", .50f, .15f, .30f, .10f, .70f, .25f),
        p(HiccupVoice.HAT_CLOSED, "Dust", .40f, .30f, .50f, .70f, .90f, .70f),
        p(HiccupVoice.HAT_OPEN, "Skid", .50f, .65f, .30f, .60f, .55f, .40f),
        p(HiccupVoice.HAT_OPEN, "Static", .30f, .80f, .90f, .50f, .40f, .95f),
        p(HiccupVoice.TOM, "Sag", .35f, .60f, .20f, .45f, .30f, .30f),
        p(HiccupVoice.TOM, "Boing", .85f, .35f, .90f, .60f, .80f, .30f),
        p(HiccupVoice.BLIP, "Coin", .80f, .30f, 1.0f, .50f, .90f, .30f),
        p(HiccupVoice.BLIP, "Morse", .70f, .20f, .70f, .90f, .95f, .50f),
        p(HiccupVoice.BLIP, "Laser", .60f, .45f, .90f, .90f, .80f, .40f),
        p(HiccupVoice.SQUEAK, "Squeeze Toy", .45f, .45f, .40f, .40f, .60f, .25f),
        p(HiccupVoice.SQUEAK, "Giggle", .80f, .20f, .70f, .95f, .85f, .40f),
    )

    fun forVoice(voice: HiccupVoice): List<HiccupPatch> = all().filter { it.voice == voice }

    fun all(): List<HiccupPatch> = authored + promoted
}
