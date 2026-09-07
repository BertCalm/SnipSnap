package com.snipsnap.shell

import com.snipsnap.audio.CaptureDoctor
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.CleanupConfig
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AtomicFile
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

    private val NAME = Regex("""snip_(\d+)\.wav""")

    /**
     * Runs [samples] through the commit-time [Cleanup] chain (DC offset,
     * then trim, then normalise, then fades — the order [Cleanup] documents
     * as load-bearing), then [CaptureDoctor]'s default visit (no denoise, no
     * declip, no deverb — those repairs stay opt-in for a later screen; only
     * the diagnosis rides along, unused here), then writes the result to
     * `root/snips/snip_<nowMillis>.wav` via [AtomicFile] — a write-then-
     * rename, so a process killed mid-write (a foreground mic service
     * holding an 8MB ring buffer is exactly the kind of thing Android kills)
     * never leaves a torn WAV for [list]/[newest] to surface and
     * [com.snipsnap.audio.WavReader] to choke on.
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

        val file = freshFile(root, nowMillis)
        val bytes = ByteArrayOutputStream().apply { WavWriter.write(this, toWrite) }.toByteArray()
        writeClaimedFile(file, bytes)
        return file
    }

    /**
     * `root/snips/snip_<millis>.wav`, at [nowMillis] or the first later
     * millisecond nothing sits on: two snips in one millisecond (a fast
     * phone, a test) must never share a path, or the second silently
     * overwrites the first and [newest] loses one. The bump keeps the
     * name's own ordering honest — later is later.
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
    private fun freshFile(root: File, nowMillis: Long): File {
        val dir = File(root, DIR).apply { mkdirs() }
        var millis = nowMillis
        while (true) {
            val file = File(dir, "snip_$millis.wav")
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
     * tape, so no trim, no normalize, no doctor's visit. Only the two
     * things the deck needs — mono (the ring is mono; the deck reads
     * mono) and the MPC rate (a 48 k or 22.05 k file through the sinc
     * [Resampler]) — and the [IMPORT_MAX_SEC] cap. Written through
     * [AtomicFile] like a commit, named `snip_<nowMillis>.wav` so
     * [newest] ranks it by arrival. An empty file is refused in words.
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
     * The dir's `snip_*.wav` files, newest first by the timestamp in the
     * name. Zero-length files are skipped: `freshFile`'s claim
     * (`createNewFile()`) creates the file before any bytes land, so a
     * process kill between the claim and [writeClaimedFile] can leave an
     * empty file with a perfectly matching name — not a snip yet, and not
     * one [com.snipsnap.audio.WavReader] could read regardless.
     */
    fun list(root: File): List<File> {
        val dir = File(root, DIR)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.length() > 0L }
            .mapNotNull { f -> NAME.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()?.let { f to it } }
            .sortedByDescending { (_, ts) -> ts }
            .map { (f, _) -> f }
    }

    fun newest(root: File): File? = list(root).firstOrNull()
}
