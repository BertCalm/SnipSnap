package com.snipsnap.synth

/**
 * THUMP's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * The app shipped eight drum voices and zero presets: every voice had exactly
 * one hardcoded [Thump.defaults] macro map, so a beginner met TUNE/SWEEP/
 * DECAY/CLICK/DRIVE instead of a sound. Sixteen presets per voice here, four
 * apiece across the genres a young producer with a phone actually reaches
 * for: BOOM BAP, HOUSE, JUNGLE, DUB. Loading one sets the macro sliders, so
 * a preset is a starting point to wreck, not a locked sound.
 *
 * For most voices, the four variants within a genre are the same genre
 * "centre" nudged along every macro at once — a fixed sign pattern per
 * voice, not four separate guesses. HAT_CLOSED, HAT_OPEN and TOM are the
 * exception: their classifier boundaries turned out too chaotic for that
 * to be safe (see the comments above those tables), so their values are
 * points an exhaustive scan actually measured as safe. Either way, every
 * one of KICK, HAT_CLOSED, HAT_OPEN, CLAP, TOM, COWBELL and RIM below was
 * authored by reasoning about the DSP — never rendered, never heard —
 * and checked only by [ThumpPresetsTest]'s classifier and sanity checks.
 *
 * SNARE is the one exception, and the difference matters. Phase 1B
 * rebuilt SNARE's body as a circular membrane with wires rattling
 * against it, and the sixteen presets below were rendered on that new
 * engine, listened to by a person, and kept because they sounded like
 * the name says — not reasoned into existence from the macro math. Say
 * so here rather than let this KDoc's silence imply every table below it
 * was checked the same way; it wasn't.
 *
 * Names never reference a real drum machine or model number
 * (`docs/SYNTH_ROADMAP.md`'s naming rule) — not even a near-miss; the
 * blocklist test in [ThumpPresetsTest] makes that machine-checked rather than
 * a matter of vigilance.
 */
object ThumpPresets {

    private fun p(voice: ThumpVoice, name: String, vararg macros: Pair<String, Float>) =
        ThumpPatch(name, voice, macros.toMap())

    fun forVoice(voice: ThumpVoice): List<ThumpPatch> = when (voice) {
        ThumpVoice.KICK -> kickPresets
        ThumpVoice.SNARE -> snarePresets
        ThumpVoice.HAT_CLOSED -> hatClosedPresets
        ThumpVoice.HAT_OPEN -> hatOpenPresets
        ThumpVoice.CLAP -> clapPresets
        ThumpVoice.TOM -> tomPresets
        ThumpVoice.COWBELL -> cowbellPresets
        ThumpVoice.RIM -> rimPresets
    }

    fun all(): List<ThumpPatch> = ThumpVoice.entries.flatMap { forVoice(it) }

    // Order within every list: BOOM BAP x4, HOUSE x4, JUNGLE x4, DUB x4.

