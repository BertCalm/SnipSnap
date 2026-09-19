package com.snipsnap.shell

import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SEND TO BENCH: the teach log's way off the phone (`docs/WORKSHOP.md`).
 *
 * TEACH THE MACHINE writes every chip correction into the kit it was made
 * on — `overrides.jsonl` beside that kit's WAVs, [TeachLog] — and there it
 * stayed. BACKUP packs kits as `.xpn` archives, which carry a program and
 * its samples and nothing else, so the harness that scores corrections
 * (`TeachLogTest`, reading `reference/calibration/`) had never been handed
 * a line from a phone. This is the last mile: every log under the shelf,
 * the bin included, merged into one file the harness reads as it is, with
 * a manifest saying which kit gave what.
 *
 * Since WS2 the same walk carries the cut ratings ([CutRatings],
 * `cuts.jsonl`) beside the labels, into a second file the same way, and
 * the confirmations CONFIRM ALL adds travel inside the first: they are
 * [TeachLog] lines like any other.
 *
 * Features, labels and ratings only, exactly as they were written: nothing
 * here opens a WAV, so what SETUP's consent line promises about the log
 * holds through the export. The merged files are re-serialized through
 * their own writers, which drops any torn record a killed append left
 * behind — what leaves is clean by construction.
 *
 * Deterministic: for one shelf and one stamp the zip is byte-stable
 * (entries in path order, a fixed entry time — the trick `XpnPackager`
 * uses), so two packs of an unchanged shelf are the same file.
 */
object BenchExport {

    /** The zip's stem; the stamp follows it, as BACKUP's `SnipSnap Shelf <stamp>.zip` does. */
    const val STEM = "SnipSnap Bench"

    /** The merged teach log inside the zip: [TeachLog.FILE_NAME], the name the harness reads. */
    const val LOG_NAME = TeachLog.FILE_NAME

    /** The merged cut ratings inside the zip: [CutRatings.FILE_NAME], likewise. */
    const val RATINGS_NAME = CutRatings.FILE_NAME

    /** The manifest inside the zip: which kit gave how many lines, and what to do with the files. */
    const val MANIFEST_NAME = "manifest.txt"

    /** The folder the merged files are for, as the manifest names it — `reference/calibration/` at the repo root. */
    const val CALIBRATION_DIR = "reference/calibration/"

    /** The harnesses the manifest says to run over the dropped-in files. */
    const val HARNESS_COMMAND = "./gradlew :shell:test --tests '*TeachLogTest*' --tests '*CutRatingsTest*'"

    /**
     * One kit's logs: where it sat under the shelf (`Break Kit`, or
     * `.bin/Break Kit-1726000000000` for one asleep in the bin), what its
     * teach log held, and what its cut ratings held. At least one of the
     * two is non-empty, or the kit is not listed.
     */
    data class Log(val path: String, val examples: List<TeachLog.Example>, val ratings: List<CutRatings.Rating> = emptyList()) {
        val corrections: Int get() = examples.count { !it.confirmation }
        val confirmations: Int get() = examples.count { it.confirmation }
    }

    /** What one pack wrote: the zip, and each kit's share of it. */
    data class Result(val file: File, val logs: List<Log>) {
        /** Every teach-log line: corrections and confirmations together. */
        val labels: Int get() = logs.sumOf { it.examples.size }
        val corrections: Int get() = logs.sumOf { it.corrections }
        val confirmations: Int get() = logs.sumOf { it.confirmations }
        val ratings: Int get() = logs.sumOf { it.ratings.size }
        val kits: Int get() = logs.size
    }

