package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * An auditable linear readout of the *actual* Rössler state. The three fixed reference scales
 * are shared by every clean voice; none depends on the instantaneous state or sample level.
 * Direction moves on a unit sphere, so VIEW and COIL can expose height (Z), not just rotate X/Y.
 */
internal data class VentLinearProjection(val x: Double, val y: Double, val z: Double) {
    fun readout(stateX: Double, stateY: Double, stateZ: Double): Double =
        GAIN * (x * stateX / 12.0 + y * stateY / 12.0 + z * stateZ / 60.0)

    fun distanceTo(other: VentLinearProjection): Double =
        sqrt((x-other.x).pow(2) + (y-other.y).pow(2) + (z-other.z).pow(2))

    companion object {
        const val GAIN = .18

        fun forVoice(voice: VentVoice, view: Double, progress: Double): VentLinearProjection {
            val turn = PI * (view-.5)
            val tilt = .4 * (view-.5)
            val motion = smooth(progress)
            val angle: Double
            val elevation: Double
            when (voice) {
                VentVoice.PULSE -> { angle = -.3+turn; elevation = 1.38+tilt }
                VentVoice.COIL -> { angle = .5+turn+.6*motion; elevation = .2+tilt+1.25*motion }
                VentVoice.PLUME -> { angle = 1.35+turn; elevation = 1.22+tilt }
                else -> error("model 2 requires a clean VENT voice")
            }
            return VentLinearProjection(cos(angle)*cos(elevation), sin(angle)*cos(elevation), sin(elevation))
        }

        private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it*it*(3.0-2.0*it) }
    }
}

/**
 * Clean model-2 instruments. One RK4 Rössler trajectory supplies all audio, with linear XYZ
 * readout, common two-pole filtering, DC removal, finite envelope and the shared level convention.
 * There is no state compression, saturator, clipped derivative, or added audio oscillator.
 *
 * PULSE strikes the orbit through a short smooth force; COIL gently excites it while its readout
 * tilts toward Z; PLUME adds sustained pressure to the ODE. These are invented instruments, not
 * acoustic simulations. PUSH zero removes those added forces, leaving the autonomous source
 * and any requested LEASH. Velocity changes the force before the shared output-level stage.
 *
 * LEASH is a continuous X-only correction in the ODE: a raised-cosine window over the first
 * 8% of each requested cycle attracts X toward zero. Y and Z keep their history. Nominal pitch
 * comes from measuring the unforced orbit for each render and scaling its model-time period;
 * forcing and chaotic folding can still depart from that nominal pitch.
 */
internal object VentModel2 {
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val MAX_STEP = .02
    private const val STATE_BOUND = 256.0
    private const val LEASH_WINDOW = .08
    private val cosineAtWindowEnd = cos(PI)

