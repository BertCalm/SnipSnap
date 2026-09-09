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
     * than a stricter character class because a future rename only needs to
     * be gated by filesystem safety, not by whatever this regex alone would
     * otherwise accept.
     */
    private val NAME = Regex("""snip_(\d+)(?:_(.+))?\.wav""")

    /**
     * A capture's own filename, given its true capture time and its name
     * half (null for the neutral fallback). The one place that assembles
     * this shape — today just [freshFile] — so the grammar [NAME] parses
     * can never drift from what actually gets written.
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
     * "SNIP" identity [Info.displayName] shows).
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
     * a tie, only [fileName]'s own construction it reuses.
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
     * give it, so it lands under the plain `snip_<millis>.wav` shape. Only
     * the two things the deck needs — mono (the ring is mono; the deck
     * reads mono) and the MPC rate (a 48 k or 22.05 k file through the sinc
     * [Resampler]) — and the [IMPORT_MAX_SEC] cap. Written through
     * [AtomicFile] like a commit, named `snip_<nowMillis>.wav` so [newest]
     * ranks it by arrival. An empty file is refused in words.
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

    /** The `snip_<millis>[_name].wav` timestamp [list]/[newest]/[listWithInfo] all sort by — one parse, shared. */
    private fun parsedTimestamp(file: File): Long? = NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()

    /** The name half of [parsedTimestamp]'s own grammar — `null` for a legacy or never-confidently-classified snip. */
    private fun parsedName(file: File): String? = NAME.matchEntire(file.name)?.groups?.get(2)?.value

    /**
     * [file]'s own label, read straight off its filename — the classified
     * name, or the neutral "SNIP" fallback when there is none.
     * [Info.displayName] already carries this for a row already in hand;
     * this is for a caller that only has the [File] itself.
     */
    fun displayName(file: File): String = parsedName(file) ?: "SNIP"

    /**
     * `root/[DIR]`'s non-empty files — the [list]/[listWithInfo] shared
     * starting point. Zero-length files are skipped: `freshFile`'s claim
     * (`createNewFile()`) creates the file before any bytes land, so a
     * process kill between the claim and [writeClaimedFile] can leave an
     * empty file with a perfectly matching name — not a snip yet, and not
     * one [com.snipsnap.audio.WavReader] could read regardless.
     */
    private fun snipFiles(root: File): List<File> {
        val dir = File(root, DIR)
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.length() > 0L }
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

    /** SNIPS delete: a straight [File.delete] — success/failure is the caller's own toast to raise. */
    fun delete(file: File): Boolean = file.delete()

    /** What the SNIPS shelf lists a row from — no decode, unlike duration (see [listWithInfo]'s own KDoc). */
    data class Info(val file: File, val sizeBytes: Long, val capturedAtMillis: Long, val name: String?) {
        /** The row's own label — the classified name, or the neutral "SNIP" fallback. Never a guess presented as fact. */
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
     * `null` on collision with an already-existing path: a live rename
     * target can only collide with itself (the capture time in the target
     * name is [file]'s own, and [freshFile] already guarantees no two live
     * snips ever share a capture time), so a genuine collision here means
     * something outside this API's own writes already claimed that exact
     * path — worth refusing loudly rather than silently freshening past a
     * name someone else's file is using.
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
}
