package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.CaptureDoctor
import com.snipsnap.audio.Classification
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.CleanupConfig
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.Names
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The commit path: a ring snapshot in, a cleaned WAV on disk out.
 *
 * The mic session ([com.snipsnap.app.MicSessionService]) does no audio work
 * of its own — SNIP hands this the last minute of the ring and this decides
 * what survives to disk. Mono-only for v1: the ring only ever holds one
 * channel.
 */
object SnipStore {
    const val DIR = "snips"

    /**
     * How much of a dead-air capture the all-silence fallback keeps. A
     * short, genuinely small file — not the full, possibly minutes-long,
     * ring snapshot written to disk. 200ms is long enough to be a valid,
     * loadable one-shot and short enough that a quiet room never costs
     * meaningful flash.
     */
    private const val SILENT_FALLBACK_MS = 200f

    /**
     * `snip_<capturedMillis>.wav` (legacy/neutral) or
     * `snip_<capturedMillis>_<name>.wav` (named) — the name half is
     * optional and additional, never a replacement for the timestamp
     * [parsedTimestamp] (and every caller of it: [list]/[newest]/
     * [listWithInfo]) already sorts and identifies a snip by. `(.+)` rather
     * than a stricter character class because a user's typed rename is only
     * gated by [Names.isMpcSafe] (spaces and most punctuation stay legal),
     * not by whatever this regex alone would otherwise accept.
     */
    private val NAME = Regex("""snip_(\d+)(?:_(.+))?\.wav""")

    /**
     * A capture's own filename, given its true capture time and its name
     * half (null for the neutral fallback). The one place that assembles
     * this shape — [freshFile], [rename], and the bin's [restore] collision
     * fallback all go through this so the grammar [NAME] parses can never
     * drift from what actually gets written.
     */
    private fun fileName(capturedAtMillis: Long, name: String?): String =
        if (name.isNullOrBlank()) "snip_$capturedAtMillis.wav" else "snip_${capturedAtMillis}_$name.wav"

    /**
     * Runs [samples] through the commit-time [Cleanup] chain (DC offset,
     * then trim, then normalise, then fades — the order [Cleanup] documents
     * as load-bearing), then [CaptureDoctor]'s default visit (no denoise, no
     * declip, no deverb — those repairs stay opt-in for a later screen; only
     * the diagnosis rides along, unused here), then classifies the result
     * (see [autoName]) and writes it to `root/snips/snip_<nowMillis>.wav`
     * (or `..._<Name>.wav` when the classifier earned its keep) via
     * [AtomicFile] — a write-then-rename, so a process killed mid-write (a
     * foreground mic service holding an 8MB ring buffer is exactly the kind
     * of thing Android kills) never leaves a torn WAV for [list]/[newest]
     * to surface and [com.snipsnap.audio.WavReader] to choke on.
     *
     * A quiet room trims to nothing under [Cleanup] — that is not an error.
     * Rather than hand an empty buffer to the doctor (or throw), this takes
     * a short (see [SILENT_FALLBACK_MS]) head slice of the *untrimmed*
     * original and DC-corrects it, but does **not** normalise it: a buffer
     * that trimmed to nothing is, by construction, entirely below
     * [CleanupConfig.silenceThresholdDb] — its peak is near the noise
     * floor, not silence's true zero. [Cleanup.normalize] computes
     * `gain = target / peak` with no floor on `peak`, so normalising a
     * floor-level buffer toward -0.3 dBFS is a several-thousand-x gain that
     * `coerceIn(-1, 1)` turns into a hard-clipped square wave — the
     * quietest possible input producing the loudest, harshest file the app
     * writes. Leaving the level alone is the honest choice: there is
     * nothing in a sub-floor buffer worth normalising toward.
     */
    fun commit(samples: FloatArray, sampleRate: Int, root: File, nowMillis: Long): File {
        val original = Snip(samples, channels = 1, sampleRate = sampleRate)
        val cleaned = Cleanup.process(original)

        val toWrite = if (cleaned.frameCount > 0) {
            CaptureDoctor.clean(cleaned).snip
        } else {
            // All-silence trim: skip the doctor entirely (nothing to
            // diagnose in dead air) and fall back to a short, DC-corrected,
            // UN-normalised slice of the original — see the normalise
            // warning above for why normalize must stay off here.
            val keepFrames = minOf(
                original.frameCount,
                (SILENT_FALLBACK_MS / 1000f * sampleRate).toInt(),
            )
            val window = original.copy(samples = original.samples.copyOf(keepFrames * original.channels))
            Cleanup.process(window, CleanupConfig(trimSilence = false, normalize = false))
        }

        require(toWrite.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
            "sample rate ${toWrite.sampleRate} is not MPC-native (${WavWriter.MPC_SAMPLE_RATE}); " +
                "capture at 44.1 kHz"
        }

        // Named here, before the file exists: this is the one point in the
        // pipeline that already holds a full Snip (not just bytes), which
        // is what Classifier.classify needs. The all-silence fallback above
        // still flows through here rather than skipping it — a near-zero
        // buffer's own peak is at or near 0, which Classifier already reads
        // as UNKNOWN (confidence 0f), so it naturally falls through to the
        // neutral name without a special case.
        val name = autoName(Classifier.classify(toWrite))

        val file = freshFile(root, nowMillis, name)
        val bytes = ByteArrayOutputStream().apply { WavWriter.write(this, toWrite) }.toByteArray()
        writeClaimedFile(file, bytes)
        return file
    }

