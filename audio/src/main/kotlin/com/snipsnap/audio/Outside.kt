package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OUTSIDE — the world as an effect, the Outsidify idea done SnipSnap's way.
 *
 * The phone plays a sound out (through its speaker, or the headphone jack
 * into a pedal, an amp, a spring tank) and listens to what comes back
 * (the mic, or the line back in). What comes back is late by however
 * long the trip took — the audio stack's own buffers plus the air — and
 * quieter or louder by however the gain fell. This object owns the two
 * things that make that usable as a pad:
 *
 * - **[align]** — *when* did the send arrive? The return is cross-
 *   correlated against the send (FFT, so a five-second listen is cheap)
 *   and the lag of the strongest match is the latency. Polarity is
 *   allowed to invert — a pedal or a speaker often does — and is
 *   reported, never silently kept. Confidence is the normalized
 *   correlation at the match; standout is how far that match rises over
 *   the correlation's own noise, which is what tells a real arrival from
 *   a lucky wiggle in a silent room.
 * - **[reamp]** — the return, cut at the arrival, run for the send's
 *   length and then as long as its tail still sounds, peak matched to
 *   the send so the room's loudness comes from the pad and not the mic
 *   gain, MIX dry to wet.
 *
 * And the room *as a room*: **[probe]** is an exponential sine sweep,
 * **[impulse]** deconvolves the return against it (Farina's inverse
 * filter — the time-reversed sweep with a 6 dB/octave tilt) into the
 * room's impulse response, which is exactly what ROOM OF ITSELF wants
 * as its parent. A sweep beats a click for this: it carries the same
 * energy at every frequency spread over two seconds, so a phone speaker
 * can play it without clipping and the mic hears every band.
 *
 * Refusals are in words ([Refused]): a return that clipped, a room that
 * said nothing, an arrival the correlation can't find.
 */
object Outside {

    /** A return may lead the send by this much — clocks and start order, never physics. */
    const val MAX_EARLY_SEC = 0.05f

    /** The correlation peak must rise this far over the correlation's own RMS to count as an arrival. */
    const val MIN_STANDOUT = 8f

    /** Below this normalized correlation the match is named, but the pad is refused. */
    const val MIN_CONFIDENCE = 0.05f

    /** The longest tail a return may keep after the send's own length. */
    const val TAIL_MAX_SEC = 3f

    /** The tail closes once its envelope has sat this far under the return's peak… */
    const val TAIL_FLOOR_DB = -54f

    /** …or this close to the room's own noise floor, measured off the return before the arrival… */
    const val TAIL_NOISE_MARGIN_DB = 6f

    /**
     * …for this long. Generous on purpose: a delay pedal's repeats arrive
     * with silence between them, and a slap off the far wall lands well
     * after the hit has died — the hold bridges those. The cut itself
     * lands where the quiet *began*, so the hold never adds hiss.
     */
    const val TAIL_HOLD_SEC = 0.6f

    /** Envelope resolution for the tail hunt. */
    private const val TAIL_WINDOW_SEC = 0.01f

    /** The cut's fade-out — a handover to silence, never a click. */
    const val FADE_MS = 10

    /** A return at or over this peak has clipped somewhere on the way in. */
    const val CLIP_PEAK = 0.99f

    /** Under this peak nothing came back at all. */
    const val SILENCE_PEAK = 1e-4f

    /** The probe sweep: two seconds, 20 Hz to as high as the rate allows, at half scale. */
    const val PROBE_SECONDS = 2f
    const val PROBE_LO_HZ = 20f
    const val PROBE_HI_HZ = 20_000f
    const val PROBE_LEVEL = 0.5f
    private const val PROBE_FADE_MS = 20

    /** The impulse response is cut from this far before its own peak, so the direct arrival is whole. */
    private const val IMPULSE_PRE_MS = 2

    /** An honest refusal, in the verb's own words. */
    class Refused(message: String) : IllegalArgumentException(message)

    /**
     * Where the send landed in the return.
     *
     * @property lagFrames how many frames late the return is (negative: early, within [MAX_EARLY_SEC]).
     * @property confidence normalized cross-correlation at the match, 0..1.
     * @property standout the match's height over the correlation's RMS.
     * @property inverted the return came back upside down.
     */
    data class Alignment(val lagFrames: Int, val confidence: Float, val standout: Float, val inverted: Boolean) {
        fun lagMs(sampleRate: Int): Float = lagFrames * 1000f / sampleRate
    }

