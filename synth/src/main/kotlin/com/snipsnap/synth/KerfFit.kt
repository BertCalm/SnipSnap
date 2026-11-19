package com.snipsnap.synth

import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * KERF's fitter: one chopped drum hit in, the numbers [KerfRender] plays back out
 * (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md, section 7). A port of `fit` and `refine` in
 * `prototype/kerf/resynth.py`.
 *
 * It runs once per chop, never per knob turn: the render is the fast half, this is the slow half.
 *
 * The order matters, because each stage takes what it can explain out of the signal before the next one looks:
 * 1. **Body partials**, strongest first, under 1.5 kHz. Each is kept only if it really lines up with the signal (a
 *    projection of at least [KEEP]); a band-passed patch of noise also looks like a sine, and this is what refuses it.
 * 2. **Metal partials**, 2.5 to 15 kHz, by the same test. Few survive (a cymbal's tones wander), which is why colour
 *    exists.
 * 3. **Events**: later hits hiding inside the slice, found where several bands jump together.
 * 4. **Bands**: what the noise must supply is "source minus body", band by band, as a crack, a ring, a floor and a
 *    layer per event, by non-negative least squares over a grid of decay times.
 * 5. **Colour**: the narrow spikes in the residual's fine spectrum, as resonances.
 * 6. **Ticks**: where the source's top end spikes harder than the render so far does.
 *
 * Nothing here is stored from the source but these measurements.
 */
object KerfFit {
    private const val RATE = KerfRender.RATE
    private const val WINDOW = KerfRender.WINDOW
    private const val HOP = KerfRender.HOP
    private const val BANDS = KerfRender.BAND_COUNT

    /** A partial is kept when its projection onto the residual is at least this. */
    const val KEEP = 0.35

    /** The classifier files anything this long as a loop; a KERF hit is a one-shot, so a longer slice is refused. */
    const val MAX_SECONDS = 1.5

    private const val MAX_LAYERS = 8
    private const val BODY_MAX = 7
    private const val BODY_TOP_HZ = 1500.0
    private const val METAL_MAX = 16
    /** Candidates tried for metal before giving up; each costs a filter and a transform, and nearly all are refused. */
    private const val METAL_TRIES = 48

    /** Decay times a band may take, 8 ms to 4 s. */
    private val T60S = DoubleArray(16) { 0.008 * (4.0 / 0.008).pow(it / 15.0) }
    private val GLIDES = DoubleArray(14) { 0.004 * (0.25 / 0.004).pow(it / 13.0) }

    /** Fit one slice. [seed] names the noise the render will use, and the pad's SLOP draw. */
    fun fit(slice: FloatArray, seed: Long): KerfPatchData {
        val n = slice.size
        require(n >= WINDOW) { "a slice needs at least $WINDOW frames, got $n" }
        require(n < MAX_SECONDS * RATE) { "a KERF hit is a one-shot under $MAX_SECONDS s; this slice is ${"%.2f".format(n.toDouble() / RATE)} s" }
        val x = DoubleArray(n) { slice[it].toDouble() }
        val res = x.copyOf()

        val partials = ArrayList<KerfPartial>()
        bodyPartials(x, res, partials)
        metalPartials(res, partials)

        val body = DoubleArray(n)
        for (p in partials) addPartial(p, body)
        val frames = ceil(n.toDouble() / HOP).toInt()
        val ex = bandPower(x, frames); val eb = bandPower(body, frames)
        val events = findEvents(ex)
        val bands = List(BANDS) { b ->
            // Subtract smoothed band energies, not bin by bin: clamping thousands of small negatives to zero left a
            // +4 dB low-mid bias in the prototype. Then rescale so body + noise carries the source's total.
            val exSum = ex[b].sum(); val ebSum = eb[b].sum()
            val target = if (ebSum > 0.02 * exSum) {
                val sx = smooth(ex[b], 9); val sb = smooth(eb[b], 9)
                DoubleArray(frames) { max(sx[it] - sb[it], 0.0) }
            } else ex[b]
            val band = fitBand(target, events)
            var got = 0.0
            for (j in 0 until frames) got += KerfRender.bandEnvelope(band, KerfRender.Controls(), j.toDouble() * HOP / RATE)
            val g = if (got > 0) max(exSum - ebSum, 0.0) / got else 0.0
            band.copy(snap = band.snap * g, level = band.level * g, floor = band.floor * g,
                layers = band.layers.map { it.copy(snap = it.snap * g, level = it.level * g) })
        }

        var patch = KerfPatchData(n.toDouble() / RATE, seed, partials, bands, null, emptyList(), events)
        val bodyRes = DoubleArray(n) { x[it] - body[it] }
        patch = patch.copy(colour = colour(bodyRes))
        return patch.copy(ticks = ticks(x, patch))
    }

    /** One pad of a kit made by [kitFromLoop]. */
    class FittedPad(val pad: KerfPad, val sourceFrame: Int, val seconds: Double)

    /**
     * Chop a loop where its hits are and fit every chop: the tool's whole job, with no screen on it yet.
     *
     * The chop is the app's own ([Chopper.byTransients], the strongest [pads] hits). Each pad carries the hit before
     * it and the hit after it in the loop, wrapping at the ends as a loop does, because BLEED and SLOP read them.
     * A slice that runs past the one-shot limit is cut there: the knife coming down is what this instrument does.
     */
    fun kitFromLoop(loop: Snip, name: String, pads: Int = 16, bleed: Double = 1.0, slop: Double = 0.5): List<FittedPad> {
        val mono = Cleanup.toMono(loop)
        require(mono.sampleRate == RATE) { "KERF fits at $RATE Hz; this loop is ${mono.sampleRate} Hz" }
        val limit = (MAX_SECONDS * RATE).toInt() - 1
        var chops = Chopper.byTransients(mono, maxSlices = pads, cleanup = null).map { it.sourceFrame to it.snip.samples }
        // A loop nearly always starts on a hit, and a hit on the very first frame has nothing to rise from, so the
        // detector does not see it and the chop would begin at the second hit. If what comes before the first cut is
        // as loud as a hit, it is one: keep it as the first pad, and drop the weakest later cut to stay at [pads].
        val first = chops.firstOrNull()?.first ?: 0
        if (first >= WINDOW * 4 && peak(mono.samples, 0, first) >= LEAD_IN * peak(mono.samples, 0, mono.frameCount)) {
            if (chops.size >= pads && pads > 1) chops = Chopper.byTransients(mono, maxSlices = pads - 1, cleanup = null).map { it.sourceFrame to it.snip.samples }
            val cut = chops.firstOrNull()?.first ?: mono.frameCount
            chops = listOf(0 to mono.samples.copyOfRange(0, cut)) + chops
        }
        val fitted = chops.filter { it.second.size >= WINDOW * 4 }.mapIndexed { i, (frame, samples) ->
            val audio = if (samples.size > limit) samples.copyOf(limit) else samples
            Triple(frame, fit(audio, seedFor(name, frame, i)), Classifier.classify(Snip(audio, 1, RATE)).drumClass.name)
        }
        return fitted.mapIndexed { i, (s, patch, cls) ->
            val prev = fitted[(i - 1 + fitted.size) % fitted.size].second; val next = fitted[(i + 1) % fitted.size].second
            FittedPad(KerfPad(i + 1, cls, patch, prev.takeIf { fitted.size > 1 }, next.takeIf { fitted.size > 1 }, bleed, slop, null),
                s, patch.seconds)
        }
    }

    /** Audio before the first detected cut counts as a hit when its peak is at least this share of the loop's. */
    const val LEAD_IN = 0.25

    private fun peak(x: FloatArray, from: Int, to: Int): Float { var m = 0f; for (i in from until to) m = max(m, abs(x[i])); return m }

    private fun seedFor(vararg parts: Any): Long = Dsp.seedFor(*parts).toLong() and 0x7fffffffL

    // ---------------------------------------------------------------- partials

    private fun bodyPartials(x: DoubleArray, res: DoubleArray, out: MutableList<KerfPartial>) {
        val size = 1 shl 17; val hzPerBin = RATE.toDouble() / size
        val ref = magnitude(x, size).max()
        val tried = ArrayList<Double>()
        for (k in 0 until BODY_MAX + 3) {
            if (out.size == BODY_MAX) break
            val s = magnitude(res, size)
            var best = -1; var bestV = 0.0
            for (i in s.indices) {
                val f = i * hzPerBin
                if (f < 30 || f > BODY_TOP_HZ) continue
                if (tried.any { f > it / 1.22 && f < it * 1.22 }) continue
                if (s[i] > bestV) { bestV = s[i]; best = i }
            }
            if (best < 0 || bestV < ref * 10.0.pow(-26.0 / 20)) break
            val f0 = best * hzPerBin
            val p = fitPartial(res, f0, wide = k == 0 && f0 < 140, narrow = false) ?: break
            if (p.amp < 0.004) break
            tried += p.hzEnd
            keep(p, res)?.let { out += it }
        }
    }

    /**
     * A cymbal's ring between 2.5 and 15 kHz, as far as it is steady tones: the spectral lines that stand clear of
     * their surroundings, each given an oscillator if it survives the projection test.
     */
    private fun metalPartials(res: DoubleArray, out: MutableList<KerfPartial>) {
        val size = 1 shl 16; val hzPerBin = RATE.toDouble() / size
        val s = magnitude(res, size)
        val span = ((600 / hzPerBin).toInt() or 1) / 8 or 1
        val coarse = DoubleArray((s.size + 7) / 8) { s[it * 8] }
        val med = medianFilter(coarse, span)
        fun around(i: Int): Double {
            val a = i / 8; val f = (i % 8) / 8.0
            return (if (a + 1 < med.size) med[a] + f * (med[a + 1] - med[a]) else med[med.size - 1]) + 1e-12
        }
        val peaks = (1 until s.size - 1).filter { i ->
            val f = i * hzPerBin
            f > 2500 && f < 15000 && s[i] > s[i - 1] && s[i] >= s[i + 1] && s[i] > 2.8 * around(i)
        }.sortedByDescending { s[it] }
        val metal = ArrayList<KerfPartial>()
        for (i in peaks.take(METAL_TRIES)) {
            if (metal.size >= METAL_MAX) break
            val f = i * hzPerBin
            if (metal.any { abs(ln(f / it.hzEnd)) < 0.03 }) continue
            val p = fitPartial(res, f, wide = false, narrow = true) ?: continue
            if (p.amp < 1e-4) continue
            keep(p, res)?.let { metal += it }
        }
        out += metal
    }

    /** Keep the part of [p] that lines up with [res], and take it out of [res]; null if too little does. */
    private fun keep(p: KerfPartial, res: DoubleArray): KerfPartial? {
        val y = DoubleArray(res.size); addPartial(p, y)
        var dot = 0.0; var yy = 0.0
        for (i in res.indices) { dot += res[i] * y[i]; yy += y[i] * y[i] }
        val g = dot / (yy + 1e-20)
        if (g < KEEP) return null
        val kept = p.copy(amp = p.amp * min(g, 1.2))
        val scale = kept.amp / p.amp
        for (i in res.indices) res[i] -= y[i] * scale
        return kept
    }

    private fun addPartial(p: KerfPartial, out: DoubleArray) {
        val one = KerfPatchData(out.size.toDouble() / RATE, 0, listOf(p), EMPTY_BANDS, null, emptyList(), emptyList())
        KerfRender.addBody(one, out)
    }
    private val EMPTY_BANDS = List(BANDS) { KerfBand(0.0, 0.01, 0.0, 0.05, 0.0, 0.0, emptyList()) }

    /**
     * One oscillator from the band around [f0]: its level and decay from the envelope of that band, its pitch and
     * glide from the band's instantaneous frequency, its phase from the moment it is loudest.
     */
    private fun fitPartial(x: DoubleArray, f0: Double, wide: Boolean, narrow: Boolean): KerfPartial? {
        val (lo, hi) = when { narrow -> 0.975 * f0 to 1.025 * f0; wide -> 0.62 * f0 to 1.9 * f0; else -> 0.78 * f0 to 1.28 * f0 }
        val pad = 2048; val n = x.size
        val padded = DoubleArray(n + 2 * pad); System.arraycopy(x, 0, padded, pad, n)
        val (re, im) = analytic(bandpassBothWays(padded, max(15.0, lo), min(hi, 20000.0)))
        val a = DoubleArray(n) { hypot(re[it + pad], im[it + pad]) }
        val sm = max(8, (0.003 * RATE).toInt())
        val env = smooth(a, sm)
        var ip = 0
        for (i in 0 until max(1, n - sm)) if (env[i] > env[ip]) ip = i
        val level = env[ip]
        if (level < 1e-5) return null

        // decay: a straight line through the envelope in dB, after the peak and above -30 dB of it
        var cnt = 0; var st = 0.0; var sy = 0.0; var stt = 0.0; var sty = 0.0
        for (i in ip + 1 until n - sm) if (env[i] > level * 0.03) {
            val t = i.toDouble() / RATE; val y = 20 * log10(env[i])
            cnt++; st += t; sy += y; stt += t * t; sty += t * y
        }
        val t60 = if (cnt > 40) {
            val slope = (cnt * sty - st * sy) / (cnt * stt - st * st)
            (-60 / min(slope, -1e-3)).coerceIn(0.02, 6.0)
        } else 0.05

        // pitch: instantaneous frequency, weighted by how loud the band is at that moment
        val freq = DoubleArray(n)
        for (i in 0 until n - 1) {
            var d = atan2(im[i + 1 + pad], re[i + 1 + pad]) - atan2(im[i + pad], re[i + pad])
            while (d > PI) d -= 2 * PI
            while (d <= -PI) d += 2 * PI
            freq[i] = d * RATE / (2 * PI)
        }
        if (n > 1) freq[n - 1] = freq[n - 2]
        val w = DoubleArray(n) { if (it >= 16 && it < n - sm && env[it] > 0.1 * level) env[it] else 0.0 }
        var bestErr = Double.MAX_VALUE; var end = f0; var glide = 0.0; var tau = 0.02
        for (cand in -1 until if (narrow) 0 else GLIDES.size) {
            // weighted least squares for f(t) = end (+ glide * exp(-t / tau)); a glide must pay for itself by 5 %
            var s11 = 0.0; var s1e = 0.0; var see = 0.0; var s1f = 0.0; var sef = 0.0
            for (i in 0 until n) {
                val ww = w[i] * w[i]; if (ww == 0.0) continue
                val e = if (cand < 0) 0.0 else exp(-i.toDouble() / RATE / GLIDES[cand])
                s11 += ww; s1e += ww * e; see += ww * e * e; s1f += ww * freq[i]; sef += ww * e * freq[i]
            }
            if (s11 == 0.0) break
            val c0: Double; val c1: Double
            val det = s11 * see - s1e * s1e
            if (cand < 0 || abs(det) < 1e-12 * s11 * see) { c0 = s1f / s11; c1 = 0.0 } else { c0 = (s1f * see - sef * s1e) / det; c1 = (s11 * sef - s1e * s1f) / det }
            var err = 0.0
            for (i in 0 until n) if (w[i] != 0.0) {
                val e = if (cand < 0) 0.0 else exp(-i.toDouble() / RATE / GLIDES[cand])
                val d = c0 + c1 * e - freq[i]; err += w[i] * d * d
            }
            if (cand >= 0) err *= 1.05
            if (err < bestErr) { bestErr = err; end = c0; glide = c1; tau = if (cand < 0) 0.02 else GLIDES[cand] }
        }
        end = end.coerceIn(20.0, if (narrow) 20000.0 else 1200.0)
        glide = glide.coerceIn(-0.5 * end, 6 * end)
        var phase = 0.0
        for (i in 0..ip) phase += 2 * PI * (end + glide * exp(-i.toDouble() / RATE / tau)) / RATE
        val phi0 = atan2(im[ip + pad], re[ip + pad]) - phase

        // where the oscillator really begins: the last moment before its peak that it was under a tenth of it
        var start = 0.0
        for (i in ip - 1 downTo 0) if (env[i] < 0.1 * level) { start = i.toDouble() / RATE; break }
        return KerfPartial(end, glide, tau, level, start, ip.toDouble() / RATE, t60, phi0)
    }

    // ---------------------------------------------------------------- bands

    /** Energy per band per frame, with [KerfRender.stft]'s scaling: the unit a patch's bands are written in. */
    private fun bandPower(x: DoubleArray, frames: Int): Array<DoubleArray> {
        val st = KerfRender.stft(x)
        return Array(BANDS) { b ->
            DoubleArray(frames) { j ->
                var s = 0.0
                if (j < st.size) for (k in KerfRender.EDGES[b] until KerfRender.EDGES[b + 1]) s += st[j].re[k].toDouble() * st[j].re[k] + st[j].im[k].toDouble() * st[j].im[k]
                s
            }
        }
    }

    /**
     * Later hits hiding inside one slice (a hat landing on a kick's tail, a flam, a ghost note the chopper missed).
     * A hit is a moment where several bands jump up together; the slice's first hit is not listed.
     */
    private fun findEvents(ex: Array<DoubleArray>): List<Double> {
        val frames = ex[0].size; val ref = ex.maxOf { it.max() }; val lag = 6
        val level = Array(BANDS) { b -> smooth(ex[b], 5).let { s -> DoubleArray(frames) { 10 * log10(s[it] + ref * 1e-5) } } }
        val d = DoubleArray(frames) { t ->
            if (t < lag) 0.0 else (0 until BANDS).sumOf { b -> (level[b][t] - level[b][t - lag]).let { if (it < 4) 0.0 else it } }
        }
        val out = ArrayList<Double>()
        for (t in 1 until frames - 1) {
            val at = t.toDouble() * HOP / RATE
            if (at < 0.02 || d[t] < 30 || d[t] < d[t - 1] || d[t] <= d[t + 1]) continue
            if (out.isNotEmpty() && at - out.last() < 0.025) continue
            out += at - lag * HOP.toDouble() / RATE * 0.5
        }
        return out
    }

    /**
     * One band as a sum of layers. Each layer is a hit: a fast crack plus a slower ring, starting at its own time;
     * under them sits one floor. Without layers a band has a single peak and can only fall afterwards, so a second
     * sound in the same slice had nowhere to go.
     *
     * Every pair of decay times on the grid is tried; for each, the levels are the non-negative least-squares answer.
     * The products every pair needs are computed once ([gram]), so a pair costs a small solve, not a pass over the data.
     */
    private fun fitBand(e: DoubleArray, events: List<Double>): KerfBand {
        val frames = e.size
        fun frameOf(t: Double): Int { var i = 0; while (i < frames && i.toDouble() * HOP / RATE < t) i++; return i }
        val lim = if (events.isEmpty()) frames else max(2, frameOf(events[0]))
        var ip = 0
        for (i in 0 until min(lim, frames)) if (e[i] > e[ip]) ip = i
        val pk = e.max()
        if (pk < 1e-13) return KerfBand(0.0, 0.01, 0.0, 0.05, 0.0, 0.0, emptyList())
        val m = frames - ip
        val starts = ArrayList<Double>().apply { add(0.0) }
        for (ev in events) {
            val a = frameOf(ev); val b = min(frames, a + (0.012 * RATE / HOP).toInt() + 1)
            if (a >= frames - 1 || a <= ip) continue
            var top = a
            for (i in a until b) if (e[i] > e[top]) top = i
            starts += (top - ip).toDouble() * HOP / RATE
        }
        // a long slice can hold dozens of events; past eight layers the solve grows with the square and adds nothing heard
        while (starts.size > MAX_LAYERS + 1) starts.removeAt(starts.size - 1)
        val k = starts.size
        val wgt = DoubleArray(m) { 1 / (e[ip + it] + pk * 0.01) }
        // candidate columns: every decay time at every start, then the constant floor
        val cols = T60S.size * k + 1
        val a = Array(cols) { c ->
            if (c == cols - 1) DoubleArray(m) { wgt[it] } else {
                val t60 = T60S[c / k]; val st = starts[c % k]
                DoubleArray(m) { r -> val tt = r.toDouble() * HOP / RATE; if (tt >= st) 10.0.pow(-6 * (tt - st) / t60) * wgt[r] else 0.0 }
            }
        }
        val y = DoubleArray(m) { e[ip + it] * wgt[it] }
        val gram = Array(cols) { i -> DoubleArray(cols) }
        for (i in 0 until cols) for (j in i until cols) { var s = 0.0; for (r in 0 until m) s += a[i][r] * a[j][r]; gram[i][j] = s; gram[j][i] = s }
        val aty = DoubleArray(cols) { i -> var s = 0.0; for (r in 0 until m) s += a[i][r] * y[r]; s }
        var yy = 0.0; for (r in 0 until m) yy += y[r] * y[r]

        var bestErr = Double.MAX_VALUE; var bi = 0; var bj = 0; var best = DoubleArray(0)
        for (i in T60S.indices) for (j in i until T60S.size) {
            val idx = IntArray(if (j > i) 2 * k + 1 else k + 1) { c ->
                when { c < k -> i * k + c; j > i && c < 2 * k -> j * k + (c - k); else -> cols - 1 }
            }
            val c = nnls(gram, aty, idx)
            var err = yy
            for (p in idx.indices) { err -= 2 * c[p] * aty[idx[p]]; for (q in idx.indices) err += c[p] * c[q] * gram[idx[p]][idx[q]] }
            if (j > i) err *= 0.97
            if (err < bestErr) { bestErr = err; bi = i; bj = j; best = c }
        }
        // a single decay is the ring, not the crack
        val two = bj > bi
        fun fast(s: Int) = if (two) best[s] else 0.0
        fun slow(s: Int) = if (two) best[k + s] else best[s]
        val attack = ip.toDouble() * HOP / RATE
        return KerfBand(fast(0), T60S[bi], slow(0), T60S[bj], best.last(), attack,
            (1 until k).filter { fast(it) + slow(it) > 0 }.map { KerfLayer(attack + starts[it], fast(it), slow(it)) })
    }

    /** Lawson and Hanson's non-negative least squares, on the normal equations restricted to the columns [idx]. */
    private fun nnls(gram: Array<DoubleArray>, aty: DoubleArray, idx: IntArray): DoubleArray {
        val n = idx.size; val x = DoubleArray(n); val active = BooleanArray(n); val banned = BooleanArray(n)
        val scale = (0 until n).maxOf { gram[idx[it]][idx[it]] }.coerceAtLeast(1e-300)
        repeat(3 * n + 3) {
            // the column whose level, raised from zero, lowers the error fastest
            var pick = -1; var grad = 1e-10 * scale
            for (p in 0 until n) if (!active[p] && !banned[p]) {
                var w = aty[idx[p]]
                for (q in 0 until n) w -= gram[idx[p]][idx[q]] * x[q]
                if (w > grad) { grad = w; pick = p }
            }
            if (pick < 0) return x
            active[pick] = true
            var guard = 0
            while (guard++ < 2 * n + 2) {
                val set = (0 until n).filter { active[it] }
                if (set.isEmpty()) break
                val s = solve(Array(set.size) { r -> DoubleArray(set.size) { c -> gram[idx[set[r]]][idx[set[c]]] } }, DoubleArray(set.size) { aty[idx[set[it]]] })
                if (s == null) { active[pick] = false; banned[pick] = true; break }   // this column repeats one already in
                if (s.all { it > 0 }) { for ((r, p) in set.withIndex()) x[p] = s[r]; break }
                // step towards the unconstrained answer until the first level reaches zero, and drop that column
                var alpha = 1.0
                for ((r, p) in set.withIndex()) if (s[r] <= 0) alpha = min(alpha, x[p] / (x[p] - s[r]))
                for ((r, p) in set.withIndex()) {
                    x[p] += alpha * (s[r] - x[p])
                    if (s[r] <= 0 && x[p] <= 1e-12 * abs(s[r])) { x[p] = 0.0; active[p] = false }
                }
            }
        }
        return x
    }

    /** Gaussian elimination with partial pivoting; null when the system is singular to working precision. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val big = (0 until n).maxOfOrNull { abs(a[it][it]) } ?: return DoubleArray(0)
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-12 * big) return null
            val t = a[c]; a[c] = a[p]; a[p] = t; val tb = b[c]; b[c] = b[p]; b[p] = tb
            for (r in c + 1 until n) { val f = a[r][c] / a[c][c]; if (f != 0.0) { for (q in c until n) a[r][q] -= f * a[c][q]; b[r] -= f * b[c] } }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) { var s = b[r]; for (q in r + 1 until n) s -= a[r][q] * x[q]; x[r] = s / a[r][r] }
        return x
    }

    // ---------------------------------------------------------------- colour

    private const val FINE = 2048

    /**
     * The residual's power spectrum in 21 Hz steps, averaged over the slice (Welch), and the same blurred to about a
     * third of an octave. Their ratio is the fine grain of the tone: 1 for plain noise, spikes where something rings.
     */
    internal fun fineSpectrum(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val src = if (x.size < FINE * 2) x.copyOf(FINE * 2) else x
        val step = FINE / 4; val half = FINE / 2
        val p = DoubleArray(half + 1); var count = 0
        var at = 0
        while (at + FINE <= src.size) {
            var mean = 0.0; for (i in 0 until FINE) mean += src[at + i]; mean /= FINE
            val re = FloatArray(FINE) { ((src[at + it] - mean) * (0.5 - 0.5 * cos(2 * PI * it / FINE))).toFloat() }; val im = FloatArray(FINE)
            Fft.forward(re, im)
            for (k in 0..half) p[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            count++; at += step
        }
        for (k in 0..half) p[k] /= count
        val cs = DoubleArray(half + 2); for (k in 0..half) cs[k + 1] = cs[k] + p[k]
        val c = DoubleArray(half + 1) { k -> val lo = (k / 1.12).toInt(); val hi = min((k * 1.12).toInt() + 1, half + 1); (cs[hi] - cs[lo]) / (hi - lo) + 1e-20 }
        return p to c
    }

    private fun colour(res: DoubleArray): KerfColour? {
        val (p, c) = fineSpectrum(res); val hzPerBin = RATE.toDouble() / FINE
        val d = DoubleArray(p.size) { p[it] / c[it] }
        val inside = d.indices.filter { it * hzPerBin > 1500 && it * hzPerBin < 16000 }
        val sorted = inside.map { d[it] }.sorted()
        val base = ((sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2).coerceIn(0.3, 1.0)
        val found = ArrayList<Pair<Double, KerfResonance>>()
        for (i in inside) {
            if (i < 1 || i >= d.size - 1 || !(d[i] > d[i - 1] && d[i] >= d[i + 1] && d[i] > 2.2)) continue
            val halfway = base + (d[i] - base) / 2
            var l = i; var r = i
            while (l > 1 && d[l - 1] > halfway) l--
            while (r < d.size - 2 && d[r + 1] > halfway) r++
            found += (d[i] - base) * p[i] to KerfResonance(i * hzPerBin, d[i] - base, max(r - l + 1.0, 1.5) * hzPerBin)
        }
        val kept = found.sortedByDescending { it.first }.take(48).map { it.second }
        return if (kept.isEmpty()) null else KerfColour(base, kept)
    }

    // ---------------------------------------------------------------- ticks

    /**
     * The stick or beater landing. Where the source's top end (above 2 kHz) spikes at least a quarter harder than the
     * render so far, a burst is asked for at that sample; then each burst is checked against the render that includes
     * it and turned down if the top end would pass the source's own spike.
     */
    private fun ticks(x: DoubleArray, patch: KerfPatchData): List<KerfTick> {
        val n = x.size
        val hx = highpass(x, 2000.0); val w = (0.003 * RATE).toInt(); val gap = (0.015 * RATE).toInt()
        fun top(h: DoubleArray, i: Int): Double { var m = 0.0; for (j in max(0, i - w) until min(n, i + w)) m = max(m, abs(h[j])); return m }
        fun rendered(p: KerfPatchData) = highpass(KerfRender.rawRender(p).let { r -> DoubleArray(n) { if (it < r.size) r[it] else 0.0 } }, 2000.0)
        val peak = hx.maxOf { abs(it) }
        if (peak <= 0) return emptyList()
        val hy = rendered(patch)
        val order = hx.indices.sortedByDescending { abs(hx[it]) }.take(4000)
        val taken = ArrayList<Int>(); val wanted = ArrayList<Double>(); var out = ArrayList<KerfTick>()
        for (i in order) {
            val v = abs(hx[i])
            if (v < 0.25 * peak || taken.size >= 6) break
            if (taken.any { abs(i - it) < gap }) continue
            taken += i
            val yp = top(hy, i)
            if (v < 1.25 * yp) continue
            val to = min(n, i + 64); var crossings = 0
            for (j in i + 1 until to) if ((hx[j] >= 0) != (hx[j - 1] >= 0)) crossings++
            val hz = (max(1, crossings) * RATE.toDouble() / (2 * max(to - i, 2))).coerceIn(1500.0, 12000.0)
            out += KerfTick(i.toDouble() / RATE, sqrt(v * v - yp * yp), hz); wanted += v
        }
        repeat(2) {
            val h = rendered(patch.copy(ticks = out))
            out = ArrayList(out.mapIndexed { t, tick ->
                val got = top(h, (tick.atS * RATE).toInt())
                if (got > wanted[t]) tick.copy(amp = tick.amp * max(0.0, 1 - (got - wanted[t]) / max(tick.amp, 1e-9))) else tick
            })
        }
        return out
    }

    // ---------------------------------------------------------------- signal helpers

    /** |FFT| of [x] under a Hann window, zero-padded (or cut) to [size]; bins 0..size/2. */
    private fun magnitude(x: DoubleArray, size: Int): DoubleArray {
        val n = x.size; val re = FloatArray(size); val im = FloatArray(size)
        for (i in 0 until min(n, size)) re[i] = (x[i] * (0.5 - 0.5 * cos(2 * PI * i / max(n - 1, 1)))).toFloat()
        Fft.forward(re, im)
        return DoubleArray(size / 2 + 1) { hypot(re[it].toDouble(), im[it].toDouble()) }
    }

    /** Moving average of [width] samples, centred the way the prototype's `np.convolve(..., 'same')` is. */
    private fun smooth(x: DoubleArray, width: Int): DoubleArray {
        val cs = DoubleArray(x.size + 1); for (i in x.indices) cs[i + 1] = cs[i] + x[i]
        val back = width - 1 - (width - 1) / 2; val fwd = (width - 1) / 2
        return DoubleArray(x.size) { i -> (cs[min(x.size, i + fwd + 1)] - cs[max(0, i - back)]) / width }
    }

    private fun medianFilter(x: DoubleArray, width: Int): DoubleArray {
        val half = width / 2; val buf = DoubleArray(width)
        return DoubleArray(x.size) { i ->
            for (k in 0 until width) { val j = i - half + k; buf[k] = if (j in x.indices) x[j] else 0.0 }
            buf.sort(); buf[half]
        }
    }

    /** The analytic signal of [x] (Hilbert transform by FFT): real part and imaginary part. */
    private fun analytic(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        var size = 1; while (size < x.size) size = size shl 1
        val re = FloatArray(size) { if (it < x.size) x[it].toFloat() else 0f }; val im = FloatArray(size)
        Fft.forward(re, im)
        for (k in 1 until size / 2) { re[k] *= 2f; im[k] *= 2f }
        for (k in size / 2 + 1 until size) { re[k] = 0f; im[k] = 0f }
        Fft.inverse(re, im)
        return DoubleArray(x.size) { re[it].toDouble() } to DoubleArray(x.size) { im[it].toDouble() }
    }

    /**
     * A second-order Butterworth band-pass (two biquads) run forwards then backwards, so it adds no delay: the
     * envelope and phase it hands [fitPartial] line up with the signal. The caller pads the ends with silence.
     */
    private fun bandpassBothWays(x: DoubleArray, lo: Double, hi: Double): DoubleArray {
        val sections = butterBandpass(lo, hi)
        var y = x.copyOf()
        repeat(2) {
            for (s in sections) {
                var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
                for (i in y.indices) {
                    val v = s[0] * y[i] + s[1] * x1 + s[2] * x2 - s[3] * y1 - s[4] * y2
                    x2 = x1; x1 = y[i]; y2 = y1; y1 = v; y[i] = v
                }
            }
            y.reverse()
        }
        return y
    }

    /**
     * Two biquads `[b0, b1, b2, a1, a2]` for a Butterworth band-pass built from a second-order low-pass prototype:
     * each prototype pole becomes a pair under the band-pass transform, the bilinear transform takes them to the unit
     * circle, and the gain is set to one at the centre of the band.
     */
    private fun butterBandpass(lo: Double, hi: Double): List<DoubleArray> {
        val fs2 = 2.0 * RATE
        val wl = fs2 * tan(PI * lo / RATE); val wh = fs2 * tan(PI * min(hi, RATE * 0.499) / RATE)
        val bw = wh - wl; val w0sq = wl * wh
        // the prototype's upper-half pole, exp(j 3 pi / 4); its conjugate gives the conjugate pair of sections
        val pr = -sqrt(0.5) * bw / 2; val pi = sqrt(0.5) * bw / 2
        // s = p' ± sqrt(p'^2 - w0^2), complex
        val dr = pr * pr - pi * pi - w0sq; val di = 2 * pr * pi
        val mag = sqrt(hypot(dr, di)); val ang = atan2(di, dr) / 2
        val sr = mag * cos(ang); val si = mag * sin(ang)
        val centre = 2 * kotlin.math.atan(sqrt(w0sq) / fs2)
        return listOf(pr + sr to pi + si, pr - sr to pi - si).map { (ar, ai) ->
            // z = (fs2 + s) / (fs2 - s); the section's poles are z and its conjugate, its zeros +1 and -1
            val den = (fs2 - ar) * (fs2 - ar) + ai * ai
            val zr = ((fs2 + ar) * (fs2 - ar) - ai * ai) / den; val zi = (ai * (fs2 - ar) + ai * (fs2 + ar)) / den
            val a1 = -2 * zr; val a2 = zr * zr + zi * zi
            // |1 - e^{-2jw}| / |1 + a1 e^{-jw} + a2 e^{-2jw}| at the centre
            val num = hypot(1 - cos(2 * centre), sin(2 * centre))
            val dre = 1 + a1 * cos(centre) + a2 * cos(2 * centre); val dim = -a1 * sin(centre) - a2 * sin(2 * centre)
            val g = hypot(dre, dim) / num
            doubleArrayOf(g, 0.0, -g, a1, a2)
        }
    }

    /** A second-order Butterworth high-pass, run once forwards. */
    private fun highpass(x: DoubleArray, hz: Double): DoubleArray {
        val k = tan(PI * hz / RATE); val norm = 1 / (1 + sqrt(2.0) * k + k * k)
        val b0 = norm; val b1 = -2 * norm; val a1 = 2 * (k * k - 1) * norm; val a2 = (1 - sqrt(2.0) * k + k * k) * norm
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return DoubleArray(x.size) { i ->
            val v = b0 * x[i] + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v; v
        }
    }
}