    /**
     * Below this, [Classifier] is shelving its own guess, not standing
     * behind it — PERC sits at a fixed 0.4 (its own KDoc calls it "the
     * no-confidence shelf") and UNKNOWN at 0.0, both by construction always
     * under this line, while every real call (KICK/SNARE/CLAP/the two
     * hats/TOM/TONAL/LOOP) clears it: [Classification]'s `margin` helper floors at
     * 0.5, and CLAP/LOOP are fixed at 0.7/0.9. This is not a new number
     * invented for naming — it is the exact split
     * [ChopReviewModel.NOT_SURE_BELOW] already draws for CHOP SHOP's own
     * "NOT SURE" chip, reused so a snip and a chop slice agree on what
     * "sure enough to print" means.
     */
    private const val NAME_CONFIDENCE_THRESHOLD = 0.5f

    /**
     * The name a fresh capture earns from [classification], or `null` for
     * the neutral fallback (today's plain `snip_<millis>.wav` shape, the
     * "SNIP" identity [Info.displayName]/[BinnedSnip.displayName] show).
     *
     * A snip is not always a drum — a voice memo, rain, a door hinge, a
     * busker all land here too — and a confident-sounding wrong name
     * ("TOM" on a voice memo) is worse than an honest blank. So this only
     * trusts [Classification.confidence] at or above
     * [NAME_CONFIDENCE_THRESHOLD]; see that constant's own KDoc for why
     * PERC and UNKNOWN can never cross it and don't need special-casing
     * here.
     */
    internal fun autoName(classification: Classification): String? =
        if (classification.confidence >= NAME_CONFIDENCE_THRESHOLD) AutoPlace.nameFor(classification.drumClass) else null

    /**
     * `root/snips/snip_<millis>[_<name>].wav`, at [nowMillis] or the first
     * later millisecond nothing sits on: two snips in one millisecond (a
     * fast phone, a test) must never share a path, or the second silently
     * overwrites the first and [newest] loses one. The bump keeps the
     * name's own ordering honest — later is later. [name] rides along
     * unmodified at every candidate millis — it never changes what breaks
     * a tie, only [fileName]'s reuse of the same construction [rename] and
     * the bin's [restore] fallback also go through.
     *
     * The claim itself is atomic: [File.createNewFile] is an OS-level
     * create-if-absent (`open(O_CREAT|O_EXCL)` underneath), so two threads
     * racing this call — a fast double-press of SNIP, most concretely —
     * can't both observe "nothing here yet" for the same candidate name the
     * way a plain `file.exists()` check-then-write would. Exactly one
     * thread's `createNewFile()` returns true for any given millis; the
     * loser bumps and retries against the next candidate instead of
     * silently overwriting the winner's file once both go on to write it.
     * The caller ([commit]/[import]) then writes the real bytes onto this
     * already-claimed (empty) file via [com.snipsnap.kit.AtomicFile], which
     * replaces it same as it would any other existing file.
     */
    private fun freshFile(root: File, nowMillis: Long, name: String? = null): File {
        val dir = File(root, DIR).apply { mkdirs() }
        var millis = nowMillis
        while (true) {
            val file = File(dir, fileName(millis, name))
            if (file.createNewFile()) return file
            millis++
        }
    }