    /**
     * The old clamped cosine is constant outside its moving interval. Reuse its exact
     * endpoint values instead of asking libm to recompute them at every RK4 stage.
     * The interior keeps the same expression and arithmetic, so this does not revoice it.
     */
    private fun windowCosine(u: Double): Double {
        val bounded = u.coerceIn(0.0, 1.0)
        return when (bounded) {
            0.0 -> 1.0
            1.0 -> cosineAtWindowEnd
            else -> cos(PI*bounded)
        }
    }

    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("VENT render no longer wanted")
    }

    /** All external gestures are bounded and smooth; no audio-rate force oscillator is mixed in. */
    private class Gesture(val voice: VentVoice, push: Double, velocity: Double, val seconds: Double) {
        val strength = push * velocity.pow(.75)
        val forceX = when (voice) { VentVoice.PULSE -> 1.1; VentVoice.COIL -> .5; VentVoice.PLUME -> .4; else -> error("clean voice required") }
        val forceY = when (voice) { VentVoice.PULSE -> -1.3; VentVoice.COIL -> -.7; VentVoice.PLUME -> -.3; else -> error("clean voice required") }
        val forceZ = when (voice) { VentVoice.PULSE -> .85; VentVoice.COIL -> .2; VentVoice.PLUME -> .65; else -> error("clean voice required") }

        fun at(t: Double): Double {
            if (strength == 0.0 || t <= 0.0) return 0.0
            val profile = when (voice) {
                VentVoice.PULSE -> pulse(t/.008)
                VentVoice.COIL -> pulse(t/.045)
                VentVoice.PLUME -> {
                    val entry = raised(t/.055)
                    val exit = 1.0-raised((t/seconds-.62)/.38)
                    entry*exit*(.7+.3*raised(t/seconds))
                }
                else -> error("clean voice required")
            }
            return strength*profile
        }

        // u² exp(2-2u) peaks at exactly one and has zero derivative at note entry.
        private fun pulse(u: Double): Double = u*u*exp(2.0-2.0*u)
        private fun raised(u: Double): Double = .5-.5*windowCosine(u)
    }

    private class Orbit(val a: Double, val b: Double, val c: Double, random: Random) {
        var x = random.nextDouble(-.8, .8)
        var y = random.nextDouble(-1.8, -.7)
        var z = random.nextDouble(.04, .15)
        var maxState = 0.0
        var failures = 0

        /** Each RK4 stage evaluates both smooth gesture and smooth X-only LEASH in the ODE. */
        fun step(dt: Double, t: Double = 0.0, audioDt: Double = 0.0, gesture: Gesture? = null,
                 hz: Double = 0.0, startPhase: Double = 0.0, anchorGain: Double = 0.0) {
            val f1 = gesture?.at(t) ?: 0.0
            val fx = gesture?.forceX ?: 0.0; val fy = gesture?.forceY ?: 0.0; val fz = gesture?.forceZ ?: 0.0
            val k1x = -y-z+fx*f1-anchor(t, hz, startPhase, anchorGain)*x
            val k1y = x+a*y+fy*f1; val k1z = b+z*(x-c)+fz*f1
            val x2 = x+.5*dt*k1x; val y2 = y+.5*dt*k1y; val z2 = z+.5*dt*k1z
            val f2 = gesture?.at(t+.5*audioDt) ?: 0.0
            // Stages two and three use the same clock, despite their different states.
            val halfAnchor = anchor(t+.5*audioDt, hz, startPhase, anchorGain)
            val k2x = -y2-z2+fx*f2-halfAnchor*x2
            val k2y = x2+a*y2+fy*f2; val k2z = b+z2*(x2-c)+fz*f2
            val x3 = x+.5*dt*k2x; val y3 = y+.5*dt*k2y; val z3 = z+.5*dt*k2z
            val k3x = -y3-z3+fx*f2-halfAnchor*x3
            val k3y = x3+a*y3+fy*f2; val k3z = b+z3*(x3-c)+fz*f2
            val x4 = x+dt*k3x; val y4 = y+dt*k3y; val z4 = z+dt*k3z
            val f4 = gesture?.at(t+audioDt) ?: 0.0
            val k4x = -y4-z4+fx*f4-anchor(t+audioDt, hz, startPhase, anchorGain)*x4
            val k4y = x4+a*y4+fy*f4; val k4z = b+z4*(x4-c)+fz*f4
            x += dt/6.0*(k1x+2*k2x+2*k3x+k4x)
            y += dt/6.0*(k1y+2*k2y+2*k3y+k4y)
            z += dt/6.0*(k1z+2*k2z+2*k3z+k4z)
            measure()
        }

        private fun anchor(t: Double, hz: Double, startPhase: Double, gain: Double): Double {
            if (gain == 0.0) return 0.0
            val phase = (startPhase+hz*t) % 1.0
            if (phase >= LEASH_WINDOW) return 0.0
            return gain*(.5-.5*cos(2.0*PI*phase/LEASH_WINDOW))
        }

        private fun measure() {
            val peak = max(abs(x), max(abs(y), abs(z)))
            if (!peak.isFinite() || peak > STATE_BOUND || z < -.01) {
                failures++
                // This is only an exceptional numeric guard, never part of the normal sound.
                x = 0.0; y = -c; z = b/c
            } else maxState = max(maxState, peak)
        }
    }

    fun render(voice: VentVoice, macros: Map<String, Float>, midi: Int, velocity: Float, seed: Int): VentRender {
        require(Vent.supportsVoiceModel(voice, Vent.CLEAN_MODEL)) { "clean VENT voice required" }
        require(midi in Vent.MIDI_MIN..Vent.MIDI_MAX) { "VENT MIDI must be ${Vent.MIDI_MIN}..${Vent.MIDI_MAX}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "VENT velocity must be 0..1" }
        stopIfUnwanted()
        val chaos = macros.getValue("CHAOS").toDouble()
        val leash = macros.getValue("LEASH").toDouble()
        val view = macros.getValue("VIEW").toDouble()
        val damp = macros.getValue("DAMP").toDouble()
        val push = macros.getValue("PUSH").toDouble()
        val vel = velocity.toDouble()
        val seconds = .18*(8.0/.18).pow(macros.getValue("LENGTH").toDouble())
        val frames = max(1, (seconds*Dsp.RATE).roundToInt())

        // Every clean voice uses the isolation experiment's seeded preroll. Voice and note do
        // not change its initial state: their causal differences are the gesture and projection.
        val random = Random(Dsp.seedFor("VENT", 1, VentVoice.ROUND, seed))
        val orbit = Orbit(.12+.13*chaos, .2, 3.0+5.8*chaos, random)
        val warmSteps = ((300.0+random.nextDouble()*24.0)/MAX_STEP).roundToInt()
        var modelTime = 0.0
        var lastCrossing = 0.0
        val crossings = ArrayList<Double>()
        for (i in 0 until warmSteps) {
            if (i % 2048 == 0) stopIfUnwanted()
            val oldX = orbit.x
            orbit.step(MAX_STEP)
            modelTime += MAX_STEP
            if (oldX < 0.0 && orbit.x >= 0.0 && orbit.y < 0.0) {
                lastCrossing = modelTime-MAX_STEP+MAX_STEP*(-oldX)/(orbit.x-oldX)
                if (modelTime >= 150.0) crossings.add(lastCrossing)
            }
        }
        check(crossings.size >= 8) { "VENT clean orbit has insufficient calibration crossings" }
        val period = (crossings.last()-crossings.first())/(crossings.size-1)
        check(period in 4.0..7.5) { "VENT clean orbit period outside the calibrated range: $period" }
        check(orbit.failures == 0) { "VENT clean calibration required emergency integration resets" }
        val hz = Keys.midiHz(midi).toDouble()
        val modelPerSecond = period*hz
        val step = modelPerSecond/INTERNAL_RATE
        val substeps = max(1, ceil(step/MAX_STEP).toInt())
        val dt = step/substeps
        val audioDt = 1.0/(INTERNAL_RATE*substeps)
        val startPhase = ((modelTime-lastCrossing)/period) % 1.0
        val anchorGain = 6.0*leash*leash/(period*LEASH_WINDOW)
        val gesture = Gesture(voice, push, vel, seconds)
        val forceMagnitude = sqrt(gesture.forceX.pow(2)+gesture.forceY.pow(2)+gesture.forceZ.pow(2))
        var forceIntegral = 0.0

        // Exactly the same DAMP mapping, linear filter, attack, release and gain for all voices.
        val lowCutoff = (hz*.85).coerceAtLeast(70.0)
        val highCutoff = (hz*.85*42.0).coerceIn(lowCutoff, 14_000.0)
        val cutoff = lowCutoff*(highCutoff/lowCutoff).pow(1.0-damp)
        val lowAlpha = 1.0-exp(-2.0*PI*cutoff/INTERNAL_RATE)
        val dcPole = exp(-2.0*PI*12.0/INTERNAL_RATE)
        var low1 = 0.0; var low2 = 0.0; var dcIn = 0.0; var dcOut = 0.0
        var stateSquares = 0.0
        val rawInternal = FloatArray(frames*Dsp.OVERSAMPLE)
        val trace = ArrayList<VentTrace>()
        val traceStride = INTERNAL_RATE/200
        val firstProjection = VentLinearProjection.forVoice(voice, view, 0.0)
        var projectionSpan = 0.0
        var previousProjection = firstProjection
        var resets = 0
        var lastCycle = startPhase.toLong()
        val velocityLevel = vel.pow(.35)
        // COIL's established motion is already stationary after 72% of the passage.
        val finalProjection = if (voice == VentVoice.COIL)
            VentLinearProjection.forVoice(voice, view, 1.0) else firstProjection
        for (i in rawInternal.indices) {
            if (i % 2048 == 0) stopIfUnwanted()
            val t = i.toDouble()/INTERNAL_RATE
            for (sub in 0 until substeps) {
                orbit.step(dt, t+sub*audioDt, audioDt, gesture, hz, startPhase, anchorGain)
            }
            val cycles = (startPhase+hz*(t+1.0/INTERNAL_RATE)).toLong()
            if (leash > 0.0 && cycles > lastCycle) resets++
            lastCycle = cycles
            forceIntegral += gesture.at(t+.5/INTERNAL_RATE)*forceMagnitude*step
            stateSquares += (orbit.x*orbit.x+orbit.y*orbit.y+orbit.z*orbit.z)/3.0
            if (i % traceStride == 0) trace.add(VentTrace(t, orbit.x, orbit.y, orbit.z))
            val projectionProgress = if (voice == VentVoice.COIL) t/(.72*seconds) else 0.0
            val projection = if (voice == VentVoice.COIL && projectionProgress < 1.0)
                VentLinearProjection.forVoice(voice, view, projectionProgress) else finalProjection
            if (i % traceStride == 0) {
                projectionSpan += previousProjection.distanceTo(projection)
                previousProjection = projection
            }
            val source = projection.readout(orbit.x, orbit.y, orbit.z)
            check(source.isFinite() && abs(source) < 1.0) { "VENT clean linear readout exceeded headroom" }
            low1 += lowAlpha*(source-low1)
            low2 += lowAlpha*(low1-low2)
            val dc = low2-dcIn+dcPole*dcOut
            dcIn = low2; dcOut = dc
            val entry = .5-.5*windowCosine(t/.006)
            val release = .5+.5*windowCosine((t/seconds-.72)/.28)
            rawInternal[i] = (dc*entry*release*velocityLevel).toFloat()
            check(rawInternal[i].isFinite() && abs(rawInternal[i]) < 1f) { "VENT clean internal audio exceeded headroom" }
        }
        stopIfUnwanted()
        check(orbit.failures == 0) { "VENT clean voice required emergency integration resets" }
        val raw = Dsp.decimate(rawInternal, Dsp.RATE)
        stopIfUnwanted()
        var peak = 0.0; var sum = 0.0; var squares = 0.0
        for (i in raw.indices) {
            if (i % 8192 == 0) stopIfUnwanted()
            val sample = raw[i].toDouble()
            check(sample.isFinite() && abs(sample) < 1.0) { "VENT clean raw audio exceeded headroom" }
            peak = max(peak, abs(sample)); sum += sample; squares += sample*sample
        }
        val output = raw.copyOf()
        Dsp.levelTo(output, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        stopIfUnwanted()
        Dsp.fadeTail(output)
        check(output.all { it.isFinite() && abs(it) < 1f }) { "VENT clean voice produced invalid leveled audio" }
        return VentRender(Snip(output, 1, Dsp.RATE), raw,
            VentDiagnostics(peak, sqrt(squares/raw.size), sum/raw.size, orbit.maxState,
                sqrt(stateSquares/rawInternal.size), orbit.failures, resets, period, dt,
                "clean-linear", forceIntegral, projectionSpan), trace)
    }
}
