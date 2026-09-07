package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Eras
import com.snipsnap.synth.Treatments

/**
 * The PAD SHEET's TREATMENT card, as data.
 *
 * Four rows. The first is the design's four segments, whose DSP is the
 * Time Machine's eras; the rest are the rack's named characters — the FX
 * chains `treat` and bank B already speak — reachable one tap at a time;
 * row four ends on TUNE and row five is the keyed family (`Keyed`), the
 * treatments that read the kit's own key.
 * Every row is a vocabulary mapping and nothing more: the words
 * are the app's, not the engine's, so [Eras] and [Treatments] never
 * learn what a "segment" is.
 *
 * The card drives the whole-pad doors — [KitBuilderModel.eraPad],
 * [KitBuilderModel.characterPad] and [KitBuilderModel.keyedPad], never
 * `treatPad` — because a pad
 * treatment is whole-pad character: every file the pad references
 * (velocity layers included) changes together, so a layered snare
 * never grows an untreated underside.
 */
object PadSheet {

    /** The leftmost segment: no treatment at all. */
    const val NONE = "NONE"

    /** Row one, left to right, as the card draws it — the eras. */
    val SEGMENTS: List<String> = listOf(NONE, "CRUSH", "TAPE", "DIRT")

    /** Row two — the rack's characters. */
    val CHARACTER_SEGMENTS: List<String> = listOf("SMEAR", "SLAP", "WASH", "PUNCH")

    /** Row three — the anatomy and the transport. */
    val MORE_SEGMENTS: List<String> = listOf("GHOST", "STOP", "START", "FLIP")

    /** Row four — the room, the tape's last two, and the key. */
    val EXTRA_SEGMENTS: List<String> = listOf("SKIM", "DUB", "SWELL", "TUNE")

    /** Row five — the treatments that read the kit itself: its key, its tempo. */
    val KEYED_SEGMENTS: List<String> = listOf("BODY", "WOBBLE", "ETERNAL")

    /** Row four's last word: the spectral retune, the first treatment that reads the kit's key. */
    const val TUNE = "TUNE"

    /** All rows, in drawing order. */
    val ROWS: List<List<String>> = listOf(SEGMENTS, CHARACTER_SEGMENTS, MORE_SEGMENTS, EXTRA_SEGMENTS, KEYED_SEGMENTS)

    /** Every segment on any row. */
    val ALL_SEGMENTS: List<String> get() = ROWS.flatten()

    /** Where the AMT stepper sits when a pad has never been treated. */
    const val DEFAULT_AMOUNT = 0.35f

    /** What a segment does when tapped: age the pad, or run it through a chain. */
    sealed interface Treatment {
        val name: String

        /** A Time Machine era — `Eras.names`. */
        data class Era(override val name: String) : Treatment

        /** A rack character — `Treatments.names`. */
        data class Character(override val name: String) : Treatment

        /** A keyed treatment — `KitBuilderModel.keyedPad`, one of `Keyed.NAMES`: it reads the kit's key. */
        data class Keyed(override val name: String) : Treatment
    }

    /** What the card found on a pad: the treatment, its AMT, and the segment that draws it (null = none does). */
    data class Applied(val treatment: Treatment, val amount: Float, val segment: String?)

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

    private val CHARACTER_FOR: Map<String, String> = mapOf(
        // The attack gone, the wash kept: the hit played as its own tail.
        "SMEAR" to "smeared",
        // One tape-delay repeat.
        "SLAP" to "slapback",
        // The spring, generously.
        "WASH" to "washed",
        // Squash into crunch: the transient pops.
        "PUNCH" to "punched",
        // The tone and the attack gone, the breath kept.
        "GHOST" to "ghosted",
        // The capstan lets go; the reel spins up.
        "STOP" to "stopped",
        "START" to "started",
        // The flip is a structural switch: AMT grades only its spring tail.
        "FLIP" to "reversed",
        // The banded smear: the click goes, the thump stays.
        "SKIM" to "skimmed",
        // A dub of a dub of a dub.
        "DUB" to "dubbed",
        // The sound arrives before it strikes.
        "SWELL" to "swelled",
        // "crushed" stays off the card: CRUSH already draws the crunchier
        // era. It remains reachable from `treat`.
    )