    /**
     * The longest import kept, in seconds. TAPE holds the whole tape in
     * memory (every peak, every zoom), and the mic ring it was built
     * around is a minute; three minutes is room for the break in the
     * middle of a song without a shared album side becoming a
     * hundred-megabyte deck. Past it the head is kept and the toast says
     * so — a refusal would lose the part the user wanted.
     */
    const val IMPORT_MAX_SEC = 180f

    /** What an import left: the file, how long it is, and whether the tail was cut at [IMPORT_MAX_SEC]. */
    data class Imported(val file: File, val seconds: Float, val truncated: Boolean)

    /**
     * The import path (F3.1/F3.2): a file shared into the app lands as a
     * snip, so TAPE finds it exactly as it finds a capture — newest first.
     * Not the commit chain: what the user shared is what goes on the
     * tape, so no trim, no normalize, no doctor's visit — and no
     * classification either, deliberately: an imported file already came
     * with whatever name it had wherever it came from, and this app has no
     * more evidence about it than [commit]'s own neutral fallback would
     * give it, so it lands under the plain `snip_<millis>.wav` shape,
     * renameable later exactly like any other snip. Only the two things
     * the deck needs — mono (the ring is mono; the deck reads mono) and
     * the MPC rate (a 48 k or 22.05 k file through the sinc [Resampler])
     * — and the [IMPORT_MAX_SEC] cap. Written through [AtomicFile] like a
     * commit, named `snip_<nowMillis>.wav` so [newest] ranks it by
     * arrival. An empty file is refused in words.
     */
    fun import(snip: Snip, root: File, nowMillis: Long): Imported {
        require(snip.frameCount > 0) { "the shared file holds no audio" }
        val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
        val atRate = if (mono.sampleRate == WavWriter.MPC_SAMPLE_RATE) mono else Resampler.resample(mono, WavWriter.MPC_SAMPLE_RATE)
        val maxFrames = (IMPORT_MAX_SEC * atRate.sampleRate).toInt()
        val truncated = atRate.frameCount > maxFrames
        val kept = if (truncated) Snip(atRate.samples.copyOf(maxFrames), 1, atRate.sampleRate) else atRate

        val file = freshFile(root, nowMillis)
        val bytes = ByteArrayOutputStream().apply { WavWriter.write(this, kept) }.toByteArray()
        writeClaimedFile(file, bytes)
        return Imported(file, kept.durationSeconds, truncated)
    }

