package com.snipsnap.shell

import com.snipsnap.kit.KitPad

/**
 * Where a sound came from — the one reader of [KitPad.source], and the
 * one place that decides which stamp wins when a pad carries several.
 *
 * **This exists because the app had three answers to that question and
 * two of them were wrong.** `PadSheetScreen.provenanceOrigin` read ten
 * keys, [LinerNotes] read four, [Lineage] read four different ones; the
 * pad sheet ranked `file` above `resampledFrom` and the liner notes
 * ranked them the other way; and a pad captured from another app
 * (`app`/`title`, which only [Lineage] knew about) showed as its bare
 * filename on the pad sheet and as *"Built by hand, pad by pad."* in the
 * notes that went on the card. The pad sheet's own KDoc claimed it read
 * "in the same priority order `LinerNotes.kt` uses", which had not been
 * true since the third key was added to either.
 *
 * That is this repo's recurring defect — one quantity in several places
 * — wearing its worst hat: the quantity is *what the app says happened
 * to somebody's audio*, and three readers disagreeing means at least two
 * of them tell that person something untrue.
 *
 * The order below is the pad sheet's, because it was the only one that
 * knew every door, with one deliberate correction taken from the liner
 * notes: a resample generation outranks the chop that made it, since
 * "generation 3, bounced from X" contains "chopped from a file" and not
 * the other way round.
 *
 * Nothing here throws or requires: a `source` map is written by a dozen
 * doors and read long afterwards, from a `kit.json` a user may have
 * edited, so every accessor answers null rather than refusing.
 */
object Provenance {

    /**
     * One way a pad can record where it came from, in the order a reader
     * should prefer them when a pad carries more than one.
     *
     * [key] is the `source` key that identifies the kind. [DUG] also
     * reads `at`, [RESAMPLED] also reads `generation`, [STRETCHED] also
     * reads `mode`, and [CAPTURED] is the one kind keyed on two
     * alternatives rather than one — see [kindOf].
     */
    enum class Kind(val key: String) {
        /** DIG: a song and a timestamp inside it. */
        DUG("song"),

        /** A resample generation: this kit was bounced from another and chopped again. */
        RESAMPLED("resampledFrom"),

        /** RE-TRIM's own key ([Retrim.FILE_KEY]) — the tape a pad was cut from. */
        TAPED("tapeFile"),

        /** The CLI's chop: the file it cut. */
        CHOPPED("file"),

        /** INSIDE: the app that was playing, and what it was playing. */
        CAPTURED("app"),

        /** An archive read in: `.xpn`, `.sfz`, an MPC3 program. */
        IMPORTED("importedFrom"),

        /** SCULPT. */
        SCULPTED("sculptedFrom"),

        /** STRETCH, or its `mode == "freeze"` sibling. */
        STRETCHED("stretchedFrom"),

        /** DISSECT. */
        DISSECTED("dissectedFrom"),

        /** MERGE: the other kit that came in. */
        MERGED("mergedFrom"),

        /** The app's own CHOP, which names no file — `origin == "chop"`. */
        CHOPPED_IN_APP("origin"),
        ;
    }

    /** The highest-priority stamp [source] actually carries, or null for a pad made by hand. */
    fun kindOf(source: Map<String, String>): Kind? = Kind.entries.firstOrNull { kind ->
        when (kind) {
            // Two keys, either of which is enough: INSIDE knows the app it
            // recorded and usually, but not always, what was playing.
            Kind.CAPTURED -> source["app"] != null || source["title"] != null
            // A value, not a presence: `origin` is a general-purpose key
            // and only its "chop" value is a provenance stamp.
            Kind.CHOPPED_IN_APP -> source["origin"] == "chop"
            else -> source[kind.key] != null
        }
    }