    private val KEYED_FOR: Map<String, String> = mapOf(
        // Every partial talked into the key.
        "TUNE" to "retuned",
        // A bank of resonators tuned to the key, struck by the hit.
        "BODY" to "bodied",
        // A filter sweep on the kit's own grid.
        "WOBBLE" to "wobbled",
        // The attack kept, the tail slowed toward forever.
        "ETERNAL" to "eternal",
    )

    /**
     * The era behind a row-one segment, or null for [NONE]. Throws on a
     * segment row one does not draw — a typo should not silently become
     * "no treatment". Row-two segments are not eras: ask [treatmentFor].
     */
    fun eraFor(segment: String): String? {
        require(segment in SEGMENTS) {
            "unknown pad-sheet segment '$segment' - the card draws: ${SEGMENTS.joinToString(", ")}"
        }
        return ERA_FOR[segment]
    }

    /**
     * What tapping [segment] does, on either row, or null for [NONE].
     * Throws on a segment the card does not draw.
     */
    fun treatmentFor(segment: String): Treatment? {
        require(segment in ALL_SEGMENTS) {
            "unknown pad-sheet segment '$segment' - the card draws: ${ALL_SEGMENTS.joinToString(", ")}"
        }
        ERA_FOR[segment]?.let { return Treatment.Era(it) }
        CHARACTER_FOR[segment]?.let { return Treatment.Character(it) }
        KEYED_FOR[segment]?.let { return Treatment.Keyed(it) }
        return null
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

    /** The phone ruling, rows two and three: "crushed" lights no segment; the provenance line says so. */
    fun segmentForCharacter(character: String): String? =
        CHARACTER_FOR.entries.firstOrNull { it.value == character }?.key

    /** The phone ruling for the keyed family too: a keyed name no segment draws lights nothing. */
    fun segmentForKeyed(name: String): String? = KEYED_FOR.entries.firstOrNull { it.value == name }?.key

    /** [segmentFor], [segmentForCharacter] or [segmentForKeyed], whichever row [treatment] belongs to. */
    fun segmentFor(treatment: Treatment): String? = when (treatment) {
        is Treatment.Era -> segmentFor(treatment.name)
        is Treatment.Character -> segmentForCharacter(treatment.name)
        is Treatment.Keyed -> segmentForKeyed(treatment.name)
    }

    /**
     * What the card should light for a pad's recipe, read defensively:
     * `eraPad` leaves `{"era", "amount"}`, `characterPad` (and `treatPad`,
     * and bank B's twins) leave a `PadRecipe` carrying `treatment` +
     * `amount`, `keyedPad` leaves `{"keyed", "key", "amount", "seed"}`. A synth patch or an fx-only recipe from any other door
     * (`replaceAudio`, `mutate`) names neither and reads as untreated —
     * the audio is what it is, but nothing here can claim a segment for it.
     */
    fun read(recipe: JsonValue.Obj?): Applied? {
        if (recipe == null) return null
        val amount = (recipe.entries["amount"] as? JsonValue.Num)?.value?.toFloat()
        (recipe.entries["era"] as? JsonValue.Str)?.value?.let { era ->
            val t = Treatment.Era(era)
            return Applied(t, amount ?: return null, segmentFor(t))
        }
        (recipe.entries["treatment"] as? JsonValue.Str)?.value?.let { name ->
            val t = Treatment.Character(name)
            return Applied(t, amount ?: return null, segmentFor(t))
        }
        (recipe.entries["keyed"] as? JsonValue.Str)?.value?.let { name ->
            val t = Treatment.Keyed(name)
            return Applied(t, amount ?: return null, segmentFor(t))
        }
        return null
    }
}
