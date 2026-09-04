package com.snipsnap.shell

import com.snipsnap.audio.CaptureDoctor
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.CleanupConfig
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

        val dir = File(root, DIR).apply { mkdirs() }
        val file = File(dir, "snip_$nowMillis.wav")
        val bytes = ByteArrayOutputStream().apply { WavWriter.write(this, toWrite) }.toByteArray()
        AtomicFile.writeBytes(file, bytes)
        return file
    }

    /** The dir's `snip_*.wav` files, newest first by the timestamp in the name. */
    fun list(root: File): List<File> {
        val dir = File(root, DIR)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .mapNotNull { f -> NAME.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()?.let { f to it } }
            .sortedByDescending { (_, ts) -> ts }
            .map { (f, _) -> f }
    }

    fun newest(root: File): File? = list(root).firstOrNull()
}
