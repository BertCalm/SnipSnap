package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * What a stereo pair costs when the phone folds it, measured one way for
 * every surface that quotes it — [FxTest]'s fold rows and the ENSEMBLE
 * gate page's captions read the same numbers from here, so a figure on
 * the page is the figure the test pins ("one quantity computed in two
 * places" being the house's named defect shape).
 *
 * The fold is the average of the channels, as `KitPreview`, `Loudness.of`
 * and the classifier take it — never the sum. [Report.lossDb] is the
 * house meter's verdict: `Loudness.of` on the fold against the mean of
 * `Loudness.of` on each channel, so its 120 Hz cut and its loudest-200 ms
 * window are part of the answer, as they are part of what the phone hears.
 * [Report.correlation], [Report.rippleDb] and [Report.pumpDb] are measured
 * after the first [FROM_SECONDS], past the onset, where the section has
 * settled. The ripple is the fold's own level swing across 100 ms windows —
 * the three taps beating, which on a steady tone *is* the ensemble, and on
 * a one-shot is mostly its decay, so the test reads it on saws only. The
 * pump is the fold's level *against the pair's* across the same windows,
 * decay cancelled: what a listener comparing a fold clip with its stereo
 * file hears swell and dip, and the number the gate page prints.
 * [Report.equalWindowLossDb] is the plain equal-window RMS loss over that
 * same span, which the identity `√((1 + ρ)/2)` describes exactly at equal
 * channel power; the meter reads more than it on a pitched source,
 * because the cut removes the coherent fundamental and leaves the less
 * coherent harmonics.
 */
internal object FoldMeter {

    /** Where the settled measurements start: past the onset and the first slow cycle's rise. */
    const val FROM_SECONDS = 0.3f

    class Report(
        /** The house meter's fold loss against the channels' mean, dB (negative is a loss). */
        val lossDb: Double,
        /** Pearson correlation of left and right past [FROM_SECONDS]. */
        val correlation: Double,
        /** The fold's level ripple across 100 ms windows past [FROM_SECONDS], peak to trough, dB. */
        val rippleDb: Double,
        /** The fold's level against the pair's mean level across the same windows, peak to trough, dB: the pumping the pair itself does not have. */
        val pumpDb: Double,
        /** The plain equal-window RMS loss of the fold against the channels past [FROM_SECONDS], dB. */
        val equalWindowLossDb: Double,
        /** What the identity `(varL + varR + 2ρ√(varL·varR)) / (2(varL + varR))` predicts for that loss, dB. */
        val equalWindowLawDb: Double,
    ) {
        /** The correlation rounded for a caption, with the sign dropped from a zero so it never prints as -0.00. */
        val correlationShown: Double get() { val r = Math.round(correlation * 100) / 100.0; return if (r == 0.0) 0.0 else r }
    }

    fun report(stereo: Snip, fromSeconds: Float = FROM_SECONDS): Report {
        require(stereo.channels == 2) { "a fold is measured on a pair; got ${stereo.channels} channel(s)" }
        val frames = stereo.frameCount
        val l = FloatArray(frames) { stereo.samples[it * 2] }
        val r = FloatArray(frames) { stereo.samples[it * 2 + 1] }
        val fold = Cleanup.toMono(stereo)
        val rate = stereo.sampleRate
        val lossDb = db(
            Loudness.of(fold).toDouble() /
                ((Loudness.of(Snip(l, 1, rate)) + Loudness.of(Snip(r, 1, rate))) / 2.0),
        )

        val from = (fromSeconds * rate).toInt().coerceAtMost(frames / 2)
        val n = frames - from
        var sl = 0.0; var sr = 0.0; var sf = 0.0
        for (i in from until frames) { sl += l[i]; sr += r[i]; sf += fold.samples[i] }
        val ml = sl / n; val mr = sr / n; val mf = sf / n
        var vl = 0.0; var vr = 0.0; var vf = 0.0; var cov = 0.0
        for (i in from until frames) {
            val dl = l[i] - ml; val dr = r[i] - mr; val df = fold.samples[i] - mf
            vl += dl * dl; vr += dr * dr; vf += df * df; cov += dl * dr
        }
        val correlation = cov / sqrt(vl * vr)
        val equalWindowLossDb = db(sqrt(vf) / sqrt((vl + vr) / 2.0))
        val equalWindowLawDb = db(sqrt((vl + vr + 2.0 * correlation * sqrt(vl * vr)) / (2.0 * (vl + vr))))

        val win = rate / 10
        var lo = Double.MAX_VALUE; var hi = 0.0
        var pumpLo = Double.MAX_VALUE; var pumpHi = 0.0
        var start = from
        while (start + win <= frames) {
            var sumF = 0.0; var sumL = 0.0; var sumR = 0.0
            for (i in start until start + win) {
                sumF += fold.samples[i].toDouble() * fold.samples[i]
                sumL += l[i].toDouble() * l[i]
                sumR += r[i].toDouble() * r[i]
            }
            val rms = sqrt(sumF / win)
            if (rms < lo) lo = rms
            if (rms > hi) hi = rms
            val pair = sqrt((sumL + sumR) / (2.0 * win))
            if (rms > 0.0 && pair > 0.0) {
                val ratio = rms / pair
                if (ratio < pumpLo) pumpLo = ratio
                if (ratio > pumpHi) pumpHi = ratio
            }
            start += win / 2
        }
        val rippleDb = if (lo > 0.0 && hi > 0.0) db(hi / lo) else 0.0
        val pumpDb = if (pumpLo < Double.MAX_VALUE && pumpHi > 0.0) db(pumpHi / pumpLo) else 0.0
        return Report(lossDb, correlation, rippleDb, pumpDb, equalWindowLossDb, equalWindowLawDb)
    }

    /** A caption for a gate page: the loss, the correlation and the pump, each saying how it was measured. */
    fun caption(report: Report): String =
        "the fold sits %.1f dB against the pair by the house meter; left and right correlate %.2f, and against the pair the fold's level swings %.1f dB across 100 ms windows — both measured after the first %d ms"
            .format(report.lossDb, report.correlationShown, report.pumpDb, (FROM_SECONDS * 1000).toInt())

    private fun db(ratio: Double): Double = 20.0 * log10(abs(ratio))
}
