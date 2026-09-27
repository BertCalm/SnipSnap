package com.snipsnap.synth

/**
 * TINES' factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md),
 * the second engine after THUMP.
 *
 * Twelve presets per voice (then four BOLD, below), spread across each
 * voice's own macro shape rather than clustered near its `defaults()`. TINES
 * has no drum-class ambiguity to guard against the way THUMP's HAT/TOM
 * boundaries did — every voice here is a fixed FM shape, not something a
 * classifier re-judges — so these were authored by reasoning from the DSP
 * in `Tines.kt` (carrier range, ratio table, index/decay mapping) and
 * verified by [TinesPresetsTest]'s sanity/round-trip/spread checks, not by
 * ear: this session has no audio playback. Names never reference a real
 * drum machine or model number, same rule THUMP's presets follow.
 *
 * The last four of every voice - BOLD - came with the sound-design macros
 * (BITE on every voice; CLANG, RATIO on CHIME/BLOCK/ZAP/TOY, BEND, SHAPE,
 * TICK - see [Tines.macrosFor]), each pushing them toward an end the
 * first twelve, which predate them, never reach. Reasoned from the DSP
 * and checked by the same tests, never heard, like the rest.
 */
object TinesPresets {

    private fun p(voice: TinesVoice, name: String, vararg macros: Pair<String, Float>) =
        TinesPatch(name, voice, macros.toMap())

    fun forVoice(voice: TinesVoice): List<TinesPatch> = when (voice) {
        TinesVoice.BELL -> bellPresets
        TinesVoice.CHIME -> chimePresets
        TinesVoice.BLOCK -> blockPresets
        TinesVoice.ZAP -> zapPresets
        TinesVoice.TOY -> toyPresets
        TinesVoice.KALIMBA -> kalimbaPresets
    }

    fun all(): List<TinesPatch> = TinesVoice.entries.flatMap { forVoice(it) }

