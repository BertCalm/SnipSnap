package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * TERRA's BOX: a cajón, struck.
 *
 * The characteristic gesture: the palm slaps the front plate's top edge -> the plate's dense
 * modes ring (the wooden crack) -> the port's air answers (the boom) -> the wires buzz while
 * the plate still moves -> silence.
 *
 * Why a voice of its own. Every other TERRA voice is four to six pure sine modes with the strike
 * pulse mixed beside them, which is a drum machine's rim: a cajón built on RESONANT_CAVITY was
 * heard (2026-11-06) as "too much like a drum machine rimshot", and measured the most tonal
 * attack on the strike gate (spectral flatness of its first 40 ms 0.058). Here the slap drives
 * the modes instead of sitting beside them, the modes are a plate's (dozens, dense), the boom is
 * an air mode the plate drives, and the wires are noise.
 *
 * Literal, from physics estimates (then design shapes):
 * - The tapa: a 3 mm birch-ply plate, [PLATE_A] by [PLATE_B] m, simply supported. Mode (m, n) sits
 *   at `((m/a)^2 + (n/b)^2) / ((1/a)^2 + (1/b)^2)` times the (1, 1) mode, which is TUNE (about
 *   76 Hz at the default, the estimate for that plate with E 8 GPa, rho 680 kg/m^3). Every mode
 *   under [MAX_MODE_HZ] is a two-pole resonator driven by the slap, weighted by the strike
 *   point's mode shape (centred across, POS up the plate), with `T60 = 6.91 / (pi f eta)` for a
 *   wood loss factor eta (DECAY).
 * - The port: a Helmholtz mode at [AIR_HZ] (a 42 L box, a 12 cm port in a 1 cm back), driven by
 *   the plate's low-passed motion, its net volume velocity.
 * Analogy: the wires are noise, high-passed, gated by how far the plate's motion envelope
 * exceeds a threshold. Not simulated: the air's push back on the plate, the frame's edge
 * contact, the hand resting after the slap.
 *
 * Ported from the Python prototype the owner picked (variant C, "deeper box", heard 2026-11-06
 * as most like a real cajón slap, the rimshot "gone"); its defaults are that variant at full
 * velocity. The prototype's noise came from the harness's own generator, so the Kotlin render is
 * the same model, not the same bytes.
 */
internal object TerraBox {

    const val PLATE_A = 0.30
    const val PLATE_B = 0.47
    const val MAX_MODE_HZ = 9000f
    const val AIR_HZ = 85f
    const val AIR_T60 = 0.28f
    const val SECONDS = 0.7f

    /** The plate's modes over its (1, 1) mode, every m and n the prototype took, lowest first. */
    private class PlateMode(val m: Int, val n: Int, val ratio: Float)

    private val PLATE: List<PlateMode> = run {
        val base = (1 / PLATE_A).pow(2) + (1 / PLATE_B).pow(2)
        buildList {
            for (m in 1..15) for (n in 1..21) add(PlateMode(m, n, (((m / PLATE_A).pow(2) + (n / PLATE_B).pow(2)) / base).toFloat()))
        }.sortedBy { it.ratio }
    }

    fun macros(): List<MacroSpec> = listOf(
        // TUNE: the plate's (1, 1) mode, 50 to 140 Hz; the default 0.4 is the 76 Hz estimate.
        MacroSpec("TUNE", 0.4f),
        // DECAY: the wood's loss factor, 0.045 (dead) through 0.022 to 0.010 (live).
        MacroSpec("DECAY", 0.5f),
        // FORCE: how hard the hand is: the slap's contact, 3 ms soft to 0.5 ms sharp, and its grit.
        MacroSpec("FORCE", 0.8f),
        // POS: where the slap lands, 0 the plate's centre to 1 its top edge (0.875: near the edge).
        MacroSpec("POS", 0.875f),
        // CAVITY: how much of the port's air mode is heard.
        MacroSpec("CAVITY", 0.3f),
        // BUZZ: the wires behind the plate.
        MacroSpec("BUZZ", 0.5f),
    )

