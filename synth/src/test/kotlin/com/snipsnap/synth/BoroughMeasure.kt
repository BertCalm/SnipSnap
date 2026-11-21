package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * `prototype/borough/hits.py` `measure_hit`, ported for the parity test: one isolated hit, its onset at the
 * steepest rise of its Hilbert envelope in the first 50 ms, reduced to band energy in six windows (dB under the
 * hit's own maximum), the kick's pitch track (strongest 35-160 Hz peak in 30 ms windows) and the snare's body
 * (strongest 140-350 Hz peak at 10-40 ms). And `fit_designed.score`'s grid distance.
 */
internal object BoroughMeasure {
    private const val SR = 44_100.0
    val BANDS = listOf("sub" to (30.0 to 60.0), "low" to (60.0 to 120.0), "punch" to (120.0 to 250.0), "mud" to (250.0 to 500.0),
        "mid" to (500.0 to 1000.0), "knock" to (1000.0 to 3000.0), "crack" to (3000.0 to 6000.0), "air" to (6000.0 to 12000.0))
    val WINDOWS = listOf(0.0 to 0.005, 0.005 to 0.02, 0.02 to 0.05, 0.05 to 0.1, 0.1 to 0.2, 0.2 to 0.3)
    val WINDOW_KEYS = listOf("0-5ms", "5-20", "20-50", "50-100", "100-200", "200-300")
    val PITCH_AT = listOf(0.005, 0.03, 0.06, 0.1, 0.16)
    val PITCH_KEYS = listOf("5ms", "30ms", "60ms", "100ms", "160ms")

    class Hit(val db: Array<DoubleArray>, val pitchHz: DoubleArray, val bodyHz: Double) {
        val kick30: Double get() = pitchHz[1]
    }

    fun fromJson(o: Map<String, JsonValue>): Hit {
        val db = o.getValue("db").obj()
        val pitch = o.getValue("pitch_hz").obj()
        return Hit(Array(WINDOWS.size) { w -> val row = db.getValue(WINDOW_KEYS[w]).obj(); DoubleArray(BANDS.size) { row.getValue(BANDS[it].first).num() } },
            DoubleArray(PITCH_KEYS.size) { pitch.getValue(PITCH_KEYS[it]).num() }, o.getValue("body_hz").num())
    }

    private fun hann(n: Int) = DoubleArray(n) { if (n == 1) 1.0 else 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

    /** |rfft(s * hanning, nfft)|^2 by bin. */
    private fun power(s: DoubleArray, nfft: Int): DoubleArray {
        val w = hann(s.size)
        val re = DoubleArray(nfft); val im = DoubleArray(nfft)
        for (i in s.indices) re[i] = s[i] * w[i]
        BoroughDsp.fft(re, im)
        return DoubleArray(nfft / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
    }

    private fun peakHz(s: DoubleArray, lo: Double, hi: Double): Double {
        val p = power(s, 16384)
        var best = -1.0; var at = 0
        for (k in p.indices) { val f = k * SR / 16384; if (f > lo && f < hi && p[k] > best) { best = p[k]; at = k } }
        return round(at * SR / 16384 * 10) / 10
    }

    fun measure(x: DoubleArray): Hit {
        val e = BoroughDsp.hilbertEnvelope(x)
        val lim = min(e.size, (0.05 * SR).toInt())
        var arg = 0; var best = Double.NEGATIVE_INFINITY
        for (i in 0 until lim - 1) { val d = e[i + 1] - e[i]; if (d > best) { best = d; arg = i } }
        val o = max(0, arg - (0.001 * SR).toInt())
        val segN = (0.3 * SR).toInt()
        val seg = DoubleArray(segN) { if (o + it < x.size) x[o + it] else 0.0 }
        val rows = Array(WINDOWS.size) { wi ->
            val (a, b) = WINDOWS[wi]
            val s = seg.copyOfRange((a * SR).toInt(), (b * SR).toInt())
            var nfft = 1; while (nfft < s.size) nfft = nfft shl 1
            nfft = max(256, nfft)
            val p = power(s, nfft)
            DoubleArray(BANDS.size) { bi ->
                val (lo, hi) = BANDS[bi].second
                var sum = 0.0
                for (k in p.indices) { val f = k * SR / nfft; if (f >= lo && f < hi) sum += p[k] }
                10 * log10(sum / max(b - a, 1e-6) + 1e-20)
            }
        }
        val top = rows.maxOf { r -> r.max() }
        val db = Array(rows.size) { w -> DoubleArray(BANDS.size) { round((rows[w][it] - top) * 10) / 10 } }
        val pitch = DoubleArray(PITCH_AT.size) { i ->
            val c = PITCH_AT[i]
            peakHz(seg.copyOfRange((c * SR).toInt(), ((c + 0.03) * SR).toInt()), 35.0, 160.0)
        }
        val body = peakHz(seg.copyOfRange((0.01 * SR).toInt(), (0.04 * SR).toInt()), 140.0, 350.0)
        return Hit(db, pitch, body)
    }

    /**
     * fit_designed.score's grid part: RMS dB over windows x bands, both floored at -40 dB under their own peak; a
     * kick's sub and low in 0-5 ms left out (too short a window to read them), a snare's sub and low everywhere (the
     * kick bleeds there in a loop). Every other pad takes the kick's mask: those two cells are empty for it anyway.
     */
    fun gridDb(ref: Hit, pad: Hit, snare: Boolean): Double {
        var s = 0.0; var n = 0
        for (w in WINDOWS.indices) for (b in BANDS.indices) {
            if (b < 2 && (snare || w == 0)) continue
            val d = max(ref.db[w][b], -40.0) - max(pad.db[w][b], -40.0)
            s += d * d; n++
        }
        return sqrt(s / n)
    }

    fun semis(a: Double, b: Double) = 12 * log2(max(a, 1.0) / max(b, 1.0))
    fun absSemis(a: Double, b: Double) = abs(semis(a, b))

    /** The share of energy above 4 kHz in the hit's first 300 ms against the bands below, dB: a brightness bias check. */
    fun brightDb(h: Hit): Double {
        var hi = 0.0; var all = 0.0
        for (w in WINDOWS.indices) for (b in BANDS.indices) { val p = Math.pow(10.0, h.db[w][b] / 10); all += p; if (b >= 6) hi += p }
        return 10 * log10(hi / all + 1e-20)
    }
}
