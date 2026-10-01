package com.snipsnap.synth

/**
 * What ARCO's tests measure on the bow's own wave, at the rate the bow runs at (the raw core, before the body,
 * the band limit, the decimator and the level, so a stuck or silent render cannot be lifted to full scale and hide).
 *
 * One slip a period is read off the string's velocity under the bow ([Strings.Bow.bowPoint]) as the *gaps between
 * its slips*, not as a count over a fixed window. A count over ten nominal periods reads 11 or 9 whenever the
 * note is a few cents off its nominal pitch (ten periods of a note 3 cents sharp are 10.02 long, so one window in
 * fifty holds an eleventh slip) - the first R1b maps lost half their cells to that. A gap near one period is a
 * clean Helmholtz slip; a gap near half or a third of it is a double or a triple slip, and no pitch error can
 * make that.
 */
internal object ArcoMeasure {

    /** The rate the bow runs at: [Dsp.RATE] times [Dsp.OVERSAMPLE]. */
    val RATE = Dsp.RATE * Dsp.OVERSAMPLE

    /** A gap is clean when it is one period long to within this share. */
    const val CLEAN_TOLERANCE = 0.15

    /** Where [bowPoint] falls through half of [vBow] (a slip begins), to a fraction of a sample, up to sample [to]. */
    fun slipTimes(bowPoint: FloatArray, to: Int, vBow: Float = Arco.V_SUSTAIN): DoubleArray {
        val threshold = 0.5f * vBow
        val found = ArrayList<Double>()
        for (i in 1 until minOf(to, bowPoint.size)) {
            if (bowPoint[i - 1] >= threshold && bowPoint[i] < threshold) {
                found.add(i - 1 + (bowPoint[i - 1] - threshold).toDouble() / (bowPoint[i - 1] - bowPoint[i]))
            }
        }
        return found.toDoubleArray()
    }

    /** True when the gap from [slips]`[i - 1]` to [slips]`[i]` is not one period of [hz] to within [CLEAN_TOLERANCE]. */
    fun unclean(slips: DoubleArray, i: Int, hz: Float): Boolean {
        val period = RATE / hz.toDouble()
        val gap = slips[i] - slips[i - 1]
        return gap < (1 - CLEAN_TOLERANCE) * period || gap > (1 + CLEAN_TOLERANCE) * period
    }

    /** How many gaps between slips that end after [fromSeconds] are not one period of [hz] long. */
    fun uncleanGaps(slips: DoubleArray, hz: Float, fromSeconds: Double = 0.0): Int {
        var n = 0
        for (i in 1 until slips.size) if (slips[i] / RATE >= fromSeconds && unclean(slips, i, hz)) n++
        return n
    }

    /**
     * When the string locks into one slip a period for good, in seconds: the time of the first slip after the
     * last unclean gap up to sample [end]; -1 if there are too few slips to tell or the last gap is still unclean.
     */
    fun lockSeconds(bowPoint: FloatArray, end: Int, hz: Float, vBow: Float = Arco.V_SUSTAIN): Double {
        val slips = slipTimes(bowPoint, end, vBow)
        if (slips.size < 12) return -1.0
        var lastBad = -1
        for (i in 1 until slips.size) if (unclean(slips, i, hz)) lastBad = i
        if (lastBad == slips.size - 1) return -1.0
        return (if (lastBad < 0) slips[0] else slips[lastBad]) / RATE
    }
}
