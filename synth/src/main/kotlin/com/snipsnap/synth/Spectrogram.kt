package com.snipsnap.synth

import com.snipsnap.audio.Pghi
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Spectral
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SPECTRUM — the literal reading: a photo *is* a picture of sound.
 * Columns are time, rows are frequency (the photo's own top row is the
 * highest, its bottom row ~40 Hz, log-spaced between — linear would put
 * nine tenths of the picture above 2 kHz), and brightness is level.
 * [read] walks the photo the way [Snap.table] walks a line shorter than
 * its table — the same fractional-index linear interpolation, over the
 * time axis instead — to build one magnitude row per output frame, then
 * hands the whole grid to [Pghi.invert] to become audio.
 */
object Spectrogram {

    /** The bottom row's frequency — low enough for a photo's darkest row to read as a foundation, not silence. */
    private const val MIN_HZ = 40f

    /**
     * Bins under this fraction of their own frame's peak are floored to
     * true zero. A photograph is rarely pure black: every bin lit a
     * little, everywhere, reads as hiss rather than a picture — this and
     * the power curve below ([read]'s own `lum * lum`) are the two knobs
     * that keep a spectrogram legible.
     */
    private const val FLOOR_FRACTION = 0.05f

    /**
     * [photo] read as a spectrogram, [seconds] long. Every bin's
     * magnitude is its column's luminance at its own row, squared — a
     * power curve, so a dark photo is near-silent rather than carrying a
     * hiss floor — with each frame's own quietest bins floored to zero
     * ([FLOOR_FRACTION]) before [Pghi.invert] turns the grid into audio.
     */
    fun read(photo: Photo, seconds: Float, sampleRate: Int = Dsp.RATE, seed: Long = 0): Snip {
        require(seconds > 0f) { "seconds must be positive, got $seconds" }
        val outFrames = (seconds * sampleRate).roundToInt().coerceAtLeast(1)
        // A few frames of slack past what [outFrames] strictly needs: Pghi.invert
        // simply stops using frames once its own accumulator is covered, so a
        // little extra costs nothing and guarantees the tail is never short.
        val frameCount = (outFrames / Spectral.HOP) + 4
        val width = photo.width
        val height = photo.height
        val nyquist = sampleRate / 2f
        val logSpan = ln(nyquist / MIN_HZ)

        // Which photo row each magnitude bin reads — fixed for the whole
        // grid, since it depends only on the bin's own frequency, never
        // on time. Bin b's own frequency is Spectral's plain linear FFT
        // spacing (b * sampleRate / FRAME) — the log scale only decides
        // which ROW that linear frequency reads from, never bin b's own
        // frequency itself.
        val rowForBin = IntArray(Spectral.BINS) { b ->
            val hz = Spectral.binHz(b, sampleRate).coerceAtLeast(MIN_HZ)
            val highFraction = (ln(hz / MIN_HZ) / logSpan).coerceIn(0f, 1f)
            ((1f - highFraction) * (height - 1)).roundToInt().coerceIn(0, height - 1)
        }

        val mags = List(frameCount) { f ->
            // The same else-branch Snap's own `resample` uses for a line
            // shorter than its table: linear interpolation between the
            // two nearest columns, never a column simply repeated.
            val pos = if (width == 1) 0.0 else f.toDouble() * (width - 1) / (frameCount - 1).coerceAtLeast(1)
            val x0 = pos.toInt().coerceIn(0, width - 1)
            val x1 = min(x0 + 1, width - 1)
            val frac = (pos - x0).toFloat()
            val frame = FloatArray(Spectral.BINS)
            var peak = 0f
            for (b in 0 until Spectral.BINS) {
                val row = rowForBin[b]
                val lumA = photo.luminance(x0, row)
                val lumB = photo.luminance(x1, row)
                val lum = lumA + (lumB - lumA) * frac
                val magnitude = lum * lum
                frame[b] = magnitude
                if (magnitude > peak) peak = magnitude
            }
            if (peak > 0f) {
                val floor = peak * FLOOR_FRACTION
                for (b in frame.indices) if (frame[b] < floor) frame[b] = 0f
            }
            frame
        }

        val snip = Pghi.invert(mags, outFrames, sampleRate, seed)
        Dsp.normalize(snip.samples)
        return snip
    }
}
