package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * The two owner-selected Velour treatments: parallel chorus/finite echo/grains,
 * wet-only finite room, then a fixed-calibration combo surrogate. Model 1 keeps
 * the Python audition's filter coefficients and procedural room/draw tables.
 * These tables are generated synthetic data, not samples of a commercial pedal.
 *
 * A per-pad board sees one onset at zero. [renderBus] accepts the actual drum
 * sends and snare events so a phrase shares memory and amp sag. Rendering each
 * pad independently is deliberately not represented as an equivalent shared bus.
 * The existing VALVE peak-relative contract and rack tail cap remain unchanged.
 */
object Velour {
    const val MODEL_VERSION = 1
    const val RATE = WavWriter.MPC_SAMPLE_RATE
    const val TAIL_SECONDS = 3.15f
    private const val MAX_FRAMES = RATE * 16
    enum class Treatment(val space: Double, val scatter: Double, val drive: Double) {
        VELOUR(.30, .25, .35), BLOOM(.60, .65, .50),
    }

    internal fun resource(name: String): ByteArray =
        checkNotNull(Velour::class.java.getResourceAsStream("/velour/v1/$name")) { "Missing Velour model-1 asset: $name" }
            .use { it.readBytes() }

    private val draws: DoubleArray by lazy {
        val b = ByteBuffer.wrap(resource("grain-draws.f64")).order(ByteOrder.LITTLE_ENDIAN)
        DoubleArray(b.remaining() / 8) { b.double }
    }

