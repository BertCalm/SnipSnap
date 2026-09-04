package com.snipsnap.shell

import com.snipsnap.audio.CaptureDoctor
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.CleanupConfig
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
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

    private val NAME = Regex("""snip_(\d+)\.wav""")

    /**
     * Runs [samples] through the commit-time [Cleanup] chain (DC offset,
     * then trim, then normalise, then fades — the order [Cleanup] documents
     * as load-bearing), then [CaptureDoctor]'s default visit (no denoise, no
     * declip, no deverb — those repairs stay opt-in for a later screen; only
     * the diagnosis rides along, unused here), then writes the result to
     * `root/snips/snip_<nowMillis>.wav`.
     *
     * A quiet room trims to nothing under [Cleanup] — that is not an error.
     * Rather than hand an empty buffer to the doctor (or throw), this falls
     * back to the untrimmed original with DC-removal and normalisation still
     * applied, so a silent minute still commits a small, honest file instead
     * of crashing a foreground service.
     */
    fun commit(samples: FloatArray, sampleRate: Int, root: File, nowMillis: Long): File {
        val original = Snip(samples, channels = 1, sampleRate = sampleRate)
        val cleaned = Cleanup.process(original)

        val toWrite = if (cleaned.frameCount > 0) {
            CaptureDoctor.clean(cleaned).snip
        } else {
            // All-silence trim: skip the doctor entirely (nothing to
            // diagnose in dead air) and fall back to the untrimmed original,
            // still DC-corrected and normalised.
            Cleanup.process(original, CleanupConfig(trimSilence = false))
        }

        val dir = File(root, DIR).apply { mkdirs() }
        val file = File(dir, "snip_$nowMillis.wav")
        WavWriter.write(file, toWrite)
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
