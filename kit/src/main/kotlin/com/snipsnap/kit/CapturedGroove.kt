package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Steal the rhythm, not just the sounds: the chop pipeline knows where
 * every hit was, what it became, and the tempo — which is a pattern. This
 * reconstructs the captured performance as the [Mpc3Clip] the native
 * formats already carry, so a chopped break arrives on the MPC **already
 * sequenced in its own rhythm**.
 *
 * Timing is anchored on the first hit (captures rarely start on the one)
 * and by default kept **as captured** — the feel is the point. Pass a
 * [quantizeTo] grid (e.g. [Mpc3Clip.PULSES_PER_16TH]) to snap it straight.
 * Velocities carry the capture's own dynamics: each hit's peak relative to
 * the loudest hit.
 */
object CapturedGroove {

    /** One placed hit of the capture. */
    data class Hit(
        /** 1-based pad slot the slice landed on. */
        val padSlot: Int,
        /** Where the hit happened in the source, in frames. */
        val sourceFrame: Long,
        /** How long the slice plays, in frames. */
        val lengthFrames: Long,
        /** 0..1; the capture's dynamics. */
        val velocity: Float,
    ) {
        init {
            require(padSlot in 1..128) { "padSlot out of range: $padSlot" }
            require(sourceFrame >= 0) { "sourceFrame must not be negative" }
            require(lengthFrames > 0) { "lengthFrames must be positive" }
            require(velocity in 0f..1f) { "velocity out of range: $velocity" }
        }
    }

    /** Notes shorter than a 64th read as data errors on the grid; floor there. */
    const val MIN_NOTE_PULSES = 60L

    /** A slice's tail can run long; a note longer than a bar reads wrong. */
    const val MAX_NOTE_PULSES = Mpc3Clip.PULSES_PER_BAR

    fun clip(
        name: String,
        hits: List<Hit>,
        bpm: Float,
        sampleRate: Int,
        /** Pulse grid to snap note starts to; null = as captured. */
        quantizeTo: Long? = null,
    ): Mpc3Clip {
        require(hits.isNotEmpty()) { "no hits, no groove" }
        require(bpm > 0f) { "bpm must be positive: $bpm" }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        quantizeTo?.let { require(it > 0) { "quantizeTo must be positive: $it" } }

        val pulsesPerFrame = bpm / 60.0 * 960.0 / sampleRate
        val anchor = hits.minOf { it.sourceFrame }

        val notes = hits.sortedBy { it.sourceFrame }.map { hit ->
            var time = ((hit.sourceFrame - anchor) * pulsesPerFrame).roundToLong()
            if (quantizeTo != null) {
                time = (time + quantizeTo / 2) / quantizeTo * quantizeTo
            }
            Mpc3Note(
                // The writer's chromatic map: pad A0N plays note 36+N-1.
                note = 36 + hit.padSlot - 1,
                timePulses = time,
                velocity = hit.velocity,
                lengthPulses = (hit.lengthFrames * pulsesPerFrame).roundToLong()
                    .coerceIn(MIN_NOTE_PULSES, MAX_NOTE_PULSES),
            )
        }

        val lastPulse = notes.maxOf { it.timePulses }
        val bars = (lastPulse / Mpc3Clip.PULSES_PER_BAR + 1).toInt().coerceIn(1, 64)
        // A capture longer than 64 bars of clip has stopped being a groove;
        // drop what falls outside rather than failing the whole export.
        val kept = notes.filter { it.timePulses < bars * Mpc3Clip.PULSES_PER_BAR }
        return Mpc3Clip(name, bars, kept)
    }
}