    private val kickPresets = listOf(
        p(ThumpVoice.KICK, "DUSTY BOOM", "TUNE" to 0.34f, "SWEEP" to 0.45f, "DECAY" to 0.37f, "CLICK" to 0.32f, "DRIVE" to 0.35f),
        p(ThumpVoice.KICK, "BASEMENT", "TUNE" to 0.16f, "SWEEP" to 0.45f, "DECAY" to 0.53f, "CLICK" to 0.08f, "DRIVE" to 0.55f),
        p(ThumpVoice.KICK, "CRATE DUST", "TUNE" to 0.34f, "SWEEP" to 0.25f, "DECAY" to 0.53f, "CLICK" to 0.08f, "DRIVE" to 0.35f),
        p(ThumpVoice.KICK, "TAPE THUMP", "TUNE" to 0.16f, "SWEEP" to 0.25f, "DECAY" to 0.37f, "CLICK" to 0.32f, "DRIVE" to 0.55f),
        p(ThumpVoice.KICK, "FOUR FLOOR", "TUNE" to 0.33f, "SWEEP" to 0.68f, "DECAY" to 0.30f, "CLICK" to 0.38f, "DRIVE" to 0.68f),
        p(ThumpVoice.KICK, "CLUB PUNCH", "TUNE" to 0.51f, "SWEEP" to 0.48f, "DECAY" to 0.30f, "CLICK" to 0.38f, "DRIVE" to 0.48f),
        p(ThumpVoice.KICK, "WAREHOUSE", "TUNE" to 0.33f, "SWEEP" to 0.48f, "DECAY" to 0.14f, "CLICK" to 0.62f, "DRIVE" to 0.68f),
        p(ThumpVoice.KICK, "PEAK TIME", "TUNE" to 0.51f, "SWEEP" to 0.68f, "DECAY" to 0.14f, "CLICK" to 0.62f, "DRIVE" to 0.48f),
        p(ThumpVoice.KICK, "BREAK CHOP", "TUNE" to 0.19f, "SWEEP" to 0.58f, "DECAY" to 0.26f, "CLICK" to 0.43f, "DRIVE" to 0.58f),
        p(ThumpVoice.KICK, "RAGGA SUB", "TUNE" to 0.01f, "SWEEP" to 0.58f, "DECAY" to 0.10f, "CLICK" to 0.67f, "DRIVE" to 0.78f),
        p(ThumpVoice.KICK, "JUNGLIST", "TUNE" to 0.19f, "SWEEP" to 0.78f, "DECAY" to 0.10f, "CLICK" to 0.67f, "DRIVE" to 0.58f),
        p(ThumpVoice.KICK, "AMEN SUB", "TUNE" to 0.01f, "SWEEP" to 0.78f, "DECAY" to 0.26f, "CLICK" to 0.43f, "DRIVE" to 0.78f),
        p(ThumpVoice.KICK, "DEEP DUB", "TUNE" to 0.00f, "SWEEP" to 0.12f, "DECAY" to 0.70f, "CLICK" to 0.15f, "DRIVE" to 0.48f),
        p(ThumpVoice.KICK, "STEPPER", "TUNE" to 0.17f, "SWEEP" to 0.32f, "DECAY" to 0.70f, "CLICK" to 0.15f, "DRIVE" to 0.28f),
        p(ThumpVoice.KICK, "ECHO CHAMBER", "TUNE" to 0.00f, "SWEEP" to 0.32f, "DECAY" to 0.86f, "CLICK" to 0.00f, "DRIVE" to 0.48f),
        p(ThumpVoice.KICK, "ROOTS", "TUNE" to 0.17f, "SWEEP" to 0.12f, "DECAY" to 0.86f, "CLICK" to 0.00f, "DRIVE" to 0.28f),
    )

