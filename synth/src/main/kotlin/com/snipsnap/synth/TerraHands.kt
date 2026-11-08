package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bessel functions of the first kind for a circular drumhead's modes: J_m by Bessel's integral and
 * the zeros of J_m, which place mode (m, n) at `j_mn / j_01` times the (0, 1) mode.
 */
internal object Bessel {

    /**
     * J_m([x]) by `(1/pi) * integral over [0, pi] of cos(m t - x sin t)`, the trapezoid rule on a
     * periodic analytic integrand, which converges exponentially once the step count passes
     * `x + m`: this takes `64 + 2 (|x| + m)` steps.
     */
    fun j(m: Int, x: Double): Double {
        val n = 64 + 2 * ceil(abs(x) + m).toInt()
        var s = 0.0
        for (i in 0..n) {
            val t = PI * i / n
            val w = if (i == 0 || i == n) 0.5 else 1.0
            s += w * cos(m * t - x * sin(t))
        }
        return s / n
    }

    private val cache = HashMap<Int, DoubleArray>()

    /** The first [count] positive zeros of J_[m], ascending (bracketed on a 0.2 grid, then bisected). */
    @Synchronized
    fun zeros(m: Int, count: Int): DoubleArray {
        cache[m]?.let { if (it.size >= count) return it.copyOf(count) }
        val out = ArrayList<Double>(count)
        var a = max(0.1, m.toDouble())
        var fa = j(m, a)
        while (out.size < count) {
            val b = a + 0.2
            val fb = j(m, b)
            if (fa == 0.0 || fa * fb < 0.0) {
                var lo = a
                var hi = b
                var flo = fa
                repeat(60) {
                    val mid = 0.5 * (lo + hi)
                    val fm = j(m, mid)
                    if (flo * fm <= 0.0) hi = mid else { lo = mid; flo = fm }
                }
                out += 0.5 * (lo + hi)
            }
            a = b
            fa = fb
        }
        val z = out.toDoubleArray()
        cache[m] = z
        return z.copyOf(count)
    }
}

/**
 * TERRA's hand drums: SLAP (a djembe slap), DAYAN (a tabla tin, the right-hand drum's rim stroke)
 * and DARBUKA (a dumbek tek, a fingertip on a goblet's rim).
 *
 * The characteristic gesture: the hand (or a fingertip) lands near the rim -> its contact drives
 * the head's own modes, hundreds of them -> the drum's body answers (a djembe's bowl, a darbuka's
 * shell, the dayan's syahi-loaded harmonics) -> the upper modes die fast -> silence.
 *
 * Why voices of their own. COMPOUND_MEMBRANE's slap and rim pads were heard (2026-11-07) as a
 * "Drum machine", the cajón's problem again: a few pure sines with the strike pulse mixed beside
 * them. Here the contact drives a circular head's modes. Ported from three rounds of a Python
 * prototype the owner heard (docs/TERRA_HANDS.md); each voice's defaults are the version picked.
 *
 * Literal, then design shapes:
 * - The head: mode (m, n) at `j_mn / j_01` times the (0, 1) mode (TUNE). A hand covers a strip
 *   of the head, so a mode's weight is its shape `J_m(j_mn r)` averaged over the strip, which
 *   gives the low modes back. Each (m > 0) mode is a pair split by uneven tension (0.2 to 0.5 %),
 *   which beats; a djembe's air loading lowers its first modes. Each mode decays as
 *   `T60 = DECAY's T60 * (f01 / f)^tilt`, floored.
 * - The contact (FORCE): a half-sine of constant area, the same push at any FORCE: a harder hand
 *   is a shorter contact (2.6 ms to 0.3 ms), which reaches higher without losing the low end. The
 *   old palm pulse kept a fixed peak, so a harder hand pushed less and FORCE's top end dulled.
 * - A djembe slap lands the palm's heel at the rim, then the fingers 2.5 and 3.6 ms later further
 *   in. Its bowl answers in air at 72 Hz, driven by the head's low-passed motion; its wood knocks.
 * - The dayan: six near-harmonic tones (the syahi's loading), each a split pair a hair off the
 *   harmonic, over a kinar click, the rim strike's own dense inharmonic modes, dead in ~18 ms.
 * - The darbuka: the head rings with its ceramic shell's three modes.
 * Analogy: the hand's smack, the fingertip's snap and the dayan's tick are band-passed noise.
 * Not simulated: air loading beyond the first modes, the hand's damping after contact.
 *
 * Velocity is the contact's own law, as on BOX: a softer hit is a longer contact (up to 1.2 ms
 * more) with less push, its noise as v^1.5, the bowl driven in proportion. The modes are not tilted
 * by [StrikeVelocity.modeFactors]: the longer contact already is that tilt.
 */
