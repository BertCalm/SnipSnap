package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ROOM: the hit in a room. The walls answer first, then the air blooms dense and settles darker.
 *
 * Three parts, summed (docs/superpowers/specs/2026-10-31-room-section-design-brief.md, and the Python
 * prototype beside it, which this follows):
 * 1. **The direct sound**, at 1/r against a 0.4 m mic, with the tone a mic's distance gives it: a close
 *    mic lifts the bass (proximity, gone by 2 m) and a far one hears less top.
 * 2. **The walls' first answers**: a box-shaped room's image sources, one and two bounces (24 paths), each
 *    jittered a little (walls are not mirrors) and darkened once per bounce.
 * 3. **The bloom**: from the first two-bounce arrival, two short all-passes into sixteen delay lines with
 *    orthonormal Hadamard feedback and Jot's damping per line, so every mode in a band decays together.
 *    The decay is Sabine's (0.161 V / (S x absorption)), held under 0.9 s.
 *
 * SIZE is the room, a booth (2.4 x 2.0 x 2.2 m) to a hall (28 x 20 x 11 m); WALLS is soft (curtains) to hard
 * (tile); DISTANCE walks the mic from 0.4 m to the far corner on a log scale, and 0 is no room at all (the
 * neutral, so a fade takes the room away). Peak-matched like every rack section; the tail stops 60 dB under
 * the peak and never runs past [MAX_TAIL_SECONDS]. Linear and time-invariant, so the same taps and network
 * run on each channel and identical channels stay identical.
 */