    // Phase 1B rebuilt SNARE's body as a membrane with wires rattling
    // against it, which broke every one of the sixteen presets above this
    // comment in source control history: several sat at SNAP 0.77-0.80,
    // which under the old two-sine body read as snare but under the new
    // membrane is mostly wire noise, so the classifier correctly calls
    // them hi-hats. These sixteen replace them and were rendered on the
    // new engine and auditioned by a person before landing here — not
    // reasoned into existence from the macro math like the rest of this
    // file. See this file's KDoc.
    private val snarePresets = listOf(
        p(ThumpVoice.SNARE, "DEEP ROOM", "TUNE" to 0.15f, "SNAP" to 0.35f, "DECAY" to 0.70f, "TONE" to 0.35f, "STRIKE" to 0.22f, "PUNCH" to 0.45f),
        p(ThumpVoice.SNARE, "DRY TIMBER", "TUNE" to 0.18f, "SNAP" to 0.30f, "DECAY" to 0.30f, "TONE" to 0.30f, "STRIKE" to 0.18f, "PUNCH" to 0.60f),
        p(ThumpVoice.SNARE, "WIDE FLOOR", "TUNE" to 0.12f, "SNAP" to 0.55f, "DECAY" to 0.75f, "TONE" to 0.50f, "STRIKE" to 0.30f, "PUNCH" to 0.40f),
        p(ThumpVoice.SNARE, "BACKBEAT", "TUNE" to 0.38f, "SNAP" to 0.45f, "DECAY" to 0.48f, "TONE" to 0.50f, "STRIKE" to 0.28f, "PUNCH" to 0.55f),
        p(ThumpVoice.SNARE, "CRACKED CLAY", "TUNE" to 0.42f, "SNAP" to 0.52f, "DECAY" to 0.35f, "TONE" to 0.70f, "STRIKE" to 0.42f, "PUNCH" to 0.75f),
        p(ThumpVoice.SNARE, "SOFT SHELL", "TUNE" to 0.35f, "SNAP" to 0.38f, "DECAY" to 0.55f, "TONE" to 0.40f, "STRIKE" to 0.20f, "PUNCH" to 0.30f),
        p(ThumpVoice.SNARE, "TIGHT STEEL", "TUNE" to 0.62f, "SNAP" to 0.48f, "DECAY" to 0.22f, "TONE" to 0.65f, "STRIKE" to 0.35f, "PUNCH" to 0.70f),
        p(ThumpVoice.SNARE, "PICCOLO", "TUNE" to 0.80f, "SNAP" to 0.42f, "DECAY" to 0.18f, "TONE" to 0.72f, "STRIKE" to 0.38f, "PUNCH" to 0.65f),
        p(ThumpVoice.SNARE, "RIM EDGE", "TUNE" to 0.68f, "SNAP" to 0.55f, "DECAY" to 0.20f, "TONE" to 0.85f, "STRIKE" to 0.55f, "PUNCH" to 0.85f),
        p(ThumpVoice.SNARE, "LOOSE WIRE", "TUNE" to 0.45f, "SNAP" to 0.72f, "DECAY" to 0.45f, "TONE" to 0.62f, "STRIKE" to 0.30f, "PUNCH" to 0.45f),
        p(ThumpVoice.SNARE, "DARK RATTLE", "TUNE" to 0.40f, "SNAP" to 0.75f, "DECAY" to 0.50f, "TONE" to 0.28f, "STRIKE" to 0.25f, "PUNCH" to 0.40f),
        p(ThumpVoice.SNARE, "DUST BURST", "TUNE" to 0.45f, "SNAP" to 0.92f, "DECAY" to 0.35f, "TONE" to 0.25f, "STRIKE" to 0.30f, "PUNCH" to 0.35f),
        p(ThumpVoice.SNARE, "LONG HISS", "TUNE" to 0.38f, "SNAP" to 0.88f, "DECAY" to 0.62f, "TONE" to 0.45f, "STRIKE" to 0.28f, "PUNCH" to 0.30f),
        p(ThumpVoice.SNARE, "FULL SWING", "TUNE" to 0.42f, "SNAP" to 0.50f, "DECAY" to 0.40f, "TONE" to 0.55f, "STRIKE" to 0.30f, "PUNCH" to 1.00f),
        p(ThumpVoice.SNARE, "CENTRE HIT", "TUNE" to 0.45f, "SNAP" to 0.45f, "DECAY" to 0.42f, "TONE" to 0.55f, "STRIKE" to 0.05f, "PUNCH" to 0.55f),
        p(ThumpVoice.SNARE, "RIM SHOT", "TUNE" to 0.45f, "SNAP" to 0.45f, "DECAY" to 0.42f, "TONE" to 0.55f, "STRIKE" to 0.85f, "PUNCH" to 0.55f),
    )

    // METAL is pinned at 0.30 on every hat preset. The classifier's
    // HAT/SNARE boundary sits on a spectral-comb knife-edge around a
    // 12kHz centroid that is chaotic almost everywhere in this voice's
    // (TUNE, DECAY, METAL) space — not a smooth function of any one
    // macro, so a value safe at one DECAY can misfire two hundredths
    // away at another. There is no clean sub-rectangle to hand-pick
    // values from (see ThumpPresetsTest's generation notes for the full
    // account); what's below is 16 points *measured* safe by an
    // exhaustive scan at this METAL, then chosen for spread by farthest-
    // point sampling and grouped into genre quartiles by DECAY (shortest
    // trail = JUNGLE, longest = the genre with the washiest hats). Do
    // not hand-edit a TUNE/DECAY pair here without re-running that scan.
    private val hatClosedPresets = listOf(
        p(ThumpVoice.HAT_CLOSED, "RAGGA TICK", "TUNE" to 0.50f, "DECAY" to 0.05f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "JUNGLE TAP", "TUNE" to 0.10f, "DECAY" to 0.15f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "BREAK TICK", "TUNE" to 0.90f, "DECAY" to 0.20f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "FAST TAP", "TUNE" to 0.30f, "DECAY" to 0.30f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "CLUB TICK", "TUNE" to 0.55f, "DECAY" to 0.35f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "DISCO TAP", "TUNE" to 0.05f, "DECAY" to 0.45f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "FOUR TICK", "TUNE" to 0.95f, "DECAY" to 0.45f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "PEAK TAP", "TUNE" to 0.30f, "DECAY" to 0.60f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "DUSTY TAP", "TUNE" to 0.75f, "DECAY" to 0.60f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "LOFI TICK", "TUNE" to 0.05f, "DECAY" to 0.70f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "CRATE TAP", "TUNE" to 0.50f, "DECAY" to 0.70f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "SOFT TICK", "TUNE" to 0.25f, "DECAY" to 0.85f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "DUB TICK", "TUNE" to 0.70f, "DECAY" to 0.85f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "ROOTS TAP", "TUNE" to 0.95f, "DECAY" to 0.95f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "SKANK TICK", "TUNE" to 0.05f, "DECAY" to 0.95f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_CLOSED, "STEPPA TAP", "TUNE" to 0.50f, "DECAY" to 0.95f, "METAL" to 0.3f),
    )