    /**
     * Every kit under [kitsRoot] with a teach log or a cut rating — a kit
     * folder's own, one asleep in the bin, or anywhere else under the shelf
     * a kit was ever moved to — in path order. A log that reads as empty (a
     * file of torn lines) counts for nothing; a kit with nothing to give is
     * not listed. A shelf that does not exist yet has none.
     */
    fun gather(kitsRoot: File): List<Log> {
        if (!kitsRoot.isDirectory) return emptyList()
        return kitsRoot.walkTopDown()
            .filter { it.isFile && (it.name == LOG_NAME || it.name == RATINGS_NAME) }
            .mapNotNull { it.parentFile }
            .distinct()
            .map { dir ->
                val path = dir.relativeTo(kitsRoot).path.replace(File.separatorChar, '/').ifEmpty { "." }
                Log(path, TeachLog.read(File(dir, LOG_NAME)), CutRatings.read(File(dir, RATINGS_NAME)))
            }
            .filter { it.examples.isNotEmpty() || it.ratings.isNotEmpty() }
            .sortedBy { it.path.lowercase(Locale.ROOT) }
            .toList()
    }

    /**
     * Pack every log [gather] finds under [kitsRoot] into
     * `<outDir>/SnipSnap Bench <stamp>.zip`. [stamp] is the caller's — the
     * app passes the same date stamp BACKUP puts on its zip (`ShareOut.stamp`),
     * handed in rather than read from the clock here so a test can name the
     * file it expects. A file that would be empty is left out of the zip
     * rather than written empty. Requires at least one line to send; the
     * app asks [gather] first, so the refusal can say why there is none.
     */
    fun pack(kitsRoot: File, outDir: File, stamp: String): Result {
        val logs = gather(kitsRoot)
        require(logs.isNotEmpty()) { "nothing to send: no label or rating is logged under $kitsRoot" }
        outDir.mkdirs()
        val file = File(outDir, "$STEM $stamp.zip")
        val examples = logs.flatMap { it.examples }
        val ratings = logs.flatMap { it.ratings }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, text: String) {
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME
                zip.putNextEntry(entry)
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(MANIFEST_NAME, manifest(stamp, logs))
            if (examples.isNotEmpty()) put(LOG_NAME, TeachLog.toJsonl(examples))
            if (ratings.isNotEmpty()) put(RATINGS_NAME, CutRatings.toJsonl(ratings))
        }
        return Result(file, logs)
    }

    /**
     * The manifest's text: the stamp, the counts, each kit's share, and what
     * to do with the files — written for the person unzipping it at a desk,
     * not for a parser. Plain prose in its own case, not TapeOS copy: it is
     * read off a laptop, never off the phone.
     */
    fun manifest(stamp: String, logs: List<Log>): String {
        val labels = logs.sumOf { it.examples.size }
        val corrections = logs.sumOf { it.corrections }
        val confirmations = logs.sumOf { it.confirmations }
        val ratings = logs.sumOf { it.ratings.size }
        val sb = StringBuilder()
        sb.append(STEM).append(' ').append(stamp).append('\n')
        sb.append(count(labels, "label", "labels"))
            .append(" (").append(count(corrections, "correction", "corrections"))
            .append(", ").append(count(confirmations, "confirmation", "confirmations")).append(")")
            .append(" and ").append(count(ratings, "cut rating", "cut ratings"))
            .append(" from ").append(count(logs.size, "kit", "kits"))
            .append(". Feature vectors, labels and ratings only, never audio.\n\n")
        sb.append("labels ratings\n")
        for (log in logs) {
            sb.append(String.format(Locale.ROOT, "%6d %7d  %s\n", log.examples.size, log.ratings.size, log.path))
        }
        sb.append('\n')
        sb.append(LOG_NAME).append(" is every label above merged into one file, ")
            .append(RATINGS_NAME).append(" every rating; a torn line is dropped, and a file with nothing to hold is not in the zip.\n")
        sb.append("Drop them into ").append(CALIBRATION_DIR).append(" and run\n")
        sb.append("  ").append(HARNESS_COMMAND).append('\n')
        sb.append("which scores every label against the rules as they stand and sums the ratings by the bench's settings.\n")
        return sb.toString()
    }

    private fun count(n: Int, singular: String, plural: String): String = "$n ${if (n == 1) singular else plural}"

    /** 2020-01-01T00:00:00 UTC — any fixed stamp keeps the zip byte-stable; the value `XpnPackager` uses. */
    private const val FIXED_TIME = 1_577_836_800_000L
}
