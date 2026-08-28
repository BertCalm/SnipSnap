package com.snipsnap.audio

/**
 * The Capture Doctor (wave MM) — `doctor`'s sibling with a different
 * patient. The mix doctor treats what the *mix* does wrong (masking,
 * levels, DC); this treats what the *capture* arrived with: the phone
 * mic in a room. Mains hum, clicks and dropouts, the noise floor.
 *
 * House rules, same as everywhere: measure first, act only on what
 * measurably exists, and leave clean audio untouched — the detectors
 * gate the treatments, so "nothing found" means bytes unchanged, not
 * "processed gently".
 */
object CaptureDoctor {

    // ---- hum (MM1) --------------------------------------------------------

    /** What the probe heard: which mains, how loud, how many harmonics stand out. */
    data class HumReport(
        val hz: Float,
        /** The fundamental's level, dBFS. */
        val levelDb: Float,
        /** Harmonics (fundamental included) that stand out — all get notched. */
        val harmonics: Int,
    )

    /** The two mains the world runs on. */
    val MAINS_HZ = listOf(50f, 60f)

    /** A hum must beat its spectral neighbors by this amplitude ratio to be real. */
    const val HUM_STANDOUT = 4f

    /** Below this amplitude (~ −80 dBFS) a hum isn't worth a filter. */
    const val HUM_FLOOR = 1e-4f

    /** Narrow — a notch takes the hum, not the kick. */
    const val NOTCH_Q = 30f

    const val MAX_HARMONICS = 4

    /** The probe listens to at most this much audio — plenty for a steady hum. */
    private const val PROBE_SEC = 4f

    /**
     * Is there mains hum in this capture? A tone probe at 50 and 60 Hz,
     * each judged against its own spectral neighborhood (±4 Hz) — a hum
     * is a *steady narrow* peak; a kick sweeping through 50 Hz smears.
     * Null when nothing stands out.
     */
    fun detectHum(snip: Snip): HumReport? {
        val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
        val n = minOf(mono.frameCount, (PROBE_SEC * mono.sampleRate).toInt())
        if (n < mono.sampleRate / 5) return null

        fun amp(hz: Float) = goertzel(mono.samples, n, hz, mono.sampleRate)
        var best: HumReport? = null
        for (f0 in MAINS_HZ) {
            val a = amp(f0)
            val base = maxOf(amp(f0 - 4f), amp(f0 + 4f), 1e-9f)
            if (a < HUM_FLOOR || a < HUM_STANDOUT * base) continue
            var harmonics = 1
            for (h in 2..MAX_HARMONICS) {
                val hf = f0 * h
                if (hf >= mono.sampleRate / 2f) break
                val ha = amp(hf)
                if (ha >= HUM_FLOOR && ha >= 2f * maxOf(amp(hf - 4f), amp(hf + 4f), 1e-9f)) {
                    harmonics = h
                }
            }
            val report = HumReport(f0, 20f * Math.log10(a.toDouble()).toFloat(), harmonics)
            if (best == null || report.levelDb > best.levelDb) best = report
        }
        return best
    }

    /**
     * Take the reported hum out: a narrow biquad notch at the fundamental
     * and each standing harmonic, per channel. Callers gate on
     * [detectHum] — this always filters what it's told to.
     */
    fun removeHum(snip: Snip, report: HumReport): Snip {
        val out = snip.samples.copyOf()
        for (h in 1..report.harmonics) {
            val hz = report.hz * h
            if (hz >= snip.sampleRate / 2f) break
            for (ch in 0 until snip.channels) {
                notchInPlace(out, snip.channels, ch, hz, snip.sampleRate)
            }
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** Goertzel amplitude of [hz] over the first [n] frames. */
    internal fun goertzel(samples: FloatArray, n: Int, hz: Float, rate: Int): Float {
        val w = 2.0 * Math.PI * hz / rate
        val coeff = 2.0 * Math.cos(w)
        var s0: Double
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            s0 = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return (2.0 * Math.sqrt(power.coerceAtLeast(0.0)) / n).toFloat()
    }

    /** RBJ cookbook notch, direct form 1, one channel of an interleaved buffer. */
    private fun notchInPlace(samples: FloatArray, channels: Int, ch: Int, hz: Float, rate: Int) {
        val w0 = 2.0 * Math.PI * hz / rate
        val cw = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * NOTCH_Q)
        val a0 = 1.0 + alpha
        val b0 = 1.0 / a0
        val b1 = -2.0 * cw / a0
        val b2 = 1.0 / a0
        val a1 = -2.0 * cw / a0
        val a2 = (1.0 - alpha) / a0

        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        var i = ch
        while (i < samples.size) {
            val x = samples[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            samples[i] = y.toFloat()
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            i += channels
        }
    }
}