    private val hatOpenPresets = listOf(
        p(ThumpVoice.HAT_OPEN, "RAGGA HISS", "TUNE" to 0.90f, "DECAY" to 0.42f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "JUNGLE WASH", "TUNE" to 0.05f, "DECAY" to 0.42f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "BREAK HISS", "TUNE" to 0.50f, "DECAY" to 0.42f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "FAST WASH", "TUNE" to 0.25f, "DECAY" to 0.42f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "DUSTY WASH", "TUNE" to 0.70f, "DECAY" to 0.47f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "LOFI HISS", "TUNE" to 0.30f, "DECAY" to 0.62f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "CRATE WASH", "TUNE" to 0.65f, "DECAY" to 0.67f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "SOFT HISS", "TUNE" to 0.95f, "DECAY" to 0.67f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "DUB WASH", "TUNE" to 0.05f, "DECAY" to 0.67f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "ROOTS HISS", "TUNE" to 0.45f, "DECAY" to 0.77f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "SKANK WASH", "TUNE" to 0.80f, "DECAY" to 0.82f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "STEPPA HISS", "TUNE" to 0.25f, "DECAY" to 0.87f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "CLUB HISS", "TUNE" to 0.95f, "DECAY" to 0.97f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "DISCO WASH", "TUNE" to 0.05f, "DECAY" to 0.97f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "FOUR SPLASH", "TUNE" to 0.50f, "DECAY" to 0.97f, "METAL" to 0.3f),
        p(ThumpVoice.HAT_OPEN, "PEAK WASH", "TUNE" to 0.70f, "DECAY" to 0.97f, "METAL" to 0.3f),
    )

    private val clapPresets = listOf(
        p(ThumpVoice.CLAP, "DUSTY CLAP", "SPREAD" to 0.44f, "DECAY" to 0.57f, "TONE" to 0.11f),
        p(ThumpVoice.CLAP, "VINYL CLAP", "SPREAD" to 0.16f, "DECAY" to 0.57f, "TONE" to 0.39f),
        p(ThumpVoice.CLAP, "LOFI CLAP", "SPREAD" to 0.44f, "DECAY" to 0.33f, "TONE" to 0.39f),
        p(ThumpVoice.CLAP, "ROOM CLAP", "SPREAD" to 0.16f, "DECAY" to 0.33f, "TONE" to 0.11f),
        p(ThumpVoice.CLAP, "CLUB CLAP", "SPREAD" to 0.41f, "DECAY" to 0.40f, "TONE" to 0.76f),
        p(ThumpVoice.CLAP, "DISCO CLAP", "SPREAD" to 0.69f, "DECAY" to 0.16f, "TONE" to 0.76f),
        p(ThumpVoice.CLAP, "FOUR CLAP", "SPREAD" to 0.41f, "DECAY" to 0.16f, "TONE" to 0.48f),
        p(ThumpVoice.CLAP, "PEAK CLAP", "SPREAD" to 0.69f, "DECAY" to 0.40f, "TONE" to 0.48f),
        p(ThumpVoice.CLAP, "RAGGA CLAP", "SPREAD" to 0.89f, "DECAY" to 0.30f, "TONE" to 0.96f),
        p(ThumpVoice.CLAP, "BREAK CLAP", "SPREAD" to 0.61f, "DECAY" to 0.30f, "TONE" to 0.68f),
        p(ThumpVoice.CLAP, "JUNGLE CLAP", "SPREAD" to 0.89f, "DECAY" to 0.54f, "TONE" to 0.68f),
        p(ThumpVoice.CLAP, "RUDE CLAP", "SPREAD" to 0.61f, "DECAY" to 0.54f, "TONE" to 0.96f),
        p(ThumpVoice.CLAP, "DUB CLAP", "SPREAD" to 0.11f, "DECAY" to 0.60f, "TONE" to 0.01f),
        p(ThumpVoice.CLAP, "ROOTS CLAP", "SPREAD" to 0.39f, "DECAY" to 0.84f, "TONE" to 0.01f),
        p(ThumpVoice.CLAP, "SKANK CLAP", "SPREAD" to 0.11f, "DECAY" to 0.84f, "TONE" to 0.29f),
        p(ThumpVoice.CLAP, "ECHO CLAP", "SPREAD" to 0.39f, "DECAY" to 0.60f, "TONE" to 0.29f),
    )

