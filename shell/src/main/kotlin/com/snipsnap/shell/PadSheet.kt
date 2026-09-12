package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Eras
import com.snipsnap.synth.Treatments

/**
 * The PAD SHEET's TREATMENT card, as data.
 *
 * Five rows. Row one draws five segments: four of them are the design's
 * Time Machine eras, and [SMEAR] — a fifth, deliberately not an era (see
 * below). The rest are the rack's named characters — the FX chains
 * `treat` and bank B already speak — reachable one tap at a time; row
 * four ends on TUNE and row five is the keyed family ([Keyed]), the
 * treatments that read the kit's own key.
 * Every row is a vocabulary mapping and nothing more: the words are the
 * app's, not the engine's, so [Eras] and [Treatments] never learn what a
 * "segment" is.
 *
 * The card drives the whole-pad doors — [KitBuilderModel.eraPad],
 * [KitBuilderModel.characterPad] and [KitBuilderModel.keyedPad], never
 * `treatPad` — because a pad treatment is whole-pad character: every file
 * the pad references (velocity layers included) changes together, so a
 * layered snare never grows an untreated underside.
 *
 * [SMEAR] is the one row-one segment that is not an era — it isn't in
 * [ERA_FOR] and never will be. It drives `com.snipsnap.audio.Smear.process`
 * through `KitBuilderModel.replaceAudio`, the same generic door
 * `Mutate.morph` uses, with its own ad-hoc recipe shape
 * (`{"verb":"smear","amount":x}`) instead of an era's `{"era","amount"}`.
 * [eraFor] and [segmentFor] (era overload) stay scoped to the era four;
 * the screen reads SMEAR's own recipe shape back separately, ahead of the
 * generic [treatmentFor] dispatch — which is also why the rack's
 * transient-removal character (`"smeared"`, `com.snipsnap.synth.Smear`)
 * carries the card label [TAIL] rather than SMEAR: same-sounding name,
 * unrelated DSP (row one bakes a destructive HPSS stretch; row two is a
 * non-destructive FX-chain transient pull), and a card cannot draw the
 * same label on two rows with two meanings.
 */
object PadSheet {

    /** The leftmost segment: no treatment at all. */
    const val NONE = "NONE"

    /** The fifth chip: HPSS + phase-locked stretch, not a Time Machine era. */
    const val SMEAR = "SMEAR"

    /**
     * The sixth chip: the tape's own hiss and room under the pad
     * (`com.snipsnap.audio.Dust`, `docs/DUST.md`). Like [SMEAR], not an
     * era and never in [ERA_FOR]: it rides its own recipe shape
     * (`{"verb":"dust","amount":x,"tape":"<file>"}`), read by [readDust],
     * because it needs a tape as well as an amount.
     */
    const val DUST = "DUST"

    /** Where DUST ALL lands every pad: lower than the card's default, since the point is one room shared, not sixteen soups. */
    const val DUST_ALL_AMOUNT = 0.5f

    /** Row one, left to right, as the card draws it — the eras, plus SMEAR and DUST. */
    val SEGMENTS: List<String> = listOf(NONE, "CRUSH", "TAPE", "DIRT", SMEAR, DUST)

    /** Row two, first chip: the rack's transient-removal character (`"smeared"`) — see the object KDoc for why it isn't named SMEAR. */
    const val TAIL = "TAIL"

    /** Row two — the rack's characters. */
    val CHARACTER_SEGMENTS: List<String> = listOf(TAIL, "SLAP", "WASH", "PUNCH")

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

    /**
     * Where the AMT stepper sits when a pad has never been treated. 70,
     * not 35: every macro scales linearly with AMT (`Treatments.chain`),
     * and at 35 the quieter characters (WASH, SLAP, GHOST) moved the
     * sound by about a tenth — the phone ruling was "did it even work?".
     * At 70 the first tap is meant to be heard; AMT ▼ is there for less.
     */
    const val DEFAULT_AMOUNT = 0.7f

    /**
     * The card's own display words for a segment — a language-only layer on
     * top of [ALL_SEGMENTS]. The segment string itself stays the id every
     * other door (`onSegmentTap`, [treatmentFor], [read]'s `Applied.segment`,
     * and the shell's own tests) reads and writes; only [displayLabel]'s
     * output is drawn on the chip. A segment absent here draws its own id
     * unchanged (e.g. CRUSH, TAPE, DIRT, SMEAR, TAIL, GHOST, FLIP, DUB,
     * SWELL, BODY, WOBBLE — none of those needed a friendlier word).
     */
    private val DISPLAY_LABELS: Map<String, String> = mapOf(
        // Row four's key-snap treatment: the card's own word for "retuned".
        "TUNE" to "IN KEY",
        // The capstan release / spin-up pair, not a transport STOP/START.
        "STOP" to "SPIN DOWN",
        "START" to "SPIN UP",
        // One tape-delay repeat.
        "SLAP" to "ECHO",
        // The attack kept, the tail stretched toward forever.
        "ETERNAL" to "DRONE",
        // The banded smear: click gone, thump kept.
        "SKIM" to "SMOOTH",
    )

