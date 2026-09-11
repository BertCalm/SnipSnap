package com.snipsnap.shell

import com.snipsnap.audio.PadCapture
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Transients
import java.io.File

/**
 * A pad capture off the mic ring (GRAB, HOLD) lands as a tape too.
 *
 * Before this, GRAB and HOLD cut their one-shot straight from the ring
 * snapshot and the pad remembered nothing — the one kind of pad RE-TRIM
 * had to refuse. Now the raw snapshot goes through the same chain a SNIP
 * gets ([SnipStore.prepare]) and becomes the tape; the pad is a cut of
 * that very audio ([Retrim.cut], so BACK ONTO reproduces it exactly); and
 * the pad's provenance names the tape and the cut ([tag]). The caller
 * writes the tape with [SnipStore.commitPrepared] and assigns [Landing.pad].
 *
 * Pure: audio in, audio and a range out. The gate is [PadCapture]'s own
 * ([PadCapture.MIN_ONESHOT_MS]): a cut shorter than that is silence, and a
 * quiet room is "nothing to grab yet", not a tape.
 */
object PadTape {

    /** The tape as it will sit on the shelf, and where the pad's audio sits in it. */
    data class Landing(val tape: Snip, val cut: IntRange) {
        /** The pad's audio: the cut, as CHOP's slice of the tape would be. */
        val pad: Snip get() = Retrim.cut(tape, cut)
    }

    /**
     * GRAB: the last hit in [raw] — the tape is the whole cleaned snapshot,
     * the cut runs from the last onset (at a zero crossing) to its end,
     * the way `PadCapture.grabOneShot` chose it.
     */
    fun grab(raw: FloatArray, sampleRate: Int): Landing? {
        val tape = prepare(raw, sampleRate) ?: return null
        val onsets = Transients.detect(tape)
        val startRaw = onsets.lastOrNull()?.frame ?: 0
        val start = Transients.zeroCrossingBefore(tape, startRaw).coerceIn(0, tape.frameCount)
        return landing(tape, start until tape.frameCount, sampleRate)
    }

    /** HOLD: the press chose the window, so the pad is the whole tape. */
    fun hold(raw: FloatArray, sampleRate: Int): Landing? {
        val tape = prepare(raw, sampleRate) ?: return null
        return landing(tape, 0 until tape.frameCount, sampleRate)
    }

    /** The pad's provenance once [file] holds the tape: the shelf's own tag plus the cut. */
    fun tag(file: File, landing: Landing): Map<String, String> =
        SnipStore.provenanceTag(file) + Retrim.tag(file.name, landing.cut.first, landing.cut.last + 1)

    private fun prepare(raw: FloatArray, sampleRate: Int): Snip? {
        if (raw.isEmpty()) return null
        return SnipStore.prepare(raw, sampleRate, silentFallback = false)?.takeIf { it.frameCount > 0 }
    }

    private fun landing(tape: Snip, cut: IntRange, sampleRate: Int): Landing? {
        val minFrames = (PadCapture.MIN_ONESHOT_MS.toLong() * sampleRate / 1000).toInt()
        if (cut.last + 1 - cut.first < minFrames) return null
        return Landing(tape, cut)
    }
}