    /**
     * Writes [bytes] onto [file] — a name [freshFile] already claimed via
     * `createNewFile()`, so it exists but is empty going in. If
     * [AtomicFile.writeBytes] itself throws (a recoverable I/O failure —
     * disk full is the live one, since [DIR] is never pruned), the empty
     * claim is removed rather than left behind: an empty `snip_*.wav` would
     * otherwise sit there passing [NAME]'s own filename match forever, ready
     * for [list]/[newest] to hand a reader a file with nothing in it. This
     * can't defend against the process being killed outright between the
     * claim and this call (no exception to catch there) — [list] filters
     * empty files too, as the second layer for exactly that path.
     */
    private fun writeClaimedFile(file: File, bytes: ByteArray) {
        try {
            AtomicFile.writeBytes(file, bytes)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /**
     * The `snip_<millis>[_name].wav` timestamp [list]/[newest]/[listWithInfo]
     * all sort by — one parse, shared. [provenanceTag] and [isUsedBy] are
     * its other callers within this object (the USED badge's write and
     * read sides); neither `:app` caller needs the raw millis directly, so
     * this stays `private` rather than crossing the module boundary for no
     * reason.
     */
    private fun parsedTimestamp(file: File): Long? = NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()

    /** The name half of [parsedTimestamp]'s own grammar — `null` for a legacy or never-confidently-classified snip. */
    private fun parsedName(file: File): String? = NAME.matchEntire(file.name)?.groups?.get(2)?.value

    /**
     * [file]'s own label, read straight off its filename — the classified
     * or typed name, or the neutral "SNIP" fallback when there is none.
     * [Info.displayName]/[BinnedSnip.displayName] both already carry this
     * for a row already in hand; this is for a caller (a rename's own
     * result, most concretely) that only has the [File] itself.
     */
    fun displayName(file: File): String = parsedName(file) ?: "SNIP"

    /**
     * `root/[DIR]`'s non-empty files — the [list]/[listWithInfo] shared
     * starting point. `isFile` excludes [binDir] itself (a subdirectory
     * living inside `[DIR]`, the same way `Rooms/.bin` sits inside
     * `Rooms/`) explicitly, rather than relying on [NAME] simply failing to
     * match its name — a directory that could never look like a snip
     * either way, but this says so instead of leaving it to a coincidence
     * of the regex. Zero-length files are skipped too: `freshFile`'s claim
     * (`createNewFile()`) creates the file before any bytes land, so a
     * process kill between the claim and [writeClaimedFile] can leave an
     * empty file with a perfectly matching name — not a snip yet, and not
     * one [com.snipsnap.audio.WavReader] could read regardless.
     */
    private fun snipFiles(root: File): List<File> {
        val dir = File(root, DIR)
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.length() > 0L }
    }

    /**
     * The dir's `snip_*.wav` files, newest first by the timestamp in the
     * name.
     */
    fun list(root: File): List<File> {
        return snipFiles(root)
            .mapNotNull { f -> parsedTimestamp(f)?.let { f to it } }
            .sortedByDescending { (_, ts) -> ts }
            .map { (f, _) -> f }
    }

    fun newest(root: File): File? = list(root).firstOrNull()

    /** The bin's folder under the snips, beside the snips: a deleted snip sleeps here, like every other delete, before it is gone. */
    const val BIN_DIR = ".bin"

    /** How long the bin keeps a deleted snip — [delete]'s promise, [sweepBin]'s job; [KitShelf.BIN_DAYS]'s own shape, for one file instead of a folder. */
    const val BIN_DAYS = 30.0

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Where deleted snips sleep under [root]. */
    private fun binDir(root: File): File = File(File(root, DIR), BIN_DIR)

    /**
     * A bin filename: the binning moment first (purely numeric, so it can
     * never be mistaken for the start of the original name's own
     * `snip_...` literal), then an underscore, then the original filename
     * verbatim — `1755700000000_snip_1755600000000_Kick.wav`. Unambiguous
     * to parse back apart ([binned]'s own `BIN_NAME`) because the original
     * half always starts with the fixed literal `snip_`, which a purely
     * numeric binning stamp never does.
     */
    private fun binFileName(binnedAtMillis: Long, originalFileName: String) = "${binnedAtMillis}_$originalFileName"

    private val BIN_NAME = Regex("""(\d+)_(snip_.+)""")

    /**
     * DELETE: [file] moves into the bin rather than disappearing — the
     * app's one rule for a delete, [KitShelf.deleteKit]/[Rooms.forget]'s
     * own promise applied to one snip. `false` when [file] is already gone
     * (a stale row, a second delete racing this one) or the move itself
     * fails; the caller's own single-shot dialog is what keeps two deletes
     * of the *same* row from racing each other at all. The bin filename is
     * stamped with [nowMillis] as its own binning moment ([binFileName]),
     * bumped forward on the vanishingly unlikely event two deletes of
     * files with byte-identical names land in the same millisecond — the
     * same bump idiom [freshFile] already uses for the live side.
     */
    fun delete(file: File, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!file.isFile) return false
        // file's own parent IS root/[DIR] already (every live snip lives
        // directly there) — [binDir] takes the app's root, not this, so
        // the bin sits beside file at File(file.parentFile, BIN_DIR)
        // rather than through that helper, which would double up DIR.
        val bin = File(file.parentFile ?: return false, BIN_DIR).apply { mkdirs() }
        var millis = nowMillis
        while (true) {
            val target = File(bin, binFileName(millis, file.name))
            if (!target.exists()) return moveFile(file, target)
            millis++
        }
    }

    /** A snip asleep in the bin: its own capture time and name (read off the preserved original filename, never guessed), its size, and when it was binned. */
    data class BinnedSnip(val file: File, val capturedAtMillis: Long, val name: String?, val sizeBytes: Long, val binnedAtMillis: Long) {
        /** The row's own label — the classified/typed name, or the neutral "SNIP" fallback. Never a guess presented as fact. */
        val displayName: String get() = name ?: "SNIP"

        /**
         * Days left before [sweepBin] takes it, rounded UP so the readout
         * agrees with the sweep that acts on it — a partial day left still
         * reads 1, and 0 only at the boundary where the snip actually
         * goes. [KitShelf.BinnedKit.daysLeft]/[Rooms.Binned.daysLeft]'s own
         * convention, kept so every bin in the app agrees about the same
         * moment.
         */
        fun daysLeft(nowMillis: Long, keepDays: Double = BIN_DAYS): Int {
            val left = (binnedAtMillis + (keepDays * DAY_MS).toLong() - nowMillis).coerceAtLeast(0L)
            return ((left + DAY_MS - 1) / DAY_MS).toInt()
        }
    }