    /** The render's length at [rate]: [SECONDS], every TUNE and DECAY (the longest mode's T60 is capped at 0.35 s). */
    fun frames(rate: Int): Int = (SECONDS * rate).toInt()

    /** The (1, 1) mode for TUNE. */
    fun fundamentalHz(m: Map<String, Float>): Float = Dsp.expMap(m.getValue("TUNE"), 50f, 140f)

    /**
     * The modes the slap drives, for [m]: ratio to the (1, 1) mode, the strike point's weight as
     * the gain, and each mode's T60. This is also the [Terra.Body] HIT colours.
     */
    fun modes(m: Map<String, Float>, rate: Int): List<Modes.Mode> {
        val f11 = fundamentalHz(m)
        val eta = Dsp.around(m.getValue("DECAY"), 0.045f, 0.022f, 0.010f)
        val y = Dsp.lin(m.getValue("POS"), 0.5f, 0.98f)
        val top = min(MAX_MODE_HZ, rate * 0.45f)
        val out = ArrayList<Modes.Mode>()
        for (p in PLATE) {
            val hz = f11 * p.ratio
            if (hz > top) continue
            val w = abs(sin(p.m * PI * 0.5) * sin(p.n * PI * y)).toFloat()
            if (w < 0.02f) continue
            val t60 = (6.91 / (PI * hz * eta)).toFloat().coerceIn(0.015f, 0.35f)
            out += Modes.Mode(ratio = p.ratio, gain = w, t60 = t60)
        }
        return out
    }

    /**
     * The box at the render [rate], before the output stage: plate + air + wires, or with
     * [plateOnly] the plate alone, before it is normalised (the bank, as [Terra.bankWith] reads
     * it). [level] is HIT's per-mode curves (one factor per mode over time, holding its last
     * value, on that mode's output), null for today's box. [velocity] is the hit; [noiseSeed]
     * and [wireSeed] seed the slap's grit and the wires.
     *
     * The hit is the slap's own contact law, not [StrikeVelocity.modeFactors]: a softer hand
     * stays on the plate longer (the pulse widens by up to 2 ms), and a longer half-sine has less
     * energy up the plate's dense modes, which is the tilt the other voices apply as a filter.
     * Both at once would count the contact twice. The grit grows as v^1.5, the port is driven in
     * proportion to v, and the wires' gate sees the plate's envelope scaled by v, so a soft hit
     * barely sets them off. Exactly the prototype's model at full velocity.
     */
    fun render(m: Map<String, Float>, rate: Int, velocity: Float, noiseSeed: Int, wireSeed: Int, level: Array<FloatArray>? = null, plateOnly: Boolean = false): FloatArray {
        val v = StrikeVelocity.settled(velocity)
        val frames = frames(rate)
        val force = m.getValue("FORCE")

        // The slap: a half-sine force pulse, shorter for a harder hand and a harder hit, plus
        // skin-on-wood grit that grows faster than the hit (v^1.5).
        val exc = FloatArray(frames)
        val widthMs = Dsp.lin(force, 3.0f, 0.5f) + (1f - v) * 2f
        val w = max(2, (widthMs * 1e-3f * rate).toInt())
        for (i in 0 until min(w, frames)) exc[i] = (sin(PI * i / w) * v).toFloat()
        val nn = min(frames, (0.004f * rate).toInt())
        val grit = Dsp.Noise(noiseSeed)
        val gritLevel = 0.35f * (force / 0.8f) * v.toDouble().pow(1.5).toFloat()
        for (i in 0 until nn) exc[i] += grit.next() * (0.5f - 0.5f * cos(2.0 * PI * i / (nn - 1)).toFloat()) * gritLevel

        val modes = modes(m, rate)
        if (level != null) require(level.size == modes.size && level.all { it.isNotEmpty() }) { "a level curve per mode (${modes.size}), each at least one value long" }
        val plate = plate(exc, modes, fundamentalHz(m), rate, level)
        if (plateOnly) return plate
        // Each stage below is scaled to its own peak, which would bend HIT's colouring (a factor
        // on each mode) before it is heard: measured, a BEATBOX KICK head moved the body's
        // overtone balance from -5.0 dB to +6.8 at HIT .25 and back to +5.7 at .5. Under HIT the
        // stages take the unstruck box's levels instead (then +7.2 and +8.0), so the colouring
        // reaches them in proportion, as it reaches the shared bank's later stages on the other
        // voices. Unstruck, each level is the stage's own peak: today's box, byte for byte.
        val levels = if (level == null) null else stages(plate(exc, modes, fundamentalHz(m), rate, null), m, v, rate, wireSeed, null).second
        return stages(plate, m, v, rate, wireSeed, levels).first
    }

