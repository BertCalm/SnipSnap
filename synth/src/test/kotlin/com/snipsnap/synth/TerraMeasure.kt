package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The measures HIT's claims tests read, defined as Phase 0 read them
 * (docs/superpowers/plans/2026-09-30-chimera-phase-0-record.md, §7 and
 * Appendix A), so a printed figure can be set beside Phase 0's own.
 * Specifically:
 * - Onset: the first sample at 1 % of the finite peak.
 * - Overtone balance (OB): from onset + 882 frames to the end, Hann over the
 *   slice, zero-padded to a power of two. Energy at or above 1.5 x the
 *   nominal f0 over energy below it, bin 0 excluded. The split is the
 *   code's, `binToHz < split` (`StruckMetrics.ob` in the evidence folder);
 *   the record's §3.1 prose says "at or below". They differ only for a bin
 *   landing exactly on 1.5 x f0, a float-equality event.
 * - f0: a 2^17-point Hann peak, interpolated on the log magnitude, over
 *   100-300 ms after the onset.
 */
internal object TerraMeasure {

    /** 20 ms at 44.1 kHz: the head is over; what is left is the body. */
    const val BODY_FROM_FRAMES = 882

    fun onsetOf(x: FloatArray): Int {
        var pk = 0f
        for (v in x) if (v.isFinite()) pk = maxOf(pk, abs(v))
        val thr = 0.01f * pk
        for (i in x.indices) if (abs(x[i]) >= thr && thr > 0f) return i
        return 0
    }

    private fun hann(i: Int, n: Int): Float = (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).toFloat()

    fun bandDb(x: FloatArray, from: Int, splitHz: Float, rate: Int): Float {
        val n = x.size - from
        if (n < 8) return 0f
        var size = 1
        while (size < n) size = size shl 1
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until n) re[i] = x[from + i] * hann(i, n)
        Fft.forward(re, im)
        var lo = 0.0
        var hi = 0.0
        for (b in 1..size / 2) {
            val pw = (re[b] * re[b] + im[b] * im[b]).toDouble()
            if (Fft.binToHz(b, size, rate) < splitHz) lo += pw else hi += pw
        }
        return (10.0 * log10((hi + 1e-20) / (lo + 1e-20))).toFloat()
    }

    /** OB of a rendered pad, split at 1.5 x [nominalHz] - the f0 the bank actually used ([bodyOf]), never one derived again from a macro. */
    fun ob(snip: Snip, nominalHz: Float): Float =
        bandDb(snip.samples, onsetOf(snip.samples) + BODY_FROM_FRAMES, nominalHz * 1.5f, snip.sampleRate)

    /** The peak in the first 5 ms after the onset over the whole clip's peak (Phase 0's stable attack figure). */
    fun firstFiveMsPeak(snip: Snip): Float {
        val x = snip.samples
        val on = onsetOf(x)
        var pk5 = 0f
        for (i in on until minOf(x.size, on + 5 * snip.sampleRate / 1000)) pk5 = maxOf(pk5, abs(x[i]))
        val peak = snip.peak()
        return if (peak > 0f) pk5 / peak else 0f
    }

    fun peakHz(x: FloatArray, from: Int, to: Int, lo: Float, hi: Float, rate: Int): Float {
        val end = minOf(x.size, to)
        val n = end - from
        if (n < 64) return 0f
        val size = 1 shl 17
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until n) re[i] = x[from + i] * hann(i, n)
        Fft.forward(re, im)
        val mag = FloatArray(size / 2 + 1) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val binHz = rate.toFloat() / size
        val i0 = (lo / binHz).toInt().coerceAtLeast(1)
        val i1 = (hi / binHz).toInt().coerceAtMost(mag.size - 2)
        var best = i0
        for (i in i0..i1) if (mag[i] > mag[best]) best = i
        val l = ln(mag[best - 1] + 1e-12)
        val c = ln(mag[best] + 1e-12)
        val r = ln(mag[best + 1] + 1e-12)
        val d = 0.5 * (l - r) / (l - 2 * c + r)
        return ((best + d) * binHz).toFloat()
    }

    fun f0(x: FloatArray, rate: Int, nominalHz: Float): Float {
        val on = onsetOf(x)
        return peakHz(x, on + rate / 10, on + 3 * rate / 10, nominalHz * 0.7f, nominalHz * 1.5f, rate)
    }

    fun cents(f: Float, ref: Float): Double = 1200.0 * ln(f.toDouble() / ref) / ln(2.0)

    /** The [Terra.Body] the bank receives for [voice] at [macros]. */
    fun bodyOf(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Terra.Body {
        var body: Terra.Body? = null
        Terra.bankWith(voice, macros) { body = it; null }
        return requireNotNull(body)
    }

    /** RESONANT_CAVITY's stage as Terra.kt runs it (75 Hz band-pass, Q 8, into tanh(x * 1.15), crossfaded by CAVITY): the signal BUZZ sees, for the printed BUZZ figures. */
    fun cavityStage(bank: FloatArray, mix: Float, rate: Int): FloatArray {
        val bp = Dsp.Biquad().apply { bandpass(75f, 8f, rate) }
        val out = bank.copyOf()
        for (i in out.indices) {
            val sat = tanh(bp.process(out[i]) * 1.15f)
            out[i] = out[i] * (1f - mix) + sat * mix
        }
        return out
    }

    /** The cavity tanh's largest input: the peak of 1.15 x the 75 Hz band-pass of the whole bank (today 0.3075, Phase 0). */
    fun tanhInput(bank: FloatArray, rate: Int): Float {
        val bp = Dsp.Biquad().apply { bandpass(75f, 8f, rate) }
        var pk = 0f
        for (v in bank) pk = maxOf(pk, abs(bp.process(v) * 1.15f))
        return pk
    }

    /** Time above [threshold], in ms at [rate] (BUZZ gates on 0.12). */
    fun msAbove(x: FloatArray, threshold: Float, rate: Int): Double = x.count { abs(it) > threshold } * 1000.0 / rate
}