    // TOM's TUNE is held to [0.46, 0.64] on every preset: measured (see
    // ThumpPresetsTest's generation notes) to be the only window that's
    // clean of both boundaries at once — below it a low, slow-swept tom
    // reads as a KICK, above it a bright one reads as PERC. SWEEP and
    // DECAY carry the genre character instead.
    private val tomPresets = listOf(
        p(ThumpVoice.TOM, "DUSTY TOM", "TUNE" to 0.53f, "SWEEP" to 0.39f, "DECAY" to 0.31f),
        p(ThumpVoice.TOM, "LOFI TOM", "TUNE" to 0.47f, "SWEEP" to 0.39f, "DECAY" to 0.59f),
        p(ThumpVoice.TOM, "CRATE TOM", "TUNE" to 0.53f, "SWEEP" to 0.11f, "DECAY" to 0.59f),
        p(ThumpVoice.TOM, "SOFT TOM", "TUNE" to 0.47f, "SWEEP" to 0.11f, "DECAY" to 0.31f),
        p(ThumpVoice.TOM, "CLUB TOM", "TUNE" to 0.54f, "SWEEP" to 0.59f, "DECAY" to 0.42f),
        p(ThumpVoice.TOM, "DISCO TOM", "TUNE" to 0.60f, "SWEEP" to 0.31f, "DECAY" to 0.42f),
        p(ThumpVoice.TOM, "FOUR TOM", "TUNE" to 0.54f, "SWEEP" to 0.31f, "DECAY" to 0.14f),
        p(ThumpVoice.TOM, "PEAK TOM", "TUNE" to 0.60f, "SWEEP" to 0.59f, "DECAY" to 0.14f),
        p(ThumpVoice.TOM, "RAGGA TOM", "TUNE" to 0.64f, "SWEEP" to 0.51f, "DECAY" to 0.34f),
        p(ThumpVoice.TOM, "BREAK TOM", "TUNE" to 0.58f, "SWEEP" to 0.51f, "DECAY" to 0.06f),
        p(ThumpVoice.TOM, "RUDE TOM", "TUNE" to 0.64f, "SWEEP" to 0.79f, "DECAY" to 0.06f),
        p(ThumpVoice.TOM, "WILD TOM", "TUNE" to 0.58f, "SWEEP" to 0.79f, "DECAY" to 0.34f),
        p(ThumpVoice.TOM, "DUB TOM", "TUNE" to 0.46f, "SWEEP" to 0.01f, "DECAY" to 0.58f),
        p(ThumpVoice.TOM, "ROOTS TOM", "TUNE" to 0.52f, "SWEEP" to 0.29f, "DECAY" to 0.58f),
        p(ThumpVoice.TOM, "SKANK TOM", "TUNE" to 0.46f, "SWEEP" to 0.29f, "DECAY" to 0.86f),
        p(ThumpVoice.TOM, "ECHO TOM", "TUNE" to 0.52f, "SWEEP" to 0.01f, "DECAY" to 0.86f),
    )

