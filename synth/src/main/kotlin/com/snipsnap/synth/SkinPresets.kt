package com.snipsnap.synth

/**
 * SKIN's factory presets — the U1 pass every other engine had already had.
 *
 * SKIN shipped as the eighth engine and the only one with no preset
 * library, which was not merely a gap in a list: [Skin.scramble] could not
 * do what its seven siblings do. `Thump.scramble` rolls *near a randomly
 * picked preset* (U2 of `docs/SYNTH_UPGRADE.md`); SKIN's had no pool to
 * pick from, so SCRAMBLE wandered around one point while every other
 * engine wandered around sixteen per voice. [Skin.scramble]'s own KDoc
 * said as much and named this file as the fix.
 *
 * Twenty per voice: four apiece across the same four genres THUMP uses —
 * BOOM BAP, HOUSE, JUNGLE, DUB — deliberately the same four, then four
 * BOLD (below), so a player who has met THUMP's list finds the same shelf
 * here rather than a second vocabulary.
 *
 * ## The values were measured, not guessed
 *
 * Every voice that has a `DrumClass` has a region of macro space where the
 * classifier still calls it what it claims to be, and that region was
 * mapped before a single preset was written — a 5-level grid over each
 * voice's tonal macros (PUNCH held at its default), then a finer sweep
 * across each boundary the grid found. What came back was unusually
 * clean: every voice's edge is governed by **one** macro, not a scattered
 * region, so the genre centres below only have to respect one inequality
 * each.
 *
 * | Voice | Safe while | Outside it, the classifier hears |
 * |---|---|---|
 * | KICK | (no constraint — all 125 grid points passed) | — |
 * | SNARE | `SNAP >= 0.20` | PERC |
 * | HAT_CLOSED | `TUNE >= 0.30` | SNARE |
 * | HAT_OPEN | `DECAY >= 0.25`, and not `TUNE` and `TONE` both at the floor | HAT_CLOSED below the decay edge; SNARE in that one corner |
 * | TOM | `TUNE` in `0.45 .. 0.85` | KICK below, PERC above |
 * | RIDE | `TUNE >= 0.40` and `TONE >= 0.40` | SNARE |
 *
 * The tables keep a margin inside every one of those edges rather than
 * sitting on it. **The coarse grid alone would have been misleading for
 * TOM**: at 0.2 resolution only `TUNE` 0.5 and 0.7 passed, which reads as
 * a sliver, and the boundary sweep showed the real band runs 0.45 to 0.85
 * with 0.9 the first failure. Sixteen presets crammed into an imaginary
 * sliver would have clustered for no reason.
 *
 * HAT_OPEN's decay edge is worth reading twice, because it is the
 * classifier being right rather than a limitation: a short open hat *is*
 * a closed hat, so `DECAY` below the edge classifies as HAT_CLOSED
 * exactly as it should.
 *
 * Its second edge was nearly written down wrong. One miss survives at
 * every `DECAY`, and the obvious reading - low `TUNE`, since that is what
 * turns HAT_CLOSED into a SNARE - is not what the corners say: measured,
 * `TUNE` 0.1 with `TONE` 0.9 classifies fine and only `TUNE` 0.1 *with*
 * `TONE` 0.1 misses. It is the joint corner, not either axis, and the
 * tables stay out of it by keeping both above their floors.
 *
 * ## SHAKER and STICK carry no classifier claim
 *
 * Neither has a dedicated `DrumClass`, the same carve-out `ThumpPresets`
 * makes for COWBELL and RIM — and measured rather than assumed, both
 * classify as SNARE at their own factory defaults. So their presets are
 * held to the clean-and-loud check only, which all 25 grid points of each
 * already passed.
 *
 * ## How the sixteen are laid out
 *
 * Per genre, a centre per macro and a fixed sign pattern across the four
 * variants — [ThumpPresets]'s own scheme, the same sign matrix, so the
 * four in a genre differ in at least two macros from every other and
 * `SkinPresetsTest`'s spread check has something real to measure. The
 * generation rule is recorded here; the values below are literals on
 * purpose. A table that regenerated itself at runtime could drift across
 * a classifier boundary the next time the rule was touched, and nothing
 * would say so until a preset stopped being a tom.
 *
 * ## BOLD, the last four of every voice
 *
 * Added with the sound-design macros (CLICK, DROP, RATTLE, SIZZLE, RING,
 * SWELL, GRAIN, BODY - see [Skin.macrosFor]), each pushing them toward an
 * end the first sixteen, which predate them, never reach. They keep the
 * safe-while table above, spell out every macro of their voice (so
 * `SkinPresetsTest`'s distance, which reads the first preset's keys from
 * both, never meets a key the second lacks), and were checked by the
 * classifier and sanity tests only - never heard, so they belong at the
 * next audition.
 *
 * Names never reference a real machine or model number
 * (`docs/SYNTH_ROADMAP.md`'s naming rule), machine-checked by
 * `SkinPresetsTest` through the shared blocklist.
 */