    /** Every snip asleep in the bin, the most recently binned first. A bin file whose name doesn't parse is skipped, not fatal. */
    fun binned(root: File): List<BinnedSnip> {
        val bin = binDir(root)
        val files = bin.listFiles { f: File -> f.isFile && f.length() > 0L } ?: return emptyList()
        return files.mapNotNull { f ->
            val m = BIN_NAME.matchEntire(f.name) ?: return@mapNotNull null
            val binnedAtMillis = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val original = File(bin, m.groupValues[2])
            val capturedAtMillis = parsedTimestamp(original) ?: return@mapNotNull null
            BinnedSnip(f, capturedAtMillis, parsedName(original), f.length(), binnedAtMillis)
        }.sortedByDescending { it.binnedAtMillis }
    }

    /**
     * RESTORE: [binnedSnip] back into `snips/`, under its own original
     * name and capture time — never a fresh timestamp, which would lie
     * about when it was actually caught. A collision at that exact name
     * (essentially impossible in real use, since [freshFile]'s millis are
     * globally unique among live snips going forward — but real enough
     * under a fixed clock, or a hand-placed file, to guard rather than
     * assume away) freshens only the NAME half, " 2", " 3"… — the same
     * suffix-bump [KitShelf.restoreKit]'s own `freshShelfName` uses — the
     * capture time never moves. `null` when [binnedSnip]'s file
     * is already gone (a stale row, a second restore racing this one) or
     * the move itself fails.
     */
    fun restore(root: File, binnedSnip: BinnedSnip): File? {
        if (!binnedSnip.file.isFile) return null
        val dir = File(root, DIR).apply { mkdirs() }
        val originalName = BIN_NAME.matchEntire(binnedSnip.file.name)?.groupValues?.get(2) ?: return null
        var target = File(dir, originalName)
        if (target.exists()) {
            var n = 2
            while (true) {
                val candidate = File(dir, fileName(binnedSnip.capturedAtMillis, "${binnedSnip.name ?: "SNIP"} $n"))
                if (!candidate.exists()) {
                    target = candidate
                    break
                }
                n++
            }
        }
        return if (moveFile(binnedSnip.file, target)) target else null
    }

    /** Empties [root]'s snip bin of whatever has slept there past [keepDays]; returns how many went — [KitShelf.sweepDeletedKits]/[Rooms.sweepBin]'s own job, for snips. */
    fun sweepBin(root: File, nowMillis: Long = System.currentTimeMillis(), keepDays: Double = BIN_DAYS): Int {
        var gone = 0
        val keepMs = (keepDays * DAY_MS).toLong()
        for (b in binned(root)) {
            if (nowMillis - b.binnedAtMillis >= keepMs && b.file.delete()) gone++
        }
        return gone
    }

    /** EMPTY THE BIN NOW: every snip asleep in [root]'s bin gone now — an early sweep the user asked for, not [sweepBin]'s age check. Returns how many went; 0 without throwing when the bin is empty or was never created. */
    fun emptyBin(root: File): Int {
        val children = binDir(root).listFiles() ?: return 0
        return children.count { it.delete() }
    }

    /**
     * [from] to [to], preferring an atomic rename and falling back to
     * copy-then-delete when the two paths can't be renamed across in one
     * step ([Rooms]'s own `move` — both live under the same parent in
     * every call site today, so the fallback is defense in depth, not an
     * expected path).
     */
    private fun moveFile(from: File, to: File): Boolean {
        return try {
            java.nio.file.Files.move(from.toPath(), to.toPath())
            true
        } catch (e: java.io.IOException) {
            try {
                from.copyTo(to, overwrite = false)
                from.delete()
            } catch (e2: Exception) {
                false
            }
        }
    }

    /** What the SNIPS shelf lists a row from — no decode, unlike duration (see [listWithInfo]'s own KDoc). */
    data class Info(val file: File, val sizeBytes: Long, val capturedAtMillis: Long, val name: String?) {
        /** The row's own label — the classified/typed name, or the neutral "SNIP" fallback. Never a guess presented as fact. */
        val displayName: String get() = name ?: "SNIP"
    }