    /** What the card draws for [segment] — [DISPLAY_LABELS]'s word, or the segment itself. */
    fun displayLabel(segment: String): String = DISPLAY_LABELS[segment] ?: segment

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
        // Labeled TAIL, not SMEAR — row one's SMEAR is a different,
        // unrelated destructive treatment; see the object KDoc.
        TAIL to "smeared",
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
     * SMEAR's own AMT off a pad's recipe — `{"verb":"smear","amount":x}`,
     * the `Mutate.morph` recipe idiom, not [read]'s shapes. [read] cannot
     * see SMEAR on purpose (see the object KDoc); this is its companion,
     * consulted ahead of [read] wherever both are. Anything else reads as
     * no SMEAR active.
     */
    fun readSmear(recipe: JsonValue.Obj?): Float? {
        if (recipe == null) return null
        val verb = (recipe.entries["verb"] as? JsonValue.Str)?.value ?: return null
        if (verb != "smear") return null
        return (recipe.entries["amount"] as? JsonValue.Num)?.value?.toFloat()
    }

    /** What a dusted pad carries: how much, and which tape's dust. */
    data class Dusted(val amount: Float, val tape: String)

    /**
     * DUST's own recipe off a pad — `{"verb":"dust","amount":x,"tape":t}`,
     * [readSmear]'s companion for the sixth chip. Anything else reads as
     * no DUST active; a dust recipe missing its tape is not a dust recipe.
     */
    fun readDust(recipe: JsonValue.Obj?): Dusted? {
        if (recipe == null) return null
        val verb = (recipe.entries["verb"] as? JsonValue.Str)?.value ?: return null
        if (verb != "dust") return null
        val amount = (recipe.entries["amount"] as? JsonValue.Num)?.value?.toFloat() ?: return null
        val tape = (recipe.entries["tape"] as? JsonValue.Str)?.value ?: return null
        return Dusted(amount, tape)
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

    /**
     * What [NONE] can do on a pad — the answer to the question every other
     * chip on the card already answers, and the one NONE used to duck.
     *
     * The treatment doors ([KitBuilderModel.eraPad], `characterPad`,
     * `keyedPad`, `smearPad`) each bin the file they rewrite, so undo is
     * `unEraPad`: every file the pad references comes back out of the bin
     * and the recipe comes off. That only works when the bin still holds
     * *all* of them, which is why this is four answers and not a boolean.
     */
    enum class UnTreat {
        /** No card recipe rides the pad, so NONE is already the truth and the chip does nothing. */
        NOTHING,

        /** The bin holds an earlier take of every file the pad references: `unEraPad` will land. */
        READY,

        /**
         * The main sample can come back but a GHOSTS layer cannot — that layer
         * was rendered *from* the treated main sample and never earned its own
         * bin entry. Restoring half would leave the pad's layers treated one
         * pass more than its main sample. That skew is what routing through
         * the whole-pad doors exists to prevent; their own KDoc calls it an
         * "untreated underside". Refusing says so.
         */
        GHOSTS_POSTDATE,

        /**
         * The pad names a treatment the bin cannot undo — a bank-B twin whose
         * recipe was copied rather than performed, a CLI `treat` run in another
         * kit folder, or a bin since emptied. The card can say what the sound
         * is; it cannot give back what it never held.
         */
        NOT_BINNED,
    }

    /**
     * Whether the card should accept a tap on [segment], given whether the
     * pad currently reads as untreated.
     *
     * Every other chip is always live — re-tapping the lit one re-treats at
     * the AMT on screen, which is the card's oldest gesture. [NONE] is the
     * one chip that can be a no-op: on an untreated pad it is already the
     * truth, and tapping the state you are in should do nothing rather than
     * toast about it. On a treated pad it is the un-treat, so it is live.
     *
     * The busy state is the screen's own business and is not read here.
     */
    fun tappable(segment: String, isNoneState: Boolean): Boolean = segment != NONE || !isNoneState

    /**
     * [UnTreat] for [pad], given the set of `originalName`s currently in the
     * kit's bin (`KitBuilderModel.binContents()`).
     *
     * Every recipe reader is consulted: SMEAR and DUST ride their own
     * shapes and [read] cannot see them (see the object KDoc), but a
     * smeared or dusted pad is every bit as un-treatable as an aged one.
     */
    fun unTreatState(pad: KitPad, binned: Set<String>): UnTreat {
        if (read(pad.recipe) == null && readSmear(pad.recipe) == null && readDust(pad.recipe) == null) return UnTreat.NOTHING
        if (pad.sampleFile !in binned) return UnTreat.NOT_BINNED
        val files = (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).distinct()
        return if (files.all { it in binned }) UnTreat.READY else UnTreat.GHOSTS_POSTDATE
    }
}