    /**
     * A pad's origin as one lower-case phrase — `dug from "Track 07.wav"
     * at 1:32`, `resampled from "Night Drive"`, `chopped from tape` — or
     * null when the pad carries no stamp at all.
     *
     * The name is quoted wherever there is one, for the same reason the
     * liner notes have always quoted it: a filename with a space in it,
     * unquoted and mid-sentence, reads as two things.
     *
     * The pad sheet and the liner notes both build from this, so the
     * screen and the paper in the case cannot name different parents.
     */
    fun phrase(source: Map<String, String>): String? {
        val kind = kindOf(source) ?: return null
        fun named(key: String) = "\"${source[key]}\""
        return when (kind) {
            Kind.DUG -> "dug from ${named("song")} at ${source["at"] ?: "?"}"
            Kind.RESAMPLED -> "resampled from ${named("resampledFrom")}"
            Kind.TAPED -> "cut from ${named("tapeFile")}"
            Kind.CHOPPED -> "chopped from ${named("file")}"
            Kind.CAPTURED ->
                "captured from " + listOfNotNull(source["app"], source["title"]?.let { "\"$it\"" }).joinToString(" ")
            Kind.IMPORTED -> "imported from ${named("importedFrom")}"
            Kind.SCULPTED -> "sculpted from ${named("sculptedFrom")}"
            Kind.STRETCHED ->
                "${if (source["mode"] == "freeze") "frozen" else "stretched"} from ${named("stretchedFrom")}"
            Kind.DISSECTED -> "dissected from ${named("dissectedFrom")}"
            Kind.MERGED -> "merged from ${named("mergedFrom")}"
            Kind.CHOPPED_IN_APP -> "chopped from tape"
        }
    }

    /**
     * A [phrase] as a sentence: capitalised, full-stopped. What [LinerNotes]
     * prints, so the paper in the case and the screen cannot name the same
     * parent in different words.
     */
    fun asSentence(phrase: String): String = phrase.replaceFirstChar { it.uppercase() } + "."

    /**
     * How the pad came to be when a door made it from *other pads* rather
     * than from audio: a twin ([KitBuilderModel.TWIN_OF], the bank-A pad
     * it was dealt from) and a bred child (`bredFrom`, "Mother × Father").
     *
     * Separate from [phrase] and shown before it, because both doors copy
     * the parent's own source keys — so a twin reads as its parent's tape
     * unless its own derivation goes first. Both show when both are
     * there: a bred kit whose parent had twins carries a twin's stamp
     * under BREED's, and neither is the whole story alone.
     */
    fun lineage(source: Map<String, String>): List<String> = listOfNotNull(
        source[KitBuilderModel.TWIN_OF]?.let { "twin of $it" },
        source["bredFrom"]?.let { "bred from ${it.replace(" x ", " × ")}" },
    )

    /**
     * The kit's own origin as one phrase: the highest-priority kind any
     * of its pads carries, phrased once, with `+N more` when pads of that
     * same kind name different parents. Null for a kit built by hand.
     *
     * This is what KIT prints under its grid. The persona review's P4.4
     * said provenance was "good and half-hidden" — good because the app
     * tracks every door, half-hidden because reaching any of it meant
     * holding a pad. A kit-wide line that cannot be dismissed is the same
     * answer S2 already gave the pad sheet's own gesture: keep the hold,
     * add a line that is always there.
     */
    fun ofKit(pads: List<KitPad>): String? {
        val top = pads.mapNotNull { kindOf(it.source) }.minByOrNull { it.ordinal } ?: return null
        val phrases = pads.filter { kindOf(it.source) == top }.mapNotNull { phrase(it.source) }.distinct()
        val first = phrases.firstOrNull() ?: return null
        return if (phrases.size == 1) first else "$first +${phrases.size - 1} more"
    }

    /**
     * Every distinct phrase the kit's pads carry of its top kind, in pad
     * order — what [LinerNotes] lists in full, where [ofKit] folds into
     * one line for a legend that gets one line.
     */
    fun allOfKit(pads: List<KitPad>): List<String> {
        val top = pads.mapNotNull { kindOf(it.source) }.minByOrNull { it.ordinal } ?: return emptyList()
        return pads.filter { kindOf(it.source) == top }.mapNotNull { phrase(it.source) }.distinct()
    }
}