    /**
     * [list] plus size, the captured timestamp, and the name half of the
     * filename ([Info.displayName]'s "SNIP" fallback when there is none),
     * newest first — cheap: only [File.length] and the filename parse
     * [parsedTimestamp]/[parsedName] already share with [list]/[newest],
     * so this and they can never disagree on ordering. Deliberately
     * missing: duration. That needs a full [com.snipsnap.audio.WavReader]
     * decode, which this list must not pay for up front — the SNIPS
     * screen computes it lazily per-row instead.
     */
    fun listWithInfo(root: File): List<Info> {
        return snipFiles(root)
            .mapNotNull { f -> parsedTimestamp(f)?.let { ts -> Info(f, f.length(), ts, parsedName(f)) } }
            .sortedByDescending { it.capturedAtMillis }
    }

    /**
     * RENAME: [file] under [newName] — `null` immediately when [newName]
     * isn't [Names.isMpcSafe] (no partial rename is ever attempted on a
     * name the card couldn't hold, [KitShelf.renameKit]'s own posture) or
     * when [file] is already gone. A no-op (hands back [file] unchanged)
     * when [newName] already matches the file's own current name half.
     * The capture time embedded in the filename never changes — only the
     * name half does, so [parsedTimestamp] keeps agreeing with reality.
     * `null` on collision with an already-existing path: unlike the bin's
     * [restore], a live rename target can only collide with itself (the
     * capture time in the target name is [file]'s own, and [freshFile]
     * already guarantees no two live snips ever share a capture time), so
     * a genuine collision here means something outside this API's own
     * writes already claimed that exact path — worth refusing loudly
     * rather than silently freshening past a name someone else's file is
     * using.
     */
    fun rename(file: File, newName: String): File? {
        if (!Names.isMpcSafe(newName)) return null
        if (!file.isFile) return null
        val dir = file.parentFile ?: return null
        val millis = parsedTimestamp(file) ?: return null
        if (newName == parsedName(file)) return file
        val target = File(dir, fileName(millis, newName))
        if (target == file) return file
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    /**
     * The provenance [com.snipsnap.shell.KitBuilderModel.assign] should
     * record when a pad is populated straight from an existing snip file:
     * `"file"` ([snip]'s current name, kept for display — the pad sheet's
     * own source line, [KitBuilder.kt]'s `assign` KDoc) plus, whenever
     * [snip]'s own filename actually parses ([parsedTimestamp]),
     * `"capturedAtMillis"` — [snip]'s immutable capture time, unaffected by
     * a later [rename]. Both keys land together on every fresh assign;
     * [isUsedBy] is what makes the second one load-bearing.
     */
    fun provenanceTag(snip: File): Map<String, String> {
        val tag = linkedMapOf("file" to snip.name)
        parsedTimestamp(snip)?.let { tag["capturedAtMillis"] = it.toString() }
        return tag
    }

    /**
     * The SNIPS shelf's USED badge, per pad per snip: does [pad]'s own
     * provenance genuinely reference [snip]? `source["capturedAtMillis"]`
     * decides it whenever present — matched against [snip]'s own immutable
     * capture time via the shared [parsedTimestamp] parse (never a second,
     * possibly-drifting parse of the same grammar) — which is what lets an
     * assign recorded through [provenanceTag] survive a [rename]: the
     * regression this fixes is that the old `source["file"]`-only check
     * broke the instant a rename changed the filename out from under it.
     *
     * A pad tagged before this key existed (or whose `capturedAtMillis`
     * simply fails to parse — hand-edited kit.json, say) falls back to
     * comparing `source["file"]` against [snip]'s CURRENT name: the
     * pre-existing behaviour, unchanged, so an untouched legacy kit.json
     * keeps resolving exactly as it always did, and one whose target snip
     * HAS since been renamed goes back to matching nothing rather than
     * matching something wrong — a known, accepted limitation of the
     * legacy fallback, not a bug this function tries to paper over.
     *
     * Never the reverse order (a filename match overriding a
     * `capturedAtMillis` mismatch): a coincidental name collision
     * producing a false "USED" would be worse than the badge silently
     * going missing. A collision can't happen between two DIFFERENT
     * captures either way — [fileName] always embeds the writing file's
     * own millis, and [freshFile] guarantees those millis are unique among
     * live snips — so the legacy fallback can only ever go quiet on a
     * genuine match, never point at a stranger.
     */
    fun isUsedBy(pad: KitPad, snip: File): Boolean {
        val taggedMillis = pad.source["capturedAtMillis"]?.toLongOrNull()
        if (taggedMillis != null) return taggedMillis == parsedTimestamp(snip)
        val taggedFile = pad.source["file"]?.takeIf { it.isNotBlank() } ?: return false
        return taggedFile == snip.name
    }
}