    /**
     * The plate: every mode a resonator driven by [exc], each output times its [level] curve when
     * there is one. The gain carries sin(theta), so a mode's peak no longer grows as its
     * frequency falls (Modes.ring's own gain does not).
     */
    private fun plate(exc: FloatArray, modes: List<Modes.Mode>, f11: Float, rate: Int, level: Array<FloatArray>?): FloatArray {
        val frames = exc.size
        val plate = FloatArray(frames)
        for ((k, mode) in modes.withIndex()) {
            val hz = f11 * mode.ratio
            val r = exp(-6.9078 / (mode.t60.toDouble() * rate))
            val theta = 2.0 * PI * hz / rate
            val a1 = 2.0 * r * cos(theta)
            val a2 = -(r * r)
            val g = mode.gain * sin(theta)
            val curve = level?.get(k)
            var y1 = 0.0
            var y2 = 0.0
            for (i in 0 until frames) {
                val y = g * exc[i] + a1 * y1 + a2 * y2
                y2 = y1
                y1 = y
                plate[i] += if (curve == null) y.toFloat() else (y * curve[min(i, curve.size - 1)]).toFloat()
            }
        }
        return plate
    }

    /** The four levels the stages scale by: the plate's peak, the port's, the wires' envelope top and the wires' peak. */
    private class Levels(val plate: Float, val air: Float, val envelope: Float, val wires: Float)

    /**
     * Plate, port and wires mixed, from [plate] (which is scaled in place), with the [Levels] it
     * used. With [levels] null each stage is scaled to its own peak, today's box; otherwise by
     * [levels].
     */
    private fun stages(plate: FloatArray, m: Map<String, Float>, v: Float, rate: Int, wireSeed: Int, levels: Levels?): Pair<FloatArray, Levels> {
        val frames = plate.size
        val platePeak = levels?.plate ?: peakOf(plate)
        divide(plate, platePeak)

        // The port: an air mode driven by the plate's net motion (low-passed), harder with the hit.
        val drive = lowpass(plate, 250f, rate)
        for (i in drive.indices) drive[i] *= v
        val air = FloatArray(frames)
        run {
            val r = exp(-6.9078 / (AIR_T60.toDouble() * rate))
            val theta = 2.0 * PI * AIR_HZ / rate
            val a1 = 2.0 * r * cos(theta)
            val a2 = -(r * r)
            val g = sin(theta)
            var y1 = 0.0
            var y2 = 0.0
            for (i in 0 until frames) {
                val y = g * drive[i] + a1 * y1 + a2 * y2
                y2 = y1
                y1 = y
                air[i] = y.toFloat()
            }
        }
        val airPeak = levels?.air ?: peakOf(air)
        divide(air, airPeak)

        // The wires: noise gated by how far the plate's envelope rises above a threshold; a soft
        // hit's envelope is scaled down by the hit first, so it barely sets them off.
        val env = FloatArray(frames) { abs(plate[it]) * v }
        val smooth = lowpass(env, 60f, rate)
        val top = levels?.envelope ?: (smooth.maxOrNull()?.takeIf { it > 1e-9f } ?: 1f)
        val wires = Dsp.Noise(wireSeed)
        val hiss = FloatArray(frames) { wires.next() }
        val buzz = highpass(hiss, 1800f, rate)
        for (i in buzz.indices) buzz[i] *= max(0f, smooth[i] / top * v - 0.12f)
        val buzzPeak = levels?.wires ?: peakOf(buzz)
        divide(buzz, buzzPeak)

        val airMix = m.getValue("CAVITY") * (0.6f + 0.4f * v)
        val wireMix = m.getValue("BUZZ") * 0.5f
        return FloatArray(frames) { plate[it] + air[it] * airMix + buzz[it] * wireMix } to Levels(platePeak, airPeak, top, buzzPeak)
    }

