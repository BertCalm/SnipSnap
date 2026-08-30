package com.snipsnap.shell

import com.snipsnap.synth.Eras

/**
 * The PAD SHEET's TREATMENT card, as data.
 *
 * The design draws four segments; the DSP behind three of them already
 * exists as Time Machine eras, so this is a vocabulary mapping and nothing
 * more. It lives in `:shell` because the words are the app's, not the
 * engine's — [Eras] should never learn what a "segment" is.
 *
 * The card drives [KitBuilderModel.eraPad], not `treatPad`: an era ages
 * every file a pad references, velocity layers included, so a pad with
 * ghost notes ages in all its zones instead of growing an untreated
 * underside.
 */
object PadSheet {

    /** The leftmost segment: no treatment at all. */
    const val NONE = "NONE"

    /** Segment order, left to right, as the card draws it. */
    val SEGMENTS: List<String> = listOf(NONE, "CRUSH", "TAPE", "DIRT")

    /** Where the AMT stepper sits when a pad has never been treated. */
    const val DEFAULT_AMOUNT = 0.35f

    private val ERA_FOR: Map<String, String> = mapOf(
        // 12-bit at 26.04 kHz — the crunchiest of the four.
        "CRUSH" to "sp1200",
        // The era named for exactly this.
        "TAPE" to "tape",
        // 12-bit, µ-law companding, an 11 kHz lid: gritty and warm.
        "DIRT" to "mpc60",
        // "phone" stays unmapped — a telephone band-limit is not a pad
        // treatment. It remains reachable from the CLI's `era` verb.
    )

    /**
     * The era behind a segment, or null for [NONE]. Throws on a segment the
     * card does not draw — a typo should not silently become "no treatment".
     */
    fun eraFor(segment: String): String? {
        require(segment in SEGMENTS) {
            "unknown pad-sheet segment '$segment' - the card draws: ${SEGMENTS.joinToString(", ")}"
        }
        return ERA_FOR[segment]
    }

    /**
     * The inverse of [eraFor]: which segment lights up for a pad aged by
     * [era], or null when no segment draws it.
     *
     * Null is a real answer, not a gap to fill in later — "phone" (and any
     * era a future Time Machine adds without a card segment) is aged
     * material the PAD SHEET can show but not select: THE PHONE RULING is
     * that an unmapped era lights *no* segment on the TREATMENT card and
     * lets the provenance line say what actually happened ("AGED: PHONE")
     * instead of lying with NONE, which would claim the pad is untreated.
     */
    fun segmentFor(era: String): String? = ERA_FOR.entries.firstOrNull { it.value == era }?.key
}
