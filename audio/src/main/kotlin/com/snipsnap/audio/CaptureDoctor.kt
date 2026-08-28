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

    // ---- clicks and dropouts (MM2) ----------------------------------------

    /** What the repair pass did — zeros throughout when nothing needed doing. */
    data class Repair(val snip: Snip, val clicks: Int, val dropouts: Int, val repairedFrames: Int) {
        val touched: Boolean get() = repairedFrames > 0
    }

    /** Wider than ~2ms isn't a click, it's music — left alone. */
    const val MAX_CLICK_FRAMES = 96

    /** A click's derivative stands this many sigmas over its block's. */
    const val CLICK_SIGMA = 8f

    /** ...and at least this far in absolute terms, so silence can't flag noise. */
    const val CLICK_FLOOR = 0.05f

    /** More than this fraction of full-scale jumps is distortion — refused. */
    const val DAMAGE_CEILING = 0.01f

    /** A single-sample jump past this is a cliff no sane capture makes often. */
    const val DAMAGE_CLIFF = 1.0f

    /** A digital dropout: at least this many exact zeros in living audio. */
    const val MIN_DROPOUT_FRAMES = 8

    /** ...and at most ~20ms — longer is intentional silence. */
    const val MAX_DROPOUT_FRAMES = 900

    /** An onset's energy persists past it; a click's dies. The follow test. */
    private const val GUARD_FRAMES = 240

    /**
     * Find and repair clicks (derivative outliers with the transient
     * guard) and dropouts (runs of exact zeros inside living audio),
     * per channel, interpolating across each region. When nothing needs
     * repair the input comes back **unchanged**. A capture where more
     * than [DAMAGE_CEILING] of the frames flag as clicks refuses: that
     * is distortion, and pretending to fix it would be a lie.
     */
    fun repairClicks(snip: Snip): Repair {
        val out = snip.samples.copyOf()
        var clicks = 0
        var dropouts = 0
        var repaired = 0

        for (ch in 0 until snip.channels) {
            val x = FloatArray(snip.frameCount) { f -> snip.samples[f * snip.channels + ch] }
            // Dropouts first: their edges are derivative cliffs, and the
            // click hunt must see the mended signal, not the hole's walls.
            val drops = dropoutRegions(x)
            for (r in drops) {
                interpolate(x, 1, 0, r.first, r.last)
                dropouts++
                repaired += r.last - r.first + 1
            }
            val regions = clickRegions(x)
            for (r in regions) {
                interpolate(x, 1, 0, r.first, r.last)
                clicks++
                repaired += r.last - r.first + 1
            }
            if (drops.isNotEmpty() || regions.isNotEmpty()) {
                for (f in 0 until snip.frameCount) out[f * snip.channels + ch] = x[f]
            }
        }
        return if (repaired == 0) {
            Repair(snip, 0, 0, 0)
        } else {
            Repair(Snip(out, snip.channels, snip.sampleRate), clicks, dropouts, repaired)
        }
    }

    /** Click candidates as frame ranges, transient-guarded. */
    private fun clickRegions(x: FloatArray): List<IntRange> {
        if (x.size < 3) return emptyList()
        val flagged = BooleanArray(x.size)
        val block = 4096
        var b = 1
        while (b < x.size) {
            val end = minOf(b + block, x.size)
            var acc = 0.0
            var n = 0
            for (i in b until end) {
                val d = x[i] - x[i - 1]
                acc += d * d
                n++
            }
            val sigma = Math.sqrt(acc / n.coerceAtLeast(1)).toFloat()
            val bar = maxOf(CLICK_SIGMA * sigma, CLICK_FLOOR)
            for (i in b until end) {
                if (Math.abs(x[i] - x[i - 1]) > bar) flagged[i] = true
            }
            b = end
        }

        // The damage ceiling can't lean on the sigma flags - wall-to-wall
        // distortion raises its own sigma until nothing "stands out". It
        // reads an absolute cliff instead: full-scale single-sample jumps,
        // which sane audio essentially never makes and clipping makes
        // constantly. Too many and repair would lie.
        var cliffs = 0
        for (i in 1 until x.size) {
            if (Math.abs(x[i] - x[i - 1]) > DAMAGE_CLIFF) cliffs++
        }
        require(cliffs <= x.size * DAMAGE_CEILING) {
            "$cliffs full-scale jumps in ${x.size} samples - " +
                "that's distortion, not clicks, and repair would lie"
        }

        val regions = mutableListOf<IntRange>()
        var i = 0
        while (i < x.size) {
            if (!flagged[i]) {
                i++
                continue
            }
            var j = i
            var gap = 0
            var k = i
            while (k < x.size && gap <= 8) {
                if (flagged[k]) {
                    j = k
                    gap = 0
                } else {
                    gap++
                }
                k++
            }
            val width = j - i + 1
            if (width <= MAX_CLICK_FRAMES && isClickNotOnset(x, i, j) && isIsolated(x, i, j)) {
                regions += i..j
            }
            i = j + 1
        }
        return regions
    }

    /**
     * A click's jump dwarfs its immediate surroundings; noise (a hat, a
     * snare body) jumps everywhere at once. The region's peak derivative
     * must stand 4× over the biggest derivative just outside it.
     */
    private fun isIsolated(x: FloatArray, start: Int, end: Int): Boolean {
        var peak = 0f
        for (i in maxOf(1, start)..minOf(end, x.size - 1)) {
            val d = Math.abs(x[i] - x[i - 1])
            if (d > peak) peak = d
        }
        var surround = 0f
        for (i in maxOf(1, start - 64) until maxOf(1, start - 1)) {
            val d = Math.abs(x[i] - x[i - 1])
            if (d > surround) surround = d
        }
        for (i in minOf(end + 2, x.size) until minOf(end + 64, x.size)) {
            val d = Math.abs(x[i] - x[i - 1])
            if (d > surround) surround = d
        }
        return surround < peak / 4f
    }

    /** The follow test: a drum onset's energy persists after the jump; a click's dies. */
    private fun isClickNotOnset(x: FloatArray, start: Int, end: Int): Boolean {
        val pre = rms(x, maxOf(0, start - GUARD_FRAMES), maxOf(0, start - 8))
        val post = rms(x, minOf(x.size, end + 8), minOf(x.size, end + GUARD_FRAMES))
        return post <= 3f * pre + 1e-4f
    }

    /** Runs of exact zeros inside living audio — the digital dropout. */
    private fun dropoutRegions(x: FloatArray): List<IntRange> {
        val regions = mutableListOf<IntRange>()
        var i = 0
        while (i < x.size) {
            if (x[i] != 0f) {
                i++
                continue
            }
            var j = i
            while (j + 1 < x.size && x[j + 1] == 0f) j++
            val width = j - i + 1
            val leftAlive = i > 0 && Math.abs(x[i - 1]) > 0.005f
            val rightAlive = j + 1 < x.size && Math.abs(x[j + 1]) > 0.005f
            if (width in MIN_DROPOUT_FRAMES..MAX_DROPOUT_FRAMES && leftAlive && rightAlive) {
                regions += i..j
            }
            i = j + 1
        }
        return regions
    }

    private fun interpolate(samples: FloatArray, channels: Int, ch: Int, startFrame: Int, endFrame: Int) {
        val fromFrame = startFrame - 1
        val toFrame = endFrame + 1
        val from = if (fromFrame >= 0) samples[fromFrame * channels + ch] else 0f
        val to = if (toFrame * channels + ch < samples.size) samples[toFrame * channels + ch] else 0f
        val span = (toFrame - fromFrame).coerceAtLeast(1)
        for (f in startFrame..endFrame) {
            val t = (f - fromFrame).toFloat() / span
            samples[f * channels + ch] = from + (to - from) * t
        }
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return 0f
        var acc = 0.0
        for (i in from until to) acc += x[i] * x[i].toDouble()
        return Math.sqrt(acc / (to - from)).toFloat()
    }

    // ---- the whole visit (MM4) --------------------------------------------

    /** Everything one pass found and did. [snip] is the input itself when untouched. */
    data class CleanReport(
        val hum: HumReport?,
        val clicks: Int,
        val dropouts: Int,
        /** The floor as measured after repairs, dBFS; null on very short audio. */
        val floorDb: Float?,
        val gated: Boolean,
        val snip: Snip,
    ) {
        val touched: Boolean get() = hum != null || clicks > 0 || dropouts > 0 || gated

        /** The one-line diagnosis, every finding named. */
        fun summary(): String {
            val parts = mutableListOf<String>()
            hum?.let { parts += "%.0f Hz hum notched (%d harmonic(s), %.0f dBFS)".format(it.hz, it.harmonics, it.levelDb) }
            if (clicks > 0) parts += "$clicks click(s) repaired"
            if (dropouts > 0) parts += "$dropouts dropout(s) repaired"
            if (gated) parts += "floor %.0f dBFS, gently gated".format(floorDb)
            return if (parts.isEmpty()) "clean - nothing done" else parts.joinToString("; ")
        }
    }

    /**
     * The full visit, every treatment gated by its own detector: hum
     * first (a hum lifts the floor reading), then clicks and dropouts,
     * then the floor and its gentle gate. Nothing found means the input
     * comes back **as-is** — the same object, bytes untouched. Throws
     * like [repairClicks] when the capture is distortion, not clicks.
     */
    fun clean(snip: Snip): CleanReport {
        var cur = snip
        val hum = detectHum(cur)
        if (hum != null) cur = removeHum(cur, hum)
        val repair = repairClicks(cur)
        cur = repair.snip
        val floor = measureFloor(cur)
        val gate = floor != null && floor > CLEAN_FLOOR_DB
        if (gate) cur = expand(cur, floor!!)
        return CleanReport(hum, repair.clicks, repair.dropouts, floor, gate, cur)
    }

    // ---- the noise floor (MM3) --------------------------------------------

    /** Below this floor the capture is clean and the gate stays out of it. */
    const val CLEAN_FLOOR_DB = -60f

    /** The expander eases in below floor + this margin. */
    const val EXPAND_MARGIN_DB = 12f

    /** 2:1 downward — quiet gets quieter, never gone. */
    const val EXPAND_RATIO = 2f

    /** The depth cap: a gentle gate, not a mute. */
    const val MAX_ATTEN_DB = 12f

    private const val FLOOR_WINDOW_SEC = 0.05f
    private const val RELEASE_SEC = 0.08f

    /**
     * The capture's noise floor in dBFS, read from its quietest tenth of
     * [FLOOR_WINDOW_SEC] windows. Null when the audio is too short to
     * say. A floor under [CLEAN_FLOOR_DB] means a clean capture —
     * callers leave those alone.
     */
    fun measureFloor(snip: Snip): Float? {
        val mono = if (snip.channels == 1) snip else Cleanup.toMono(snip)
        val win = (FLOOR_WINDOW_SEC * mono.sampleRate).toInt()
        val hop = win / 2
        if (mono.frameCount < win * 4) return null
        val rmses = mutableListOf<Float>()
        var at = 0
        while (at + win <= mono.frameCount) {
            rmses += rms(mono.samples, at, at + win)
            at += hop
        }
        rmses.sort()
        val floor = rmses[(rmses.size / 10).coerceIn(0, rmses.size - 1)]
        return 20f * Math.log10(floor.toDouble().coerceAtLeast(1e-7)).toFloat()
    }

    /**
     * The gentle gate: a downward expander below `floor + margin`,
     * [EXPAND_RATIO]:1 in dB with the depth capped at [MAX_ATTEN_DB] —
     * hiss recedes, tails breathe, nothing slams shut. The envelope
     * opens instantly (a transient must never be clipped by its own
     * gate) and releases over [RELEASE_SEC]. Channels move together.
     */
    fun expand(snip: Snip, floorDb: Float): Snip {
        val thresholdLin = Math.pow(10.0, (floorDb + EXPAND_MARGIN_DB) / 20.0).toFloat()
        val release = Math.exp(-1.0 / (RELEASE_SEC * snip.sampleRate)).toFloat()
        val out = FloatArray(snip.samples.size)
        var env = 0f
        for (f in 0 until snip.frameCount) {
            var level = 0f
            for (ch in 0 until snip.channels) {
                val v = Math.abs(snip.samples[f * snip.channels + ch])
                if (v > level) level = v
            }
            env = if (level > env) level else level + release * (env - level)
            val gainDb = if (env >= thresholdLin) {
                0f
            } else {
                val envDb = 20f * Math.log10(env.toDouble().coerceAtLeast(1e-7)).toFloat()
                ((envDb - (floorDb + EXPAND_MARGIN_DB)) * (EXPAND_RATIO - 1f))
                    .coerceAtLeast(-MAX_ATTEN_DB)
            }
            val g = Math.pow(10.0, gainDb / 20.0).toFloat()
            for (ch in 0 until snip.channels) {
                out[f * snip.channels + ch] = snip.samples[f * snip.channels + ch] * g
            }
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    // ---- spectral de-noise (NN2) ------------------------------------------

    /** Gate a bin only when it sits within this factor of the profile (+6 dB). */
    const val DENOISE_MARGIN = 2f

    /** The attenuation cap, −12 dB as a gain: noise recedes, never vanishes into warble. */
    const val DENOISE_FLOOR_GAIN = 0.25f

    /** The quietest tenth of frames teaches the fingerprint. */
    private const val QUIET_FRACTION = 0.1f

    /** Fewer quiet frames than this is nothing to learn from. */
    private const val MIN_PROFILE_FRAMES = 8

    /** Per-bin gain release, one pole per frame; opening is instant. */
    private const val GAIN_RELEASE = 0.6f

    /** Gains move with their neighbors: ±2 bins averaged, no lone flickers. */
    private const val FREQ_SMOOTH_BINS = 2

    /** What the deep clean learned and did. */
    data class DenoiseReport(val floorDb: Float, val profileFrames: Int, val snip: Snip)

    /**
     * Spectral de-noise — the frequency-domain answer to [expand]'s
     * time-domain gate. The expander can only duck the gaps *between*
     * hits; hiss lives in different bins than the drums, so this pulls
     * it out from **underneath** them.
     *
     * The noise fingerprint is the average spectrum of the capture's
     * quietest tenth of frames — its own gaps, nobody else's room. Each
     * frame's bins then gate downward against `profile ×`[DENOISE_MARGIN]:
     * a bin at hiss level recedes toward [DENOISE_FLOOR_GAIN], a bin
     * carrying a drum passes untouched. Three defenses against the
     * classic musical-noise warble: the attenuation cap (noise reduced,
     * never zeroed), frequency smoothing (neighbors move together), and
     * an instant-open / eased-shut gain envelope per bin — the same
     * attack philosophy as [expand], for the same reason.
     *
     * Null when there is nothing to do or nothing to learn from: a
     * floor already under [CLEAN_FLOOR_DB], audio too short to measure,
     * or too few quiet frames to make an honest fingerprint.
     */
    fun denoise(snip: Snip): DenoiseReport? {
        val floorDb = measureFloor(snip) ?: return null
        if (floorDb <= CLEAN_FLOOR_DB) return null

        // Pass one: every frame's spectrum, per channel.
        val allMags = Array(snip.channels) { mutableListOf<FloatArray>() }
        Spectral.forEachFrame(snip) { ch, _, mags -> allMags[ch].add(mags.copyOf()) }
        val frameCount = allMags[0].size
        val quietCount = Math.max(MIN_PROFILE_FRAMES, Math.ceil(frameCount * QUIET_FRACTION.toDouble()).toInt())
        if (frameCount < quietCount * 2) return null

        // The fingerprint: the quietest frames' average spectrum.
        val profiles = Array(snip.channels) { ch ->
            val byEnergy = allMags[ch].sortedBy { mags -> mags.sumOf { (it * it).toDouble() } }
            val profile = FloatArray(Spectral.BINS)
            for (i in 0 until quietCount) {
                val m = byEnergy[i]
                for (b in profile.indices) profile[b] += m[b] / quietCount
            }
            profile
        }

        // Pass two: gate each bin against the fingerprint, smoothed.
        val prev = Array(snip.channels) { FloatArray(Spectral.BINS) { 1f } }
        val target = FloatArray(Spectral.BINS)
        val cleaned = Spectral.process(snip) { ch, _, mags ->
            val profile = profiles[ch]
            for (b in target.indices) {
                target[b] = if (mags[b] < profile[b] * DENOISE_MARGIN) DENOISE_FLOOR_GAIN else 1f
            }
            val gains = FloatArray(Spectral.BINS)
            for (b in gains.indices) {
                var acc = 0f
                var n = 0
                for (k in b - FREQ_SMOOTH_BINS..b + FREQ_SMOOTH_BINS) {
                    if (k in target.indices) {
                        acc += target[k]
                        n++
                    }
                }
                val smoothed = acc / n
                val p = prev[ch][b]
                gains[b] = (if (smoothed > p) smoothed else smoothed + GAIN_RELEASE * (p - smoothed))
                    .coerceIn(DENOISE_FLOOR_GAIN, 1f)
                prev[ch][b] = gains[b]
            }
            gains
        }
        return DenoiseReport(floorDb, quietCount, cleaned)
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
