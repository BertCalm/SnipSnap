package com.snipsnap.synth

import com.snipsnap.audio.Pghi
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Spectral
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        val rowForBin = rowForBin(sampleRate, height)

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

    /**
     * [read] run backwards: a picture of [snip], [width] columns of time by
     * [height] rows of log frequency, on exactly the scale [read] plays a
     * picture back on — so the portrait of a sound, read, is that sound
     * again, less whatever the trip loses.
     *
     * Each row takes the loudest of the bins [read] would light from it,
     * so a partial between two rows is never averaged into the dark; a low
     * row finer than the FFT's own bins interpolates between the two it
     * falls between. Brightness is the square root of level against the
     * whole portrait's peak — [read]'s power curve undone — so a fading
     * tail fades on the picture too.
     *
     * The colour carries pitch the way SNAP hears it ([Snap.hueForSemitones]),
     * at exactly the brightness the level asks for: [read] sees only
     * brightness, so the tint costs the round trip nothing and the eye
     * gets a second reading of the same rows.
     */
    fun portrait(snip: Snip, width: Int = PORTRAIT_WIDTH, height: Int = PORTRAIT_HEIGHT): Photo {
        require(width > 0 && height > 0) { "a portrait needs a width and a height: ${width}x$height" }
        val frames = analysisFrames(snip)
        val rowForBin = rowForBin(snip.sampleRate, height)
        val binsForRow = Array(height) { ArrayList<Int>() }
        for ((b, row) in rowForBin.withIndex()) binsForRow[row] += b
        val binWidth = snip.sampleRate.toFloat() / Spectral.FRAME
        val logSpan = ln((snip.sampleRate / 2f) / MIN_HZ)

        val levels = Array(height) { FloatArray(width) }
        var peak = 0f
        for (x in 0 until width) {
            val column = frameAt(frames, x, width)
            for (y in 0 until height) {
                val bins = binsForRow[y]
                val level = if (bins.isNotEmpty()) {
                    bins.maxOf { column[it] }
                } else {
                    val hz = rowHz(y, height, logSpan)
                    val at = (hz / binWidth).coerceIn(0f, (Spectral.BINS - 1).toFloat())
                    val b0 = at.toInt().coerceAtMost(Spectral.BINS - 2)
                    val frac = at - b0
                    column[b0] + (column[b0 + 1] - column[b0]) * frac
                }
                levels[y][x] = level
                if (level > peak) peak = level
            }
        }
        val tints = IntArray(height) { y ->
            val semitones = (12.0 * ln(rowHz(y, height, logSpan) / A2_HZ) / ln(2.0)).toFloat()
            Photo.hue(Snap.hueForSemitones(semitones))
        }
        return Photo.of(width, height) { x, y ->
            val lum = if (peak <= 0f) 0f else sqrt(levels[y][x] / peak)
            Photo.tint(tints[y], lum)
        }
    }

    /** Columns a [portrait] has unless asked otherwise: enough for a pad's detail, small enough to hold as a picture. */
    const val PORTRAIT_WIDTH = 256

    /** Rows a [portrait] has unless asked otherwise: a quarter-semitone apart over the ~9 octaves [read] spans. */
    const val PORTRAIT_HEIGHT = 256

    private const val A2_HZ = 110.0

    /**
     * Which photo row each magnitude bin reads, at [height] rows — fixed
     * for the whole grid, since it depends only on the bin's own
     * frequency, never on time. Bin b's own frequency is Spectral's plain
     * linear FFT spacing (b * sampleRate / FRAME) — the log scale only
     * decides which ROW that linear frequency reads from, never bin b's
     * own frequency itself. [read] and [portrait] share it, which is what
     * makes one the other's way back.
     */
    private fun rowForBin(sampleRate: Int, height: Int): IntArray {
        val logSpan = ln((sampleRate / 2f) / MIN_HZ)
        return IntArray(Spectral.BINS) { b ->
            val hz = Spectral.binHz(b, sampleRate).coerceAtLeast(MIN_HZ)
            val highFraction = (ln(hz / MIN_HZ) / logSpan).coerceIn(0f, 1f)
            ((1f - highFraction) * (height - 1)).roundToInt().coerceIn(0, height - 1)
        }
    }

    /** The frequency at the centre of row [y]: [rowForBin]'s log scale, solved for hertz. */
    private fun rowHz(y: Int, height: Int, logSpan: Float): Float {
        val highFraction = if (height == 1) 0f else 1f - y.toFloat() / (height - 1)
        return MIN_HZ * exp(highFraction * logSpan)
    }

    /**
     * [snip]'s magnitude frames, channels averaged, keeping only frames
     * centred inside the audio: [Spectral.forEachFrame] pads a frame of
     * silence at each end, and a portrait that drew them would add a dark
     * margin that the next [read] plays back as time that was not there.
     * Centred rather than wholly inside, so the picture's edges are the
     * sound's edges and a pass does not stretch time; the cost is an edge
     * column a little darker, its frame half over the end.
     */
    private fun analysisFrames(snip: Snip): List<FloatArray> {
        val sums = ArrayList<FloatArray>()
        Spectral.forEachFrame(snip) { _, frame, mags ->
            while (sums.size <= frame) sums += FloatArray(Spectral.BINS)
            val sum = sums[frame]
            for (b in 0 until Spectral.BINS) sum[b] += mags[b] / snip.channels
        }
        val inside = sums.filterIndexed { k, _ ->
            val centre = k * Spectral.HOP - Spectral.FRAME / 2
            centre in 0 until snip.frameCount
        }
        return inside.ifEmpty { listOf(FloatArray(Spectral.BINS)) }
    }

    /** The magnitude frame under column [x] of [width], interpolated between neighbours as [read] interpolates columns. */
    private fun frameAt(frames: List<FloatArray>, x: Int, width: Int): FloatArray {
        if (frames.size == 1 || width == 1) return frames[0]
        val pos = x.toDouble() * (frames.size - 1) / (width - 1)
        val f0 = pos.toInt().coerceIn(0, frames.size - 1)
        val f1 = min(f0 + 1, frames.size - 1)
        val frac = (pos - f0).toFloat()
        val a = frames[f0]
        val b = frames[f1]
        return FloatArray(Spectral.BINS) { a[it] + (b[it] - a[it]) * frac }
    }
}
