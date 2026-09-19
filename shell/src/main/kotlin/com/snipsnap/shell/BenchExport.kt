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
 * Features and labels only, exactly as [TeachLog] wrote them: nothing here
 * opens a WAV, so what SETUP's consent line promises about the log holds
 * through the export. The merged file is re-serialized through
 * [TeachLog.toJsonl], which drops any torn record a killed append left
 * behind — what leaves is clean by construction.
 *
 * Deterministic: for one shelf and one stamp the zip is byte-stable
 * (entries in path order, a fixed entry time — the trick `XpnPackager`
 * uses), so two packs of an unchanged shelf are the same file.
 */
object BenchExport {

    /** The zip's stem; the stamp follows it, as BACKUP's `SnipSnap Shelf <stamp>.zip` does. */
    const val STEM = "SnipSnap Bench"

    /** The merged log inside the zip: [TeachLog.FILE_NAME], the name the harness reads. */
    const val LOG_NAME = TeachLog.FILE_NAME

    /** The manifest inside the zip: which kit gave how many corrections, and what to do with the file. */
    const val MANIFEST_NAME = "manifest.txt"

    /** The folder the merged log is for, as the manifest names it — `reference/calibration/` at the repo root. */
    const val CALIBRATION_DIR = "reference/calibration/"

    /** The harness the manifest says to run over the dropped-in log. */
    const val HARNESS_COMMAND = "./gradlew :shell:test --tests '*TeachLogTest*'"

    /**
     * One kit's log: where it sat under the shelf (`Break Kit`, or
     * `.bin/Break Kit-1726000000000` for one asleep in the bin), and what
     * it held.
     */
    data class Log(val path: String, val examples: List<TeachLog.Example>)

    /** What one pack wrote: the zip, and each kit's share of it. */
    data class Result(val file: File, val logs: List<Log>) {
        val corrections: Int get() = logs.sumOf { it.examples.size }
        val kits: Int get() = logs.size
    }

    /**
     * Every teach log under [kitsRoot] — a kit folder's own, one asleep in
     * the bin, or anywhere else under the shelf a kit was ever moved to —
     * in path order. A log that reads as empty (a file of torn lines) is
     * left out: it has nothing to send. A shelf that does not exist yet
     * has none.
     */
    fun gather(kitsRoot: File): List<Log> {
        if (!kitsRoot.isDirectory) return emptyList()
        return kitsRoot.walkTopDown()
            .filter { it.isFile && it.name == TeachLog.FILE_NAME }
            .map { file ->
                val dir = file.parentFile ?: kitsRoot
                val path = dir.relativeTo(kitsRoot).path.replace(File.separatorChar, '/').ifEmpty { "." }
                Log(path, TeachLog.read(file))
            }
            .filter { it.examples.isNotEmpty() }
            .sortedBy { it.path.lowercase(Locale.ROOT) }
            .toList()
    }

    /**
     * Pack every log [gather] finds under [kitsRoot] into
     * `<outDir>/SnipSnap Bench <stamp>.zip`. [stamp] is the caller's — the
     * app passes the same date stamp BACKUP puts on its zip (`ShareOut.stamp`),
     * handed in rather than read from the clock here so a test can name the
     * file it expects. Requires at least one correction; the app asks
     * [gather] first, so the refusal can say why there is none.
     */
    fun pack(kitsRoot: File, outDir: File, stamp: String): Result {
        val logs = gather(kitsRoot)
        require(logs.isNotEmpty()) { "nothing to send: no correction is logged under $kitsRoot" }
        outDir.mkdirs()
        val file = File(outDir, "$STEM $stamp.zip")
        val merged = TeachLog.toJsonl(logs.flatMap { it.examples })
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, text: String) {
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME
                zip.putNextEntry(entry)
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(MANIFEST_NAME, manifest(stamp, logs))
            put(LOG_NAME, merged)
        }
        return Result(file, logs)
    }

    /**
     * The manifest's text: the stamp, the count, each kit's share, and what
     * to do with the file — written for the person unzipping it at a desk,
     * not for a parser. Plain prose in its own case, not TapeOS copy: it is
     * read off a laptop, never off the phone.
     */
    fun manifest(stamp: String, logs: List<Log>): String {
        val total = logs.sumOf { it.examples.size }
        val sb = StringBuilder()
        sb.append(STEM).append(' ').append(stamp).append('\n')
        sb.append(count(total, "correction", "corrections")).append(" from ")
            .append(count(logs.size, "kit", "kits"))
            .append(". Feature vectors and labels only, never audio.\n\n")
        for (log in logs) {
            sb.append(String.format(Locale.ROOT, "%6d  %s\n", log.examples.size, log.path))
        }
        sb.append('\n')
        sb.append(LOG_NAME).append(" is every log above merged into one file; a torn line is dropped.\n")
        sb.append("Drop it into ").append(CALIBRATION_DIR).append(" and run\n")
        sb.append("  ").append(HARNESS_COMMAND).append('\n')
        sb.append("which scores every correction against the rules as they stand.\n")
        return sb.toString()
    }

    private fun count(n: Int, singular: String, plural: String): String = "$n ${if (n == 1) singular else plural}"

    /** 2020-01-01T00:00:00 UTC — any fixed stamp keeps the zip byte-stable; the value `XpnPackager` uses. */
    private const val FIXED_TIME = 1_577_836_800_000L
}
