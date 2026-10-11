package com.snipsnap.synth

/** Musical families in the initial 36-recipe audition roster. */
enum class TanglePresetGroup { BASS, LEAD, PULSE, METALLIC, EXPERIMENTAL }

/**
 * TANGLE's first listening roster: 10 basses, 8 leads, 6 pulses, 6 metallic
 * tones and 6 experimental knots. HOLLOW belongs to the lead family; it is
 * the quieter fixed-interval option within the shared oscillator architecture.
 * Every voice has a held example, and the clean recipes give the five timbral
 * controls room to move. These recipes await the owner's listening verdict.
 */
object TanglePresets {
    private fun p(voice: TangleVoice, name: String, vararg macros: Pair<String, Float>) =
        TanglePatch(name, voice, Tangle.defaults(voice) + macros.toMap())

    private val bass = listOf(
        p(TangleVoice.BASS, "Root Knot"),
        p(TangleVoice.BASS, "Soft Pursuit", "TANGLE" to .10f, "CHASE" to .18f, "CATCH" to .15f, "BITE" to .18f, "WEIGHT" to .70f),
        p(TangleVoice.BASS, "Quiet Trap", "TANGLE" to .18f, "CHASE" to .42f, "CATCH" to .62f, "BITE" to .22f, "WEIGHT" to .85f),
        p(TangleVoice.BASS, "Low Wrestle", "TANGLE" to .58f, "CHASE" to .12f, "CATCH" to .40f, "BITE" to .42f, "WEIGHT" to .95f),
        p(TangleVoice.BASS, "Lean Runner", "TANGLE" to .28f, "CHASE" to .72f, "CATCH" to .12f, "BITE" to .48f, "WEIGHT" to .20f),
        p(TangleVoice.BASS, "Caught Root", "TANGLE" to .38f, "CHASE" to .35f, "CATCH" to .82f, "BITE" to .35f, "WEIGHT" to .90f),
        p(TangleVoice.BASS, "Free Growl", "TANGLE" to .65f, "CHASE" to .62f, "CATCH" to 0f, "BITE" to .65f, "WEIGHT" to .65f),
        p(TangleVoice.BASS, "Round Pair", "TANGLE" to .06f, "CHASE" to .50f, "CATCH" to 0f, "BITE" to .12f, "WEIGHT" to .60f),
        p(TangleVoice.BASS, "Held Root", "TANGLE" to .30f, "CHASE" to .25f, "CATCH" to .55f, "BITE" to .28f, "WEIGHT" to .85f, "HOLD" to 1f),
        p(TangleVoice.BASS, "Endless Pursuit", "TANGLE" to .52f, "CHASE" to .68f, "CATCH" to .70f, "BITE" to .45f, "WEIGHT" to .70f, "HOLD" to 1f),
    )

    private val lead = listOf(
        p(TangleVoice.LEAD, "Catch Wire"),
        p(TangleVoice.LEAD, "Plain Thread", "TANGLE" to .10f, "CHASE" to .50f, "CATCH" to .12f, "BITE" to .42f, "WEIGHT" to .40f),
        p(TangleVoice.LEAD, "Tearing Line", "TANGLE" to .70f, "CHASE" to .85f, "CATCH" to .58f, "BITE" to .88f, "WEIGHT" to .22f),
        p(TangleVoice.LEAD, "Returning Edge", "TANGLE" to .42f, "CHASE" to .20f, "CATCH" to .88f, "BITE" to .75f, "WEIGHT" to .36f),
        p(TangleVoice.LEAD, "Bright Escape", "TANGLE" to .54f, "CHASE" to .75f, "CATCH" to .08f, "BITE" to .92f, "WEIGHT" to .18f),
        p(TangleVoice.LEAD, "Long Escape", "TANGLE" to .45f, "CHASE" to .70f, "CATCH" to .65f, "BITE" to .68f, "WEIGHT" to .32f, "HOLD" to 1f),
        p(TangleVoice.HOLLOW, "Hollow Pair", "TANGLE" to .12f, "CHASE" to .50f, "CATCH" to .15f, "BITE" to .30f, "WEIGHT" to .30f),
        p(TangleVoice.HOLLOW, "Held Interval", "TANGLE" to .30f, "CHASE" to .38f, "CATCH" to .45f, "BITE" to .42f, "WEIGHT" to .40f, "HOLD" to 1f),
    )

