package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * SPECTRAL RETUNE — every partial snapped to the nearest note of the
 * key, phases by [Pghi]. Where the tuner moves a whole pad by one
 * coarse/fine offset (so an inharmonic clang stays exactly as out of
 * tune with *itself* as it was), the retune moves each partial on its
 * own: a bell whose overtones never agreed on a key gets every one of
 * them talked into the scale.
 *
 * Three steps. **Listen**: one long, fine FFT over the body finds the
 * partials — local maxima that stand [PROMINENCE_DB] above their
 * neighbourhood and within [RANGE_DB] of the loudest — and how much of
 * the sound's energy they carry (the tonalness); a drum, whose energy
 * lives between peaks rather than in them, is refused as unpitched
 * rather than "corrected". **Map**: each partial's ratio to its nearest
 * in-key note becomes a plateau on the frequency axis, joined by
 * straight lines, so a partial's whole lobe moves rigidly and the
 * regions between partials stretch gently. **Move**: every frame of the
 * [Spectral] STFT is resampled along frequency through that map
 * (interpolated in the log domain, which keeps a lobe's parabolic shape
 * and so the peak position the phase integration reads), and [Pghi]
 * reinvents the phases from the moved magnitudes.
 *
 * AMOUNT is how far toward the note: 0 returns the input itself, 1 lands
 * on it. Peak-matched to the source; deterministic per seed.
 */
object Retune {

    /** The listening FFT: ~1.35 Hz bins at 44.1 kHz, fine enough to place a low partial within a few cents. */
    const val ANALYSIS = 32768

    /** Partials outside this band are left where they are — rumble below, air above. */
    const val MIN_HZ = 40f
    const val MAX_HZ = 8000f

    /** A partial stands this far above its neighbourhood's median level. */
    const val PROMINENCE_DB = 12f

    /** ...and within this of the loudest partial; the rest is floor. */
    const val RANGE_DB = 40f

    /** The loudest partials that get a say. */
    const val MAX_PARTIALS = 24

    /** The fraction of the band's energy the partials must carry before a sound counts as pitched. */
    const val MIN_TONALNESS = 0.5f

    /** Under this the sound is already in key: nothing is rewritten. */
    const val ALREADY_IN_KEY_CENTS = 1f