    private fun room(t: Treatment): FloatArray {
        val b = ByteBuffer.wrap(resource(if (t == Treatment.VELOUR) "velour-room.f32" else "bloom-room.f32"))
            .order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.remaining() / 4) { b.float }
    }

    /** Immutable spectra, bounded to the last two sizes/treatments (at most ~32 MiB). */
    private data class Spectrum(val re: FloatArray, val im: FloatArray)
    private val roomCache = object : LinkedHashMap<Pair<Treatment, Int>, Spectrum>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Treatment, Int>, Spectrum>?) = size > 2
    }
    @Synchronized private fun spectrum(t: Treatment, n: Int): Spectrum = roomCache.getOrPut(t to n) {
        val re = room(t).copyOf(n); val im = FloatArray(n)
        Fft.forward(re, im)
        Spectrum(re, im)
    }
    internal fun clearCache() = synchronized(this) { roomCache.clear() }

    /** Hard bypass at zero; intermediate amounts blend the complete treatment at fixed source gain. */
    fun process(snip: Snip, amount: Float = 1f, treatment: Treatment = Treatment.VELOUR): Snip {
        require(amount.isFinite() && amount in 0f..1f) { "Velour amount must be finite and in 0..1" }
        if (amount == 0f || snip.frameCount == 0) return snip
        require(snip.sampleRate == RATE && snip.channels in 1..2) { "Velour needs mono/stereo 44.1 kHz audio" }
        require(snip.samples.all { it.isFinite() }) { "Velour needs finite source audio" }
        val frames = snip.frameCount.toLong() + (TAIL_SECONDS.toDouble() * RATE).roundToInt()
        require(frames <= MAX_FRAMES) { "Velour source plus release must be at most 16 seconds" }
        val n = frames.toInt()
        val out = FloatArray(n * snip.channels)
        for (ch in 0 until snip.channels) {
            val dry = FloatArray(n) { if (it < snip.frameCount) snip.samples[it * snip.channels + ch] else 0f }
            val wet = renderBus(dry, dry, doubleArrayOf(0.0), treatment).samples
            for (i in 0 until n) out[i * snip.channels + ch] = dry[i] + amount * (wet[i] - dry[i])
        }
        // Linear attenuation only at the sample-print boundary; raw bus diagnostics
        // retain the reference gain, and quiet sources are never raised.
        Dsp.limitPeak(out, .95f)
        return Snip(out, snip.channels, RATE)
    }

    /** Finite release is already included in these equal-length mono buffers. No normalization here. */
    fun renderBus(dry: FloatArray, send: FloatArray, onsets: DoubleArray, treatment: Treatment = Treatment.VELOUR): Snip {
        require(dry.size == send.size && dry.size in 1..MAX_FRAMES) { "Velour bus buffers must match and fit 16 seconds" }
        require(dry.all { it.isFinite() } && send.all { it.isFinite() }) { "Velour bus needs finite samples" }
        require(onsets.size <= 256 && onsets.all { it.isFinite() && it >= 0 && it < dry.size.toDouble() / RATE }) {
            "Velour needs at most 256 onsets inside the input"
        }
        require(onsets.asList().zipWithNext().all { (a, b) -> a <= b }) { "Velour onsets must be ordered" }
        val n = dry.size
        val wet = DoubleArray(n)
        // Three short reads, four feed-forward echoes. No feedback gain or unstable loop.
        for (i in 0 until n) {
            var chorus = 0.0
            for (k in 0..2) {
                val pos = i - RATE * (.014 + .0014 * sin(2 * PI * .32 * i / RATE + k * 2 * PI / 3))
                chorus += read(send, pos) / 3
            }
            wet[i] = .14 * chorus
            for (k in 1..4) {
                val delay = (RATE * (60.0 / 84 / 2) * k).roundToInt()
                if (i >= delay) wet[i] += .16 * .32.pow(k - 1) * send[i - delay]
            }
        }
        val grains = DoubleArray(n); val weight = DoubleArray(n)
        for ((event, onset) in onsets.withIndex()) for (j in 0..7) {
            val index = (event * 8 + j) * 3
            val draw = draws[index]; val jitter = draws[index + 1]; val speedDraw = draws[index + 2]
            if (draw > .06 + .84 * treatment.scatter) continue
            val speed = if (speedDraw < .24) .5 else 1.0
            val length = (RATE * (.075 + .05 * jitter)).toInt()
            val start = ((onset + .14 + j * .125 + .025 * jitter) * RATE).toInt()
            for (g in 0 until minOf(length, n - start).coerceAtLeast(0)) {
                val w = .5 * (1 - cos(2 * PI * g / (length - 1)))
                grains[start + g] += w * read(send, onset * RATE + g * speed) * .55 * .82.pow(j)
                weight[start + g] += w
            }
        }
        for (i in wet.indices) wet[i] += .65 * grains[i] / max(1.0, weight[i])
        val room = convolve(wet, treatment)
        for (i in wet.indices) wet[i] += (.3 + 1.7 * treatment.space) * 12 * room[i]
        val colored = filter(wet, VelourCoefficients.lp4800)
        val mixed = DoubleArray(n) { dry[it] + colored[it] * (.55 + 1.3 * treatment.space) }
        val finished = clean(amp(clean(mixed), treatment.drive))
        return Snip(FloatArray(n) { finished[it].toFloat() }, 1, RATE)
    }

    private fun read(x: FloatArray, pos: Double): Double {
        if (pos < 0 || pos > x.lastIndex) return 0.0
        val a = pos.toInt(); val f = pos - a
        return x[a] * (1 - f) + x[minOf(a + 1, x.lastIndex)] * f
    }

    private fun convolve(x: DoubleArray, t: Treatment): FloatArray {
        var n = 1
        while (n < x.size + RATE * 4 - 1) n *= 2
        val h = spectrum(t, n)
        val re = FloatArray(n) { if (it < x.size) x[it].toFloat() else 0f }; val im = FloatArray(n)
        Fft.forward(re, im)
        for (i in re.indices) {
            val a = re[i]; val b = im[i]
            re[i] = a * h.re[i] - b * h.im[i]
            im[i] = a * h.im[i] + b * h.re[i]
        }
        Fft.inverse(re, im)
        return re.copyOf(x.size)
    }

    /** Model-local Double SOS state preserves the approved filter shape without changing shared DSP. */
    internal fun filter(x: DoubleArray, sos: Array<DoubleArray>): DoubleArray {
        var out = x
        for (c in sos) {
            var s1 = 0.0; var s2 = 0.0
            out = DoubleArray(x.size) { i ->
                val input = out[i]; val y = c[0] * input + s1
                s1 = c[1] * input - c[4] * y + s2
                s2 = c[2] * input - c[5] * y
                y
            }
        }
        return out
    }

    internal fun clean(x: DoubleArray): DoubleArray {
        val mean = x.average()
        val out = filter(DoubleArray(x.size) { x[it] - mean }, VelourCoefficients.hp20)
        val fade = minOf(out.size, (RATE * .012).toInt())
        if (fade > 1) for (i in 0 until fade) out[out.size - fade + i] *= 1.0 - i.toDouble() / (fade - 1)
        return out
    }

    /** Symmetric polyphase FIR: same centered 81 taps and zero extension as the Python reference. */
    private fun up(x: DoubleArray): DoubleArray = DoubleArray(x.size * 4) { i ->
        var y = 0.0
        for (tap in (i + 40) % 4..80 step 4) {
            val at = i + 40 - tap
            if (at / 4 in x.indices) y += x[at / 4] * VelourCoefficients.fir[tap] * 4
        }
        y
    }
    private fun down(x: DoubleArray): DoubleArray = DoubleArray(x.size / 4) { i ->
        var y = 0.0
        for (tap in 0..80) {
            val at = i * 4 + 40 - tap
            if (at in x.indices) y += x[at] * VelourCoefficients.fir[tap]
        }
        y
    }
    private fun amp(x: DoubleArray, drive: Double): DoubleArray {
        val pre = filter(up(x), VelourCoefficients.hp48)
        val a = exp(-1 / (.035 * RATE * 4)); var env = 0.0
        for (i in pre.indices) {
            val z = (1 + 7 * drive) * pre[i]
            val tube = if (z >= 0) tanh(z) else .85 * tanh(z / .85)
            env = (1 - a) * abs(tube) + a * env
            pre[i] = tube / (1 + 1.25 * drive * env)
        }
        return down(filter(filter(pre, VelourCoefficients.hp55), VelourCoefficients.cab))
    }
}