    private val bellPresets = listOf(
        p(TinesVoice.BELL, "VESPER", "TUNE" to 0.15f, "RATIO" to 0.05f, "BRIGHT" to 0.25f, "DECAY" to 0.75f),
        p(TinesVoice.BELL, "MATIN", "TUNE" to 0.15f, "RATIO" to 0.05f, "BRIGHT" to 0.55f, "DECAY" to 0.35f),
        p(TinesVoice.BELL, "CHAPEL", "TUNE" to 0.35f, "RATIO" to 0.2f, "BRIGHT" to 0.4f, "DECAY" to 0.65f),
        p(TinesVoice.BELL, "ABBEY", "TUNE" to 0.35f, "RATIO" to 0.35f, "BRIGHT" to 0.65f, "DECAY" to 0.5f),
        p(TinesVoice.BELL, "HANDBELL", "TUNE" to 0.5f, "RATIO" to 0.5f, "BRIGHT" to 0.5f, "DECAY" to 0.45f),
        p(TinesVoice.BELL, "CARILLON", "TUNE" to 0.55f, "RATIO" to 0.65f, "BRIGHT" to 0.7f, "DECAY" to 0.55f),
        p(TinesVoice.BELL, "CRYSTAL", "TUNE" to 0.7f, "RATIO" to 0.8f, "BRIGHT" to 0.85f, "DECAY" to 0.4f),
        p(TinesVoice.BELL, "GLACIER", "TUNE" to 0.75f, "RATIO" to 0.95f, "BRIGHT" to 0.9f, "DECAY" to 0.6f),
        p(TinesVoice.BELL, "PENNY", "TUNE" to 0.85f, "RATIO" to 0.35f, "BRIGHT" to 0.3f, "DECAY" to 0.2f),
        p(TinesVoice.BELL, "THIMBLE", "TUNE" to 0.9f, "RATIO" to 0.5f, "BRIGHT" to 0.45f, "DECAY" to 0.15f),
        p(TinesVoice.BELL, "FOGHORN", "TUNE" to 0.05f, "RATIO" to 0.05f, "BRIGHT" to 0.15f, "DECAY" to 0.95f),
        p(TinesVoice.BELL, "SLEIGH", "TUNE" to 0.6f, "RATIO" to 0.8f, "BRIGHT" to 0.75f, "DECAY" to 0.25f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(TinesVoice.BELL, "GONG", "TUNE" to 0.10f, "RATIO" to 0.20f, "BRIGHT" to 0.60f, "DECAY" to 0.95f, "BITE" to 0.15f, "CLANG" to 1.00f),
        p(TinesVoice.BELL, "CHURCH", "TUNE" to 0.30f, "RATIO" to 0.45f, "BRIGHT" to 0.70f, "DECAY" to 0.85f, "BITE" to 0.35f, "CLANG" to 0.85f),
        p(TinesVoice.BELL, "GLASS FIFTH", "TUNE" to 0.65f, "RATIO" to 0.10f, "BRIGHT" to 0.35f, "DECAY" to 0.60f, "BITE" to 0.80f, "CLANG" to 0.00f),
        p(TinesVoice.BELL, "GROWL BELL", "TUNE" to 0.40f, "RATIO" to 0.90f, "BRIGHT" to 0.90f, "DECAY" to 0.50f, "BITE" to 0.00f, "CLANG" to 0.70f),
    )

    private val chimePresets = listOf(
        p(TinesVoice.CHIME, "DEWDROP", "TUNE" to 0.2f, "SHIMMER" to 0.15f, "BRIGHT" to 0.35f, "DECAY" to 0.6f),
        p(TinesVoice.CHIME, "RAINDROP", "TUNE" to 0.25f, "SHIMMER" to 0.3f, "BRIGHT" to 0.45f, "DECAY" to 0.45f),
        p(TinesVoice.CHIME, "ICICLE", "TUNE" to 0.4f, "SHIMMER" to 0.5f, "BRIGHT" to 0.6f, "DECAY" to 0.5f),
        p(TinesVoice.CHIME, "FROSTBELL", "TUNE" to 0.45f, "SHIMMER" to 0.65f, "BRIGHT" to 0.5f, "DECAY" to 0.7f),
        p(TinesVoice.CHIME, "STARLIGHT", "TUNE" to 0.55f, "SHIMMER" to 0.8f, "BRIGHT" to 0.7f, "DECAY" to 0.55f),
        p(TinesVoice.CHIME, "MOONGLOW", "TUNE" to 0.5f, "SHIMMER" to 0.9f, "BRIGHT" to 0.8f, "DECAY" to 0.65f),
        p(TinesVoice.CHIME, "TINSEL", "TUNE" to 0.65f, "SHIMMER" to 0.4f, "BRIGHT" to 0.85f, "DECAY" to 0.3f),
        p(TinesVoice.CHIME, "SPARKLER", "TUNE" to 0.7f, "SHIMMER" to 0.55f, "BRIGHT" to 0.9f, "DECAY" to 0.25f),
        p(TinesVoice.CHIME, "PINPRICK", "TUNE" to 0.85f, "SHIMMER" to 0.2f, "BRIGHT" to 0.5f, "DECAY" to 0.15f),
        p(TinesVoice.CHIME, "NEEDLE", "TUNE" to 0.9f, "SHIMMER" to 0.1f, "BRIGHT" to 0.6f, "DECAY" to 0.2f),
        p(TinesVoice.CHIME, "DEEP CHIME", "TUNE" to 0.1f, "SHIMMER" to 0.35f, "BRIGHT" to 0.3f, "DECAY" to 0.8f),
        p(TinesVoice.CHIME, "HOLLOW BELL", "TUNE" to 0.15f, "SHIMMER" to 0.05f, "BRIGHT" to 0.2f, "DECAY" to 0.9f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(TinesVoice.CHIME, "TUBE", "TUNE" to 0.30f, "SHIMMER" to 0.30f, "BRIGHT" to 0.50f, "DECAY" to 0.70f, "BITE" to 0.60f, "RATIO" to 0.05f),
        p(TinesVoice.CHIME, "ROD", "TUNE" to 0.60f, "SHIMMER" to 0.60f, "BRIGHT" to 0.70f, "DECAY" to 0.50f, "BITE" to 0.70f, "RATIO" to 0.95f),
        p(TinesVoice.CHIME, "BUZZ GLASS", "TUNE" to 0.50f, "SHIMMER" to 0.80f, "BRIGHT" to 0.60f, "DECAY" to 0.60f, "BITE" to 0.10f, "RATIO" to 0.60f),
        p(TinesVoice.CHIME, "PURE DROP", "TUNE" to 0.80f, "SHIMMER" to 0.10f, "BRIGHT" to 0.30f, "DECAY" to 0.40f, "BITE" to 1.00f, "RATIO" to 0.20f),
    )

    private val blockPresets = listOf(
        p(TinesVoice.BLOCK, "KNUCKLE", "TUNE" to 0.15f, "BRIGHT" to 0.25f, "DECAY" to 0.2f),
        p(TinesVoice.BLOCK, "PENCIL TAP", "TUNE" to 0.25f, "BRIGHT" to 0.35f, "DECAY" to 0.3f),
        p(TinesVoice.BLOCK, "CASTANET", "TUNE" to 0.35f, "BRIGHT" to 0.5f, "DECAY" to 0.4f),
        p(TinesVoice.BLOCK, "COCONUT", "TUNE" to 0.4f, "BRIGHT" to 0.3f, "DECAY" to 0.6f),
        p(TinesVoice.BLOCK, "CHOPSTICKS", "TUNE" to 0.5f, "BRIGHT" to 0.6f, "DECAY" to 0.35f),
        p(TinesVoice.BLOCK, "TICK TOCK", "TUNE" to 0.55f, "BRIGHT" to 0.45f, "DECAY" to 0.5f),
        p(TinesVoice.BLOCK, "DRY KNOCK", "TUNE" to 0.6f, "BRIGHT" to 0.55f, "DECAY" to 0.25f),
        p(TinesVoice.BLOCK, "HARD RAP", "TUNE" to 0.65f, "BRIGHT" to 0.7f, "DECAY" to 0.45f),
        p(TinesVoice.BLOCK, "SOFT KNOCK", "TUNE" to 0.3f, "BRIGHT" to 0.2f, "DECAY" to 0.8f),
        p(TinesVoice.BLOCK, "LOUD CRACK", "TUNE" to 0.7f, "BRIGHT" to 0.8f, "DECAY" to 0.3f),
        p(TinesVoice.BLOCK, "DEEP THUD", "TUNE" to 0.1f, "BRIGHT" to 0.15f, "DECAY" to 0.9f),
        p(TinesVoice.BLOCK, "GLASS TAP", "TUNE" to 0.8f, "BRIGHT" to 0.9f, "DECAY" to 0.2f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(TinesVoice.BLOCK, "METAL BAR", "TUNE" to 0.50f, "BRIGHT" to 0.70f, "DECAY" to 0.60f, "BITE" to 0.30f, "RATIO" to 0.90f),
        p(TinesVoice.BLOCK, "PLASTIC", "TUNE" to 0.70f, "BRIGHT" to 0.50f, "DECAY" to 0.30f, "BITE" to 0.60f, "RATIO" to 0.40f),
        p(TinesVoice.BLOCK, "SOFT WOOD", "TUNE" to 0.30f, "BRIGHT" to 0.30f, "DECAY" to 0.50f, "BITE" to 1.00f, "RATIO" to 0.00f),
        p(TinesVoice.BLOCK, "ICE TAP", "TUNE" to 0.85f, "BRIGHT" to 0.80f, "DECAY" to 0.20f, "BITE" to 0.20f, "RATIO" to 0.70f),
    )

    private val zapPresets = listOf(
        p(TinesVoice.ZAP, "LASER", "TUNE" to 0.5f, "DROP" to 0.8f, "BRIGHT" to 0.7f, "DECAY" to 0.3f),
        p(TinesVoice.ZAP, "PEW PEW", "TUNE" to 0.6f, "DROP" to 0.9f, "BRIGHT" to 0.8f, "DECAY" to 0.2f),
        p(TinesVoice.ZAP, "RAY GUN", "TUNE" to 0.4f, "DROP" to 0.7f, "BRIGHT" to 0.6f, "DECAY" to 0.35f),
        p(TinesVoice.ZAP, "STUN", "TUNE" to 0.3f, "DROP" to 0.6f, "BRIGHT" to 0.5f, "DECAY" to 0.25f),
        p(TinesVoice.ZAP, "WARP", "TUNE" to 0.2f, "DROP" to 0.5f, "BRIGHT" to 0.4f, "DECAY" to 0.5f),
        p(TinesVoice.ZAP, "SONAR PING", "TUNE" to 0.7f, "DROP" to 0.3f, "BRIGHT" to 0.3f, "DECAY" to 0.6f),
        p(TinesVoice.ZAP, "DEPTH CHARGE", "TUNE" to 0.1f, "DROP" to 0.95f, "BRIGHT" to 0.2f, "DECAY" to 0.7f),
        p(TinesVoice.ZAP, "BLASTER", "TUNE" to 0.55f, "DROP" to 0.85f, "BRIGHT" to 0.9f, "DECAY" to 0.15f),
        p(TinesVoice.ZAP, "RICOCHET", "TUNE" to 0.45f, "DROP" to 0.4f, "BRIGHT" to 0.65f, "DECAY" to 0.4f),
        p(TinesVoice.ZAP, "TORPEDO", "TUNE" to 0.35f, "DROP" to 0.55f, "BRIGHT" to 0.35f, "DECAY" to 0.55f),
        p(TinesVoice.ZAP, "ZERO G", "TUNE" to 0.65f, "DROP" to 0.2f, "BRIGHT" to 0.75f, "DECAY" to 0.3f),
        p(TinesVoice.ZAP, "GALAXY", "TUNE" to 0.8f, "DROP" to 0.1f, "BRIGHT" to 0.55f, "DECAY" to 0.45f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(TinesVoice.ZAP, "SLOW LASER", "TUNE" to 0.50f, "DROP" to 0.80f, "BRIGHT" to 0.50f, "DECAY" to 0.80f, "BITE" to 0.40f, "BEND" to 0.00f, "RATIO" to 0.50f),
        p(TinesVoice.ZAP, "PITCH THUD", "TUNE" to 0.60f, "DROP" to 0.50f, "BRIGHT" to 0.30f, "DECAY" to 0.30f, "BITE" to 0.80f, "BEND" to 1.00f, "RATIO" to 0.20f),
        p(TinesVoice.ZAP, "METAL ZAP", "TUNE" to 0.60f, "DROP" to 0.60f, "BRIGHT" to 0.80f, "DECAY" to 0.50f, "BITE" to 0.10f, "BEND" to 0.40f, "RATIO" to 0.95f),
        p(TinesVoice.ZAP, "SOFT PEW", "TUNE" to 0.70f, "DROP" to 0.30f, "BRIGHT" to 0.20f, "DECAY" to 0.40f, "BITE" to 0.70f, "BEND" to 0.20f, "RATIO" to 0.00f),
    )

    private val toyPresets = listOf(
        p(TinesVoice.TOY, "WOBBLE POP", "TUNE" to 0.5f, "WOBBLE" to 0.5f, "BRIGHT" to 0.5f, "DECAY" to 0.3f),
        p(TinesVoice.TOY, "KAZOO", "TUNE" to 0.4f, "WOBBLE" to 0.7f, "BRIGHT" to 0.4f, "DECAY" to 0.35f),
        p(TinesVoice.TOY, "SQUEAKY", "TUNE" to 0.6f, "WOBBLE" to 0.3f, "BRIGHT" to 0.6f, "DECAY" to 0.25f),
        p(TinesVoice.TOY, "RUBBER DUCK", "TUNE" to 0.35f, "WOBBLE" to 0.6f, "BRIGHT" to 0.35f, "DECAY" to 0.4f),
        p(TinesVoice.TOY, "PLASTIC", "TUNE" to 0.55f, "WOBBLE" to 0.2f, "BRIGHT" to 0.5f, "DECAY" to 0.2f),
        p(TinesVoice.TOY, "WARBLY", "TUNE" to 0.45f, "WOBBLE" to 0.85f, "BRIGHT" to 0.55f, "DECAY" to 0.3f),
        p(TinesVoice.TOY, "SILLY BOING", "TUNE" to 0.3f, "WOBBLE" to 0.9f, "BRIGHT" to 0.3f, "DECAY" to 0.45f),
        p(TinesVoice.TOY, "GAME OVER", "TUNE" to 0.7f, "WOBBLE" to 0.4f, "BRIGHT" to 0.7f, "DECAY" to 0.15f),
        p(TinesVoice.TOY, "BUBBLE", "TUNE" to 0.65f, "WOBBLE" to 0.55f, "BRIGHT" to 0.45f, "DECAY" to 0.35f),
        p(TinesVoice.TOY, "TIN TOY", "TUNE" to 0.5f, "WOBBLE" to 0.15f, "BRIGHT" to 0.6f, "DECAY" to 0.5f),
        p(TinesVoice.TOY, "CHEAP LASER", "TUNE" to 0.8f, "WOBBLE" to 0.65f, "BRIGHT" to 0.8f, "DECAY" to 0.1f),
        p(TinesVoice.TOY, "NOVELTY", "TUNE" to 0.25f, "WOBBLE" to 0.45f, "BRIGHT" to 0.4f, "DECAY" to 0.55f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(TinesVoice.TOY, "CHIP TRILL", "TUNE" to 0.60f, "WOBBLE" to 0.60f, "BRIGHT" to 0.50f, "DECAY" to 0.50f, "BITE" to 0.50f, "RATIO" to 0.40f, "SHAPE" to 1.00f),
        p(TinesVoice.TOY, "ARCADE", "TUNE" to 0.70f, "WOBBLE" to 0.80f, "BRIGHT" to 0.70f, "DECAY" to 0.40f, "BITE" to 0.30f, "RATIO" to 0.70f, "SHAPE" to 0.80f),
        p(TinesVoice.TOY, "BUZZ TOY", "TUNE" to 0.40f, "WOBBLE" to 0.30f, "BRIGHT" to 0.80f, "DECAY" to 0.50f, "BITE" to 0.00f, "RATIO" to 0.55f, "SHAPE" to 0.30f),
        p(TinesVoice.TOY, "MUSIC CARD", "TUNE" to 0.80f, "WOBBLE" to 0.20f, "BRIGHT" to 0.30f, "DECAY" to 0.30f, "BITE" to 0.90f, "RATIO" to 0.10f, "SHAPE" to 0.50f),
    )

    // The kalimba names carried over from PLUCK's voice of the same name,
    // mapped onto TUNE/BUZZ/BRIGHT/DECAY; authored from the DSP like every
    // other list here, to be re-authored by ear.
    private val kalimbaPresets = listOf(
        p(TinesVoice.KALIMBA, "THUMBPIANO", "TUNE" to 0.3f, "BUZZ" to 0.1f, "BRIGHT" to 0.4f, "DECAY" to 0.5f),
        p(TinesVoice.KALIMBA, "RUSTY TINE", "TUNE" to 0.2f, "BUZZ" to 0.35f, "BRIGHT" to 0.3f, "DECAY" to 0.4f),
        p(TinesVoice.KALIMBA, "BRASS TINE", "TUNE" to 0.4f, "BUZZ" to 0.05f, "BRIGHT" to 0.7f, "DECAY" to 0.6f),
        p(TinesVoice.KALIMBA, "WOOD BODY", "TUNE" to 0.35f, "BUZZ" to 0.0f, "BRIGHT" to 0.3f, "DECAY" to 0.45f),
        p(TinesVoice.KALIMBA, "GLASSY MBIRA", "TUNE" to 0.5f, "BUZZ" to 0.2f, "BRIGHT" to 0.85f, "DECAY" to 0.7f),
        p(TinesVoice.KALIMBA, "SOFT PLUCK", "TUNE" to 0.45f, "BUZZ" to 0.0f, "BRIGHT" to 0.2f, "DECAY" to 0.35f),
        p(TinesVoice.KALIMBA, "TWANG", "TUNE" to 0.6f, "BUZZ" to 0.45f, "BRIGHT" to 0.6f, "DECAY" to 0.3f),
        p(TinesVoice.KALIMBA, "DUSTY KEYS", "TUNE" to 0.55f, "BUZZ" to 0.25f, "BRIGHT" to 0.45f, "DECAY" to 0.5f),
        p(TinesVoice.KALIMBA, "BRIGHT TINE", "TUNE" to 0.7f, "BUZZ" to 0.1f, "BRIGHT" to 0.95f, "DECAY" to 0.55f),
        p(TinesVoice.KALIMBA, "MUTED THUMB", "TUNE" to 0.25f, "BUZZ" to 0.0f, "BRIGHT" to 0.15f, "DECAY" to 0.15f),
        p(TinesVoice.KALIMBA, "FULL RATTLE", "TUNE" to 0.65f, "BUZZ" to 0.9f, "BRIGHT" to 0.6f, "DECAY" to 0.5f),
        p(TinesVoice.KALIMBA, "HIGH TINE", "TUNE" to 0.85f, "BUZZ" to 0.15f, "BRIGHT" to 0.8f, "DECAY" to 0.8f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(TinesVoice.KALIMBA, "NAIL PICK", "TUNE" to 0.50f, "BUZZ" to 0.10f, "BRIGHT" to 0.70f, "DECAY" to 0.80f, "BITE" to 0.60f, "TICK" to 1.00f),
        p(TinesVoice.KALIMBA, "SOFT THUMB", "TUNE" to 0.40f, "BUZZ" to 0.00f, "BRIGHT" to 0.30f, "DECAY" to 0.95f, "BITE" to 0.80f, "TICK" to 0.00f),
        p(TinesVoice.KALIMBA, "RATTLE BOX", "TUNE" to 0.60f, "BUZZ" to 0.70f, "BRIGHT" to 0.60f, "DECAY" to 0.70f, "BITE" to 0.30f, "TICK" to 0.70f),
        p(TinesVoice.KALIMBA, "SHINY TINE", "TUNE" to 0.75f, "BUZZ" to 0.20f, "BRIGHT" to 0.90f, "DECAY" to 0.85f, "BITE" to 0.10f, "TICK" to 0.60f),
    )
}
