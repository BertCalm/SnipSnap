package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.util.Locale

/**
 * RATE THE CUTS (`docs/WORKSHOP.md`, WS2): the second thing TEACH THE
 * MACHINE logs, and the first that measures the *cuts* rather than the
 * labels. [TeachLog] can say whether the classifier named a slice right;
 * nothing said whether the detector cut it right — where the hits were
 * found, how many, how far off the attack — and the bench's every knob
 * (HITS, EAR, CUT, SNAP) moves exactly that.
 *
 * One line per rated chop: the stars, the bench's settings that produced
 * the cuts, and what it took to get there — how many chops ran before
 * this one, how many cuts were then moved by hand, how many chips the
 * human corrected or confirmed. No audio and no features: a rating is a
 * verdict on a configuration, and the configuration is the whole of the
 * record. It lands beside [TeachLog]'s file in the kit the chop became,
 * and SEND TO BENCH ([BenchExport]) carries it to the desk, where
 * `CutRatingsTest` sums it by setting.
 */
object CutRatings {

    const val FILE_NAME = "cuts.jsonl"

    /** The scale: one to five, as the row on CHOP draws it. */
    const val STARS = 5

    data class Rating(
        /** 1..[STARS], the human's verdict on the cuts. */
        val stars: Int,
        /** HITS, GRID, GHOSTS, HUMMED or LADDER — the CUT bench's segment. */
        val mode: String,
        /** BY HITS and GHOSTS: the HITS asked for; GRID: its parts; null where the bench has no count. */
        val count: Int?,
        /** BY HITS and GHOSTS: the [ChopReviewModel.Ear], [ChopReviewModel.Cut] and [ChopReviewModel.GridSnap] by name; null elsewhere. */
        val ear: String?,
        val cut: String?,
        val grid: String?,
        /** Slices on the screen when rated. */
        val rows: Int,
        /** Chops run on this source before the rated one: [ChopReviewModel.tries]. */
        val tries: Int,
        /** Cuts moved by hand since the last chop: [ChopReviewModel.merges] and [ChopReviewModel.splits]. */
        val merges: Int,
        val splits: Int,
        /** Chips the human corrected, and chips vouched for, at the moment of rating. */
        val corrected: Int,
        val confirmed: Int,
        /** The source's length. */
        val seconds: Float,
        /** The source's confident tempo, or null when the tape has no pulse the bench would trust. */
        val bpm: Float?,
    ) {
        init {
            require(stars in 1..STARS) { "stars is 1..$STARS, got $stars" }
        }

        /** The bench's settings as one line — the key the harness sums by. Every field named, defaults included, so two settings never read the same. */
        val setting: String
            get() = buildString {
                append(mode)
                if (count != null) append(" ×").append(count)
                if (ear != null) append(" · ").append(ear)
                if (cut != null) append(" · CUT ").append(cut)
                if (grid != null) append(" · SNAP ").append(grid)
            }

        /** Hand edits after the chop, for the summary. */
        val handEdits: Int get() = merges + splits
    }

    /** The rating of [model]'s cuts as it stands now, [stars] out of [STARS]. */
    fun of(model: ChopReviewModel, stars: Int): Rating {
        val hits = ChopReviewModel.hitsOf(model.mode)
        val mode = model.mode
        return Rating(
            stars = stars,
            mode = when (mode) {
                is ChopReviewModel.ChopMode.ByHits -> "HITS"
                is ChopReviewModel.ChopMode.Grid -> "GRID"
                is ChopReviewModel.ChopMode.Ghosts -> "GHOSTS"
                is ChopReviewModel.ChopMode.Hummed -> "HUMMED"
                is ChopReviewModel.ChopMode.Ladder -> "LADDER"
            },
            count = hits?.maxSlices ?: (mode as? ChopReviewModel.ChopMode.Grid)?.parts,
            ear = hits?.ear?.name,
            cut = hits?.cut?.name,
            grid = hits?.grid?.name,
            rows = model.sliceCount,
            tries = model.tries,
            merges = model.merges,
            splits = model.splits,
            corrected = model.labeledOverrides().size,
            confirmed = model.labeledConfirmations().size,
            seconds = model.source.frameCount.toFloat() / model.source.sampleRate,
            bpm = model.tempo?.takeIf { it.confidence >= ChopReviewModel.TEMPO_CONFIDENCE }?.bpm,
        )
    }