    /** The output stage's DC high-pass, the house one-pole's corner (the harness's OUTPUT_DC_HZ). */
    const val DC_HZ = 20f

    /**
     * The house output stage for the box, [raw] at [rate] ([Dsp.RATE] times [Dsp.OVERSAMPLE]):
     * band-limit and decimate, take out the resonators' DC (subtract the mean, then a one-pole
     * high-pass at [DC_HZ]), peak-normalise, fade the tail. Not TERRA's fixed PUNCH: the
     * prototype the owner picked had none.
     */
    fun finish(raw: FloatArray, rate: Int): FloatArray {
        require(rate == Dsp.RATE * Dsp.OVERSAMPLE) { "the box renders at ${Dsp.RATE * Dsp.OVERSAMPLE} Hz, got $rate" }
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, Dsp.RATE)
        val mean = out.average()
        val rc = 1.0 / (2.0 * PI * DC_HZ)
        val alpha = rc / (rc + 1.0 / Dsp.RATE)
        var prevX = 0.0
        var prevY = 0.0
        for (i in out.indices) {
            val x = out[i] - mean
            prevY = alpha * (prevY + x - prevX)
            prevX = x
            out[i] = prevY.toFloat()
        }
        Dsp.normalize(out)
        Dsp.limitPeak(out)
        Dsp.fadeTail(out)
        return out
    }

    private fun peakOf(x: FloatArray): Float {
        var p = 0f
        for (s in x) p = max(p, abs(s))
        return p
    }

    /** [x] over [peak], in place; nothing for a silent [peak], as the prototype's max(1e-9, peak) left it. */
    private fun divide(x: FloatArray, peak: Float) {
        if (peak > 1e-9f) for (i in x.indices) x[i] /= peak
    }

    /** A second-order Butterworth low-pass at [hz], bilinear and pre-warped (scipy's butter(2)). */
    internal fun lowpass(x: FloatArray, hz: Float, rate: Int): FloatArray {
        val k = tan(PI * hz / rate)
        val norm = 1.0 / (1.0 + sqrt(2.0) * k + k * k)
        val b0 = k * k * norm
        return biquad(x, b0, 2 * b0, b0, 2 * (k * k - 1) * norm, (1 - sqrt(2.0) * k + k * k) * norm)
    }

    /** A second-order Butterworth high-pass at [hz], bilinear and pre-warped. */
    internal fun highpass(x: FloatArray, hz: Float, rate: Int): FloatArray {
        val k = tan(PI * hz / rate)
        val norm = 1.0 / (1.0 + sqrt(2.0) * k + k * k)
        return biquad(x, norm, -2 * norm, norm, 2 * (k * k - 1) * norm, (1 - sqrt(2.0) * k + k * k) * norm)
    }

    private fun biquad(x: FloatArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): FloatArray {
        val out = FloatArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = y
            out[i] = y.toFloat()
        }
        return out
    }
}
