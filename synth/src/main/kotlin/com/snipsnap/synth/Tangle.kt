package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

enum class TangleVoice { BASS, LEAD, HOLLOW, PULSE, METAL, KNOT }

/**
 * Two powered oscillators whose previous outputs change each other's next phase rate.
 * Encounters clock held controls and temporary, smoothly ramped phase attraction. The
 * quiet unmodulated root is support, while the two oscillators make the timbre.
 *
 * Numeric voicing is a prototype, subject to the listening audition. HOLD imposes a
 * periodic constraint: a sustained causal prerender supplies the repeating encounter
 * and capture schedule and phase trajectory. Its small mean phase drift is removed to
 * fit whole root/sub periods. A periodic phase-rate correction makes that trajectory a
 * solution of the *same causal oscillator equations*, and attraction to the trajectory
 * stabilizes the solution. Actual previous oscillator outputs still drive reciprocal
 * FM. Filter, held-register, modulation and DC memory settle over consecutive periods;
 * a bounded search compares the complete state and independently compares the audio.
 * There is no crossfade of completed oscillator recordings or seam repair on a failed
 * candidate. The periodic constraint is audible as repetition of one catch sequence.
 */
object Tangle {
    const val MIN_MIDI = 24
    const val MAX_MIDI = 84
    const val LOOP_THRESHOLD = .99f
    const val MAX_HELD_EVENTS_HZ = 18.0
    const val MAX_CAPTURE_OPPORTUNITIES_HZ = 12.0
    const val MAX_PITCH_OFFSET_OCTAVES = .65
    const val MIN_PULSE_WIDTH = .10
    const val MAX_PULSE_WIDTH = .90
    const val MAX_FILTER_CUTOFF_HZ = 7_000.0
    const val MAX_DRIVE = 2.8
    const val MAX_OSCILLATOR_FREQUENCY_HZ = 12_000.0
    const val MAX_LOOP_RESIDUAL_RATIO = 1.20
    const val MAX_LOOP_TOTAL_CORRECTION_RATIO = 2.05
    private const val LOOP_PASSES = 8
    private const val LOOP_STATE_TOLERANCE = 2e-6
    private const val LOOP_SEAM_ROOM = 1e-4

    enum class CaptureState { FREE, APPROACH, CAUGHT, RELEASE }

    data class HeldEvent(
        val seconds: Double, val value: Double, val sourceA: Double, val sourceB: Double,
        val phaseError: Double, val encounter: Int,
        val relationship: String = "phase crossing",
    )

    data class CaptureEvent(
        val seconds: Double, val state: CaptureState, val phaseError: Double, val strength: Double,
    )

    data class LoopDiagnostics(
        val periodFrames: Int, val periodSeconds: Double, val attempts: Int,
        val stateError: Double, val seam: Double, val converged: Boolean, val strategy: String,
        val conditioningStateError: Double = 0.0,
    )

    data class Diagnostics(
        val rawPeak: Double, val rawRms: Double, val rawDc: Double,
        val minFrequencyHz: Double, val maxFrequencyHz: Double,
        val minPulseWidth: Double, val maxPulseWidth: Double,
        val maxPhaseCorrectionHz: Double, val maxFilterState: Double,
        val heldEvents: List<HeldEvent>, val captureEvents: List<CaptureEvent>,
        val loop: LoopDiagnostics? = null,
    )

    /** Raw is after the synthesis filter/DC stage, before antialias filtering and leveling. */
    data class Probe(
        val raw: FloatArray, val samples: FloatArray, val sampleRate: Int, val diagnostics: Diagnostics,
        val seamContext: FloatArray? = null,
    )

    data class LoopRender(
        val loop: FloatArray, val seam: Double, val stateError: Double,
        val attempts: Int, val diagnostics: Diagnostics,
        /** Independently synthesized preceding 256 frames followed by the final loop, at its gain. */
        val seamContext: FloatArray,
        val loopStartFrame: Int = 256,
    )

    private data class Voice(
        val aSaw: Double, val aPulse: Double, val aTri: Double,
        val bSaw: Double, val bPulse: Double, val ratio: Int,
        val detune: Double, val pulse: Double, val attack: Double,
        val seconds: Double, val dwellScale: Double,
    )

    private fun voice(v: TangleVoice): Voice = when (v) {
        TangleVoice.BASS -> Voice(.40, .22, .38, .65, .35, 1, 1.002, .50, .016, 2.2, 1.15)
        TangleVoice.LEAD -> Voice(.65, .25, .10, .40, .60, 2, 1.0015, .43, .014, 3.0, .90)
        TangleVoice.HOLLOW -> Voice(.10, .12, .78, .18, .82, 2, 1.001, .50, .055, 3.0, 1.30)
        TangleVoice.PULSE -> Voice(.18, .70, .12, .10, .90, 1, 1.004, .35, .025, 3.0, 1.0)
        TangleVoice.METAL -> Voice(.80, .15, .05, .65, .35, 3, 1.0008, .46, .022, 3.6, .80)
        TangleVoice.KNOT -> Voice(.48, .35, .17, .40, .60, 1, 1.003, .42, .040, 3.6, 1.05)
    }