object SkinPresets {

    private fun p(voice: SkinVoice, name: String, vararg macros: Pair<String, Float>) =
        SkinPatch(name, voice, macros.toMap())

    fun forVoice(voice: SkinVoice): List<SkinPatch> = when (voice) {
        SkinVoice.KICK -> kickPresets
        SkinVoice.SNARE -> snarePresets
        SkinVoice.HAT_CLOSED -> hatclosedPresets
        SkinVoice.HAT_OPEN -> hatopenPresets
        SkinVoice.TOM -> tomPresets
        SkinVoice.RIDE -> ridePresets
        SkinVoice.SHAKER -> shakerPresets
        SkinVoice.STICK -> stickPresets
    }

    fun all(): List<SkinPatch> = SkinVoice.entries.flatMap { forVoice(it) }

    // Order within every list: BOOM BAP x4, HOUSE x4, JUNGLE x4, DUB x4.

    private val kickPresets = listOf(
        p(SkinVoice.KICK, "TAPE SKIN", "TUNE" to 0.38f, "DECAY" to 0.53f, "TONE" to 0.30f, "PUNCH" to 0.55f),
        p(SkinVoice.KICK, "OLD FLOOR", "TUNE" to 0.22f, "DECAY" to 0.53f, "TONE" to 0.50f, "PUNCH" to 0.35f),
        p(SkinVoice.KICK, "DUSTY SHELL", "TUNE" to 0.38f, "DECAY" to 0.37f, "TONE" to 0.50f, "PUNCH" to 0.35f),
        p(SkinVoice.KICK, "PAPER HEAD", "TUNE" to 0.22f, "DECAY" to 0.37f, "TONE" to 0.30f, "PUNCH" to 0.55f),
        p(SkinVoice.KICK, "ROUND ROOM", "TUNE" to 0.50f, "DECAY" to 0.38f, "TONE" to 0.45f, "PUNCH" to 0.72f),
        p(SkinVoice.KICK, "CLUB SHELL", "TUNE" to 0.34f, "DECAY" to 0.38f, "TONE" to 0.65f, "PUNCH" to 0.52f),
        p(SkinVoice.KICK, "TIGHT FLOOR", "TUNE" to 0.50f, "DECAY" to 0.22f, "TONE" to 0.65f, "PUNCH" to 0.52f),
        p(SkinVoice.KICK, "WARM PUNCH", "TUNE" to 0.34f, "DECAY" to 0.22f, "TONE" to 0.45f, "PUNCH" to 0.72f),
        p(SkinVoice.KICK, "FAST SHELL", "TUNE" to 0.28f, "DECAY" to 0.50f, "TONE" to 0.56f, "PUNCH" to 0.88f),
        p(SkinVoice.KICK, "RAGGA SKIN", "TUNE" to 0.12f, "DECAY" to 0.50f, "TONE" to 0.76f, "PUNCH" to 0.68f),
        p(SkinVoice.KICK, "BREAK DRUM", "TUNE" to 0.28f, "DECAY" to 0.34f, "TONE" to 0.76f, "PUNCH" to 0.68f),
        p(SkinVoice.KICK, "RUSH FLOOR", "TUNE" to 0.12f, "DECAY" to 0.34f, "TONE" to 0.56f, "PUNCH" to 0.88f),
        p(SkinVoice.KICK, "DEEP SHELL", "TUNE" to 0.23f, "DECAY" to 0.83f, "TONE" to 0.20f, "PUNCH" to 0.45f),
        p(SkinVoice.KICK, "LONG ROOM", "TUNE" to 0.07f, "DECAY" to 0.83f, "TONE" to 0.40f, "PUNCH" to 0.25f),
        p(SkinVoice.KICK, "ROOTS DRUM", "TUNE" to 0.23f, "DECAY" to 0.67f, "TONE" to 0.40f, "PUNCH" to 0.25f),
        p(SkinVoice.KICK, "SLOW SKIN", "TUNE" to 0.07f, "DECAY" to 0.67f, "TONE" to 0.20f, "PUNCH" to 0.45f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(SkinVoice.KICK, "HARD BEATER", "TUNE" to 0.70f, "DECAY" to 0.25f, "TONE" to 0.30f, "PUNCH" to 0.95f, "CLICK" to 1.00f, "DROP" to 0.20f),
        p(SkinVoice.KICK, "LOOSE HEAD", "TUNE" to 0.15f, "DECAY" to 0.75f, "TONE" to 0.35f, "PUNCH" to 0.60f, "CLICK" to 0.50f, "DROP" to 0.90f),
        p(SkinVoice.KICK, "FELT BOOM", "TUNE" to 0.10f, "DECAY" to 0.95f, "TONE" to 0.15f, "PUNCH" to 0.30f, "CLICK" to 0.00f, "DROP" to 0.40f),
        p(SkinVoice.KICK, "TENSION", "TUNE" to 0.60f, "DECAY" to 0.30f, "TONE" to 0.75f, "PUNCH" to 0.70f, "CLICK" to 0.80f, "DROP" to 0.60f),
    )

    private val snarePresets = listOf(
        p(SkinVoice.SNARE, "BRUSH WIRE", "TUNE" to 0.47f, "SNAP" to 0.60f, "DECAY" to 0.31f, "PUNCH" to 0.55f),
        p(SkinVoice.SNARE, "OLD WIRE", "TUNE" to 0.29f, "SNAP" to 0.60f, "DECAY" to 0.49f, "PUNCH" to 0.35f),
        p(SkinVoice.SNARE, "DUSTY RIM", "TUNE" to 0.47f, "SNAP" to 0.40f, "DECAY" to 0.49f, "PUNCH" to 0.35f),
        p(SkinVoice.SNARE, "SOFT CRACK", "TUNE" to 0.29f, "SNAP" to 0.40f, "DECAY" to 0.31f, "PUNCH" to 0.55f),
        p(SkinVoice.SNARE, "BRIGHT WIRE", "TUNE" to 0.61f, "SNAP" to 0.76f, "DECAY" to 0.19f, "PUNCH" to 0.72f),
        p(SkinVoice.SNARE, "CLUB CRACK", "TUNE" to 0.43f, "SNAP" to 0.76f, "DECAY" to 0.37f, "PUNCH" to 0.52f),
        p(SkinVoice.SNARE, "TIGHT SNARE", "TUNE" to 0.61f, "SNAP" to 0.56f, "DECAY" to 0.37f, "PUNCH" to 0.52f),
        p(SkinVoice.SNARE, "DISCO WIRE", "TUNE" to 0.43f, "SNAP" to 0.56f, "DECAY" to 0.19f, "PUNCH" to 0.72f),
        p(SkinVoice.SNARE, "RAGGA CRACK", "TUNE" to 0.79f, "SNAP" to 0.94f, "DECAY" to 0.29f, "PUNCH" to 0.88f),
        p(SkinVoice.SNARE, "FAST WIRE", "TUNE" to 0.61f, "SNAP" to 0.94f, "DECAY" to 0.47f, "PUNCH" to 0.68f),
        p(SkinVoice.SNARE, "BREAK SNARE", "TUNE" to 0.79f, "SNAP" to 0.74f, "DECAY" to 0.47f, "PUNCH" to 0.68f),
        p(SkinVoice.SNARE, "SHARP RIM", "TUNE" to 0.61f, "SNAP" to 0.74f, "DECAY" to 0.29f, "PUNCH" to 0.88f),
        p(SkinVoice.SNARE, "WIDE WIRE", "TUNE" to 0.39f, "SNAP" to 0.52f, "DECAY" to 0.56f, "PUNCH" to 0.45f),
        p(SkinVoice.SNARE, "LONG CRACK", "TUNE" to 0.21f, "SNAP" to 0.52f, "DECAY" to 0.74f, "PUNCH" to 0.25f),
        p(SkinVoice.SNARE, "ROOTS SNARE", "TUNE" to 0.39f, "SNAP" to 0.32f, "DECAY" to 0.74f, "PUNCH" to 0.25f),
        p(SkinVoice.SNARE, "ECHO WIRE", "TUNE" to 0.21f, "SNAP" to 0.32f, "DECAY" to 0.56f, "PUNCH" to 0.45f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(SkinVoice.SNARE, "DRY PAPER", "TUNE" to 0.30f, "SNAP" to 0.50f, "DECAY" to 0.35f, "PUNCH" to 0.70f, "RATTLE" to 0.10f, "SIZZLE" to 0.00f),
        p(SkinVoice.SNARE, "HISS TAIL", "TUNE" to 0.50f, "SNAP" to 0.65f, "DECAY" to 0.45f, "PUNCH" to 0.40f, "RATTLE" to 1.00f, "SIZZLE" to 0.80f),
        p(SkinVoice.SNARE, "THIN WIRE", "TUNE" to 0.70f, "SNAP" to 0.60f, "DECAY" to 0.20f, "PUNCH" to 0.75f, "RATTLE" to 0.60f, "SIZZLE" to 1.00f),
        p(SkinVoice.SNARE, "MUDDY WIRE", "TUNE" to 0.20f, "SNAP" to 0.55f, "DECAY" to 0.60f, "PUNCH" to 0.50f, "RATTLE" to 0.80f, "SIZZLE" to 0.15f),
    )

    private val hatclosedPresets = listOf(
        p(SkinVoice.HAT_CLOSED, "SHUT BRASS", "TUNE" to 0.54f, "DECAY" to 0.32f, "TONE" to 0.35f, "PUNCH" to 0.55f),
        p(SkinVoice.HAT_CLOSED, "DRY TICK", "TUNE" to 0.38f, "DECAY" to 0.32f, "TONE" to 0.55f, "PUNCH" to 0.35f),
        p(SkinVoice.HAT_CLOSED, "OLD TICK", "TUNE" to 0.54f, "DECAY" to 0.18f, "TONE" to 0.55f, "PUNCH" to 0.35f),
        p(SkinVoice.HAT_CLOSED, "CLOSED FOIL", "TUNE" to 0.38f, "DECAY" to 0.18f, "TONE" to 0.35f, "PUNCH" to 0.55f),
        p(SkinVoice.HAT_CLOSED, "CRISP TICK", "TUNE" to 0.66f, "DECAY" to 0.27f, "TONE" to 0.52f, "PUNCH" to 0.72f),
        p(SkinVoice.HAT_CLOSED, "CLUB SHUT", "TUNE" to 0.50f, "DECAY" to 0.27f, "TONE" to 0.72f, "PUNCH" to 0.52f),
        p(SkinVoice.HAT_CLOSED, "BRIGHT FOIL", "TUNE" to 0.66f, "DECAY" to 0.13f, "TONE" to 0.72f, "PUNCH" to 0.52f),
        p(SkinVoice.HAT_CLOSED, "TIGHT BRASS", "TUNE" to 0.50f, "DECAY" to 0.13f, "TONE" to 0.52f, "PUNCH" to 0.72f),
        p(SkinVoice.HAT_CLOSED, "FAST TICK", "TUNE" to 0.82f, "DECAY" to 0.39f, "TONE" to 0.66f, "PUNCH" to 0.88f),
        p(SkinVoice.HAT_CLOSED, "SHARP SHUT", "TUNE" to 0.66f, "DECAY" to 0.39f, "TONE" to 0.86f, "PUNCH" to 0.68f),
        p(SkinVoice.HAT_CLOSED, "RUSH FOIL", "TUNE" to 0.82f, "DECAY" to 0.25f, "TONE" to 0.86f, "PUNCH" to 0.68f),
        p(SkinVoice.HAT_CLOSED, "CUT BRASS", "TUNE" to 0.66f, "DECAY" to 0.25f, "TONE" to 0.66f, "PUNCH" to 0.88f),
        p(SkinVoice.HAT_CLOSED, "SOFT TICK", "TUNE" to 0.60f, "DECAY" to 0.47f, "TONE" to 0.30f, "PUNCH" to 0.45f),
        p(SkinVoice.HAT_CLOSED, "WARM SHUT", "TUNE" to 0.44f, "DECAY" to 0.47f, "TONE" to 0.50f, "PUNCH" to 0.25f),
        p(SkinVoice.HAT_CLOSED, "ROOTS FOIL", "TUNE" to 0.60f, "DECAY" to 0.33f, "TONE" to 0.50f, "PUNCH" to 0.25f),
        p(SkinVoice.HAT_CLOSED, "DUB TICK", "TUNE" to 0.44f, "DECAY" to 0.33f, "TONE" to 0.30f, "PUNCH" to 0.45f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(SkinVoice.HAT_CLOSED, "TRASH TICK", "TUNE" to 0.45f, "DECAY" to 0.35f, "TONE" to 0.50f, "PUNCH" to 0.60f, "RING" to 0.00f),
        p(SkinVoice.HAT_CLOSED, "GLASS TICK", "TUNE" to 0.75f, "DECAY" to 0.15f, "TONE" to 0.60f, "PUNCH" to 0.65f, "RING" to 1.00f),
        p(SkinVoice.HAT_CLOSED, "DUST TICK", "TUNE" to 0.40f, "DECAY" to 0.55f, "TONE" to 0.25f, "PUNCH" to 0.50f, "RING" to 0.30f),
        p(SkinVoice.HAT_CLOSED, "PING TICK", "TUNE" to 0.90f, "DECAY" to 0.30f, "TONE" to 0.80f, "PUNCH" to 0.85f, "RING" to 0.95f),
    )

    private val hatopenPresets = listOf(
        p(SkinVoice.HAT_OPEN, "LOOSE BRASS", "TUNE" to 0.54f, "DECAY" to 0.58f, "TONE" to 0.35f, "PUNCH" to 0.55f),
        p(SkinVoice.HAT_OPEN, "OLD WASH", "TUNE" to 0.38f, "DECAY" to 0.58f, "TONE" to 0.55f, "PUNCH" to 0.35f),
        p(SkinVoice.HAT_OPEN, "DUSTY OPEN", "TUNE" to 0.54f, "DECAY" to 0.42f, "TONE" to 0.55f, "PUNCH" to 0.35f),
        p(SkinVoice.HAT_OPEN, "SOFT SPILL", "TUNE" to 0.38f, "DECAY" to 0.42f, "TONE" to 0.35f, "PUNCH" to 0.55f),
        p(SkinVoice.HAT_OPEN, "CLUB WASH", "TUNE" to 0.66f, "DECAY" to 0.52f, "TONE" to 0.52f, "PUNCH" to 0.72f),
        p(SkinVoice.HAT_OPEN, "BRIGHT OPEN", "TUNE" to 0.50f, "DECAY" to 0.52f, "TONE" to 0.72f, "PUNCH" to 0.52f),
        p(SkinVoice.HAT_OPEN, "WIDE BRASS", "TUNE" to 0.66f, "DECAY" to 0.36f, "TONE" to 0.72f, "PUNCH" to 0.52f),
        p(SkinVoice.HAT_OPEN, "PEAK SPILL", "TUNE" to 0.50f, "DECAY" to 0.36f, "TONE" to 0.52f, "PUNCH" to 0.72f),
        p(SkinVoice.HAT_OPEN, "FAST WASH", "TUNE" to 0.82f, "DECAY" to 0.66f, "TONE" to 0.66f, "PUNCH" to 0.88f),
        p(SkinVoice.HAT_OPEN, "SHARP OPEN", "TUNE" to 0.66f, "DECAY" to 0.66f, "TONE" to 0.86f, "PUNCH" to 0.68f),
        p(SkinVoice.HAT_OPEN, "RUSH BRASS", "TUNE" to 0.82f, "DECAY" to 0.50f, "TONE" to 0.86f, "PUNCH" to 0.68f),
        p(SkinVoice.HAT_OPEN, "CUT SPILL", "TUNE" to 0.66f, "DECAY" to 0.50f, "TONE" to 0.66f, "PUNCH" to 0.88f),
        p(SkinVoice.HAT_OPEN, "LONG WASH", "TUNE" to 0.60f, "DECAY" to 0.88f, "TONE" to 0.30f, "PUNCH" to 0.45f),
        p(SkinVoice.HAT_OPEN, "DEEP OPEN", "TUNE" to 0.44f, "DECAY" to 0.88f, "TONE" to 0.50f, "PUNCH" to 0.25f),
        p(SkinVoice.HAT_OPEN, "ROOTS BRASS", "TUNE" to 0.60f, "DECAY" to 0.72f, "TONE" to 0.50f, "PUNCH" to 0.25f),
        p(SkinVoice.HAT_OPEN, "ECHO SPILL", "TUNE" to 0.44f, "DECAY" to 0.72f, "TONE" to 0.30f, "PUNCH" to 0.45f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(SkinVoice.HAT_OPEN, "TRASH WASH", "TUNE" to 0.50f, "DECAY" to 0.75f, "TONE" to 0.50f, "PUNCH" to 0.60f, "RING" to 0.00f),
        p(SkinVoice.HAT_OPEN, "SINGING HAT", "TUNE" to 0.70f, "DECAY" to 0.80f, "TONE" to 0.60f, "PUNCH" to 0.55f, "RING" to 1.00f),
        p(SkinVoice.HAT_OPEN, "DUST WASH", "TUNE" to 0.30f, "DECAY" to 0.35f, "TONE" to 0.50f, "PUNCH" to 0.40f, "RING" to 0.30f),
        p(SkinVoice.HAT_OPEN, "METAL SPLASH", "TUNE" to 0.85f, "DECAY" to 0.95f, "TONE" to 0.75f, "PUNCH" to 0.70f, "RING" to 0.90f),
    )

    private val tomPresets = listOf(
        p(SkinVoice.TOM, "LOW WOOD", "TUNE" to 0.64f, "DECAY" to 0.59f, "TONE" to 0.35f, "PUNCH" to 0.55f),
        p(SkinVoice.TOM, "OLD TOM", "TUNE" to 0.52f, "DECAY" to 0.59f, "TONE" to 0.55f, "PUNCH" to 0.35f),
        p(SkinVoice.TOM, "DUSTY DRUM", "TUNE" to 0.64f, "DECAY" to 0.41f, "TONE" to 0.55f, "PUNCH" to 0.35f),
        p(SkinVoice.TOM, "SOFT SHELL", "TUNE" to 0.52f, "DECAY" to 0.41f, "TONE" to 0.35f, "PUNCH" to 0.55f),
        p(SkinVoice.TOM, "ROUND TOM", "TUNE" to 0.70f, "DECAY" to 0.47f, "TONE" to 0.48f, "PUNCH" to 0.72f),
        p(SkinVoice.TOM, "CLUB WOOD", "TUNE" to 0.58f, "DECAY" to 0.47f, "TONE" to 0.68f, "PUNCH" to 0.52f),
        p(SkinVoice.TOM, "TIGHT DRUM", "TUNE" to 0.70f, "DECAY" to 0.29f, "TONE" to 0.68f, "PUNCH" to 0.52f),
        p(SkinVoice.TOM, "WARM SHELL", "TUNE" to 0.58f, "DECAY" to 0.29f, "TONE" to 0.48f, "PUNCH" to 0.72f),
        p(SkinVoice.TOM, "FAST TOM", "TUNE" to 0.84f, "DECAY" to 0.57f, "TONE" to 0.64f, "PUNCH" to 0.88f),
        p(SkinVoice.TOM, "SHARP WOOD", "TUNE" to 0.72f, "DECAY" to 0.57f, "TONE" to 0.84f, "PUNCH" to 0.68f),
        p(SkinVoice.TOM, "RUSH DRUM", "TUNE" to 0.84f, "DECAY" to 0.39f, "TONE" to 0.84f, "PUNCH" to 0.68f),
        p(SkinVoice.TOM, "HIGH SHELL", "TUNE" to 0.72f, "DECAY" to 0.39f, "TONE" to 0.64f, "PUNCH" to 0.88f),
        p(SkinVoice.TOM, "LONG TOM", "TUNE" to 0.66f, "DECAY" to 0.87f, "TONE" to 0.30f, "PUNCH" to 0.45f),
        p(SkinVoice.TOM, "DEEP WOOD", "TUNE" to 0.54f, "DECAY" to 0.87f, "TONE" to 0.50f, "PUNCH" to 0.25f),
        p(SkinVoice.TOM, "ROOTS DRUM", "TUNE" to 0.66f, "DECAY" to 0.69f, "TONE" to 0.50f, "PUNCH" to 0.25f),
        p(SkinVoice.TOM, "ECHO SHELL", "TUNE" to 0.54f, "DECAY" to 0.69f, "TONE" to 0.30f, "PUNCH" to 0.45f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(SkinVoice.TOM, "BEND TOM", "TUNE" to 0.50f, "DECAY" to 0.70f, "TONE" to 0.45f, "PUNCH" to 0.60f, "DROP" to 1.00f, "CLICK" to 0.00f),
        p(SkinVoice.TOM, "STICK SHELL", "TUNE" to 0.55f, "DECAY" to 0.45f, "TONE" to 0.65f, "PUNCH" to 0.75f, "DROP" to 0.20f, "CLICK" to 0.90f),
        p(SkinVoice.TOM, "DEEP BEND", "TUNE" to 0.50f, "DECAY" to 0.85f, "TONE" to 0.15f, "PUNCH" to 0.45f, "DROP" to 0.70f, "CLICK" to 0.30f),
        p(SkinVoice.TOM, "TIGHT HEAD", "TUNE" to 0.60f, "DECAY" to 0.22f, "TONE" to 0.80f, "PUNCH" to 0.95f, "DROP" to 0.30f, "CLICK" to 0.60f),
    )

    private val ridePresets = listOf(
        p(SkinVoice.RIDE, "OLD BELL", "TUNE" to 0.64f, "DECAY" to 0.64f, "TONE" to 0.48f, "PUNCH" to 0.55f),
        p(SkinVoice.RIDE, "DUSTY RIDE", "TUNE" to 0.48f, "DECAY" to 0.64f, "TONE" to 0.64f, "PUNCH" to 0.35f),
        p(SkinVoice.RIDE, "SOFT PING", "TUNE" to 0.64f, "DECAY" to 0.46f, "TONE" to 0.64f, "PUNCH" to 0.35f),
        p(SkinVoice.RIDE, "WARM BOW", "TUNE" to 0.48f, "DECAY" to 0.46f, "TONE" to 0.48f, "PUNCH" to 0.55f),
        p(SkinVoice.RIDE, "CLUB RIDE", "TUNE" to 0.74f, "DECAY" to 0.57f, "TONE" to 0.58f, "PUNCH" to 0.72f),
        p(SkinVoice.RIDE, "BRIGHT BELL", "TUNE" to 0.58f, "DECAY" to 0.57f, "TONE" to 0.74f, "PUNCH" to 0.52f),
        p(SkinVoice.RIDE, "TIGHT PING", "TUNE" to 0.74f, "DECAY" to 0.39f, "TONE" to 0.74f, "PUNCH" to 0.52f),
        p(SkinVoice.RIDE, "PEAK BOW", "TUNE" to 0.58f, "DECAY" to 0.39f, "TONE" to 0.58f, "PUNCH" to 0.72f),
        p(SkinVoice.RIDE, "FAST RIDE", "TUNE" to 0.92f, "DECAY" to 0.51f, "TONE" to 0.72f, "PUNCH" to 0.88f),
        p(SkinVoice.RIDE, "SHARP BELL", "TUNE" to 0.76f, "DECAY" to 0.51f, "TONE" to 0.88f, "PUNCH" to 0.68f),
        p(SkinVoice.RIDE, "RUSH PING", "TUNE" to 0.92f, "DECAY" to 0.33f, "TONE" to 0.88f, "PUNCH" to 0.68f),
        p(SkinVoice.RIDE, "CUT BOW", "TUNE" to 0.76f, "DECAY" to 0.33f, "TONE" to 0.72f, "PUNCH" to 0.88f),
        p(SkinVoice.RIDE, "LONG RIDE", "TUNE" to 0.68f, "DECAY" to 0.89f, "TONE" to 0.48f, "PUNCH" to 0.45f),
        p(SkinVoice.RIDE, "DEEP BELL", "TUNE" to 0.52f, "DECAY" to 0.89f, "TONE" to 0.64f, "PUNCH" to 0.25f),
        p(SkinVoice.RIDE, "ROOTS PING", "TUNE" to 0.68f, "DECAY" to 0.71f, "TONE" to 0.64f, "PUNCH" to 0.25f),
        p(SkinVoice.RIDE, "ECHO BOW", "TUNE" to 0.52f, "DECAY" to 0.71f, "TONE" to 0.48f, "PUNCH" to 0.45f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(SkinVoice.RIDE, "SIZZLE RIDE", "TUNE" to 0.70f, "DECAY" to 0.75f, "TONE" to 0.65f, "PUNCH" to 0.55f, "SIZZLE" to 1.00f),
        p(SkinVoice.RIDE, "RIVET WASH", "TUNE" to 0.60f, "DECAY" to 0.90f, "TONE" to 0.70f, "PUNCH" to 0.45f, "SIZZLE" to 0.70f),
        p(SkinVoice.RIDE, "DRY RIVET", "TUNE" to 0.85f, "DECAY" to 0.40f, "TONE" to 0.65f, "PUNCH" to 0.70f, "SIZZLE" to 0.50f),
        p(SkinVoice.RIDE, "JAZZ RIDE", "TUNE" to 0.55f, "DECAY" to 0.85f, "TONE" to 0.55f, "PUNCH" to 0.60f, "SIZZLE" to 0.30f),
    )

    private val shakerPresets = listOf(
        p(SkinVoice.SHAKER, "SOFT SEED", "TONE" to 0.55f, "DECAY" to 0.44f, "PUNCH" to 0.35f),
        p(SkinVoice.SHAKER, "OLD SHAKE", "TONE" to 0.35f, "DECAY" to 0.44f, "PUNCH" to 0.55f),
        p(SkinVoice.SHAKER, "DUSTY POD", "TONE" to 0.55f, "DECAY" to 0.26f, "PUNCH" to 0.55f),
        p(SkinVoice.SHAKER, "SLOW SAND", "TONE" to 0.35f, "DECAY" to 0.26f, "PUNCH" to 0.35f),
        p(SkinVoice.SHAKER, "CLUB SHAKE", "TONE" to 0.66f, "DECAY" to 0.37f, "PUNCH" to 0.52f),
        p(SkinVoice.SHAKER, "BRIGHT POD", "TONE" to 0.46f, "DECAY" to 0.37f, "PUNCH" to 0.72f),
        p(SkinVoice.SHAKER, "TIGHT SAND", "TONE" to 0.66f, "DECAY" to 0.19f, "PUNCH" to 0.72f),
        p(SkinVoice.SHAKER, "PEAK SEED", "TONE" to 0.46f, "DECAY" to 0.19f, "PUNCH" to 0.52f),
        p(SkinVoice.SHAKER, "FAST SHAKE", "TONE" to 0.88f, "DECAY" to 0.53f, "PUNCH" to 0.68f),
        p(SkinVoice.SHAKER, "SHARP POD", "TONE" to 0.68f, "DECAY" to 0.53f, "PUNCH" to 0.88f),
        p(SkinVoice.SHAKER, "RUSH SAND", "TONE" to 0.88f, "DECAY" to 0.35f, "PUNCH" to 0.88f),
        p(SkinVoice.SHAKER, "CUT SEED", "TONE" to 0.68f, "DECAY" to 0.35f, "PUNCH" to 0.68f),
        p(SkinVoice.SHAKER, "LONG SHAKE", "TONE" to 0.50f, "DECAY" to 0.71f, "PUNCH" to 0.25f),
        p(SkinVoice.SHAKER, "DEEP POD", "TONE" to 0.30f, "DECAY" to 0.71f, "PUNCH" to 0.45f),
        p(SkinVoice.SHAKER, "ROOTS SAND", "TONE" to 0.50f, "DECAY" to 0.53f, "PUNCH" to 0.45f),
        p(SkinVoice.SHAKER, "ECHO SEED", "TONE" to 0.30f, "DECAY" to 0.53f, "PUNCH" to 0.25f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(SkinVoice.SHAKER, "SLOW SHAKE", "TONE" to 0.60f, "DECAY" to 0.60f, "PUNCH" to 0.30f, "SWELL" to 1.00f, "GRAIN" to 0.20f),
        p(SkinVoice.SHAKER, "SEED POD", "TONE" to 0.75f, "DECAY" to 0.45f, "PUNCH" to 0.60f, "SWELL" to 0.20f, "GRAIN" to 1.00f),
        p(SkinVoice.SHAKER, "SAND SWELL", "TONE" to 0.40f, "DECAY" to 0.75f, "PUNCH" to 0.15f, "SWELL" to 0.70f, "GRAIN" to 0.50f),
        p(SkinVoice.SHAKER, "BEAD SNAP", "TONE" to 0.80f, "DECAY" to 0.25f, "PUNCH" to 0.80f, "SWELL" to 0.00f, "GRAIN" to 0.80f),
    )

    private val stickPresets = listOf(
        p(SkinVoice.STICK, "OLD STICK", "TUNE" to 0.55f, "DECAY" to 0.44f, "PUNCH" to 0.35f),
        p(SkinVoice.STICK, "DRY CLICK", "TUNE" to 0.35f, "DECAY" to 0.44f, "PUNCH" to 0.55f),
        p(SkinVoice.STICK, "DUSTY TAP", "TUNE" to 0.55f, "DECAY" to 0.26f, "PUNCH" to 0.55f),
        p(SkinVoice.STICK, "SOFT KNOCK", "TUNE" to 0.35f, "DECAY" to 0.26f, "PUNCH" to 0.35f),
        p(SkinVoice.STICK, "CLUB CLICK", "TUNE" to 0.66f, "DECAY" to 0.37f, "PUNCH" to 0.52f),
        p(SkinVoice.STICK, "BRIGHT TAP", "TUNE" to 0.46f, "DECAY" to 0.37f, "PUNCH" to 0.72f),
        p(SkinVoice.STICK, "TIGHT STICK", "TUNE" to 0.66f, "DECAY" to 0.19f, "PUNCH" to 0.72f),
        p(SkinVoice.STICK, "PEAK KNOCK", "TUNE" to 0.46f, "DECAY" to 0.19f, "PUNCH" to 0.52f),
        p(SkinVoice.STICK, "FAST CLICK", "TUNE" to 0.88f, "DECAY" to 0.53f, "PUNCH" to 0.68f),
        p(SkinVoice.STICK, "SHARP TAP", "TUNE" to 0.68f, "DECAY" to 0.53f, "PUNCH" to 0.88f),
        p(SkinVoice.STICK, "RUSH STICK", "TUNE" to 0.88f, "DECAY" to 0.35f, "PUNCH" to 0.88f),
        p(SkinVoice.STICK, "CUT KNOCK", "TUNE" to 0.68f, "DECAY" to 0.35f, "PUNCH" to 0.68f),
        p(SkinVoice.STICK, "LONG CLICK", "TUNE" to 0.50f, "DECAY" to 0.67f, "PUNCH" to 0.25f),
        p(SkinVoice.STICK, "DEEP TAP", "TUNE" to 0.30f, "DECAY" to 0.67f, "PUNCH" to 0.45f),
        p(SkinVoice.STICK, "ROOTS STICK", "TUNE" to 0.50f, "DECAY" to 0.49f, "PUNCH" to 0.45f),
        p(SkinVoice.STICK, "ECHO KNOCK", "TUNE" to 0.30f, "DECAY" to 0.49f, "PUNCH" to 0.25f),
        // BOLD x4: the sound-design macros, pushed (see the KDoc).
        p(SkinVoice.STICK, "SHELL KNOCK", "TUNE" to 0.45f, "DECAY" to 0.55f, "PUNCH" to 0.60f, "BODY" to 0.80f, "CLICK" to 0.20f),
        p(SkinVoice.STICK, "WOOD CRACK", "TUNE" to 0.75f, "DECAY" to 0.30f, "PUNCH" to 0.80f, "BODY" to 0.00f, "CLICK" to 1.00f),
        p(SkinVoice.STICK, "RIMSHOT", "TUNE" to 0.60f, "DECAY" to 0.55f, "PUNCH" to 0.60f, "BODY" to 0.50f, "CLICK" to 0.60f),
        p(SkinVoice.STICK, "LOG DRUM", "TUNE" to 0.30f, "DECAY" to 0.80f, "PUNCH" to 0.35f, "BODY" to 1.00f, "CLICK" to 0.00f),
    )
}