    /** The reamped pad and how it was lined up. */
    data class Reamped(val snip: Snip, val alignment: Alignment)

    /** The room's impulse response (mono), the latency that placed it, and the deconvolution's standout. */
    data class Impulse(val snip: Snip, val lagFrames: Int, val standout: Float) {
        fun lagMs(sampleRate: Int): Float = lagFrames * 1000f / sampleRate
    }

    // ---- align ------------------------------------------------------------

    /**
     * Find [sent] inside [returned]. Both are mono-folded; [returned] is
     * resampled to [sent]'s rate if it differs. Refuses a clipped or a
     * silent return before any arithmetic, and a match that fails to
     * stand out after it.
     */
    fun align(sent: Snip, returned: Snip): Alignment {
        val s = mono(sent)
        val r = mono(sameRate(returned, sent.sampleRate))
        checkReturn(r)
        require(s.size >= 32) { "the send is too short to find: ${s.size} frames" }
        require(r.size >= s.size / 4) { "the return is shorter than a quarter of the send - nothing to line up" }

        val rate = sent.sampleRate
        val early = min((MAX_EARLY_SEC * rate).toInt(), r.size - 1)
        val corr = crossCorrelate(r, s)

        // Window energy of the return under a send-length window at every
        // lag, via a prefix sum, so confidence is a true normalized
        // correlation and not a raw dot product that favours loud lags.
        val prefix = DoubleArray(r.size + 1)
        for (i in r.indices) prefix[i + 1] = prefix[i] + r[i] * r[i].toDouble()
        var sentEnergy = 0.0
        for (v in s) sentEnergy += v * v.toDouble()
        val sentNorm = sqrt(sentEnergy)

        val n = corr.size
        var bestLag = 0
        var bestAbs = -1f
        var sumSq = 0.0
        var counted = 0
        for (lag in -early..r.size - 1) {
            val idx = if (lag >= 0) lag else n + lag
            val v = corr[idx]
            sumSq += v * v.toDouble()
            counted++
            val a = abs(v)
            if (a > bestAbs) {
                bestAbs = a
                bestLag = lag
            }
        }
        val rms = sqrt(sumSq / counted.coerceAtLeast(1)).toFloat()
        val standout = if (rms > 1e-12f) bestAbs / rms else 0f

        val from = bestLag.coerceIn(0, r.size)
        val to = (bestLag + s.size).coerceIn(0, r.size)
        val windowNorm = sqrt(prefix[to] - prefix[from])
        val confidence = if (windowNorm > 1e-9 && sentNorm > 1e-9) {
            (bestAbs / (windowNorm * sentNorm)).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val bestIdx = if (bestLag >= 0) bestLag else n + bestLag
        val inverted = corr[bestIdx] < 0f

        if (standout < MIN_STANDOUT || confidence < MIN_CONFIDENCE) {
            throw Refused(
                "the room didn't answer - nothing in the return lines up with the send " +
                    "(confidence %.2f, standout %.1f); is the speaker reaching the mic, or the pedal unmuted?"
                        .format(java.util.Locale.ROOT, confidence, standout),
            )
        }
        return Alignment(bestLag, confidence, standout, inverted)
    }

    // ---- reamp ------------------------------------------------------------

    /**
     * The return as the pad: cut at the arrival [align] found, the send's
     * length plus a tail that lasts while it still sounds (capped at
     * [TAIL_MAX_SEC]), polarity restored if it came back inverted, peak
     * matched to [sent], then MIX crossfades dry to wet. MIX 0 is the pad
     * itself, padded to the wet length so a dry/wet sweep never jumps in
     * length. Channels follow the send; a mono return is spread.
     */
    fun reamp(sent: Snip, returned: Snip, mix: Float = 1f): Reamped {
        require(mix in 0f..1f) { "mix is 0..1, got $mix" }
        val rate = sent.sampleRate
        val ret = sameRate(returned, rate)
        val alignment = align(sent, ret)
        val wet = cutAtArrival(ret, alignment.lagFrames, sent.frameCount, alignment.inverted)
        val wetCh = channelsLike(wet, sent.channels)
        normalizeTo(wetCh.samples, sent.peak())

        val frames = max(wetCh.frameCount, sent.frameCount)
        val ch = sent.channels
        val out = FloatArray(frames * ch)
        for (i in out.indices) {
            val dry = if (i < sent.samples.size) sent.samples[i] else 0f
            val w = if (i < wetCh.samples.size) wetCh.samples[i] else 0f
            out[i] = dry * (1f - mix) + w * mix
        }
        return Reamped(Snip(out, ch, rate), alignment)
    }

    // ---- the room -----------------------------------------------------------

    /**
     * The probe: an exponential sine sweep, [PROBE_LO_HZ] to [PROBE_HI_HZ]
     * (or 45 % of the rate, whichever is lower) over [PROBE_SECONDS] at
     * [PROBE_LEVEL], faded in and out so the speaker never thumps. Mono.
     */
    fun probe(sampleRate: Int): Snip {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        val n = (PROBE_SECONDS * sampleRate).toInt()
        val f1 = PROBE_LO_HZ.toDouble()
        val f2 = min(PROBE_HI_HZ, sampleRate * 0.45f).toDouble()
        val t = PROBE_SECONDS.toDouble()
        val k = ln(f2 / f1)
        val fade = (PROBE_FADE_MS / 1000f * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(n) { i ->
            val time = i.toDouble() / sampleRate
            val phase = 2.0 * PI * f1 * t / k * (exp(time / t * k) - 1.0)
            val env = when {
                i < fade -> i / fade.toFloat()
                i >= n - fade -> (n - 1 - i) / fade.toFloat()
                else -> 1f
            }
            (PROBE_LEVEL * env * sin(phase)).toFloat()
        }
        return Snip(out, 1, sampleRate)
    }

    /**
     * The room's impulse response, deconvolved from what [returned] heard of
     * [probe]. Farina's inverse filter — the sweep reversed, with a
     * 6 dB/octave tilt so every band weighs the same — is convolved with
     * the return; the linear response lands at the sweep's length plus
     * the latency, and the harmonic distortion the speaker added lands
     * *before* it, where the cut leaves it behind. Scaled so a perfect
     * wire (the probe fed straight back) is a unit impulse: the room's
     * own gain reads true, and ROOM OF ITSELF re-normalizes anyway. Cut
     * from [IMPULSE_PRE_MS] before the peak, then as long as the tail
     * sounds, faded at the end.
     */
    fun impulse(probe: Snip, returned: Snip): Impulse {
        val rate = probe.sampleRate
        val p = mono(probe)
        val r = mono(sameRate(returned, rate))
        checkReturn(r)
        require(p.size >= 32) { "the probe is too short to deconvolve: ${p.size} frames" }

        val inv = inverseFilter(p, rate)
        val deconvolved = convolve(r, inv)
        val reference = convolve(p, inv)
        var refPeak = 0f
        for (v in reference) if (abs(v) > refPeak) refPeak = abs(v)
        require(refPeak > 1e-9f) { "the probe deconvolves to nothing - it is silent" }

        // The arrival: the deconvolution's loudest point, standing out over
        // the rest of it the way align's match must.
        var peakIdx = 0
        var peakAbs = -1f
        var sumSq = 0.0
        for (i in deconvolved.indices) {
            val a = abs(deconvolved[i])
            sumSq += a * a.toDouble()
            if (a > peakAbs) {
                peakAbs = a
                peakIdx = i
            }
        }
        val rms = sqrt(sumSq / deconvolved.size.coerceAtLeast(1)).toFloat()
        val standout = if (rms > 1e-12f) peakAbs / rms else 0f
        if (standout < MIN_STANDOUT) {
            throw Refused(
                "the room didn't answer - the sweep never came back clearly (standout %.1f); " +
                    "is the speaker reaching the mic?".format(java.util.Locale.ROOT, standout),
            )
        }
        // The reference's own peak sits at the probe's length minus one;
        // whatever is past that is the trip's latency.
        val lag = peakIdx - (p.size - 1)

        val pre = (IMPULSE_PRE_MS / 1000f * rate).toInt()
        val start = (peakIdx - pre).coerceAtLeast(0)
        // Well before the arrival the deconvolution holds only what the
        // room's noise became through the inverse filter — its floor.
        val quietUntil = (peakIdx - (0.05f * rate).toInt()).coerceAtLeast(0)
        val end = tailEnd(deconvolved, start, peakIdx + 1, rate, noiseRms(deconvolved, 0, quietUntil, rate))
        val ir = FloatArray(end - start) { deconvolved[start + it] / refPeak }
        fadeOut(ir, (FADE_MS / 1000f * rate).toInt())
        return Impulse(Snip(ir, 1, rate), lag, standout)
    }

    // ---- helpers --------------------------------------------------------------

    private fun checkReturn(r: FloatArray) {
        var peak = 0f
        for (v in r) {
            if (!v.isFinite()) continue
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak < SILENCE_PEAK) {
            throw Refused("the room said nothing back - is the mic hearing the speaker, or the line plugged in?")
        }
        if (peak >= CLIP_PEAK) {
            throw Refused(
                "the return clipped (peak %.2f) - turn the send down, or the mic gain, and try again"
                    .format(java.util.Locale.ROOT, peak),
            )
        }
    }

    /**
     * The return cut at the arrival: [sentFrames] from [lag], then the
     * tail while it sounds. Polarity restored when [inverted].
     */
    private fun cutAtArrival(ret: Snip, lag: Int, sentFrames: Int, inverted: Boolean): Snip {
        val rate = ret.sampleRate
        val ch = ret.channels
        val monoRet = mono(ret)
        val start = lag.coerceIn(0, ret.frameCount)
        val sendEnd = (start + sentFrames).coerceAtMost(ret.frameCount)
        // The room before the arrival is the room's noise floor — what the
        // pre-roll is for. Nothing was playing yet, so whatever the mic
        // heard is hiss, hum and traffic, and the tail is over once it
        // sinks back into that.
        val end = tailEnd(monoRet, start, sendEnd, rate, noiseRms(monoRet, 0, start, rate))
        val frames = end - start
        val out = FloatArray(frames * ch)
        System.arraycopy(ret.samples, start * ch, out, 0, frames * ch)
        if (inverted) for (i in out.indices) out[i] = -out[i]
        fadeOutInterleaved(out, ch, (FADE_MS / 1000f * rate).toInt())
        return Snip(out, ch, rate)
    }

    /**
     * The RMS of `mono[from, to)`, or null when that span is shorter than
     * one envelope window — nothing to measure a floor from.
     */
    private fun noiseRms(mono: FloatArray, from: Int, to: Int, rate: Int): Float? {
        val win = (TAIL_WINDOW_SEC * rate).toInt().coerceAtLeast(1)
        val a = from.coerceAtLeast(0)
        val b = to.coerceAtMost(mono.size)
        if (b - a < win) return null
        var acc = 0.0
        for (i in a until b) acc += mono[i] * mono[i].toDouble()
        return sqrt(acc / (b - a)).toFloat()
    }

    /**
     * Where the sound stops, past [minEnd]: the first point after which
     * the 10 ms RMS envelope has sat under the floor for [TAIL_HOLD_SEC],
     * capped at [TAIL_MAX_SEC] past [minEnd], never past the end of the
     * audio. The floor is [TAIL_FLOOR_DB] under the segment's peak, or
     * [TAIL_NOISE_MARGIN_DB] over the room's own [noiseRms] when that is
     * known and higher — a phone in a kitchen never gets 54 dB quiet, and
     * a tail that has sunk into the hiss is over whatever the peak was.
     */
    private fun tailEnd(mono: FloatArray, start: Int, minEnd: Int, rate: Int, noiseRms: Float?): Int {
        val cap = min(mono.size, minEnd + (TAIL_MAX_SEC * rate).toInt())
        if (minEnd >= cap) return cap.coerceAtLeast(start + 1).coerceAtMost(mono.size)
        var peak = 0f
        for (i in start until cap) {
            val a = abs(mono[i])
            if (a > peak) peak = a
        }
        if (peak <= 0f) return minEnd.coerceAtLeast(start + 1)
        val floor = max(peak * dbToLinear(TAIL_FLOOR_DB), (noiseRms ?: 0f) * dbToLinear(TAIL_NOISE_MARGIN_DB))
        val win = (TAIL_WINDOW_SEC * rate).toInt().coerceAtLeast(1)
        val holdWindows = (TAIL_HOLD_SEC / TAIL_WINDOW_SEC).toInt().coerceAtLeast(1)
        var quiet = 0
        var quietFrom = minEnd
        var pos = minEnd
        while (pos + win <= cap) {
            var acc = 0.0
            for (i in pos until pos + win) acc += mono[i] * mono[i].toDouble()
            val rms = sqrt(acc / win).toFloat()
            if (rms < floor) {
                if (quiet == 0) quietFrom = pos
                quiet++
                // Quiet for the whole hold: the tail ended where the quiet
                // began, plus one window so the last decay isn't clipped.
                if (quiet >= holdWindows) return (quietFrom + win).coerceAtMost(cap)
            } else {
                quiet = 0
            }
            pos += win
        }
        // Quiet ran into the cap: still over where it began.
        return if (quiet > 0) (quietFrom + win).coerceAtMost(cap) else cap
    }

    /** Farina's inverse: the sweep reversed, tilted by exp(-t·ln(f2/f1)/T) so every octave weighs the same. */
    private fun inverseFilter(sweep: FloatArray, rate: Int): FloatArray {
        val f1 = PROBE_LO_HZ.toDouble()
        val f2 = min(PROBE_HI_HZ, rate * 0.45f).toDouble()
        val t = sweep.size.toDouble() / rate
        val k = ln(f2 / f1)
        return FloatArray(sweep.size) { i ->
            val time = i.toDouble() / rate
            (sweep[sweep.size - 1 - i] * exp(-time / t * k)).toFloat()
        }
    }

    /**
     * Cross-correlation of [x] against [y] by FFT: `out[k] = Σ x[n+k]·y[n]`
     * for `k ≥ 0` at index k, negative k wrapped to the top. Sized so no
     * lag aliases.
     */
    private fun crossCorrelate(x: FloatArray, y: FloatArray): FloatArray {
        val n = x.size + y.size
        var size = 1
        while (size < n) size = size shl 1
        val xr = FloatArray(size)
        val xi = FloatArray(size)
        val yr = FloatArray(size)
        val yi = FloatArray(size)
        System.arraycopy(x, 0, xr, 0, x.size)
        System.arraycopy(y, 0, yr, 0, y.size)
        Fft.forward(xr, xi)
        Fft.forward(yr, yi)
        for (b in 0 until size) {
            // X · conj(Y)
            val re = xr[b] * yr[b] + xi[b] * yi[b]
            val im = xi[b] * yr[b] - xr[b] * yi[b]
            xr[b] = re
            xi[b] = im
        }
        Fft.inverse(xr, xi)
        return xr
    }

    /** Linear convolution by FFT; the result runs `x.size + h.size - 1`. */
    private fun convolve(x: FloatArray, h: FloatArray): FloatArray {
        val n = x.size + h.size - 1
        var size = 1
        while (size < n) size = size shl 1
        val xr = FloatArray(size)
        val xi = FloatArray(size)
        val hr = FloatArray(size)
        val hi = FloatArray(size)
        System.arraycopy(x, 0, xr, 0, x.size)
        System.arraycopy(h, 0, hr, 0, h.size)
        Fft.forward(xr, xi)
        Fft.forward(hr, hi)
        for (b in 0 until size) {
            val re = xr[b] * hr[b] - xi[b] * hi[b]
            val im = xr[b] * hi[b] + xi[b] * hr[b]
            xr[b] = re
            xi[b] = im
        }
        Fft.inverse(xr, xi)
        return xr.copyOf(n)
    }

    private fun mono(snip: Snip): FloatArray =
        if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples

    private fun sameRate(snip: Snip, rate: Int): Snip =
        if (snip.sampleRate == rate) snip else Resampler.resample(snip, rate)

    /** [snip] in [channels] channels: a mono spread, a stereo folded. */
    private fun channelsLike(snip: Snip, channels: Int): Snip = when {
        snip.channels == channels -> snip
        channels == 1 -> Cleanup.toMono(snip)
        else -> {
            val out = FloatArray(snip.frameCount * 2)
            for (f in 0 until snip.frameCount) {
                out[f * 2] = snip.samples[f]
                out[f * 2 + 1] = snip.samples[f]
            }
            Snip(out, 2, snip.sampleRate)
        }
    }

    private fun normalizeTo(samples: FloatArray, target: Float) {
        var p = 0f
        for (v in samples) {
            val a = abs(v)
            if (a > p) p = a
        }
        if (p < 1e-9f || target <= 0f) return
        val k = target / p
        for (i in samples.indices) samples[i] *= k
    }

    private fun fadeOut(samples: FloatArray, frames: Int) {
        val n = min(frames, samples.size)
        if (n <= 0) return
        for (i in 0 until n) {
            val g = i / n.toFloat()
            samples[samples.size - 1 - i] *= g
        }
    }

    private fun fadeOutInterleaved(samples: FloatArray, channels: Int, frames: Int) {
        val total = samples.size / channels
        val n = min(frames, total)
        if (n <= 0) return
        for (i in 0 until n) {
            val g = i / n.toFloat()
            val f = total - 1 - i
            for (ch in 0 until channels) samples[f * channels + ch] *= g
        }
    }

    private fun dbToLinear(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
}