internal object TerraHands {

    const val SECONDS = 0.9f
    private const val MAX_MODE_HZ = 12000.0
    private const val MMAX = 30
    private const val NMAX = 18

    fun frames(rate: Int): Int = (SECONDS * rate).toInt()

    // ---------------------------------------------------------------- macros

    fun macros(voice: TerraVoice): List<MacroSpec> = when (voice) {
        // TUNE: the head's (0, 1) mode, 160 to 400 Hz (0.53 is 260 Hz). DECAY: the head's T60,
        // 50 ms to 110 to 250. POS: how far the hand reaches in from the rim. BOWL: the bowl's air.
        // SMACK: the hand's own sound.
        TerraVoice.SLAP -> listOf(MacroSpec("TUNE", 0.53f), MacroSpec("DECAY", 0.5f), MacroSpec("FORCE", 0.95f),
            MacroSpec("POS", 0.875f), MacroSpec("BOWL", 0.5f), MacroSpec("SMACK", 0.5f))
        // TUNE: the tonic, 196 to 440 Hz (0.5 is D4). DECAY: the ring's length. OPEN: 0 the ring
        // finger mutes the fundamental (na), 1 it rings. CLICK: the finger's snap on the kinar.
        TerraVoice.DAYAN -> listOf(MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.5f), MacroSpec("FORCE", 0.9f),
            MacroSpec("OPEN", 1f), MacroSpec("CLICK", 0.65f))
        // TUNE: the head's (0, 1) mode, 200 to 600 Hz (0.483 is 340 Hz). DECAY: the head's T60,
        // 150 ms to 480 to 1 s. SHELL: how long the ceramic rings. SNAP: the fingertip's snap.
        TerraVoice.DARBUKA -> listOf(MacroSpec("TUNE", 0.483f), MacroSpec("DECAY", 0.5f), MacroSpec("FORCE", 0.92f),
            MacroSpec("POS", 0.875f), MacroSpec("SHELL", 0.5f), MacroSpec("SNAP", 0.5f))
        // The doum: DARBUKA's goblet struck in its centre. TUNE as DARBUKA's (0.483 is 340 Hz). DECAY:
        // the head's T60, 150 ms to 400 to 1 s. AIR: the goblet's air at 110 Hz, driven by the head's
        // net volume, its peak 0 to 1x the head's (0.5 heard). SHELL: the ceramic's ring, 0 to 0.24.
        TerraVoice.DOUM -> listOf(MacroSpec("TUNE", 0.483f), MacroSpec("DECAY", 0.5f), MacroSpec("FORCE", 0.4f),
            MacroSpec("AIR", 0.5f), MacroSpec("SHELL", 0.5f))
        TerraVoice.DJEMBE, TerraVoice.DHOLAK, TerraVoice.BAYAN -> TerraFitted.macros(voice)
        else -> error("$voice is not a TERRA hand drum")
    }

    // ---------------------------------------------------------------- the head

    /** One contact: a strip of the head from radius [r0] to [r1] (0 centre, 1 rim), its share of the push, and when it lands. */
    class Impact(val r0: Double, val r1: Double, val share: Double, val delayMs: Double)

    /**
     * A mode of the head: its frequency, its weight per impact, its T60, and the air it moves: an
     * (0, n) mode displaces `2 J1(j0n) / j0n` of its peak (0.43, -0.12, 0.06 for n = 1..3); every
     * m > 0 mode nets zero, so only a centre stroke drives a goblet's or a bowl's air.
     */
    class HeadMode(val hz: Double, val weights: DoubleArray, val t60: Double, val volume: Double = 0.0)

    private fun stripWeight(m: Int, z: Double, r0: Double, r1: Double): Double {
        var s = 0.0
        for (k in 0 until 9) s += Bessel.j(m, z * (r0 + (r1 - r0) * k / 8.0))
        return s / 9.0
    }

    /**
     * Every (m, n) mode under 12 kHz, m to 30 and n to 18, weighted per impact; the ones no impact
     * reaches are left out. [air] lowers named (m, n) modes. Each (m > 0) mode is a split pair,
     * each half at 0.7 of the weight, the split and the second half's T60 jittered from [seed].
     */
    fun headModes(f01: Double, impacts: List<Impact>, t60: Double, tilt: Double, floor: Double, seed: Int,
                  air: Map<Pair<Int, Int>, Double> = emptyMap(), split: Double = 0.005): List<HeadMode> {
        val j01 = Bessel.zeros(0, 1)[0]
        val rng = Random(seed)
        val out = ArrayList<HeadMode>()
        for (m in 0..MMAX) {
            val zs = Bessel.zeros(m, NMAX)
            for ((idx, z) in zs.withIndex()) {
                var f = f01 * z / j01
                air[m to idx + 1]?.let { f *= it }
                if (f > MAX_MODE_HZ) continue
                val ws = DoubleArray(impacts.size) { stripWeight(m, z, impacts[it].r0, impacts[it].r1) * impacts[it].share }
                if (ws.maxOf { abs(it) } < 0.004) continue
                val t = max(floor, t60 * (f01 / f).pow(tilt))
                if (m == 0) {
                    out += HeadMode(f, ws, t, volume = 2.0 * Bessel.j(1, z) / z)
                } else {
                    val d = split * (0.4 + 0.6 * rng.nextDouble())
                    val half = DoubleArray(ws.size) { ws[it] * 0.7 }
                    out += HeadMode(f * (1 - d / 2), half, t)
                    out += HeadMode(f * (1 + d / 2), half, t * (0.85 + 0.3 * rng.nextDouble()))
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- the contact

    /**
     * The contact pulse: a half-sine of constant area (the same push at any FORCE), 2.6 ms soft to
     * 0.3 ms hard, up to 1.2 ms longer on a soft hit, its push in proportion to the hit.
     */
    fun pulse(force: Float, velocity: Float, rate: Int): DoubleArray {
        val widthMs = 2.6 + (0.3 - 2.6) * force + (1.0 - velocity) * 1.2
        val w = max(3, (widthMs * 1e-3 * rate).toInt())
        val p = DoubleArray(w) { sin(PI * it / w) }
        val area = p.sum()
        for (i in p.indices) p[i] *= velocity / area * 400.0
        return p
    }

    /**
     * The head driven by [impacts], every mode a resonator; [level] is HIT's curve per mode (on
     * that mode's output, holding its last value). A mode runs until its own excitation is over
     * and it has decayed 133 dB (2.2 x its T60), the rest of the buffer silent for it.
     */
    fun ringHead(modes: List<HeadMode>, impacts: List<Impact>, force: Float, velocity: Float, rate: Int, frames: Int,
                 level: Array<FloatArray>? = null): FloatArray {
        val p = pulse(force, velocity, rate)
        val starts = IntArray(impacts.size) { (impacts[it].delayMs * 1e-3 * rate).toInt() }
        val excEnd = (starts.maxOrNull() ?: 0) + p.size
        val out = FloatArray(frames)
        for ((k, mode) in modes.withIndex()) {
            if (mode.hz >= rate / 2.0) continue
            val r = exp(-6.9078 / (mode.t60 * rate))
            val th = 2.0 * PI * mode.hz / rate
            val a1 = 2.0 * r * cos(th)
            val a2 = -(r * r)
            val g = sin(th)
            val end = min(frames, excEnd + (2.2 * mode.t60 * rate).toInt())
            val curve = level?.get(k)
            var y1 = 0.0
            var y2 = 0.0
            for (i in 0 until end) {
                var x = 0.0
                if (i < excEnd) for (q in impacts.indices) {
                    val j = i - starts[q]
                    if (j >= 0 && j < p.size) x += mode.weights[q] * p[j]
                }
                val y = g * x + a1 * y1 + a2 * y2
                y2 = y1
                y1 = y
                out[i] += if (curve == null) y.toFloat() else (y * curve[min(i, curve.size - 1)]).toFloat()
            }
        }
        return out
    }

    /** One two-pole resonator at [hz] over [x], T60 [t60], peak-compensated (gain sin theta). */
    fun resonator(x: DoubleArray, hz: Double, t60: Double, gain: Double, rate: Int, frames: Int): DoubleArray {
        val out = DoubleArray(frames)
        val r = exp(-6.9078 / (t60 * rate))
        val th = 2.0 * PI * hz / rate
        val a1 = 2.0 * r * cos(th)
        val a2 = -(r * r)
        val g = gain * sin(th)
        var y1 = 0.0
        var y2 = 0.0
        for (i in 0 until frames) {
            val xi = if (i < x.size) x[i] else 0.0
            val y = g * xi + a1 * y1 + a2 * y2
            y2 = y1
            y1 = y
            out[i] = y
        }
        return out
    }

    /**
     * Noise from [seed] for [ms], high-passed at [lo] and low-passed at [hi], decaying with time
     * constant [decayMs], scaled to RMS [rms] over its [ms]. Not to its peak: a burst this short
     * is a few dozen samples, so one realisation's peak against its energy varies by up to
     * +-40 % from seed to seed (Python harness, 40 seeds), which made the dumbek's snap 2.8x
     * weaker in Kotlin than in the prototype and would vary it from take to take. [rms] is the
     * energy of the burst the owner heard in the prototype.
     */
    fun bandNoise(seed: Int, lo: Float, hi: Float, ms: Double, decayMs: Double, rate: Int, frames: Int, rms: Double): FloatArray {
        val k = min(frames, (ms * 1e-3 * rate).toInt())
        val n = Dsp.Noise(seed)
        val raw = FloatArray(frames)
        for (i in 0 until k) raw[i] = n.next()
        var x = TerraBox.highpass(TerraBox.lowpass(raw, hi, rate), lo, rate)
        var e = 0.0
        for (i in x.indices) {
            x[i] = if (i < k) (x[i] * exp(-i / (decayMs * 1e-3 * rate))).toFloat() else 0f
            if (i < k) e += x[i].toDouble() * x[i]
        }
        val now = kotlin.math.sqrt(e / max(1, k))
        if (now > 1e-12) { val g = (rms / now).toFloat(); for (i in 0 until k) x[i] *= g }
        return x
    }

    private fun peakOf(x: FloatArray): Float { var p = 0f; for (s in x) p = max(p, abs(s)); return p }
    private fun peakOf(x: DoubleArray): Double { var p = 0.0; for (s in x) p = max(p, abs(s)); return p }
    private fun divide(x: FloatArray, peak: Float) { if (peak > 1e-9f) for (i in x.indices) x[i] /= peak }

    // ---------------------------------------------------------------- the three drums

    private fun offset(pos: Float) = (pos - 0.875) * 0.4

    private fun clampR(r: Double) = r.coerceIn(0.0, 0.995)

    /** SLAP's contacts: the palm's heel at the rim, then the fingers 2.5 and 3.6 ms later, moved in or out by POS. */
    fun slapImpacts(m: Map<String, Float>): List<Impact> {
        val o = offset(m.getValue("POS"))
        return listOf(Impact(clampR(0.86 + o), clampR(0.97 + o), 0.5, 0.0), Impact(clampR(0.62 + o), clampR(0.85 + o), 0.35, 2.5),
            Impact(clampR(0.66 + o), clampR(0.88 + o), 0.25, 3.6))
    }

    fun slapF01(m: Map<String, Float>): Double = Dsp.expMap(m.getValue("TUNE"), 160f, 400f).toDouble()

    fun slapModes(m: Map<String, Float>, seed: Int): List<HeadMode> = headModes(
        slapF01(m), slapImpacts(m), Dsp.around(m.getValue("DECAY"), 0.05f, 0.11f, 0.25f).toDouble(), tilt = 0.8, floor = 0.006,
        seed = seed, air = mapOf((0 to 1) to 0.85, (1 to 1) to 0.92, (2 to 1) to 0.96),
    )

    fun darbukaImpacts(m: Map<String, Float>): List<Impact> {
        val o = offset(m.getValue("POS"))
        return listOf(Impact(clampR(0.88 + o), clampR(0.98 + o), 1.0, 0.0))
    }

    fun darbukaF01(m: Map<String, Float>): Double = Dsp.expMap(m.getValue("TUNE"), 200f, 600f).toDouble()

    /** The doum's contact: the fingers flat near the centre. */
    fun doumImpacts(): List<Impact> = listOf(Impact(0.05, 0.5, 1.0, 0.0))

    fun doumModes(m: Map<String, Float>, seed: Int): List<HeadMode> = headModes(
        darbukaF01(m), doumImpacts(), Dsp.around(m.getValue("DECAY"), 0.15f, 0.4f, 1.0f).toDouble(), tilt = 0.6, floor = 0.006, seed = seed,
    )

    /** The goblet's air (110 Hz, T60 0.3 s) driven by the head's net volume, over the head's peak [headPeak]. */
    private fun goblet(modes: List<HeadMode>, force: Float, velocity: Float, headPeak: Double, rate: Int, frames: Int): DoubleArray {
        val pushing = modes.filter { it.volume != 0.0 }.map { HeadMode(it.hz, DoubleArray(it.weights.size) { k -> it.weights[k] * it.volume }, it.t60) }
        val vol = ringHead(pushing, doumImpacts(), force, velocity, rate, frames)
        return resonator(DoubleArray(frames) { vol[it] / headPeak }, 110.0, 0.3, 1.0, rate, frames)
    }

    fun darbukaModes(m: Map<String, Float>, seed: Int): List<HeadMode> = headModes(
        darbukaF01(m), darbukaImpacts(m), Dsp.around(m.getValue("DECAY"), 0.15f, 0.48f, 1.0f).toDouble(), tilt = 0.6, floor = 0.006, seed = seed,
    )

    fun dayanF0(m: Map<String, Float>): Double = Dsp.expMap(m.getValue("TUNE"), 196f, 440f).toDouble()

    /** The dayan's ring: six near-harmonic tones, each a split pair, as (hz, gain, t60); OPEN sets the fundamental's share. */
    fun dayanRing(m: Map<String, Float>, seed: Int): List<Triple<Double, Double, Double>> {
        val f0 = dayanF0(m)
        val ringScale = Dsp.around(m.getValue("DECAY"), 0.3f, 0.65f, 1.5f).toDouble()
        val fundamental = Dsp.lin(m.getValue("OPEN"), 0.12f, 1f).toDouble()
        val harm = listOf(Triple(1, fundamental, 0.5), Triple(2, 0.9, 0.75), Triple(3, 0.75, 0.6), Triple(4, 0.5, 0.5), Triple(5, 0.32, 0.38), Triple(6, 0.15, 0.25))
        val rng = Random(seed)
        val out = ArrayList<Triple<Double, Double, Double>>()
        for ((k, g, t) in harm) {
            val off = 1.0 + (rng.nextDouble() * 2 - 1) * 0.006
            val split = 0.0015 + rng.nextDouble() * 0.0025
            for (s in listOf(-0.5, 0.5)) out += Triple(f0 * k * off * (1 + s * split), g * 0.7, t * ringScale * (0.9 + 0.2 * rng.nextDouble()))
        }
        return out
    }

    fun dayanClickImpacts(): List<Impact> = listOf(Impact(0.95, 1.0, 1.0, 0.0))

    fun dayanClickModes(m: Map<String, Float>, seed: Int): List<HeadMode> =
        headModes(dayanF0(m) * 1.3, dayanClickImpacts(), 0.018, tilt = 0.3, floor = 0.003, seed = seed)

    // ---------------------------------------------------------------- render

    /** The voice's [Terra.Body] modes for HIT: the head's modes (SLAP, DARBUKA) or the dayan's ring, as ratios to [fundamentalHz]. */
    fun bodyModes(voice: TerraVoice, m: Map<String, Float>, seed: Int): Pair<List<Modes.Mode>, Float> = when (voice) {
        TerraVoice.SLAP -> slapF01(m).let { f -> slapModes(m, seed).map { Modes.Mode((it.hz / f).toFloat(), it.weights.sumOf { w -> abs(w) }.toFloat(), it.t60.toFloat()) } to f.toFloat() }
        TerraVoice.DARBUKA -> darbukaF01(m).let { f -> darbukaModes(m, seed).map { Modes.Mode((it.hz / f).toFloat(), abs(it.weights[0]).toFloat(), it.t60.toFloat()) } to f.toFloat() }
        TerraVoice.DAYAN -> dayanF0(m).let { f -> dayanRing(m, seed).map { (hz, g, t) -> Modes.Mode((hz / f).toFloat(), g.toFloat(), t.toFloat()) } to f.toFloat() }
        TerraVoice.DOUM -> darbukaF01(m).let { f -> doumModes(m, seed).map { Modes.Mode((it.hz / f).toFloat(), abs(it.weights[0]).toFloat(), it.t60.toFloat()) } to f.toFloat() }
        TerraVoice.DJEMBE, TerraVoice.DHOLAK, TerraVoice.BAYAN -> TerraFitted.bodyModes(voice, m)
        else -> error("$voice is not a TERRA hand drum")
    }

    /**
     * [voice] at the render [rate], before the output stage ([TerraBox.finish]). [structureSeed]
     * fixes the head's split pairs (the drum, the same on every take); [noiseSeed] the hand's
     * noise (each take its own). [level] is HIT's curve per [bodyModes] mode. Under HIT the stages
     * after the head are scaled by the unstruck drum's levels, as on BOX, so the colouring reaches
     * them in proportion; unstruck, each is its own peak.
     */
    fun render(voice: TerraVoice, m: Map<String, Float>, rate: Int, velocity: Float, structureSeed: Int, noiseSeed: Int,
               level: Array<FloatArray>? = null, headOnly: Boolean = false): FloatArray {
        val v = StrikeVelocity.settled(velocity)
        val frames = frames(rate)
        val force = m.getValue("FORCE")
        return when (voice) {
            TerraVoice.SLAP -> {
                val impacts = slapImpacts(m)
                val modes = slapModes(m, structureSeed)
                if (level != null) require(level.size == modes.size && level.all { it.isNotEmpty() }) { "a level curve per mode (${modes.size}), each at least one value long" }
                val head = ringHead(modes, impacts, force, v, rate, frames, level)
                if (headOnly) return head
                val headPeak = if (level == null) peakOf(head) else peakOf(ringHead(modes, impacts, force, v, rate, frames))
                divide(head, headPeak)
                val drive = TerraBox.lowpass(head, 220f, rate)
                val bowl = resonator(DoubleArray(frames) { drive[it] * v.toDouble() }, 72.0, 0.22, 1.0, rate, frames)
                val bowlPeak = if (level == null) peakOf(bowl) else {
                    val ref = ringHead(modes, impacts, force, v, rate, frames).also { divide(it, headPeak) }
                    val refDrive = TerraBox.lowpass(ref, 220f, rate)
                    peakOf(resonator(DoubleArray(frames) { refDrive[it] * v.toDouble() }, 72.0, 0.22, 1.0, rate, frames))
                }
                val p = pulse(force, v, rate)
                val knock = DoubleArray(frames)
                for ((hz, g) in listOf(410.0 to 1.0, 760.0 to 0.7, 1130.0 to 0.45)) {
                    val r = resonator(p, hz, 0.045, 1.0, rate, frames)
                    for (i in knock.indices) knock[i] += r[i] * g
                }
                val knockPeak = peakOf(knock)
                val smack = bandNoise(noiseSeed, 700f, 5000f, 6.0, 1.6, rate, frames, rms = 0.2045)
                val bowlMix = 0.3 * m.getValue("BOWL") * (0.6 + 0.4 * v)
                val smackMix = 0.7 * m.getValue("SMACK") * force * v.toDouble().pow(1.5)
                FloatArray(frames) {
                    (head[it] + (if (bowlPeak > 1e-12) bowl[it] / bowlPeak else 0.0) * bowlMix +
                        (if (knockPeak > 1e-12) knock[it] / knockPeak else 0.0) * 0.18 + smack[it] * smackMix).toFloat()
                }
            }
            TerraVoice.DARBUKA -> {
                val impacts = darbukaImpacts(m)
                val modes = darbukaModes(m, structureSeed)
                if (level != null) require(level.size == modes.size && level.all { it.isNotEmpty() }) { "a level curve per mode (${modes.size}), each at least one value long" }
                val head = ringHead(modes, impacts, force, v, rate, frames, level)
                if (headOnly) return head
                divide(head, if (level == null) peakOf(head) else peakOf(ringHead(modes, impacts, force, v, rate, frames)))
                val p = pulse(force, v, rate)
                val shellScale = Dsp.lin(m.getValue("SHELL"), 1f, 3.6f).toDouble()
                val shell = DoubleArray(frames)
                for ((hz, g, t) in listOf(Triple(1380.0, 1.0, 0.16), Triple(2310.0, 0.6, 0.12), Triple(3420.0, 0.4, 0.09))) {
                    val r = resonator(p, hz, t * shellScale, g, rate, frames)
                    for (i in shell.indices) shell[i] += r[i]
                }
                val shellPeak = peakOf(shell)
                val snap = bandNoise(noiseSeed, 2500f, 10000f, 1.5, 0.35, rate, frames, rms = 0.2486)
                val snapMix = 1.2 * m.getValue("SNAP") * force * v.toDouble().pow(1.5)
                FloatArray(frames) { (head[it] + (if (shellPeak > 1e-12) shell[it] / shellPeak else 0.0) * 0.3 * v + snap[it] * snapMix).toFloat() }
            }
            TerraVoice.DAYAN -> {
                val ring = dayanRing(m, structureSeed)
                if (level != null) require(level.size == ring.size && level.all { it.isNotEmpty() }) { "a level curve per mode (${ring.size}), each at least one value long" }
                val p = pulse(force, v, rate)
                fun ringOf(curves: Array<FloatArray>?): DoubleArray {
                    val out = DoubleArray(frames)
                    for ((k, tone) in ring.withIndex()) {
                        val (hz, g, t) = tone
                        val r = resonator(p, hz, t, g, rate, frames)
                        val c = curves?.get(k)
                        for (i in out.indices) out[i] += if (c == null) r[i] else r[i] * c[min(i, c.size - 1)]
                    }
                    return out
                }
                val tones = ringOf(level)
                if (headOnly) return FloatArray(frames) { tones[it].toFloat() }
                val tonePeak = if (level == null) peakOf(tones) else peakOf(ringOf(null))
                val click = ringHead(dayanClickModes(m, structureSeed + 1), dayanClickImpacts(), min(1f, force + 0.2f), v, rate, frames)
                divide(click, peakOf(click))
                val tick = bandNoise(noiseSeed, 3000f, 11000f, 1.2, 0.3, rate, frames, rms = 0.2995)
                val clickMix = Dsp.lin(m.getValue("CLICK"), 0.6f, 2.6f).toDouble()
                val tickMix = clickMix * 0.7 * force * v.toDouble().pow(1.5)
                FloatArray(frames) {
                    ((if (tonePeak > 1e-12) tones[it] / tonePeak else 0.0) * 0.6 + click[it] * clickMix * v + tick[it] * tickMix).toFloat()
                }
            }
            TerraVoice.DOUM -> {
                // Round 5's A, heard 2026-11-08 (docs/TERRA_HANDS.md): the head struck near its centre,
                // the goblet's air answering through the head's net volume, the ceramic shell, the
                // fingers' thud.
                val impacts = doumImpacts()
                val modes = doumModes(m, structureSeed)
                if (level != null) require(level.size == modes.size && level.all { it.isNotEmpty() }) { "a level curve per mode (${modes.size}), each at least one value long" }
                val head = ringHead(modes, impacts, force, v, rate, frames, level)
                if (headOnly) return head
                val unstruck = if (level == null) head else ringHead(modes, impacts, force, v, rate, frames)
                val headPeak = peakOf(unstruck).toDouble()
                divide(head, headPeak.toFloat())
                // the air's level is set at the full hit (its peak AIR x the head's), then follows the hit
                val air = goblet(modes, force, v, headPeak, rate, frames)
                // at the full hit the reference is this render's own head and air; below it, the full hit's
                val refPeak = if (v >= 1f) peakOf(goblet(modes, force, 1f, headPeak, rate, frames))
                else peakOf(goblet(modes, force, 1f, peakOf(ringHead(modes, impacts, force, 1f, rate, frames)).toDouble(), rate, frames))
                val airGain = if (refPeak > 1e-12) Dsp.lin(m.getValue("AIR"), 0f, 1f) / refPeak else 0.0
                val p = pulse(force, v, rate)
                val shell = DoubleArray(frames)
                for ((hz, g, t) in listOf(Triple(1380.0, 1.0, 0.256), Triple(2310.0, 0.6, 0.192), Triple(3420.0, 0.4, 0.144))) {
                    val r = resonator(p, hz, t, g, rate, frames)
                    for (i in shell.indices) shell[i] += r[i]
                }
                val shellPeak = peakOf(shell)
                val shellMix = Dsp.lin(m.getValue("SHELL"), 0f, 0.24f) * v
                val thud = bandNoise(noiseSeed, 200f, 2500f, 4.0, 1.2, rate, frames, rms = 0.2594)
                val thudMix = 0.06 * force * v.toDouble().pow(1.5)
                FloatArray(frames) {
                    (head[it] + air[it] * airGain + (if (shellPeak > 1e-12) shell[it] / shellPeak else 0.0) * shellMix + thud[it] * thudMix).toFloat()
                }
            }
            else -> error("$voice is not a TERRA hand drum")
        }
    }
}