    /** One line per rating; append-friendly, the same shape as [TeachLog.toJsonl]. */
    fun toJsonl(ratings: List<Rating>): String =
        ratings.joinToString("") { Json.write(toJson(it)).replace("\n", "").replace("    ", "") + "\n" }

    fun fromJsonl(text: String): List<Rating> =
        text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            // A process killed mid-append leaves a torn last line, and one
            // bad line must not lose the rest — [TeachLog.fromJsonl]'s rule.
            try {
                fromJson(Json.parse(line))
            } catch (e: Exception) {
                null
            }
        }.toList()

    fun append(file: File, ratings: List<Rating>) {
        if (ratings.isEmpty()) return
        file.parentFile?.mkdirs()
        file.appendText(toJsonl(ratings))
    }

    fun read(file: File): List<Rating> =
        if (file.isFile) fromJsonl(file.readText()) else emptyList()

    /**
     * What the desk reads: every setting that was rated, best first, with
     * how many times, the mean stars, and what it cost on average to get
     * there — tries before the chop, hand edits after it, chips corrected.
     * Prose for a person, not a parser; the lines are the data.
     */
    fun summary(ratings: List<Rating>): String {
        if (ratings.isEmpty()) return "cut ratings: none logged yet\n"
        val sb = StringBuilder()
        sb.append("cut ratings: ").append(count(ratings.size, "chop", "chops")).append(" rated, ")
            .append(String.format(Locale.ROOT, "%.1f", ratings.map { it.stars }.average()))
            .append(" stars on average\n")
        val groups = ratings.groupBy { it.setting }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Rating>>> { it.value.map { r -> r.stars }.average() }.thenByDescending { it.value.size }.thenBy { it.key })
        for ((setting, rs) in groups) {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "  %.1f stars  n=%-3d %-40s tries %.1f  hand edits %.1f  corrected %.1f of %.1f chips\n",
                    rs.map { it.stars }.average(),
                    rs.size,
                    setting,
                    rs.map { it.tries }.average(),
                    rs.map { it.handEdits }.average(),
                    rs.map { it.corrected }.average(),
                    rs.map { it.rows }.average(),
                ),
            )
        }
        return sb.toString()
    }

    private fun count(n: Int, singular: String, plural: String): String = "$n ${if (n == 1) singular else plural}"

    private fun toJson(r: Rating): JsonValue {
        val entries = linkedMapOf<String, JsonValue>(
            "stars" to JsonValue.Num(r.stars.toDouble()),
            "mode" to JsonValue.Str(r.mode),
        )
        r.count?.let { entries["count"] = JsonValue.Num(it.toDouble()) }
        r.ear?.let { entries["ear"] = JsonValue.Str(it) }
        r.cut?.let { entries["cut"] = JsonValue.Str(it) }
        r.grid?.let { entries["grid"] = JsonValue.Str(it) }
        entries["rows"] = JsonValue.Num(r.rows.toDouble())
        entries["tries"] = JsonValue.Num(r.tries.toDouble())
        entries["merges"] = JsonValue.Num(r.merges.toDouble())
        entries["splits"] = JsonValue.Num(r.splits.toDouble())
        entries["corrected"] = JsonValue.Num(r.corrected.toDouble())
        entries["confirmed"] = JsonValue.Num(r.confirmed.toDouble())
        entries["seconds"] = JsonValue.Num(r.seconds.toDouble())
        r.bpm?.let { entries["bpm"] = JsonValue.Num(it.toDouble()) }
        return JsonValue.Obj(entries)
    }

    private fun fromJson(v: JsonValue): Rating {
        val o = (v as JsonValue.Obj).entries
        fun int(k: String) = (o[k] as JsonValue.Num).value.toInt()
        fun intOrNull(k: String) = (o[k] as? JsonValue.Num)?.value?.toInt()
        fun str(k: String) = (o[k] as JsonValue.Str).value
        fun strOrNull(k: String) = (o[k] as? JsonValue.Str)?.value
        return Rating(
            stars = int("stars"),
            mode = str("mode"),
            count = intOrNull("count"),
            ear = strOrNull("ear"),
            cut = strOrNull("cut"),
            grid = strOrNull("grid"),
            rows = int("rows"),
            tries = int("tries"),
            merges = int("merges"),
            splits = int("splits"),
            corrected = int("corrected"),
            confirmed = int("confirmed"),
            seconds = (o["seconds"] as JsonValue.Num).value.toFloat(),
            bpm = (o["bpm"] as? JsonValue.Num)?.value?.toFloat(),
        )
    }
}