    private val cowbellPresets = listOf(
        p(ThumpVoice.COWBELL, "DUSTY BELL", "TUNE" to 0.25f, "DECAY" to 0.51f),
        p(ThumpVoice.COWBELL, "LOFI BELL", "TUNE" to 0.05f, "DECAY" to 0.51f),
        p(ThumpVoice.COWBELL, "CRATE BELL", "TUNE" to 0.25f, "DECAY" to 0.33f),
        p(ThumpVoice.COWBELL, "SOFT BELL", "TUNE" to 0.05f, "DECAY" to 0.33f),
        p(ThumpVoice.COWBELL, "CLUB BELL", "TUNE" to 0.40f, "DECAY" to 0.29f),
        p(ThumpVoice.COWBELL, "DISCO BELL", "TUNE" to 0.60f, "DECAY" to 0.11f),
        p(ThumpVoice.COWBELL, "FOUR BELL", "TUNE" to 0.40f, "DECAY" to 0.11f),
        p(ThumpVoice.COWBELL, "PEAK BELL", "TUNE" to 0.60f, "DECAY" to 0.29f),
        p(ThumpVoice.COWBELL, "RAGGA BELL", "TUNE" to 0.95f, "DECAY" to 0.03f),
        p(ThumpVoice.COWBELL, "BREAK BELL", "TUNE" to 0.75f, "DECAY" to 0.03f),
        p(ThumpVoice.COWBELL, "RUDE BELL", "TUNE" to 0.95f, "DECAY" to 0.21f),
        p(ThumpVoice.COWBELL, "WILD BELL", "TUNE" to 0.75f, "DECAY" to 0.21f),
        p(ThumpVoice.COWBELL, "DUB BELL", "TUNE" to 0.22f, "DECAY" to 0.69f),
        p(ThumpVoice.COWBELL, "ROOTS BELL", "TUNE" to 0.42f, "DECAY" to 0.87f),
        p(ThumpVoice.COWBELL, "SKANK BELL", "TUNE" to 0.22f, "DECAY" to 0.87f),
        p(ThumpVoice.COWBELL, "ECHO BELL", "TUNE" to 0.42f, "DECAY" to 0.69f),
    )

    private val rimPresets = listOf(
        p(ThumpVoice.RIM, "DUSTY RIM", "TUNE" to 0.28f, "DECAY" to 0.46f),
        p(ThumpVoice.RIM, "LOFI RIM", "TUNE" to 0.08f, "DECAY" to 0.46f),
        p(ThumpVoice.RIM, "CRATE RIM", "TUNE" to 0.28f, "DECAY" to 0.30f),
        p(ThumpVoice.RIM, "SOFT RIM", "TUNE" to 0.08f, "DECAY" to 0.30f),
        p(ThumpVoice.RIM, "CLUB RIM", "TUNE" to 0.42f, "DECAY" to 0.26f),
        p(ThumpVoice.RIM, "DISCO RIM", "TUNE" to 0.62f, "DECAY" to 0.10f),
        p(ThumpVoice.RIM, "FOUR RIM", "TUNE" to 0.42f, "DECAY" to 0.10f),
        p(ThumpVoice.RIM, "PEAK RIM", "TUNE" to 0.62f, "DECAY" to 0.26f),
        p(ThumpVoice.RIM, "RAGGA RIM", "TUNE" to 0.95f, "DECAY" to 0.02f),
        p(ThumpVoice.RIM, "BREAK RIM", "TUNE" to 0.75f, "DECAY" to 0.02f),
        p(ThumpVoice.RIM, "RUDE RIM", "TUNE" to 0.95f, "DECAY" to 0.18f),
        p(ThumpVoice.RIM, "WILD RIM", "TUNE" to 0.75f, "DECAY" to 0.18f),
        p(ThumpVoice.RIM, "DUB RIM", "TUNE" to 0.25f, "DECAY" to 0.57f),
        p(ThumpVoice.RIM, "ROOTS RIM", "TUNE" to 0.45f, "DECAY" to 0.73f),
        p(ThumpVoice.RIM, "SKANK RIM", "TUNE" to 0.25f, "DECAY" to 0.73f),
        p(ThumpVoice.RIM, "ECHO RIM", "TUNE" to 0.45f, "DECAY" to 0.57f),
    )
}