object Room {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("SIZE", 0.3f),
        MacroSpec("WALLS", 0.5f),
        MacroSpec("DISTANCE", 0.6f),
    )

    /** The most tail any setting may add, seconds: the rack's own budget. */
    const val MAX_TAIL_SECONDS = 1.0f

    private const val SPEED_OF_SOUND = 343f            // ChamberRoom.SPEED_OF_SOUND
    private const val CLOSE_MIC_METRES = 0.4f
    private const val MAX_RT60 = 0.9f                  // under the tail budget, with room for the fade
    private const val WET_TRIM = 0.35f                 // the prototype's level sweep: a snare plays about 0.5 dB louder at the default
    private const val RT_CALIBRATION = 1.3f            // the network's measured decay ran 0.75 to 0.78 of Sabine's in the prototype
    private const val PROXIMITY_DB = 5f
    private const val DARKEN_DB_PER_DOUBLING = 3f
    private val ROOM_MIN = floatArrayOf(2.4f, 2.0f, 2.2f)
    private val ROOM_MAX = floatArrayOf(28f, 20f, 11f)

    // The late level follows the decay (energy in proportion to RT60), anchored to the first gate's heard
    // default room (4.86 x 3.81 x 3.29 m, absorption 0.182). Sabine's own diffuse level falls as a room grows,
    // which made a hall drier than a small room in the prototype's first round.
    private val ANCHOR_S = 2f * (4.86f * 3.81f + 4.86f * 3.29f + 3.81f * 3.29f)
    private val DIFFUSE_AT_ANCHOR = sqrt(16f * PI.toFloat() / (ANCHOR_S * 0.182f / 0.818f))
    private val RT_AT_ANCHOR = 0.161f * 4.86f * 3.81f * 3.29f / (ANCHOR_S * 0.182f)

    // Lines 15 to 80 ms at the middle of SIZE: shorter lines sang (strong single modes), longer ones filled in late.
    private const val SHORTEST_LINE_SECONDS = 0.015
    private const val LONGEST_LINE_SECONDS = 0.08
    private const val LINES = 16

    private val JITTER_SEED = Dsp.seedFor("ROOM", "walls")

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> = MACROS.associate { it.name to random.nextFloat() }

    internal fun settled(macros: Map<String, Float>): Map<String, Float> {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    /** Everything the knobs decide, computed once per render. */
    internal class Plan(
        val dims: FloatArray,
        val absorption: Float,
        val micMetres: Float,
        val rt60: Float,
        val wallHz: Float,
        /** Tap delay after the direct sound (samples, fractional), gain, and bounce count. */
        val taps: List<Triple<Float, Float, Int>>,
        val bloomStart: Int,
        val lines: IntArray,
        val lineGain: FloatArray,
        val jot: FloatArray,
        val proximityDb: Float,
        val darkenDb: Float,
    )

    internal fun plan(m: Map<String, Float>, rate: Int, jotClamp: Float = JOT_CLAMP): Plan {
        val size = m.getValue("SIZE")
        val walls = m.getValue("WALLS")
        val distance = m.getValue("DISTANCE")
        val dims = FloatArray(3) { ROOM_MIN[it] * (ROOM_MAX[it] / ROOM_MIN[it]).pow(size) }
        val absorption = Dsp.expMap(walls, 0.55f, 0.06f)
        val src = floatArrayOf(dims[0] * 0.33f, dims[1] * 0.45f, 0.9f)
        val far = floatArrayOf(dims[0] * 0.85f, dims[1] * 0.8f, 1.6f)
        val span = length(far[0] - src[0], far[1] - src[1], far[2] - src[2])
        // The mic walks out on a log scale: the first gate's linear walk put a hall's default mic halfway across
        // the floor, where the floor bounce lands with the drum and the room reads small.
        val want = Dsp.expMap(distance, CLOSE_MIC_METRES, max(span, CLOSE_MIC_METRES + 0.01f))
        val t = want / span
        val mic = FloatArray(3) { src[it] + (far[it] - src[it]) * t }
        val direct = length(src[0] - mic[0], src[1] - mic[1], src[2] - mic[2])

        val r = sqrt(1f - absorption)                 // pressure reflection per bounce
        val noise = Dsp.Noise(JITTER_SEED)
        val taps = mutableListOf<Triple<Float, Float, Int>>()
        for (nx in -2..2) for (ny in -2..2) for (nz in -2..2) for (px in 0..1) for (py in 0..1) for (pz in 0..1) {
            val bounces = abs(2 * nx - px) + abs(2 * ny - py) + abs(2 * nz - pz)
            if (bounces == 0 || bounces > 2) continue
            val ix = (1 - 2 * px) * src[0] + 2 * nx * dims[0]
            val iy = (1 - 2 * py) * src[1] + 2 * ny * dims[1]
            val iz = (1 - 2 * pz) * src[2] + 2 * nz * dims[2]
            val d = length(ix - mic[0], iy - mic[1], iz - mic[2])
            val jitter = noise.next() * 0.0004f * bounces
            val delay = ((d - direct) / SPEED_OF_SOUND + jitter) * rate
            taps += Triple(max(delay, 0f), r.pow(bounces) / max(d, 0.25f), bounces)
        }
        val bloomStart = taps.filter { it.third == 2 }.minOf { it.first }.toInt()

        val volume = dims[0] * dims[1] * dims[2]
        val surface = 2f * (dims[0] * dims[1] + dims[0] * dims[2] + dims[1] * dims[2])
        val rt60 = min(0.161f * volume / (surface * absorption), MAX_RT60)
        val drive = min(rt60 * RT_CALIBRATION, 1f)
        val hfRatio = Dsp.lin(walls, 0.25f, 0.75f)
        val scale = Dsp.expMap(size, 0.6f, 2.2f)
        val lines = primesNear(LINES) { k ->
            SHORTEST_LINE_SECONDS * (LONGEST_LINE_SECONDS / SHORTEST_LINE_SECONDS).pow(k / (LINES - 1.0)) * rate * scale
        }
        val gains = FloatArray(LINES) { 10.0.pow(-3.0 * lines[it] / (drive * rate)).toFloat() }
        // Jot (1992): b = ln(10)/4 x log10(g) x (1 - 1/hf^2), so a line's highs die hf times as fast as its lows
        // per second whatever its length. Clamped: soft walls on a long line pushed b past 1, and the prototype's
        // network diverged.
        val jot = FloatArray(LINES) {
            (ln(10.0) / 4 * log10(gains[it].toDouble()) * (1 - 1 / (hfRatio * hfRatio))).toFloat().coerceIn(0f, jotClamp)
        }
        val proximity = PROXIMITY_DB * ((2f - direct) / 1.6f).coerceIn(0f, 1f).let { it * it }
        val darken = -DARKEN_DB_PER_DOUBLING * max(log2(max(direct, CLOSE_MIC_METRES) / CLOSE_MIC_METRES), 0f)
        return Plan(dims, absorption, direct, rt60, Dsp.expMap(walls, 3_500f, 14_000f), taps, bloomStart,
            lines, gains, jot, proximity, darken)
    }

    /** The stability clamp on Jot's coefficient; RoomTest proves a render diverges without it. */
    internal const val JOT_CLAMP = 0.9f

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip = process(snip, macros, JOT_CLAMP)

    /** [jotClamp] is the stability clamp; only RoomTest passes another, to prove the clamp is needed. */
    internal fun process(snip: Snip, macros: Map<String, Float>, jotClamp: Float): Snip {
        val m = settled(macros)
        val inPeak = snip.peak()
        if (m.getValue("DISTANCE") <= 0.001f || inPeak <= 0f || snip.frameCount == 0) {
            return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        }
        val rate = snip.sampleRate
        val p = plan(m, rate, jotClamp)
        val n = snip.frameCount
        val full = n + (MAX_TAIL_SECONDS * rate).toInt()
        val lateGain = lateGain(p, rate)
        val directGain = CLOSE_MIC_METRES / max(p.micMetres, CLOSE_MIC_METRES)
        val wet = CLOSE_MIC_METRES * WET_TRIM

        val channels = Array(snip.channels) { ch ->
            val x = FloatArray(n) { snip.samples[it * snip.channels + ch] }
            val out = FloatArray(full)
            // 1. the direct sound, with its distance tone
            val low = Dsp.Biquad().apply { lowShelf(150f, p.proximityDb, rate) }
            val high = Dsp.Biquad().apply { highShelf(3_500f, p.darkenDb, rate) }
            for (i in 0 until n) out[i] = directGain * high.process(low.process(x[i]))
            // 2. the walls: one-bounce and two-bounce taps, darkened once and twice
            val early = Array(3) { FloatArray(full) }
            for ((delay, gain, bounces) in p.taps) {
                val whole = delay.toInt()
                val frac = delay - whole
                val e = early[bounces]
                for (i in 0 until n) {
                    val k = i + whole
                    if (k + 1 >= full) break
                    e[k] += x[i] * gain * (1f - frac)
                    e[k + 1] += x[i] * gain * frac
                }
            }
            for (b in 1..2) {
                val e = early[b]
                repeat(b) { val lp = Dsp.FixedOnePole(p.wallHz, rate); for (i in e.indices) e[i] = lp.lp(e[i]) }
                for (i in 0 until full) out[i] += wet * e[i]
            }
            // 3. the bloom
            val feed = FloatArray(full)
            for (i in 0 until n) if (i + p.bloomStart < full) feed[i + p.bloomStart] = x[i]
            val late = bloom(feed, p, rate)
            for (i in 0 until full) out[i] += wet * lateGain * late[i]
            highPass(out, 20f, rate)
            out
        }

        // Peak-matched together, then the tail stops 60 dB under the input's peak, with a 10 ms fade.
        var outPeak = 0f
        for (c in channels) for (v in c) outPeak = max(outPeak, abs(v))
        val g = if (outPeak > 1e-9f) inPeak / outPeak else 1f
        var last = n - 1
        for (c in channels) for (i in c.indices) {
            c[i] = (c[i] * g).coerceIn(-1f, 1f)
            if (abs(c[i]) > inPeak * 1e-3f && i > last) last = i
        }
        val frames = min(full, max(n, last + 1 + (0.02f * rate).toInt()))
        val samples = FloatArray(frames * snip.channels)
        for (f in 0 until frames) for (ch in 0 until snip.channels) samples[f * snip.channels + ch] = channels[ch][f]
        if (frames > n) Dsp.fadeTail(samples, ms = 10f, rate = rate, channels = snip.channels)
        return Snip(samples, snip.channels, rate)
    }

    /**
     * The bloom's level: the late response to one click, measured once, scaled to the diffuse level the decay
     * calls for. ROOM is linear, so the gain that sets the click's energy sets every pad's.
     */
    private fun lateGain(p: Plan, rate: Int): Float {
        val click = FloatArray((1.2f * rate).toInt())
        click[0] = 1f
        val late = bloom(click, p, rate)
        var energy = 0.0
        for (v in late) energy += v.toDouble() * v
        val diffuse = DIFFUSE_AT_ANCHOR * sqrt(p.rt60 / RT_AT_ANCHOR)
        return (diffuse / sqrt(max(energy, 1e-24)) * 0.5).toFloat()
    }

    /** Two short all-passes, then the sixteen-line network, then a 40 Hz high-pass (rooms carry no DC). */
    internal fun bloom(feed: FloatArray, p: Plan, rate: Int): FloatArray {
        val diffused = allpass(allpass(feed, 142, 0.7f), 107, 0.7f)
        val bufs = Array(LINES) { FloatArray(p.lines[it]) }
        val heads = IntArray(LINES)
        val state = FloatArray(LINES)
        val v = FloatArray(LINES)
        val out = FloatArray(feed.size)
        for (f in feed.indices) {
            var sum = 0f
            for (i in 0 until LINES) {
                val old = bufs[i][heads[i]]
                sum += SIGN[i] * old
                state[i] = p.lineGain[i] * (1f - p.jot[i]) * old + p.jot[i] * state[i]
                v[i] = state[i]
            }
            out[f] = sum * 0.25f
            hadamard16(v)
            val drive = diffused[f] * 0.25f
            for (i in 0 until LINES) {
                bufs[i][heads[i]] = v[i] * 0.25f + drive * SIGN[i]
                if (++heads[i] == p.lines[i]) heads[i] = 0
            }
        }
        highPass(out, 40f, rate)
        highPass(out, 40f, rate)
        return out
    }

    private val SIGN = FloatArray(LINES) { if (it % 3 == 0) -1f else 1f }

    /** In place, unscaled: four butterfly stages. With the 0.25 the caller applies, an orthonormal matrix. */
    private fun hadamard16(v: FloatArray) {
        var span = 1
        while (span < LINES) {
            var group = 0
            while (group < LINES) {
                for (j in 0 until span) {
                    val a = v[group + j]
                    val b = v[group + j + span]
                    v[group + j] = a + b
                    v[group + j + span] = a - b
                }
                group += span * 2
            }
            span *= 2
        }
    }

    /** Schroeder all-pass: y[n] = -g x[n] + x[n-d] + g y[n-d]. */
    private fun allpass(x: FloatArray, d: Int, g: Float): FloatArray {
        val line = FloatArray(d)
        var head = 0
        return FloatArray(x.size) { i ->
            val delayed = line[head]
            val w = x[i] + g * delayed
            line[head] = w
            if (++head == d) head = 0
            delayed - g * w
        }
    }

    /** A one-pole high-pass in place: the input less its low-pass. */
    private fun highPass(x: FloatArray, hz: Float, rate: Int) {
        val lp = Dsp.FixedOnePole(hz, rate)
        for (i in x.indices) x[i] -= lp.lp(x[i])
    }

    /** [count] distinct primes, each the first at or above [target] of its index. */
    private fun primesNear(count: Int, target: (Int) -> Double): IntArray {
        val out = IntArray(count)
        for (k in 0 until count) {
            var p = max(3, target(k).toInt())
            while (!isPrime(p) || (0 until k).any { out[it] == p }) p++
            out[k] = p
        }
        return out
    }

    private fun isPrime(p: Int): Boolean {
        if (p < 2) return false
        var q = 2
        while (q * q <= p) { if (p % q == 0) return false; q++ }
        return true
    }

    private fun length(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
}
