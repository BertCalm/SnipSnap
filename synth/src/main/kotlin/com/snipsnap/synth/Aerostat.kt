package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.OVERSAMPLE
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * AEROSTAT — a pitched tube and airflow instrument.
 *
 * One strike hits two tube banks (Quick and Heavy). The hit's air pulse spins
 * a one-way rotor; the rotor opens a valve; the valve lets a shared reservoir
 * blow a tuned whistle. Exhaust warms an imaginary vessel, and the vessel's
 * rise and settle change loading and radiation. The gesture is knock, catch,
 * bloom, relaxation, floating tail — each stage caused by the one before it.
 *
 * Design: `docs/superpowers/specs/2026-10-04-aerostat-engine-design.md`.
 *
 * **V1 plays one note on both banks.** There is no bank selector. Quick is
 * lighter and brighter; Heavy has [HEAVY_INERTIA_RATIO] times Quick's inertia,
 * a darker tube, and a whistle [HEAVY_DETUNE_CENTS] cents sharp (the only
 * intentional detune). Pitch is the host note, snapped by TUNE across
 * [ROOT_MIDI]..[ROOT_MIDI]+[TUNE_SEMITONES] (C3–C5). The six musical controls
 * are STRIKE, PRESSURE, INERTIA, RELEASE, LIFT and HOLD.
 *
 * **State.** Every render starts from a fresh reservoir and vessel unless a
 * [Carry] is passed. That carry is the phrase harness: shared pressure and
 * altitude survive from one diagnostic strike to the next. It is not part of
 * [AerostatPatch.render].
 *
 * **HOLD at [LOOP_THRESHOLD] and above** returns the settled sustain only.
 * The static sample format is a [Snip] with no loop-start field, so the
 * buffer is the loop itself and the opening strike is not in it. Below that
 * step, HOLD is a powered latch: after the catch it holds the valve open on
 * reservoir energy and does not restrike.
 *
 * **Velocity** is event energy, separate from STRIKE (contact hardness).
 * It is a render argument, the same way MERCURY's rubbed voices take it.
 *
 * Constants marked listening are calibration for the audition, not measured
 * physics. Pressure, rotor speed and height are normalized musical state.
 */
enum class AerostatVoice { FLOAT }

/** Which branch a diagnostic render keeps. Engineering taps, not product controls. */
enum class AerostatTap { FULL, TUBE, FLOW, QUICK, HEAVY }

/**
 * Reservoir and vessel state a phrase may carry between strikes.
 * A NaN [pressure] means "not started": the next render charges it to the
 * patch's target. Rotors, valves and tubes always start cold.
 */
class AerostatCarry {
    var pressure: Double = Double.NaN
    var temp: Double = 0.0
    var height: Double = 0.0
    var verticalSpeed: Double = 0.0
}

object Aerostat {

    const val TUNE_SEMITONES = 24
    const val ROOT_MIDI = 48

    /** Heavy's rotor inertia over Quick's. Inside the spec's 2.0–3.5 starting range. */
    const val HEAVY_INERTIA_RATIO = 2.6

    /** Heavy's whistle, cents sharp of the requested note. The only intentional detune. */
    const val HEAVY_DETUNE_CENTS = 5.0

    /** Motion-induced pitch at LIFT 1 and full height, cents. Default LIFT is 0.40, so the same height is 10 cents. */
    const val MAX_LIFT_CENTS = 25.0

    /** Control rate. 176400 / 1050 = 168, so each control step is a whole number of internal samples. */
    const val CONTROL_RATE = 1050

    /** A faster control rate used to check the mechanical step converges. 176400 / 2100 = 84. */
    const val CONTROL_RATE_FAST = 2100

    const val LOOP_THRESHOLD = 0.99f
    const val SCRAMBLE_HOLD_CEILING = 0.95f

    private const val QUICK_SPLIT = 0.56
    private const val HEAVY_SPLIT = 0.44
    private const val TORQUE_SCALE = 120.0
    private const val OPEN_SPEED = 3.6
    private const val CLOSE_SPEED = 1.6
    private const val TRAVEL = 0.18
    private const val OMEGA_SAT = 22.0
    private const val OMEGA_MAX = 60.0
    private const val LIN_DRAG = 0.085
    private const val QUAD_DRAG = 0.004
    private const val LINKAGE = 0.35
    private const val FLOW_DRAW = 1.15
    private const val REFILL = 1.6
    private const val LEAK = 0.08
    private const val P_MAX = 1.25
    private const val HEAT = 2.0
    private const val THERMAL_TAU = 0.55
    private const val BUOY = 7.0
    private const val VERT_DRAG = 1.4
    private const val RESTORE = 11.0
    private const val TEMP_MAX = 3.0
    private const val V_MAX = 2.0
    private const val MODES = 6