    private val pulse = listOf(
        p(TangleVoice.PULSE, "Crawling Teeth"),
        p(TangleVoice.PULSE, "Slow Encounter", "TANGLE" to .20f, "CHASE" to .18f, "CATCH" to .32f, "BITE" to .28f, "WEIGHT" to .55f),
        p(TangleVoice.PULSE, "Crossing Steps", "TANGLE" to .58f, "CHASE" to .72f, "CATCH" to .38f, "BITE" to .58f, "WEIGHT" to .35f),
        p(TangleVoice.PULSE, "Narrow Chase", "TANGLE" to .48f, "CHASE" to .92f, "CATCH" to .72f, "BITE" to .62f, "WEIGHT" to .25f),
        p(TangleVoice.PULSE, "Open Current", "TANGLE" to .34f, "CHASE" to .50f, "CATCH" to 0f, "BITE" to .38f, "WEIGHT" to .60f),
        p(TangleVoice.PULSE, "Held Crawl", "TANGLE" to .48f, "CHASE" to .32f, "CATCH" to .65f, "BITE" to .48f, "WEIGHT" to .48f, "HOLD" to 1f),
    )

    private val metallic = listOf(
        p(TangleVoice.METAL, "Metal Recoil"),
        p(TangleVoice.METAL, "Soft Alloy", "TANGLE" to .30f, "CHASE" to .45f, "CATCH" to .25f, "BITE" to .48f, "WEIGHT" to .40f),
        p(TangleVoice.METAL, "Wire Encounter", "TANGLE" to .62f, "CHASE" to .22f, "CATCH" to .70f, "BITE" to .68f, "WEIGHT" to .28f),
        p(TangleVoice.METAL, "Bright Lattice", "TANGLE" to .88f, "CHASE" to .80f, "CATCH" to .35f, "BITE" to .92f, "WEIGHT" to .18f),
        p(TangleVoice.METAL, "Heavy Sideband", "TANGLE" to .68f, "CHASE" to .55f, "CATCH" to .52f, "BITE" to .72f, "WEIGHT" to .75f),
        p(TangleVoice.METAL, "Held Recoil", "TANGLE" to .72f, "CHASE" to .65f, "CATCH" to .62f, "BITE" to .78f, "WEIGHT" to .30f, "HOLD" to 1f),
    )

    private val experimental = listOf(
        p(TangleVoice.KNOT, "Tight Knot"),
        p(TangleVoice.KNOT, "Opposing Pull", "TANGLE" to .90f, "CHASE" to .08f, "CATCH" to .55f, "BITE" to .72f, "WEIGHT" to .45f),
        p(TangleVoice.KNOT, "Loose Ends", "TANGLE" to .65f, "CHASE" to .78f, "CATCH" to 0f, "BITE" to .48f, "WEIGHT" to .50f),
        p(TangleVoice.KNOT, "Rooted Tangle", "TANGLE" to 1f, "CHASE" to .50f, "CATCH" to .90f, "BITE" to .82f, "WEIGHT" to .95f),
        p(TangleVoice.KNOT, "Held Struggle", "TANGLE" to .85f, "CHASE" to .50f, "CATCH" to .80f, "BITE" to .60f, "WEIGHT" to .60f, "HOLD" to 1f),
        p(TangleVoice.KNOT, "Quiet Capture", "TANGLE" to .28f, "CHASE" to .62f, "CATCH" to .90f, "BITE" to .28f, "WEIGHT" to .70f, "HOLD" to 1f),
    )

    fun forGroup(group: TanglePresetGroup): List<TanglePatch> = when (group) {
        TanglePresetGroup.BASS -> bass
        TanglePresetGroup.LEAD -> lead
        TanglePresetGroup.PULSE -> pulse
        TanglePresetGroup.METALLIC -> metallic
        TanglePresetGroup.EXPERIMENTAL -> experimental
    }

    fun all(): List<TanglePatch> = TanglePresetGroup.entries.flatMap { forGroup(it) }

    fun forVoice(voice: TangleVoice): List<TanglePatch> = all().filter { it.voice == voice }

    fun groupFor(patch: TanglePatch): TanglePresetGroup = when (patch.voice) {
        TangleVoice.BASS -> TanglePresetGroup.BASS
        TangleVoice.LEAD, TangleVoice.HOLLOW -> TanglePresetGroup.LEAD
        TangleVoice.PULSE -> TanglePresetGroup.PULSE
        TangleVoice.METAL -> TanglePresetGroup.METALLIC
        TangleVoice.KNOT -> TanglePresetGroup.EXPERIMENTAL
    }
}