    /**
     * Families the classifier knows are drums, never "corrected": a
     * kick's thump and a snare's ring are the drum, not a note — the same
     * line the tuner's IN KEY draws. A tom is a tuned drum and passes.
     */
    val DRUMS: Set<DrumClass> = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN,
    )

    /** After the first pass a partial is measured where it landed and its ratio trimmed by the miss, once. */
    private const val REFINE_MATCH_CENTS = 50f

    /** Peak makeup never exceeds this: a retune is character, not loudness. */
    private const val MAKEUP_MAX = 4f

    /** How far the attack is skipped before listening: the click is not the note. */
    private const val SKIP_SEC = 0.02f

    /** The plateau each partial's ratio holds, in STFT bins either side of it — a Hann lobe's half width. */
    private const val PLATEAU_BINS = 2f

    /** One partial: where it is, how loud, and where the key wants it. */
    data class Partial(
        val hz: Float,
        /** Linear magnitude from the listening FFT, relative to the loudest partial (1.0). */
        val level: Float,
        val targetMidi: Int,
        val targetHz: Float,
        /** The move the key asks for, in cents (positive = up). */
        val cents: Float,
    ) {
        val targetName: String get() = Scales.nameOf(targetMidi)
    }

    /** What listening found. */
    data class Analysis(
        val partials: List<Partial>,
        /** Energy in the partials over energy in the band, 0..1. */
        val tonalness: Float,
        /** The drum family the classifier heard, when it is one of [DRUMS]. */
        val drum: DrumClass? = null,
    ) {
        /** Pitched enough to retune. */
        val pitched: Boolean get() = drum == null && partials.isNotEmpty() && tonalness >= MIN_TONALNESS

        /** Why not, in plain words; null when [pitched]. */
        val refusal: String?
            get() = when {
                drum != null -> "a ${drum.name.lowercase().replace('_', ' ')} is a drum, not a note"
                partials.isEmpty() -> "no partial stands out of the noise"
                tonalness < MIN_TONALNESS ->
                    "only %.0f%% of the sound lives in its partials".format(java.util.Locale.ROOT, tonalness * 100f)
                else -> null
            }

        /** Every partial already within [ALREADY_IN_KEY_CENTS] of its note. */
        val inKey: Boolean get() = partials.all { abs(it.cents) < ALREADY_IN_KEY_CENTS }
    }

    data class Retuned(val snip: Snip, val analysis: Analysis)

    /**
     * The retune, or null when the sound is honestly unpitched. At
     * [amount] 0, or for a sound already in [key], the input itself
     * comes back untouched.
     */
    fun retune(snip: Snip, key: KeySpec, amount: Float = 1f, seed: Long = 0): Retuned? {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        require(snip.frameCount > 0) { "the source is empty" }
        val analysis = analyze(snip, key)
        if (!analysis.pitched) return null
        if (amount <= 0f || analysis.inKey) return Retuned(snip, analysis)

        val partials = analysis.partials
        val ratios = partials.map { (it.targetHz / it.hz).pow(amount) }
        var out = render(snip, partials, ratios, seed)

        // A Hann lobe is not quite the parabola the log-domain move
        // assumes, so the first pass lands within ~10 cents. Measure where
        // each partial actually went and trim its ratio by the miss: the
        // second pass lands within a cent or two.
        val landed = listen(out).first.map { it.first }
        var trimmed = false
        val refined = ratios.mapIndexed { k, r ->
            val want = partials[k].hz * r
            val got = landed.minByOrNull { abs(log2(it / want)) } ?: return@mapIndexed r
            if (abs(1200f * log2(got / want)) > REFINE_MATCH_CENTS) return@mapIndexed r
            if (abs(1200f * log2(got / want)) >= ALREADY_IN_KEY_CENTS) trimmed = true
            r * (want / got)
        }
        if (trimmed) out = render(snip, partials, refined, seed)
        return Retuned(out, analysis)
    }

    /** Every frame through the map, phases from the seed, peak matched. */
    private fun render(snip: Snip, partials: List<Partial>, ratios: List<Float>, seed: Long): Snip {
        val rate = snip.sampleRate
        val map = warp(partials, ratios, rate)
        // Every channel through the same map; phases per channel from
        // the seed, so a stereo pad stays a stereo pad.
        val channels = snip.channels
        val perChannel = Array(channels) { ArrayList<FloatArray>() }
        Spectral.forEachFrame(snip) { ch, _, mags -> perChannel[ch].add(map.apply(mags)) }
        val frames = snip.frameCount
        val out = FloatArray(frames * channels)
        for (ch in 0 until channels) {
            val mono = Pghi.invert(perChannel[ch], frames, rate, seed + ch)
            for (i in 0 until frames) out[i * channels + ch] = mono.samples[i]
        }
        val result = Snip(out, channels, rate)
        val inPeak = snip.peak()
        val outPeak = result.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val makeup = (inPeak / outPeak).coerceAtMost(MAKEUP_MAX)
            for (i in out.indices) out[i] *= makeup
        }
        return result
    }

    /** Listen alone: the partials, where [key] would send them, the tonalness, and the drum ruling. */
    fun analyze(snip: Snip, key: KeySpec): Analysis {
        val (peaks, tonalness) = listen(snip)
        val partials = peaks.map { (hz, level) ->
            val targetMidi = Scales.nearestInKey(hz, key.rootSemitone, key.scale)
            val targetHz = Scales.midiToHz(targetMidi)
            Partial(hz, level, targetMidi, targetHz, 1200f * log2(targetHz / hz))
        }
        val drum = Classifier.classify(snip).drumClass.takeIf { it in DRUMS }
        return Analysis(partials, tonalness, drum)
    }

    /** The long FFT: (hz, level) per partial, loudest = 1, ascending in frequency; and the tonalness. */
    private fun listen(snip: Snip): Pair<List<Pair<Float, Float>>, Float> {
        require(snip.frameCount > 0) { "the source is empty" }
        val rate = snip.sampleRate
        val mono = if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples
        val skip = min((SKIP_SEC * rate).toInt(), mono.size / 10)
        val n = min(ANALYSIS, mono.size - skip)
        val re = FloatArray(ANALYSIS)
        val im = FloatArray(ANALYSIS)
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n)
            re[i] = (mono[skip + i] * w).toFloat()
        }
        Fft.forward(re, im)
        val bins = ANALYSIS / 2 + 1
        val mag = FloatArray(bins) { b -> Math.hypot(re[b].toDouble(), im[b].toDouble()).toFloat() }
        val hzPerBin = rate.toFloat() / ANALYSIS
        val lo = (MIN_HZ / hzPerBin).toInt().coerceAtLeast(1)
        val hi = (MAX_HZ / hzPerBin).toInt().coerceAtMost(bins - 2)
        if (hi <= lo + 2) return emptyList<Pair<Float, Float>>() to 0f

        val db = FloatArray(bins) { b -> 20f * Math.log10(mag[b].toDouble() + 1e-12).toFloat() }
        val floor = localMedian(db, lo, hi)
        var loudest = -Float.MAX_VALUE
        for (b in lo..hi) if (db[b] > loudest) loudest = db[b]

        // Candidates: local maxima that stand out of their neighbourhood
        // and within range of the loudest, loudest first.
        val candidates = ArrayList<Int>()
        for (b in lo..hi) {
            if (mag[b] <= mag[b - 1] || mag[b] < mag[b + 1]) continue
            if (db[b] < loudest - RANGE_DB) continue
            if (db[b] < floor[b] + PROMINENCE_DB) continue
            candidates.add(b)
        }
        candidates.sortByDescending { mag[it] }
        val chosen = candidates.take(MAX_PARTIALS).sorted()

        var bandEnergy = 0.0
        for (b in lo..hi) bandEnergy += mag[b].toDouble() * mag[b]
        var lobeEnergy = 0.0
        val peaks = ArrayList<Pair<Float, Float>>()
        val loudestMag = chosen.maxOfOrNull { mag[it] } ?: 0f
        for (b in chosen) {
            // Parabolic interpolation on the log magnitude: the lobe's true center.
            val a = db[b - 1]
            val c = db[b + 1]
            val denom = a - 2f * db[b] + c
            val offset = if (denom != 0f) (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f) else 0f
            val hz = (b + offset) * hzPerBin
            // The lobe: down both slopes until the level rises again or falls 30 dB.
            var l = b
            while (l > lo && mag[l - 1] < mag[l] && db[l - 1] > db[b] - 30f) l--
            var r = b
            while (r < hi && mag[r + 1] < mag[r] && db[r + 1] > db[b] - 30f) r++
            for (i in l..r) lobeEnergy += mag[i].toDouble() * mag[i]
            peaks.add(hz to mag[b] / loudestMag)
        }
        val tonalness = if (bandEnergy > 0.0) (lobeEnergy / bandEnergy).toFloat().coerceIn(0f, 1f) else 0f
        return peaks to tonalness
    }

    /** The neighbourhood's median level per bin: block medians, interpolated. */
    private fun localMedian(db: FloatArray, lo: Int, hi: Int): FloatArray {
        val block = 256
        val centers = ArrayList<Int>()
        val medians = ArrayList<Float>()
        var at = lo
        while (at <= hi) {
            val end = min(at + block, hi + 1)
            val sorted = db.copyOfRange(at, end).also { it.sort() }
            centers.add((at + end) / 2)
            medians.add(sorted[sorted.size / 2])
            at = end
        }
        val out = FloatArray(db.size)
        for (b in lo..hi) {
            var k = 0
            while (k < centers.size - 1 && centers[k + 1] < b) k++
            out[b] = if (k >= centers.size - 1 || b <= centers[k]) {
                medians[k]
            } else {
                val t = (b - centers[k]).toFloat() / (centers[k + 1] - centers[k])
                medians[k] + (medians[k + 1] - medians[k]) * t
            }
        }
        return out
    }

    /** The frequency map as a per-output-bin source position on the STFT grid. */
    private class Warp(private val index: IntArray, private val frac: FloatArray) {
        fun apply(mags: FloatArray): FloatArray = FloatArray(Spectral.BINS) { b ->
            val i = index[b]
            if (i < 0) return@FloatArray 0f
            val d = frac[b]
            if (d <= 0f || i + 1 >= Spectral.BINS) return@FloatArray mags[i]
            // Log-domain interpolation keeps a lobe's parabolic shape.
            exp((1f - d) * ln(mags[i] + 1e-12f) + d * ln(mags[i + 1] + 1e-12f)) - 1e-12f
        }
    }

    private fun warp(partials: List<Partial>, ratios: List<Float>, rate: Int): Warp {
        val binHz = rate.toFloat() / Spectral.FRAME
        val plateau = PLATEAU_BINS * binHz
        // Control points (hz, ratio): unity at DC, a plateau per partial
        // (edges never crossing the midpoint to a neighbour), the last
        // ratio held to Nyquist so upper harmonics move with their tone.
        // Partials arrive ascending from listen().
        val sorted = partials
        val pts = ArrayList<Pair<Float, Float>>()
        pts.add(0f to 1f)
        for (k in sorted.indices) {
            val p = sorted[k].hz
            val left = if (k == 0) p - plateau else maxOf(p - plateau, (sorted[k - 1].hz + p) / 2f)
            val right = if (k == sorted.size - 1) p + plateau else minOf(p + plateau, (p + sorted[k + 1].hz) / 2f)
            pts.add(maxOf(left, 1f) to ratios[k])
            pts.add(right to ratios[k])
        }
        pts.add(rate / 2f to ratios.last())
        fun ratioAt(hz: Float): Float {
            var k = 0
            while (k < pts.size - 2 && pts[k + 1].first < hz) k++
            val (h0, r0) = pts[k]
            val (h1, r1) = pts[k + 1]
            if (h1 <= h0) return r1
            val t = ((hz - h0) / (h1 - h0)).coerceIn(0f, 1f)
            return r0 + (r1 - r0) * t
        }
        // Where every source bin lands, kept monotone so the inverse exists.
        val landing = FloatArray(Spectral.BINS)
        for (b in 0 until Spectral.BINS) {
            val y = b * ratioAt(b * binHz)
            landing[b] = if (b == 0) 0f else maxOf(y, landing[b - 1] + 1e-3f)
        }
        val index = IntArray(Spectral.BINS) { -1 }
        val frac = FloatArray(Spectral.BINS)
        var src = 0
        for (b in 0 until Spectral.BINS) {
            while (src < Spectral.BINS - 1 && landing[src + 1] <= b) src++
            if (landing[src] > b) continue // below where the lowest bin lands (never: DC stays)
            if (src >= Spectral.BINS - 1) {
                // Beyond the last landing: content pushed past Nyquist is gone.
                if (landing[src] >= b) { index[b] = src; frac[b] = 0f }
                continue
            }
            index[b] = src
            frac[b] = ((b - landing[src]) / (landing[src + 1] - landing[src])).coerceIn(0f, 1f)
        }
        return Warp(index, frac)
    }

    /** "+54¢ → A3" style, for provenance lines. */
    fun describe(p: Partial): String =
        "%.1f Hz %s%d¢ -> %s".format(java.util.Locale.ROOT, p.hz, if (p.cents >= 0) "+" else "", p.cents.roundToInt(), p.targetName)
}