    fun rootMidi(voice: TangleVoice): Int = when (voice) {
        TangleVoice.BASS, TangleVoice.KNOT -> 36
        TangleVoice.METAL -> 60
        else -> 48
    }

    fun macrosFor(voice: TangleVoice): List<MacroSpec> {
        val d = when (voice) {
            TangleVoice.BASS -> floatArrayOf(.30f, .25f, .35f, .30f, .80f)
            TangleVoice.LEAD -> floatArrayOf(.45f, .70f, .60f, .70f, .30f)
            TangleVoice.HOLLOW -> floatArrayOf(.25f, .50f, .25f, .40f, .35f)
            TangleVoice.PULSE -> floatArrayOf(.40f, .35f, .50f, .45f, .45f)
            TangleVoice.METAL -> floatArrayOf(.75f, .65f, .40f, .80f, .20f)
            TangleVoice.KNOT -> floatArrayOf(.85f, .50f, .80f, .60f, .60f)
        }
        return listOf(
            MacroSpec("TANGLE", d[0], .30f), MacroSpec("CHASE", d[1], .50f),
            MacroSpec("CATCH", d[2], .25f), MacroSpec("BITE", d[3], .40f),
            MacroSpec("WEIGHT", d[4], .45f), MacroSpec("HOLD", 0f, 0f),
        )
    }