    private val MODE_RATIO = doubleArrayOf(1.0, 3.0, 5.0, 7.0, 9.0, 11.0)
    private val QUICK_GAIN = doubleArrayOf(1.0, 0.48, 0.26, 0.14, 0.07, 0.035)
    private val HEAVY_GAIN = doubleArrayOf(1.0, 0.22, 0.08, 0.03, 0.012, 0.005)

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: AerostatVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("STRIKE", 0.60f),
        MacroSpec("PRESSURE", 0.55f),
        MacroSpec("INERTIA", 0.45f),
        MacroSpec("RELEASE", 0.50f),
        MacroSpec("LIFT", 0.40f),
        MacroSpec("HOLD", 0f),
    )

    fun defaults(voice: AerostatVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    fun midiFor(tune: Float): Int =
        ROOT_MIDI + (tune.coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt().coerceIn(0, TUNE_SEMITONES)

    fun frequencyFor(tune: Float): Float = Keys.midiHz(midiFor(tune))

    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD

    /**
     * What a pad is filed as. LOOP when HOLD is the sustaining step or the
     * one-shot runs past the classifier's 1.5 s line; otherwise PERC. Never a
     * drum class — the excitation is a strike, the instrument is pitched.
     */
    fun drumClassFor(voice: AerostatVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) return DrumClass.LOOP
        val seconds = oneShotSeconds(m.getValue("RELEASE"))
        return if (seconds > 1.5) DrumClass.LOOP else DrumClass.PERC
    }

    fun scramble(voice: AerostatVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + AerostatPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        val rolled = Dsp.scrambleNear(seed, temperature, random).toMutableMap()
        rolled["HOLD"] = rolled.getValue("HOLD").coerceAtMost(SCRAMBLE_HOLD_CEILING)
        return rolled
    }

    /**
     * One note. [tap] selects a diagnostic branch. [forcedPressure] replaces
     * the reservoir (0 silences airflow and leaves the tube). [normalize]
     * false keeps the raw mix so a listening pass can compare it with the
     * levelled clip. [carry], when passed, is read at the start and written
     * at the end.
     */
    fun render(
        voice: AerostatVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        tap: AerostatTap = AerostatTap.FULL,
        forcedPressure: Double? = null,
        normalize: Boolean = true,
        carry: AerostatCarry? = null,
        controlHz: Int = CONTROL_RATE,
    ): Snip {
        val m = settled(macros, voice)
        val raw = if (isLoop(m.getValue("HOLD"))) {
            renderLoop(m, velocity)
        } else {
            val mech = mechanics(m, velocity, forcedPressure, false, controlHz, carry)
            mixDown(m, mech, velocity, tap)
        }
        val out = if (normalize) level(raw) else raw
        if (!isLoop(m.getValue("HOLD"))) Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /**
     * Several strikes in one buffer, sharing reservoir and vessel state.
     * Levelled once as a whole, so a later strike that found the reservoir
     * drawn down stays quieter. Development harness, not the patch API.
     */
    fun phrase(
        voice: AerostatVoice,
        midis: List<Int>,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
    ): Snip {
        require(midis.isNotEmpty()) { "a phrase needs a strike" }
        val base = settled(macros, voice).toMutableMap()
        base["HOLD"] = min(base.getValue("HOLD"), SCRAMBLE_HOLD_CEILING)
        val carry = AerostatCarry()
        val parts = midis.map { midi ->
            val tune = (midi - ROOT_MIDI).coerceIn(0, TUNE_SEMITONES) / TUNE_SEMITONES.toFloat()
            render(voice, base + ("TUNE" to tune), velocity, normalize = false, carry = carry).samples
        }
        val n = parts.sumOf { it.size }
        val joined = FloatArray(n)
        var at = 0
        for (p in parts) {
            p.copyInto(joined, at)
            at += p.size
        }
        return Snip(level(joined), channels = 1, sampleRate = RATE)
    }

    /** Control-rate trace of one strike. The audio pass interpolates this; it does not resimulate. */
    internal fun mechanics(
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        forcedPressure: Double? = null,
        isolate: Boolean = false,
        controlHz: Int = CONTROL_RATE,
        carry: AerostatCarry? = null,
    ): Mechanics {
        val m = settled(macros, AerostatVoice.FLOAT)
        return simulate(m, velocity.coerceIn(0f, 1f).toDouble(), forcedPressure, isolate, controlHz, carry)
    }

    internal class Mechanics(
        val rate: Int,
        val seconds: DoubleArray,
        val omegaQuick: DoubleArray,
        val omegaHeavy: DoubleArray,
        val valveQuick: DoubleArray,
        val valveHeavy: DoubleArray,
        val pressure: DoubleArray,
        val pressureQuick: DoubleArray,
        val pressureHeavy: DoubleArray,
        val flowQuick: DoubleArray,
        val flowHeavy: DoubleArray,
        val torqueQuick: DoubleArray,
        val torqueHeavy: DoubleArray,
        val temp: DoubleArray,
        val height: DoubleArray,
    )

    internal class LoopRender(val loop: FloatArray, val seam: Double, val continuous: FloatArray, val loopStart: Int)

    private fun settled(macros: Map<String, Float>, voice: AerostatVoice): Map<String, Float> {
        val out = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (k in out) out[k] = v.coerceIn(0f, 1f)
        return out
    }

    private fun oneShotSeconds(release: Float): Double {
        val tail = Dsp.expMap(release, 0.55f, 2.4f).toDouble()
        return 0.22 + tail
    }

    private fun targetPressure(pressure: Float): Double = 0.22 + 0.73 * pressure

    private fun springK(release: Double): Double {
        val lnK = ln(14000.0) + (ln(45.0) - ln(14000.0)) * release
        return exp(lnK)
    }

    private fun inertiaOf(inertia: Double, heavy: Boolean): Double {
        val quick = 0.0055 + 0.026 * inertia
        return if (heavy) quick * HEAVY_INERTIA_RATIO else quick
    }

    /** Finite torque pulse. Zero at and after [dur]; never negative. */
    private fun torqueAt(t: Double, strike: Double, velocity: Double, split: Double, freq: Double): Double {
        val dur = 0.014 + 0.012 * (1.0 - strike)
        if (t < 0.0 || t >= dur) return 0.0
        val env = sin(PI * t / dur)
        val impulse = velocity * strike.pow(1.35)
        val mod = 0.05 * exp(-t / 0.03) * sin(2.0 * PI * freq * t)
        return max(0.0, impulse * env * split * TORQUE_SCALE * (1.0 + mod))
    }

    private fun contactAt(t: Double, strike: Double, velocity: Double): Double {
        val dur = 0.0035 + 0.007 * (1.0 - strike)
        if (t < 0.0 || t >= dur) return 0.0
        return velocity * (0.3 + 0.7 * strike) * sin(PI * t / dur)
    }

    private fun puffAt(t: Double, strike: Double, velocity: Double): Double {
        val dur = 0.016 + 0.014 * (1.0 - strike)
        if (t < 0.0 || t >= dur) return 0.0
        return velocity * strike.pow(1.15) * sin(PI * t / dur)
    }

    private fun simulate(
        m: Map<String, Float>,
        velocity: Double,
        forcedPressure: Double?,
        isolate: Boolean,
        controlHz: Int,
        carry: AerostatCarry?,
    ): Mechanics {
        val dt = 1.0 / controlHz
        val seconds = oneShotSeconds(m.getValue("RELEASE"))
        val n = max(2, (seconds * controlHz).roundToInt() + 1)
        val strike = m.getValue("STRIKE").toDouble()
        val inertia = m.getValue("INERTIA").toDouble()
        val release = m.getValue("RELEASE").toDouble()
        val hold = m.getValue("HOLD").toDouble()
        val freq = frequencyFor(m.getValue("TUNE")).toDouble()
        val target = targetPressure(m.getValue("PRESSURE"))
        val sustainFor = if (hold > 0.02) hold * 1.8 else 0.0
        val iQ = inertiaOf(inertia, heavy = false)
        val iH = inertiaOf(inertia, heavy = true)

        var omegaQ = 0.0
        var omegaH = 0.0
        var angleQ = 0.0
        var angleH = 0.0
        var valveQ = 0.0
        var valveH = 0.0
        var velQ = 0.0
        var velH = 0.0
        var latchedQ = false
        var latchedH = false
        var caughtAtQ = Double.NaN
        var caughtAtH = Double.NaN
        var pShared = if (carry != null && carry.pressure.isFinite()) carry.pressure else target
        var pQ = pShared
        var pH = pShared
        var temp = carry?.temp ?: 0.0
        var height = carry?.height ?: 0.0
        var vSpeed = carry?.verticalSpeed ?: 0.0
        if (forcedPressure != null) {
            pShared = forcedPressure
            pQ = forcedPressure
            pH = forcedPressure
        }

        val sec = DoubleArray(n)
        val oQ = DoubleArray(n)
        val oH = DoubleArray(n)
        val vlvQ = DoubleArray(n)
        val vlvH = DoubleArray(n)
        val pArr = DoubleArray(n)
        val pArrQ = DoubleArray(n)
        val pArrH = DoubleArray(n)
        val fQ = DoubleArray(n)
        val fH = DoubleArray(n)
        val tQ = DoubleArray(n)
        val tH = DoubleArray(n)
        val tempArr = DoubleArray(n)
        val hArr = DoubleArray(n)

        for (i in 0 until n) {
            val t = i * dt
            sec[i] = t
            val torqueQ = torqueAt(t, strike, velocity, QUICK_SPLIT, freq)
            val torqueH = torqueAt(t, strike, velocity, HEAVY_SPLIT, freq)
            omegaQ = stepRotor(omegaQ, torqueQ, valveQ, iQ, dt)
            omegaH = stepRotor(omegaH, torqueH, valveH, iH, dt)
            angleQ += omegaQ * dt
            angleH += omegaH * dt
            val stepQ = stepValve(omegaQ, angleQ, valveQ, velQ, latchedQ, release, dt)
            val stepH = stepValve(omegaH, angleH, valveH, velH, latchedH, release, dt)
            valveQ = stepQ.pos
            velQ = stepQ.vel
            latchedQ = stepQ.latched
            valveH = stepH.pos
            velH = stepH.vel
            latchedH = stepH.latched
            if (latchedQ && !caughtAtQ.isFinite()) caughtAtQ = t
            if (latchedH && !caughtAtH.isFinite()) caughtAtH = t
            if (hold > 0.02 && caughtAtQ.isFinite() && t < caughtAtQ + sustainFor) valveQ = max(valveQ, 0.62 + 0.3 * hold)
            if (hold > 0.02 && caughtAtH.isFinite() && t < caughtAtH + sustainFor) valveH = max(valveH, 0.55 + 0.28 * hold)
            valveQ = finite(valveQ).coerceIn(0.0, 1.0)
            valveH = finite(valveH).coerceIn(0.0, 1.0)

            val srcQ = if (isolate) pQ else pShared
            val srcH = if (isolate) pH else pShared
            var flowQ = valveQ * sqrt(srcQ.coerceAtLeast(0.0))
            var flowH = valveH * sqrt(srcH.coerceAtLeast(0.0))
            if (forcedPressure != null && forcedPressure <= 0.0) {
                flowQ = 0.0
                flowH = 0.0
            }
            val extraQ = if (hold > 0.02 && valveQ > 0.5) hold * 0.12 * valveQ else 0.0
            val extraH = if (hold > 0.02 && valveH > 0.5) hold * 0.12 * valveH else 0.0
            if (forcedPressure != null) {
                pShared = forcedPressure.coerceIn(0.0, P_MAX)
                pQ = pShared
                pH = pShared
            } else if (isolate) {
                pQ = stepPressure(pQ, target, flowQ + extraQ, dt)
                pH = stepPressure(pH, target, flowH + extraH, dt)
                pShared = 0.5 * (pQ + pH)
            } else {
                pShared = stepPressure(pShared, target, flowQ + flowH + extraQ + extraH, dt)
                pQ = pShared
                pH = pShared
            }
            val exhaust = flowQ + flowH
            temp = finite(temp + dt * (HEAT * exhaust - temp / THERMAL_TAU)).coerceIn(0.0, TEMP_MAX)
            val acc = BUOY * temp - VERT_DRAG * vSpeed - RESTORE * height
            vSpeed = finite(vSpeed + dt * acc).coerceIn(-V_MAX, V_MAX)
            height = finite(height + dt * vSpeed).coerceIn(0.0, 1.0)

            oQ[i] = omegaQ
            oH[i] = omegaH
            vlvQ[i] = valveQ
            vlvH[i] = valveH
            pArr[i] = pShared
            pArrQ[i] = pQ
            pArrH[i] = pH
            fQ[i] = flowQ
            fH[i] = flowH
            tQ[i] = torqueQ
            tH[i] = torqueH
            tempArr[i] = temp
            hArr[i] = height
        }
        if (carry != null) {
            carry.pressure = pShared
            carry.temp = temp
            carry.height = height
            carry.verticalSpeed = vSpeed
        }
        return Mechanics(controlHz, sec, oQ, oH, vlvQ, vlvH, pArr, pArrQ, pArrH, fQ, fH, tQ, tH, tempArr, hArr)
    }

    private fun stepRotor(omega: Double, torque: Double, valve: Double, inertia: Double, dt: Double): Double {
        val drag = LIN_DRAG * omega + QUAD_DRAG * omega * abs(omega)
        val load = LINKAGE * valve * omega
        val accel = (torque - drag - load) / inertia
        return finite(omega + accel * dt).coerceIn(0.0, OMEGA_MAX)
    }

    private class ValveStep(val pos: Double, val vel: Double, val latched: Boolean)

    private fun stepValve(omega: Double, angle: Double, pos: Double, vel: Double, latched: Boolean, release: Double, dt: Double): ValveStep {
        val open = if (latched) omega > CLOSE_SPEED else omega > OPEN_SPEED && angle > TRAVEL
        val sat = (omega / OMEGA_SAT).coerceIn(0.0, 1.0)
        val target = if (open) 0.48 + 0.52 * sat else 0.0
        val k = springK(release)
        val d = 2.0 * sqrt(k) * 1.15
        val acc = k * (target - pos) - d * vel
        val nextVel = finite(vel + acc * dt).coerceIn(-40.0, 40.0)
        val nextPos = finite(pos + nextVel * dt).coerceIn(0.0, 1.0)
        return ValveStep(nextPos, nextVel, open)
    }

    private fun stepPressure(p: Double, target: Double, demand: Double, dt: Double): Double {
        val dp = REFILL * (target - p) - FLOW_DRAW * demand - LEAK * p
        return finite(p + dt * dp).coerceIn(0.0, P_MAX)
    }

    private fun finite(x: Double): Double = if (x.isFinite()) x else 0.0

    /** Internal-rate mix of the two branches, then the project's two-step decimator. */
    private fun mixDown(m: Map<String, Float>, mech: Mechanics, velocity: Float, tap: AerostatTap): FloatArray {
        val internal = RATE * OVERSAMPLE
        val hop = internal / mech.rate
        require(hop > 0 && hop * mech.rate == internal) { "control rate ${mech.rate} does not divide the internal rate" }
        val steps = mech.seconds.size - 1
        val total = steps * hop
        val buf = DoubleArray(total)
        val strike = m.getValue("STRIKE").toDouble()
        val vel = velocity.coerceIn(0f, 1f).toDouble()
        val lift = m.getValue("LIFT").toDouble()
        val freq = frequencyFor(m.getValue("TUNE")).toDouble()
        val seed = Dsp.seedFor("AEROSTAT", midiFor(m.getValue("TUNE")), strikeBits(m), velocity)
        val noiseQ = Dsp.Noise(seed)
        val noiseH = Dsp.Noise(seed xor 0x5bd1e995)
        val contactNoise = Dsp.Noise(seed xor 0x27bb2ee6)
        val quick = Whistle(internal, quick = true, noiseQ)
        val heavy = Whistle(internal, quick = false, noiseH)
        val tubes = TubePair(freq, internal)
        var lip = 0.0
        var i = 0
        val dt = 1.0 / internal
        for (s in 0 until steps) {
            val t0 = mech.seconds[s]
            for (h in 0 until hop) {
                val u = h / hop.toDouble()
                val t = t0 + h * dt
                val flowQ = lerp(mech.flowQuick[s], mech.flowQuick[s + 1], u)
                val flowH = lerp(mech.flowHeavy[s], mech.flowHeavy[s + 1], u)
                val height = lerp(mech.height[s], mech.height[s + 1], u)
                val cents = height * lift * MAX_LIFT_CENTS
                val ratio = 2.0.pow(cents / 1200.0)
                val puff = puffAt(t, strike, vel)
                val contact = contactAt(t, strike, vel)
                var tex = 0.0
                if (contact > 0.0) {
                    val n = contactNoise.next().toDouble()
                    tex = n * contact * 0.12
                    // One pole keeps the contact under 2 kHz so the head is not a snare.
                    tex = tubes.shapeContact(tex)
                }
                val tube = tubes.sample(contact, dt)
                val wQ = quick.sample(flowQ, freq * ratio, puff, dt)
                val wH = heavy.sample(flowH, freq * ratio * centsToRatio(HEAVY_DETUNE_CENTS), puff, dt)
                // The tube is the knock. It has to be audible, and it has to lose
                // to the whistle once the valve is open, or the note files as a tom.
                val tubeScale = 0.42
                val mixed = when (tap) {
                    AerostatTap.TUBE -> (tube.first + tube.second + tex) * tubeScale
                    AerostatTap.FLOW -> wQ + wH
                    AerostatTap.QUICK -> tube.first * tubeScale + tex * tubeScale + wQ
                    AerostatTap.HEAVY -> tube.second * tubeScale + wH
                    AerostatTap.FULL -> (tube.first + tube.second + tex) * tubeScale + wQ + wH
                }
                val open = (height * lift).coerceIn(0.0, 1.0)
                val cutoff = 650.0 + 8000.0 * open
                val a = 1.0 - exp(-2.0 * PI * cutoff / internal)
                lip += a * (mixed - lip)
                if (abs(lip) < 1e-18) lip = 0.0
                buf[i++] = mixed * (0.62 + 0.38 * open) + lip * (0.38 * (1.0 - open))
            }
        }
        val floats = FloatArray(buf.size) { finiteFloat(buf[it]) }
        return Dsp.decimate(floats, RATE)
    }

    private fun strikeBits(m: Map<String, Float>): Int =
        (m.getValue("STRIKE") * 1000).roundToInt() xor (m.getValue("PRESSURE") * 1000).roundToInt()

    private fun centsToRatio(cents: Double): Double = 2.0.pow(cents / 1200.0)

    private fun lerp(a: Double, b: Double, u: Double): Double = a + (b - a) * u

    private fun finiteFloat(x: Double): Float {
        if (!x.isFinite()) return 0f
        val f = x.toFloat()
        return if (f.isFinite()) f else 0f
    }

    private fun level(buf: FloatArray): FloatArray {
        val out = buf.copyOf()
        Dsp.limitPeak(out, 0.85f)
        Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET, ceiling = 0.99f)
        return out
    }

    private fun renderLoop(m: Map<String, Float>, velocity: Float): FloatArray {
        val measured = renderLoopMeasured(m, velocity)
        require(measured.seam < Keys.MAX_SEAM_ERROR) {
            "AEROSTAT: the held loop does not close (seam %.2e, bar %.0e)".format(
                java.util.Locale.ROOT, measured.seam, Keys.MAX_SEAM_ERROR,
            )
        }
        return measured.loop
    }

    internal fun renderLoopMeasured(m: Map<String, Float>, velocity: Float): LoopRender {
        val freq = frequencyFor(m.getValue("TUNE")).toDouble()
        val lift = m.getValue("LIFT").toDouble()
        val pressure = m.getValue("PRESSURE").toDouble()
        val target = targetPressure(m.getValue("PRESSURE"))
        // Latched valves, equilibrium pressure: refill balances both flows plus leak.
        val valveQ = 0.78
        val valveH = 0.7
        // Equilibrium: refill(target, p) = draw·(valveQ+valveH)·√p + leak·p.
        val qa = REFILL + LEAK
        val qb = FLOW_DRAW * (valveQ + valveH)
        val disc = qb * qb + 4.0 * qa * REFILL * target
        val s = (-qb + sqrt(disc)) / (2.0 * qa)
        val p = (s * s).coerceIn(0.05, P_MAX)
        val flowQ = valveQ * sqrt(p)
        val flowH = valveH * sqrt(p)
        val exhaust = flowQ + flowH
        val temp = (HEAT * exhaust * THERMAL_TAU).coerceIn(0.0, TEMP_MAX)
        val height = (BUOY * temp / RESTORE).coerceIn(0.0, 1.0)
        val cents = height * lift * MAX_LIFT_CENTS
        val ratio = centsToRatio(cents)
        val sounded = freq * ratio
        // The loop is K periods of the requested note. Heavy's one-shot detune
        // is 5 cents, which is not a whole number of this period, so the held
        // Heavy whistle is pulled onto the nearest odd harmonic of the same
        // loop (at 5 cents that harmonic is the fundamental). The strike is
        // not in this buffer: the static sample contract has no loop-start field.
        // Whole output periods, so 4x oversampling decimates onto the same
        // cycle. Half a frame of pitch is the cost; a fractional period will
        // not close. Heavy's 5 cent one-shot detune is not a whole number of
        // this period (the nearest odd harmonic is the fundamental), so the
        // held Heavy whistle sits on the same cycle.
        val periodOut = (RATE / sounded).roundToInt().coerceIn(32, 4096)
        val k = max(8, (0.50 * RATE / periodOut).roundToInt())
        val frames = k * periodOut
        val fPlay = RATE.toDouble() / periodOut
        val pad = frames
        val totalOut = pad + frames * 2 + pad
        val internal = RATE * OVERSAMPLE
        val totalIn = totalOut * OVERSAMPLE
        val buf = DoubleArray(totalIn)
        val seed = Dsp.seedFor("AEROSTAT-LOOP", midiFor(m.getValue("TUNE")), pressure, velocity)
        val quick = Whistle(internal, quick = true, Dsp.Noise(seed))
        val heavy = Whistle(internal, quick = false, Dsp.Noise(seed xor 0x51ed))
        quick.prime(fPlay)
        heavy.prime(fPlay)
        val dt = 1.0 / internal
        var lip = 0.0
        val open = (height * lift).coerceIn(0.0, 1.0)
        val cutoff = 650.0 + 8000.0 * open
        val a = 1.0 - exp(-2.0 * PI * cutoff / internal)
        for (i in 0 until totalIn) {
            // No breath noise in the loop: a noise stream is not periodic, and the
            // tone is already speaking. Flow is constant, so the delay oscillator is too.
            val wQ = quick.sample(flowQ, fPlay, puff = 0.0, dt, noiseAmp = 0.0, exact = true)
            val wH = heavy.sample(flowH * 0.55, fPlay, puff = 0.0, dt, noiseAmp = 0.0, exact = true)
            val mixed = (wQ + wH) * (0.85 + 0.15 * velocity.coerceIn(0f, 1f))
            lip += a * (mixed - lip)
            buf[i] = mixed * (0.62 + 0.38 * open) + lip * (0.38 * (1.0 - open))
        }
        val floats = FloatArray(buf.size) { finiteFloat(buf[it]) }
        val decimated = Dsp.decimate(floats, RATE)
        val start = min(pad, decimated.size - frames * 2)
        require(start >= 0 && start + frames * 2 <= decimated.size) {
            "AEROSTAT loop: decimated ${decimated.size} frames, wanted ${frames * 2} from $start"
        }
        val continuous = decimated.copyOfRange(start, start + frames * 2)
        val seam = Keys.seamError(continuous, frames)
        val loop = continuous.copyOfRange(frames, frames * 2)
        return LoopRender(loop, seam, continuous, frames)
    }

    private class TubePair(freq: Double, rate: Int) {
        private val quick = Modes(freq, rate, QUICK_GAIN, t60 = 0.20)
        private val heavy = Modes(freq, rate, HEAVY_GAIN, t60 = 0.30)
        private var contactLp = 0.0
        private val contactA = 1.0 - exp(-2.0 * PI * 900.0 / rate)

        fun sample(contact: Double, dt: Double): Pair<Double, Double> {
            val q = quick.tick(contact, dt)
            val h = heavy.tick(contact * 0.85, dt)
            return q to h
        }

        fun shapeContact(x: Double): Double {
            contactLp += contactA * (x - contactLp)
            if (abs(contactLp) < 1e-18) contactLp = 0.0
            return contactLp
        }
    }

    private class Modes(freq: Double, rate: Int, gains: DoubleArray, t60: Double) {
        private val re = DoubleArray(MODES)
        private val im = DoubleArray(MODES)
        private val c = DoubleArray(MODES)
        private val s = DoubleArray(MODES)
        private val decay = DoubleArray(MODES)
        private val gain = gains

        init {
            val dt = 1.0 / rate
            for (i in 0 until MODES) {
                val ratio = MODE_RATIO[i]
                val w = 2.0 * PI * freq * ratio
                c[i] = cos(w * dt)
                s[i] = sin(w * dt)
                val tau = t60 / ratio.pow(1.15)
                decay[i] = exp(-dt * 6.907755 / tau)
            }
        }

        fun tick(drive: Double, @Suppress("UNUSED_PARAMETER") dt: Double): Double {
            var sum = 0.0
            for (i in 0 until MODES) {
                val nr = (re[i] * c[i] - im[i] * s[i]) * decay[i]
                val ni = (re[i] * s[i] + im[i] * c[i]) * decay[i] + drive * gain[i] * 0.02
                re[i] = if (nr.isFinite()) nr else 0.0
                im[i] = if (ni.isFinite()) ni else 0.0
                if (abs(re[i]) < 1e-18) re[i] = 0.0
                if (abs(im[i]) < 1e-18) im[i] = 0.0
                sum += re[i] * gain[i]
            }
            return sum
        }
    }

    /**
     * A flue whistle: a delay of one period and a memoryless jet.
     * The delay is the pitch. Darkening is feed-forward, so it cannot add
     * samples to the loop and walk the note off. At the internal rate a
     * reflection like 0.99 is a few milliseconds of decay, so the ring
     * coefficient is an exponential of the sample rate, not a constant
     * borrowed from 44.1 kHz.
     */
    private class Whistle(val rate: Int, val quick: Boolean, val noise: Dsp.Noise) {
        private val buf = DoubleArray(8192)
        private var w = 0
        private var lp = 0.0

        fun prime(freq: Double) {
            val n = (rate / freq).roundToInt().coerceIn(8, buf.size - 2)
            for (i in buf.indices) buf[i] = 0.0
            for (i in 0 until n) buf[i] = 0.55 * sin(2.0 * PI * i / n)
            // The first reads are delay samples behind the write, so the sine
            // has to sit there, not under the write pointer.
            w = n % buf.size
        }

        fun sample(
            flow: Double,
            freq: Double,
            puff: Double,
            @Suppress("UNUSED_PARAMETER") dt: Double,
            noiseAmp: Double = 0.01,
            exact: Boolean = false,
        ): Double {
            val f = freq.coerceAtLeast(20.0)
            val delay = if (exact) {
                (rate / f).roundToInt().toDouble()
            } else {
                rate / f
            }.coerceIn(4.0, (buf.size - 4).toDouble())
            val bore = read(delay)
            val speaking = (flow - 0.05).coerceAtLeast(0.0)
            val flowing = flow > 1e-4
            if (!flowing && abs(bore) < 1e-12 && puff == 0.0) {
                write(0.0)
                return 0.0
            }
            val breath = if (!flowing || noiseAmp == 0.0) 0.0 else noise.next().toDouble() * noiseAmp * sqrt(flow)
            val kick = if (flowing) puff * 0.12 else 0.0
            val written = if (speaking > 0.02) {
                val jet = 1.35 + 2.8 * speaking
                val hold = exp(-1.0 / (rate * 6.0))
                hold * tanh(jet * bore + breath + kick)
            } else {
                val t60 = if (quick) 0.42 else 0.62
                val ring = exp(-6.907755 / (t60 * rate))
                ring * bore + breath + kick
            }
            write(written)
            if (!flowing && abs(bore) < 1e-10) return 0.0
            if (exact) return bore
            val follow = if (quick) 0.18 else 0.06
            lp += follow * (bore - lp)
            if (abs(lp) < 1e-18) lp = 0.0
            return if (quick) bore + 0.18 * (bore - lp) else 0.72 * bore + 0.28 * lp
        }

        private fun read(delay: Double): Double {
            var pos = w - delay
            val n = buf.size.toDouble()
            pos %= n
            if (pos < 0.0) pos += n
            val i = pos.toInt()
            val frac = pos - i
            val j = if (i + 1 >= buf.size) 0 else i + 1
            return buf[i] * (1.0 - frac) + buf[j] * frac
        }

        private fun write(x: Double) {
            buf[w] = if (x.isFinite()) x.coerceIn(-1.5, 1.5) else 0.0
            w++
            if (w >= buf.size) w = 0
        }
    }
}