    fun defaults(voice: TangleVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(macros: Map<String, Float>, voice: TangleVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((key, value) in macros) if (key in m) {
            require(value.isFinite()) { "TANGLE $key must be finite" }
            m[key] = value.coerceIn(0f, 1f)
        }
        return m
    }

    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD

    fun drumClassFor(voice: TangleVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(macros, voice).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    fun scramble(voice: TangleVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = if (near is TanglePatch && near.voice == voice) defaults(voice) + near.macros else defaults(voice)
        val spread = temperature.coerceIn(0f, 1f)
        return base.mapValues { (key, value) ->
            if (key == "HOLD") value else (value + (random.nextFloat() * 2f - 1f) * spread).coerceIn(0f, 1f)
        }
    }

    private fun wrap(x: Double): Double = x - floor(x)
    private fun wrapSub(x: Double): Double = x - 2.0 * floor(x * .5)
    private fun error(x: Double): Double = x - floor(x + .5)
    private fun smoothstep(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    private fun blamp(t: Double, dt: Double): Double = when {
        t < dt -> { val x = 1.0 - t / dt; x * x * x / 3.0 }
        t > 1.0 - dt -> { val x = 1.0 + (t - 1.0) / dt; x * x * x / 3.0 }
        else -> 0.0
    }

    private fun saw(t: Double, dt: Double): Double = 2.0 * t - 1.0 - blep(t, dt)

    /** DC-corrected pulse, with a fixed peak budget at narrow widths. */
    private fun pulse(t: Double, dt: Double, width: Double): Double =
        ((if (t < width) 1.0 else -1.0) + blep(t, dt) - blep(wrap(t - width), dt) - (2.0 * width - 1.0)) /
            (2.0 * max(width, 1.0 - width))

    private fun triangle(t: Double, dt: Double): Double =
        1.0 - 4.0 * abs(t - .5) + 4.0 * dt * (blamp(t, dt) - blamp(wrap(t + .5), dt))

    private class Meter {
        var minHz = Double.POSITIVE_INFINITY
        var maxHz = 0.0
        var minPw = 1.0
        var maxPw = 0.0
        var correction = 0.0
        var filter = 0.0
        val held = mutableListOf<HeldEvent>()
        val capture = mutableListOf<CaptureEvent>()

        fun result(raw: FloatArray, loop: LoopDiagnostics? = null): Diagnostics {
            var peak = 0.0; var sum = 0.0; var squares = 0.0
            for (x in raw) { require(x.isFinite()) { "TANGLE produced non-finite audio" }; peak = max(peak, abs(x.toDouble())); sum += x; squares += x.toDouble() * x }
            return Diagnostics(peak, kotlin.math.sqrt(squares / max(1, raw.size)), sum / max(1, raw.size),
                minHz, maxHz, minPw, maxPw, correction, filter, held.toList(), capture.toList(), loop)
        }
    }

    /** Every memory item influencing future samples is represented here; counters are period-local. */
    private data class State(
        val phaseA: Double, val phaseB: Double, val rootPhase: Double,
        val previousA: Double, val previousB: Double, val modA: Double, val modB: Double,
        val heldTarget: Double, val held: Double, val pulseA: Double, val pulseB: Double,
        val capture: CaptureState, val captureAge: Int, val refractory: Int,
        val heldCooldown: Int, val encounterDivider: Int, val oldError: Double,
        val ic1: Double, val ic2: Double, val dcInput: Double, val dcOutput: Double,
        val clock: Int, val envelope: Double,
    ) {
        fun distance(other: State): Double {
            var d = max(abs(error((phaseA - other.phaseA) * .5)) * 2, abs(error((phaseB - other.phaseB) * .5)) * 2)
            d = max(d, abs(error(rootPhase - other.rootPhase)))
            val a = doubleArrayOf(previousA, previousB, modA, modB, heldTarget, held, pulseA, pulseB,
                oldError, ic1, ic2, dcInput, dcOutput, envelope)
            val b = doubleArrayOf(other.previousA, other.previousB, other.modA, other.modB, other.heldTarget,
                other.held, other.pulseA, other.pulseB, other.oldError, other.ic1, other.ic2, other.dcInput, other.dcOutput, other.envelope)
            for (i in a.indices) d = max(d, abs(a[i] - b[i]))
            if (capture != other.capture || captureAge != other.captureAge || refractory != other.refractory ||
                heldCooldown != other.heldCooldown || encounterDivider != other.encounterDivider || clock != other.clock) return max(1.0, d)
            return d
        }
    }

    private data class Schedule(
        val phaseA: DoubleArray, val phaseB: DoubleArray,
        val advanceA: DoubleArray, val advanceB: DoubleArray,
        val held: DoubleArray, val capture: Array<CaptureState>, val strength: DoubleArray,
        val age: IntArray, val refractory: IntArray, val cooldown: IntArray, val divider: IntArray,
        val heldEvents: List<HeldEvent>, val captureEvents: List<CaptureEvent>,
    ) { val frames get() = phaseA.size }

    private class Machine(
        val v: Voice, val m: Map<String, Float>, val hz: Double, val rate: Int,
        val velocity: Double, seed: Long, val meter: Meter,
    ) {
        val tangle = m.getValue("TANGLE").toDouble()
        val chase = m.getValue("CHASE").toDouble()
        val catch = m.getValue("CATCH").toDouble()
        val bite = m.getValue("BITE").toDouble()
        val weight = m.getValue("WEIGHT").toDouble()
        val random = Random(seed)
        var phaseA = random.nextDouble() * 2.0
        var phaseB = random.nextDouble() * 2.0
        var rootPhase = random.nextDouble()
        var previousA = 0.0; var previousB = 0.0
        var modA = 0.0; var modB = 0.0
        var heldTarget = 0.0; var held = 0.0
        var pulseA = v.pulse; var pulseB = 1.0 - v.pulse
        var capture = CaptureState.FREE; var captureAge = 0
        var refractory = 0; var heldCooldown = 0; var encounterDivider = 0
        var oldError = error(phaseB - v.ratio * phaseA)
        var ic1 = 0.0; var ic2 = 0.0
        var dcInput = 0.0; var dcOutput = 0.0
        var clock = 0; var envelope = 1.0
        var lastHzA = hz; var lastHzB = hz * v.ratio
        var lastNaturalA = hz; var lastNaturalB = hz * v.ratio
        var lastStrength = 0.0
        val modAlphaA = 1.0 - exp(-1.0 / (rate * (.00010 + .00065 * (1.0 - chase))))
        val modAlphaB = 1.0 - exp(-1.0 / (rate * (.00012 + .00090 * chase)))
        val heldAlpha = 1.0 - exp(-1.0 / (rate * .018))
        val pwAlpha = 1.0 - exp(-1.0 / (rate * .006))
        val dcPole = exp(-2.0 * PI * 8.0 / rate)
        val register = 1.0 / (1.0 + (hz / 700.0).pow(1.3))
        // Below A2 the sub's superperiod can obscure a clean requested root,
        // especially B's odd-ratio sub. Spend less gain on infrasonic support;
        // the two phase-derived subs retain their separate, WEIGHT-controlled budget.
        val subHeadroom = (hz / 110.0).coerceAtMost(1.0).pow(2.0)
        val depth = (.018 * tangle + .48 * tangle.pow(1.2)) * register
        val approachFrames = ((.022 + .025 * catch) * rate).roundToInt()
        val dwellFrames = ((.030 + .110 * catch) * v.dwellScale * rate).roundToInt()
        val releaseFrames = ((.035 + .065 * catch) * rate).roundToInt()
        val refractoryFrames = ((.045 + .220 * (1.0 - catch)) * rate).roundToInt()
        val opportunityFrames = (rate / (.5 + 11.5 * catch)).roundToInt()
        val eventFrames = (rate / (5.0 + 13.0 * tangle)).roundToInt()
        val filterStride = 8 * (rate / RATE)
        var filterG = 0.0
        val filterK = 1.25 - .78 * bite
        val filterDrive = 1.0 + 1.8 * bite
        val filterDriveNorm = tanh(filterDrive)

        fun snapshot(): State = State(phaseA, phaseB, rootPhase, previousA, previousB, modA, modB,
            heldTarget, held, pulseA, pulseB, capture, captureAge, refractory, heldCooldown, encounterDivider,
            oldError, ic1, ic2, dcInput, dcOutput, clock, envelope)

        fun restore(s: State) {
            phaseA = s.phaseA; phaseB = s.phaseB; rootPhase = s.rootPhase
            previousA = s.previousA; previousB = s.previousB; modA = s.modA; modB = s.modB
            heldTarget = s.heldTarget; held = s.held; pulseA = s.pulseA; pulseB = s.pulseB
            capture = s.capture; captureAge = s.captureAge; refractory = s.refractory
            heldCooldown = s.heldCooldown; encounterDivider = s.encounterDivider; oldError = s.oldError
            ic1 = s.ic1; ic2 = s.ic2; dcInput = s.dcInput; dcOutput = s.dcOutput; clock = s.clock; envelope = s.envelope
        }

        fun changeCapture(state: CaptureState, phaseError: Double) {
            capture = state; captureAge = 0
            meter.capture.add(CaptureEvent(clock.toDouble() / rate, state, phaseError, strength()))
        }

        fun strength(): Double = when (capture) {
            CaptureState.FREE -> 0.0
            CaptureState.APPROACH -> smoothstep(captureAge.toDouble() / approachFrames)
            CaptureState.CAUGHT -> 1.0
            CaptureState.RELEASE -> 1.0 - smoothstep(captureAge.toDouble() / releaseFrames)
        }

        /** Reads only the preceding update's A/B outputs, before either new oscillator is evaluated. */
        fun step(
            env: Double, permitCapture: Boolean = true, schedule: Schedule? = null, index: Int = 0,
            forcedPhases: Boolean = false, correctionA: Double = 0.0, correctionB: Double = 0.0,
        ): Float {
            envelope = env
            if (schedule != null) {
                if (forcedPhases) { phaseA = schedule.phaseA[index]; phaseB = schedule.phaseB[index] }
                heldTarget = schedule.held[index]; capture = schedule.capture[index]
                captureAge = schedule.age[index]; refractory = schedule.refractory[index]
                heldCooldown = schedule.cooldown[index]; encounterDivider = schedule.divider[index]
            }
            modA += modAlphaA * (previousA - modA)
            modB += modAlphaB * (previousB - modB)
            held += heldAlpha * (heldTarget - held)
            val pwA = (v.pulse + .20 * tangle * held + .08 * (chase - .5)).coerceIn(MIN_PULSE_WIDTH, MAX_PULSE_WIDTH)
            val pwB = (1.0 - v.pulse - .16 * tangle * held - .06 * (chase - .5)).coerceIn(MIN_PULSE_WIDTH, MAX_PULSE_WIDTH)
            pulseA += pwAlpha * (pwA - pulseA); pulseB += pwAlpha * (pwB - pulseB)
            val effort = 1.0 + .16 * velocity * exp(-clock.toDouble() / (rate * .12))
            val balance = (1.0 + .17 * tangle * held).coerceIn(.80, 1.20)
            val depthA = depth * (.28 + .90 * chase) * balance * effort
            val depthB = depth * (1.18 - .90 * chase) / balance * effort
            val offsetA = (depthA * modB).coerceIn(-MAX_PITCH_OFFSET_OCTAVES, MAX_PITCH_OFFSET_OCTAVES)
            val offsetB = (depthB * modA).coerceIn(-MAX_PITCH_OFFSET_OCTAVES, MAX_PITCH_OFFSET_OCTAVES)
            // The small second-order compensation reduces FM's exponential mean-pitch bias.
            var freqA = hz * 2.0.pow(offsetA) / (1.0 + .038 * depthA * depthA)
            var freqB = hz * v.ratio * v.detune * 2.0.pow(offsetB) / (1.0 + .038 * depthB * depthB)
            val phaseError = error(phaseB - v.ratio * phaseA)
            lastStrength = schedule?.strength?.get(index) ?: strength()
            // Solve e = phaseB - r*phaseA. Both directions obey that same ratio;
            // CHASE continuously reallocates correction rather than exchanging identities.
            val pull = (phaseError * hz * (.55 + 3.2 * catch) * lastStrength).coerceIn(-.38 * hz, .38 * hz)
            val pullA = pull * chase / v.ratio
            val pullB = -pull * (1.0 - chase)
            freqA += pullA; freqB += pullB
            lastNaturalA = freqA; lastNaturalB = freqB
            var totalCorrectionA = pullA; var totalCorrectionB = pullB
            if (schedule != null && !forcedPhases) {
                // Error attraction is a phase-rate control inside the synthesis loop.
                val refA = error((phaseA - schedule.phaseA[index]) * .5) * 2.0
                val refB = error((phaseB - schedule.phaseB[index]) * .5) * 2.0
                val rateCorrectionA = correctionA - (refA * hz * 6.0).coerceIn(-.45 * hz, .45 * hz)
                val rateCorrectionB = correctionB - (refB * hz * v.ratio * 6.0).coerceIn(-.45 * hz * v.ratio, .45 * hz * v.ratio)
                totalCorrectionA += rateCorrectionA; totalCorrectionB += rateCorrectionB
                check(abs(totalCorrectionA) <= MAX_LOOP_TOTAL_CORRECTION_RATIO * hz &&
                    abs(totalCorrectionB) <= MAX_LOOP_TOTAL_CORRECTION_RATIO * hz * v.ratio) {
                    "TANGLE HOLD total phase correction exceeded its explicit budget"
                }
                freqA += rateCorrectionA; freqB += rateCorrectionB
            }
            if (forcedPhases && schedule != null) {
                freqA = schedule.advanceA[index] * rate; freqB = schedule.advanceB[index] * rate
            }
            freqA = freqA.coerceIn(hz * .25, MAX_OSCILLATOR_FREQUENCY_HZ)
            freqB = freqB.coerceIn(hz * v.ratio * .25, MAX_OSCILLATOR_FREQUENCY_HZ)
            lastHzA = freqA; lastHzB = freqB
            val dtA = freqA / rate; val dtB = freqB / rate
            val a = wrap(phaseA); val b = wrap(phaseB)
            val waveA = v.aSaw * saw(a, dtA) + v.aPulse * pulse(a, dtA, pulseA) + v.aTri * triangle(a, dtA)
            val waveB = v.bSaw * saw(b, dtB) + v.bPulse * pulse(b, dtB, pulseB)
            val oldDifference = previousA - previousB
            val newDifference = waveA - waveB
            // A fixed nonzero phase relationship may never cross zero phase error.
            // The waveforms still meet twice a cycle: accept those crossings too,
            // using their actual phase-derived outputs, never an independent clock.
            val waveformMeeting = clock > 0 && oldDifference * newDifference <= 0.0 && oldDifference != newDifference
            val subA = pulse(phaseA * .5, dtA * .5, .5)
            val subB = pulse(phaseB * .5, dtB * .5, .5)
            previousA = waveA; previousB = waveB
            val nextA = wrapSub(phaseA + dtA); val nextB = wrapSub(phaseB + dtB)
            val newError = error(nextB - v.ratio * nextA)
            if (schedule == null) {
                if (heldCooldown > 0) heldCooldown--
                if (refractory > 0) refractory--
                val crossing = oldError * newError <= 0.0 && abs(oldError - newError) < .5 && oldError != newError
                val jointWrap = (wrap(nextA) < a || wrap(nextB) < b) && abs(newError) < .18
                if (crossing || jointWrap || waveformMeeting) {
                    encounterDivider = encounterDivider % 64 + 1
                    if (heldCooldown <= 0) {
                        heldTarget = (.62 * waveA - .38 * waveB).coerceIn(-1.0, 1.0)
                        val relationship = when {
                            crossing -> "wrapped ratio-phase crossing"
                            jointWrap -> "joint phase-wrap window"
                            else -> "phase-derived waveform meeting"
                        }
                        meter.held.add(HeldEvent(clock.toDouble() / rate, heldTarget, waveA, waveB, newError, encounterDivider, relationship))
                        heldCooldown = eventFrames
                        if (permitCapture && catch > 0.0 && capture == CaptureState.FREE && refractory <= 0) {
                            changeCapture(CaptureState.APPROACH, newError)
                        }
                    }
                }
                when (capture) {
                    CaptureState.FREE -> Unit
                    CaptureState.APPROACH -> if (++captureAge >= approachFrames) changeCapture(CaptureState.CAUGHT, newError)
                    CaptureState.CAUGHT -> if (++captureAge >= dwellFrames) changeCapture(CaptureState.RELEASE, newError)
                    CaptureState.RELEASE -> if (++captureAge >= releaseFrames) {
                        changeCapture(CaptureState.FREE, newError)
                        refractory = max(refractoryFrames, opportunityFrames - approachFrames - dwellFrames - releaseFrames)
                    }
                }
            }
            oldError = newError
            phaseA = nextA; phaseB = nextB
            rootPhase = wrap(rootPhase + hz / rate)
            val leadA = .65 - .20 * chase
            val mixed = .66 * (leadA * waveA + (1.0 - leadA) * waveB) + .12 * weight * subHeadroom * (.65 * subA + .35 * subB)
            // A soft key closes enough upper harmonics to survive shared output leveling.
            val velocityOpening = .45 + .55 * velocity
            if (clock % filterStride == 0 || filterG == 0.0) {
                val cutoff = ((hz * (2.5 + 11.0 * bite) + 220.0) * velocityOpening *
                    (1.0 + .23 * tangle * held) * (.70 + .30 * env)).coerceIn(100.0, min(MAX_FILTER_CUTOFF_HZ, rate * .08))
                filterG = tan(PI * cutoff / rate)
            }
            val input = tanh(mixed * env * filterDrive) / filterDriveNorm
            // Topology-preserving SVF, Q <= 2.13, no feedback saturation hiding state growth.
            val a1 = 1.0 / (1.0 + filterG * (filterG + filterK))
            val bp = a1 * (ic1 + filterG * (input - ic2))
            val lp = ic2 + filterG * bp
            ic1 = 2.0 * bp - ic1; ic2 = 2.0 * lp - ic2
            val root = .85 * sin(2.0 * PI * rootPhase) + .15 * triangle(rootPhase, hz / rate)
            val amplitude = velocity.pow(.35)
            val output = amplitude * (.72 * lp + .06 * bite * bp + (.095 + .28 * weight) * root * env)
            val dc = output - dcInput + dcPole * dcOutput
            dcInput = output; dcOutput = dc
            meter.minHz = min(meter.minHz, min(freqA, freqB)); meter.maxHz = max(meter.maxHz, max(freqA, freqB))
            meter.minPw = min(meter.minPw, min(pulseA, pulseB)); meter.maxPw = max(meter.maxPw, max(pulseA, pulseB))
            meter.correction = max(meter.correction, max(abs(totalCorrectionA), abs(totalCorrectionB)))
            meter.filter = max(meter.filter, max(abs(ic1), abs(ic2)))
            check(ic1.isFinite() && ic2.isFinite() && abs(ic1) < 8.0 && abs(ic2) < 8.0) { "TANGLE filter exceeded its internal bound" }
            clock++
            return dc.toFloat()
        }
    }

    private fun validated(voice: TangleVoice, macros: Map<String, Float>, midi: Int, velocity: Float, oversample: Int): Map<String, Float> {
        require(midi in MIN_MIDI..MAX_MIDI) { "TANGLE pitch must be MIDI $MIN_MIDI..$MAX_MIDI, got $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "TANGLE velocity must be 0..1" }
        require(oversample == 4 || oversample == 8 || oversample == 16) { "TANGLE oversampling must be 4, 8 or 16" }
        return settled(macros, voice)
    }

    private fun seed(voice: TangleVoice, midi: Int): Long = Dsp.seedFor("TANGLE", voice.name, midi.toFloat()).toLong()

    /** The house 19.5kHz eighth-order Butterworth, with memory exposed to HOLD's check. */
    private class Antialias(rate: Int) {
        val memory = DoubleArray(8)
        val g = tan(PI * Tide.BAND_LIMIT_HZ / rate)
        val damping = doubleArrayOf(1.9616, 1.6629, 1.1111, .3902)

        fun next(sample: Float): Float {
            var value = sample
            for (j in damping.indices) {
                val index = j * 2
                val v3 = value - memory[index + 1]
                val v1 = (memory[index] + g * v3) / (1.0 + g * (g + damping[j]))
                val v2 = memory[index + 1] + g * v1
                memory[index] = 2.0 * v1 - memory[index]
                memory[index + 1] = 2.0 * v2 - memory[index + 1]
                value = v2.toFloat()
            }
            return value
        }
    }

    private data class Conditioned(val samples: FloatArray, val stateError: Double)

    // SynthScreen previews run in runInterruptible: a newer request interrupts this thread,
    // and every long pass polls it so a stale render stops instead of finishing unwanted.
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("TANGLE render no longer wanted")
    }

    private fun condition(raw: FloatArray, oversample: Int, boundary: Int = 0, period: Int = 0): Conditioned {
        stopIfUnwanted()
        val filter = Antialias(RATE * oversample)
        val filtered = FloatArray(raw.size)
        var firstMemory: DoubleArray? = null
        var memoryError = 0.0
        for (i in raw.indices) {
            filtered[i] = filter.next(raw[i])
            if (period > 0 && i + 1 == boundary) firstMemory = filter.memory.copyOf()
            if (period > 0 && i + 1 == boundary + period) {
                for (j in filter.memory.indices) memoryError = max(memoryError, abs(filter.memory[j] - firstMemory!![j]))
                // Resampler is a finite 16-tap-per-stage sinc. This history covers
                // the whole cascade (including the additional 8x/16x reference stages).
                val history = 64 * oversample
                for (j in 1..history) memoryError = max(memoryError,
                    abs(filtered[boundary - j].toDouble() - filtered[boundary + period - j]))
            }
        }
        if (oversample == Dsp.OVERSAMPLE) return Conditioned(Dsp.decimate(filtered, RATE), memoryError)
        var snip = Snip(filtered, channels = 1, sampleRate = RATE * oversample)
        repeat(Integer.numberOfTrailingZeros(oversample)) { snip = Resampler.resample(snip, snip.sampleRate / 2) }
        return Conditioned(snip.samples, memoryError)
    }

    /** Deterministic prelevel output and the event trace used by engineering probes. */
    fun synthesize(
        voice: TangleVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice),
        velocity: Float = 1f, oversample: Int = 4, durationSeconds: Float? = null,
    ): Probe {
        val m = validated(voice, macros, midi, velocity, oversample)
        if (isLoop(m.getValue("HOLD"))) return loopProbe(voice, m, midi, velocity, oversample)
        val v = voice(voice)
        val seconds = durationSeconds?.toDouble() ?: v.seconds * (1.0 + .65 * m.getValue("HOLD"))
        require(seconds.isFinite() && seconds in .1..12.0) { "TANGLE finite render duration must be .1..12 seconds" }
        val rate = RATE * oversample
        val meter = Meter()
        val machine = Machine(v, m, Keys.midiHz(midi).toDouble(), rate, velocity.toDouble(), seed(voice, midi), meter)
        val frames = (seconds * rate).roundToInt()
        val raw = FloatArray(frames)
        val release = min(.24, seconds * .22)
        val gate = seconds - release - .04
        for (i in raw.indices) {
            if (i % 8192 == 0) stopIfUnwanted()
            val time = i.toDouble() / rate
            val attack = smoothstep(time / (v.attack * (1.25 - .4 * velocity)))
            val body = .76 + .24 * exp(-time / .32) * velocity
            val releaseEnvelope = if (time < gate) 1.0 else 1.0 - smoothstep((time - gate) / release)
            raw[i] = machine.step(attack * body * releaseEnvelope, permitCapture = time < gate - .2)
        }
        val samples = condition(raw, oversample).samples
        Dsp.fadeTail(samples, rate = RATE)
        return Probe(raw, samples, rate, meter.result(raw))
    }

    fun probe(
        voice: TangleVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice),
        velocity: Float = 1f, oversample: Int = 4, durationSeconds: Float? = null,
    ): Probe = synthesize(voice, macros, midi, velocity, oversample, durationSeconds)

    fun render(
        voice: TangleVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice), velocity: Float = 1f,
    ): Snip {
        val samples = synthesize(voice, macros, midi, velocity).samples
        Dsp.levelTo(samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        return Snip(samples, channels = 1, sampleRate = RATE)
    }

    /** Fits whole sub periods to frames; >=1.5s sustained material follows the house loop convention. */
    private data class LoopPlan(val frames: Int, val hz: Double, val cycles: Int)

    private fun loopPlan(hz: Double): LoopPlan {
        val cycles = max(2, (1.65 * hz / 2.0).roundToInt() * 2)
        val frames = (cycles * RATE / hz).roundToInt()
        return LoopPlan(frames, cycles * RATE.toDouble() / frames, cycles)
    }

    private fun schedule(v: Voice, m: Map<String, Float>, midi: Int, velocity: Float, oversample: Int, plan: LoopPlan): Schedule {
        val rate = RATE * oversample
        val n = plan.frames * oversample
        val meter = Meter()
        val pilot = Machine(v, m, plan.hz, rate, velocity.toDouble(), seed(TangleVoice.entries.first { voice(it) == v }, midi), meter)
        // No onset in a held loop. Preroll preserves the interaction before recording a schedule.
        repeat((.55 * rate).roundToInt()) { if (it % 8192 == 0) stopIfUnwanted(); pilot.step(1.0) }
        pilot.capture = CaptureState.FREE; pilot.captureAge = 0; pilot.refractory = 0
        pilot.clock = 0; meter.held.clear(); meter.capture.clear()
        val phaseA = DoubleArray(n); val phaseB = DoubleArray(n)
        val advanceA = DoubleArray(n); val advanceB = DoubleArray(n)
        val held = DoubleArray(n); val capture = Array(n) { CaptureState.FREE }
        val strength = DoubleArray(n); val age = IntArray(n); val refractory = IntArray(n)
        val cooldown = IntArray(n); val divider = IntArray(n)
        var accumulatedA = 0.0; var accumulatedB = 0.0
        val initialA = pilot.phaseA; val initialB = pilot.phaseB
        val noNewCapture = pilot.approachFrames + pilot.dwellFrames + pilot.releaseFrames + pilot.refractoryFrames + 64
        for (i in 0 until n) {
            if (i % 8192 == 0) stopIfUnwanted()
            phaseA[i] = initialA + accumulatedA; phaseB[i] = initialB + accumulatedB
            held[i] = pilot.heldTarget; capture[i] = pilot.capture; strength[i] = pilot.strength()
            age[i] = pilot.captureAge; refractory[i] = pilot.refractory
            cooldown[i] = pilot.heldCooldown; divider[i] = pilot.encounterDivider
            pilot.step(1.0, permitCapture = i < n - noNewCapture)
            advanceA[i] = pilot.lastHzA / rate; advanceB[i] = pilot.lastHzB / rate
            accumulatedA += advanceA[i]; accumulatedB += advanceB[i]
        }
        val driftA = (accumulatedA - plan.cycles) / n
        val driftB = (accumulatedB - plan.cycles * v.ratio) / n
        for (i in 0 until n) {
            phaseA[i] = wrapSub(phaseA[i] - driftA * i)
            phaseB[i] = wrapSub(phaseB[i] - driftB * i)
            advanceA[i] -= driftA; advanceB[i] -= driftB
            check(advanceA[i] > 0.0 && advanceB[i] > 0.0) { "TANGLE HOLD phase fitting exceeded the positive frequency budget" }
        }
        // Before the first event the previous period's last register is still held.
        val firstEvent = meter.held.firstOrNull()?.let { (it.seconds * rate).roundToInt() + 1 } ?: n
        val lastHeld = pilot.heldTarget
        for (i in 0 until min(n, firstEvent)) held[i] = lastHeld
        return Schedule(phaseA, phaseB, advanceA, advanceB, held, capture, strength, age, refractory, cooldown, divider,
            meter.held.toList(), meter.capture.toList())
    }

    private fun periodicBoundary(machine: Machine, schedule: Schedule) {
        // These are the prescribed periodic control clock, not waveform/filter state resets.
        machine.clock = 0; machine.capture = schedule.capture[0]; machine.captureAge = schedule.age[0]
        machine.refractory = schedule.refractory[0]; machine.heldCooldown = schedule.cooldown[0]
        machine.encounterDivider = schedule.divider[0]; machine.heldTarget = schedule.held[0]
        machine.envelope = 1.0
    }

    private fun loopProbe(voice: TangleVoice, m: Map<String, Float>, midi: Int, velocity: Float, oversample: Int): Probe {
        val plan = loopPlan(Keys.midiHz(midi).toDouble())
        val v = voice(voice); val rate = RATE * oversample
        val s = schedule(v, m, midi, velocity, oversample, plan)
        val meter = Meter()
        val machine = Machine(v, m, plan.hz, rate, velocity.toDouble(), seed(voice, midi), meter)
        val correctionA = DoubleArray(s.frames); val correctionB = DoubleArray(s.frames)
        machine.phaseA = s.phaseA[0]; machine.phaseB = s.phaseB[0]
        machine.rootPhase = 0.0; machine.heldTarget = s.held[0]; machine.held = s.held[0]
        // Solve the periodic reference's modulation/held/filter memory, then record the
        // additive phase-rate term. Only the reference solve prescribes oscillator phases.
        repeat(3) {
            periodicBoundary(machine, s)
            for (i in 0 until s.frames) {
                if (i % 8192 == 0) stopIfUnwanted()
                machine.step(1.0, schedule = s, index = i, forcedPhases = true)
                correctionA[i] = s.advanceA[i] * rate - machine.lastNaturalA
                correctionB[i] = s.advanceB[i] * rate - machine.lastNaturalB
                check(abs(correctionA[i]) <= MAX_LOOP_RESIDUAL_RATIO * plan.hz &&
                    abs(correctionB[i]) <= MAX_LOOP_RESIDUAL_RATIO * plan.hz * v.ratio) {
                    "TANGLE HOLD reference phase-rate residual exceeded its explicit budget"
                }
            }
        }
        machine.phaseA = s.phaseA[0]; machine.phaseB = s.phaseB[0]
        machine.rootPhase = 0.0
        var previousAudio: FloatArray? = null
        var kept = FloatArray(s.frames)
        var stateError = Double.POSITIVE_INFINITY
        var seam = Double.POSITIVE_INFINITY
        var attempts = 0
        var seamContext: FloatArray? = null
        var conditioningError = Double.POSITIVE_INFINITY
        for (pass in 1..LOOP_PASSES) {
            periodicBoundary(machine, s)
            val before = machine.snapshot()
            val period = FloatArray(s.frames)
            for (i in 0 until s.frames) {
                if (i % 8192 == 0) stopIfUnwanted()
                period[i] = machine.step(1.0, schedule = s, index = i, correctionA = correctionA[i], correctionB = correctionB[i])
            }
            periodicBoundary(machine, s)
            stateError = before.distance(machine.snapshot())
            val previous = previousAudio
            if (previous != null) {
                // Filtering/decimation see two *different consecutive* physical periods
                // surrounded by periodic guards. Their edge padding never reaches the join.
                val guards = min(s.frames, 2048 * oversample)
                val joined = previous.copyOfRange(previous.size - guards, previous.size) + previous + period + period.copyOfRange(0, guards)
                val conditionedResult = condition(joined, oversample, guards + s.frames, s.frames)
                val conditioned = conditionedResult.samples
                conditioningError = conditionedResult.stateError
                stateError = max(stateError, conditioningError)
                val start = guards / oversample
                val comparison = conditioned.copyOfRange(start + plan.frames - 256, start + 2 * plan.frames)
                seam = if (velocity == 0f) 0.0 else Keys.seamError(comparison, 256)
                seamContext = comparison
                kept = period
            }
            attempts = pass
            if (previous != null && stateError < LOOP_STATE_TOLERANCE && seam < LOOP_SEAM_ROOM) break
            previousAudio = period
        }
        val converged = stateError < LOOP_STATE_TOLERANCE && seam < Keys.MAX_SEAM_ERROR
        val ld = LoopDiagnostics(plan.frames, plan.frames.toDouble() / RATE, attempts, stateError, seam, converged,
            "periodic encounter/capture schedule and phase-rate constraint from a causal prerender; full-state recurrence",
            conditioningError)
        check(converged) { "TANGLE HOLD $voice MIDI $midi did not converge in $attempts periods (state=$stateError, seam=$seam)" }
        meter.held.clear(); meter.held.addAll(s.heldEvents)
        meter.capture.clear(); meter.capture.addAll(s.captureEvents)
        val context = seamContext!!
        val samples = context.copyOfRange(256, context.size)
        return Probe(kept, samples, rate, meter.result(kept, ld), context)
    }

    fun renderLoopMeasured(
        voice: TangleVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice), velocity: Float = 1f,
    ): LoopRender {
        val probe = synthesize(voice, macros + ("HOLD" to 1f), midi, velocity)
        val loop = probe.samples
        val rawPeak = loop.maxOf { abs(it) }
        Dsp.levelTo(loop, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val gain = if (rawPeak > 0f) loop.maxOf { abs(it) } / rawPeak else 1f
        val context = probe.seamContext!!.map { it * gain }.toFloatArray()
        val d = probe.diagnostics.loop!!
        return LoopRender(loop, if (velocity == 0f) 0.0 else Keys.seamError(context, 256), d.stateError, d.attempts, probe.diagnostics, context)
    }

    fun renderLoop(
        voice: TangleVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice), velocity: Float = 1f,
    ): FloatArray = renderLoopMeasured(voice, macros, midi, velocity).loop
}
