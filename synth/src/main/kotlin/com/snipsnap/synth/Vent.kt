package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

enum class VentVoice { ROUND, WIRE, TEAR, PULSE, COIL, PLUME }

/** A sparse trajectory record at playback time; these coordinates are the actual sound source. */
data class VentTrace(val seconds: Double, val x: Double, val y: Double, val z: Double)

data class VentDiagnostics(
    val rawPeak: Double, val rawRms: Double, val rawDc: Double,
    val maxState: Double, val stateRms: Double,
    val integrationFailures: Int, val leashResets: Int,
    val measuredOrbitPeriod: Double, val modelTimeStep: Double,
    val signalPath: String = "voice-shaped",
    /** Model-time integral of the magnitude of added gesture force; excludes LEASH. */
    val externalForceIntegral: Double = 0.0,
    /** Distance traveled by the linear readout direction on its unit sphere. */
    val projectionSpan: Double = 0.0,
)

/** Raw is 44.1 kHz, after antialias filtering and before the shared melodic level convention. */
data class VentRender(
    val snip: Snip, val raw: FloatArray,
    val diagnostics: VentDiagnostics, val trace: List<VentTrace>,
)

/** Fixed conditioning for the listening experiment, separate from model-1 voice recipes. */
internal object VentIsolationSpec {
    const val X_WEIGHT = .65 / 12.0
    const val Y_WEIGHT = .15 / 12.0
    const val Z_WEIGHT = .30 / 60.0
    const val GAIN = .25
    const val FILTER_CUTOFF_HZ = 10_000.0
    const val DC_CUTOFF_HZ = 12.0
    const val ATTACK_SECONDS = .006
    const val RELEASE_START_FRACTION = .72
    const val RELEASE_FRACTION = .28
    const val VELOCITY_EXPONENT = .35
    const val DEFAULT_SECONDS = 8.0
    val SEED_VOICE = VentVoice.ROUND

    /** A linear state projection: no state-dependent normalization, saturation or Z compression. */
    fun readout(x: Double, y: Double, z: Double): Double =
        GAIN * (X_WEIGHT * x + Y_WEIGHT * y + Z_WEIGHT * z)
}

/**
 * First-listening Rössler instrument. The source is an RK4-integrated trajectory:
 * dx=-y-z, dy=x+a*y, dz=b+z*(x-c). No oscillator supplies audio underneath it.
 * CHAOS follows a=.12.. .25 and c=3..8.8 with b=.2. These regions range from a
 * regular orbit to irregular folding; the knob is not a universal chaos metric.
 *
 * A seeded 300+ model-unit preroll measures the mean positive-X crossing period.
 * Scaling that period to the requested note gives free motion a nominal pitch.
 * LEASH applies a smooth X-only correction over 8% of each requested cycle;
 * Y/Z retain their history. It encourages pitch without imposing full state
 * periodicity, and high CHAOS can still produce other pitches and tearing.
 *
 * VIEW rotates the X/Y readout and exposes compressed Z spikes. Voice-specific
 * projection/drive and a two-pole DAMP filter shape that same source. PUSH and
 * velocity displace the preroll state, while velocity also changes readout drive.
 * A finite envelope makes each trajectory a sample; this prototype has no loops.
 *
 * Integration uses real model-time steps <=.02, with no derivative clipping or
 * nonlinear state damping in the normal range. A nonfinite/out-of-bounds state
 * triggers an explicitly counted emergency reset. Output saturation never feeds
 * back into the ODE. Model 1 and a saved seed reproduce the render exactly.
 */
object Vent {
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val DEFAULT_MIDI = 60
    const val MODEL = 1
    const val MODEL_VERSION = MODEL
    const val CLEAN_MODEL = 2
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val MAX_STEP = .02
    private const val STATE_BOUND = 256.0

    fun supportsModel(model: Int): Boolean = model == MODEL || model == CLEAN_MODEL

    /** Historical voices keep their original model; clean voices have separate saved recipes. */
    fun defaultModelFor(voice: VentVoice): Int = when (voice) {
        VentVoice.ROUND, VentVoice.WIRE, VentVoice.TEAR -> MODEL
        VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME -> CLEAN_MODEL
    }

    fun supportsVoiceModel(voice: VentVoice, model: Int): Boolean = model == defaultModelFor(voice)

    fun macrosFor(voice: VentVoice): List<MacroSpec> {
        val d = when (voice) {
            VentVoice.ROUND -> floatArrayOf(.12f, .85f, .18f, .72f, .25f, .68f)
            VentVoice.WIRE -> floatArrayOf(.45f, .65f, .54f, .28f, .45f, .68f)
            VentVoice.TEAR -> floatArrayOf(.85f, .20f, .78f, .12f, .78f, .68f)
            VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME -> floatArrayOf(.65f, .55f, .5f, .25f, .6f, .74f)
        }
        return listOf("CHAOS", "LEASH", "VIEW", "DAMP", "PUSH", "LENGTH")
            .mapIndexed { i, name -> MacroSpec(name, d[i], .35f) }
    }

    fun defaultsFor(voice: VentVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun defaults(voice: VentVoice): Map<String, Float> = defaultsFor(voice)

    fun drumClassFor(voice: VentVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        settled(voice, macros)
        return DrumClass.TONAL
    }

    fun scramble(voice: VentVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = defaultsFor(voice) + if (near is VentPatch && near.voice == voice) near.macros else emptyMap()
        val spread = temperature.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .35f
        return base.mapValues { (_, value) -> (value + (random.nextFloat() * 2f - 1f) * spread).coerceIn(0f, 1f) }
    }

    private fun settled(voice: VentVoice, macros: Map<String, Float>): Map<String, Float> {
        val result = defaultsFor(voice).toMutableMap()
        for ((key, value) in macros) {
            require(key in result) { "VENT has no macro $key" }
            require(value.isFinite()) { "VENT $key must be finite" }
            result[key] = value.coerceIn(0f, 1f)
        }
        return result
    }

    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("VENT render no longer wanted")
    }

    private class Orbit(val a: Double, val b: Double, val c: Double, random: Random) {
        var x = random.nextDouble(-.8, .8)
        var y = random.nextDouble(-1.8, -.7)
        var z = random.nextDouble(.04, .15)
        var maxState = 0.0
        var failures = 0

        /** Scalars avoid per-sample allocation; all four stages evaluate the unmodified ODE. */
        fun step(dt: Double) {
            val k1x = -y-z; val k1y = x+a*y; val k1z = b+z*(x-c)
            val x2 = x+.5*dt*k1x; val y2 = y+.5*dt*k1y; val z2 = z+.5*dt*k1z
            val k2x = -y2-z2; val k2y = x2+a*y2; val k2z = b+z2*(x2-c)
            val x3 = x+.5*dt*k2x; val y3 = y+.5*dt*k2y; val z3 = z+.5*dt*k2z
            val k3x = -y3-z3; val k3y = x3+a*y3; val k3z = b+z3*(x3-c)
            val x4 = x+dt*k3x; val y4 = y+dt*k3y; val z4 = z+dt*k3z
            val k4x = -y4-z4; val k4y = x4+a*y4; val k4z = b+z4*(x4-c)
            x += dt/6.0*(k1x+2*k2x+2*k3x+k4x)
            y += dt/6.0*(k1y+2*k2y+2*k3y+k4y)
            z += dt/6.0*(k1z+2*k2z+2*k3z+k4z)
            measure()
        }

        fun measure() {
            val peak = max(abs(x), max(abs(y), abs(z)))
            if (!peak.isFinite() || peak > STATE_BOUND || z < -.01) {
                failures++
                x = 0.0; y = -c; z = b/c
            } else {
                maxState = max(maxState, peak)
            }
        }
    }

    fun render(
        voice: VentVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, seed: Int = 0, model: Int = defaultModelFor(voice),
    ): Snip = renderDetailed(voice, macros, midi, velocity, seed, model).snip

    fun renderDetailed(
        voice: VentVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, seed: Int = 0, model: Int = defaultModelFor(voice),
    ): VentRender {
        require(supportsModel(model)) { "unsupported VENT model $model" }
        require(supportsVoiceModel(voice, model)) { "VENT $voice does not support model $model" }
        return if (model == CLEAN_MODEL) VentModel2.render(voice, settled(voice, macros), midi, velocity, seed)
            else renderCore(voice, macros, midi, velocity, seed, model)
    }

    /**
     * Controlled audition of the same orbit and X-only LEASH without voice coloring.
     * [VentRender.raw] has one fixed linear projection, gain, filter and envelope;
     * [VentRender.snip] additionally follows the normal shared melodic level convention.
     * This path is an experiment, not a new saved production model or voice.
     */
    internal fun renderIsolation(
        chaos: Float, leash: Float, midi: Int = DEFAULT_MIDI, velocity: Float = .8f,
        seed: Int = 17, seconds: Double = VentIsolationSpec.DEFAULT_SECONDS,
    ): VentRender {
        require(chaos.isFinite() && chaos in 0f..1f) { "VENT isolation CHAOS must be 0..1" }
        require(leash.isFinite() && leash in 0f..1f) { "VENT isolation LEASH must be 0..1" }
        require(seconds.isFinite() && seconds in .02..30.0) { "VENT isolation duration must be .02..30 seconds" }
        return renderCore(VentIsolationSpec.SEED_VOICE,
            mapOf("CHAOS" to chaos, "LEASH" to leash, "PUSH" to 0f),
            midi, velocity, seed, MODEL, seconds)
    }

    private fun renderCore(
        voice: VentVoice, macros: Map<String, Float>, midi: Int,
        velocity: Float, seed: Int, model: Int, isolationSeconds: Double? = null,
    ): VentRender {
        require(midi in MIDI_MIN..MIDI_MAX) { "VENT MIDI must be $MIDI_MIN..$MIDI_MAX" }
        require(velocity.isFinite() && velocity in 0f..1f) { "VENT velocity must be 0..1" }
        require(supportsModel(model)) { "unsupported VENT model $model" }
        stopIfUnwanted()
        val m = settled(voice, macros)
        val chaos = m.getValue("CHAOS").toDouble()
        val leash = m.getValue("LEASH").toDouble()
        val view = m.getValue("VIEW").toDouble()
        val damp = m.getValue("DAMP").toDouble()
        val push = m.getValue("PUSH").toDouble()
        val vel = velocity.toDouble()
        val seconds = isolationSeconds ?: (.18 * 30.0.pow(m.getValue("LENGTH").toDouble()))
        val isolation = isolationSeconds != null
        val frames = max(1, (seconds * Dsp.RATE).roundToInt())
        val random = Random(Dsp.seedFor("VENT", model, voice, seed))
        val orbit = Orbit(.12+.13*chaos, .2, 3.0+5.8*chaos, random)

        // Note is deliberately absent from the seed so transposition starts on the same orbit.
        val warmSteps = ((300.0 + random.nextDouble()*24.0)/MAX_STEP).roundToInt()
        var modelTime = 0.0
        var lastCrossing = 0.0
        val crossings = ArrayList<Double>()
        for (i in 0 until warmSteps) {
            if (i % 2048 == 0) stopIfUnwanted()
            val oldX = orbit.x
            orbit.step(MAX_STEP)
            modelTime += MAX_STEP
            if (oldX < 0.0 && orbit.x >= 0.0 && orbit.y < 0.0) {
                lastCrossing = modelTime-MAX_STEP + MAX_STEP*(-oldX)/(orbit.x-oldX)
                if (modelTime >= 150.0) crossings.add(lastCrossing)
            }
        }
        check(crossings.size >= 8) { "VENT orbit has insufficient calibration crossings" }
        val period = (crossings.last()-crossings.first())/(crossings.size-1)
        check(period in 4.0..7.5) { "VENT measured orbit period outside the calibrated range: $period" }
        val hz = Keys.midiHz(midi).toDouble()
        val step = period*hz/INTERNAL_RATE
        val substeps = max(1, ceil(step/MAX_STEP).toInt())
        val dt = step/substeps
        var phase = ((modelTime-lastCrossing)/period) % 1.0
        val phaseStep = hz/INTERNAL_RATE

        // A finite strike changes the actual trajectory, before any audio is read from it.
        orbit.x += (1.0+4.0*push)*push*vel
        orbit.y -= (.25+2.0*push)*push*vel
        orbit.z += .28*push*vel*vel
        orbit.measure()
        val theta = PI*(view-.35)
        val readX = cos(theta)
        val readY = sin(theta)
        val zWeight = when (voice) {
            VentVoice.ROUND -> .13; VentVoice.WIRE -> .62; VentVoice.TEAR -> 1.28
            else -> error("clean VENT voice requires model 2")
        } * (.15+1.4*view)
        val drive = when (voice) {
            VentVoice.ROUND -> .75; VentVoice.WIRE -> 1.7; VentVoice.TEAR -> 2.7
            else -> error("clean VENT voice requires model 2")
        } * (.70+.40*vel)
        val zScale = ln(1.0+3.0*orbit.c)
        // Bound the endpoints before mapping, preserving DAMP activity at high notes.
        val lowCutoff = (hz*.85).coerceAtLeast(70.0)
        val highCutoff = (hz*.85*42.0).coerceIn(lowCutoff, 14_000.0)
        val cutoff = if (isolation) VentIsolationSpec.FILTER_CUTOFF_HZ
            else lowCutoff*(highCutoff/lowCutoff).pow(1.0-damp)
        val lowAlpha = 1.0-exp(-2.0*PI*cutoff/INTERNAL_RATE)
        val dcPole = exp(-2.0*PI*(if (isolation) VentIsolationSpec.DC_CUTOFF_HZ else 12.0)/INTERNAL_RATE)
        var low1 = 0.0; var low2 = 0.0
        var dcIn = 0.0; var dcOut = 0.0
        var correction = 0.0; var correctionAge = 1.0
        var resets = 0
        var stateSquares = 0.0
        val rawInternal = FloatArray(frames*Dsp.OVERSAMPLE)
        val trace = ArrayList<VentTrace>()
        val traceStride = INTERNAL_RATE/200
        val attack = if (isolation) VentIsolationSpec.ATTACK_SECONDS
            else when (voice) {
                VentVoice.ROUND -> .006; VentVoice.WIRE -> .004; VentVoice.TEAR -> .003
                else -> error("clean VENT voice requires model 2")
            }
        for (i in rawInternal.indices) {
            if (i % 2048 == 0) stopIfUnwanted()
            repeat(substeps) { orbit.step(dt) }
            phase += phaseStep
            if (phase >= 1.0) {
                phase -= 1.0
                if (leash > 0.0) {
                    correction = -orbit.x*leash*leash
                    correctionAge = 0.0
                    resets++
                }
            }
            if (correctionAge < 1.0) {
                val nextAge = (correctionAge+phaseStep/.08).coerceAtMost(1.0)
                orbit.x += correction*.5*(cos(PI*correctionAge)-cos(PI*nextAge))
                correctionAge = nextAge
                orbit.measure()
            }
            stateSquares += (orbit.x*orbit.x+orbit.y*orbit.y+orbit.z*orbit.z)/3.0
            if (i % traceStride == 0) trace.add(VentTrace(i.toDouble()/INTERNAL_RATE, orbit.x, orbit.y, orbit.z))
            val source = if (isolation) VentIsolationSpec.readout(orbit.x, orbit.y, orbit.z) else {
                val projection = (readX*orbit.x+readY*orbit.y)/orbit.c + zWeight*ln(1.0+max(0.0, orbit.z))/zScale
                .32*tanh(drive*projection)
            }
            if (isolation) check(source.isFinite() && abs(source) < 1.0) { "VENT isolation readout exceeded headroom" }
            low1 += lowAlpha*(source-low1)
            low2 += lowAlpha*(low1-low2)
            val dc = low2-dcIn+dcPole*dcOut
            dcIn = low2; dcOut = dc
            val t = i.toDouble()/INTERNAL_RATE
            val entry = (t/attack).coerceIn(0.0, 1.0).let { .5-.5*cos(PI*it) }
            val releasePosition = if (isolation)
                (t/seconds-VentIsolationSpec.RELEASE_START_FRACTION)/VentIsolationSpec.RELEASE_FRACTION
                else (t/seconds-.72)/.28
            val release = releasePosition.coerceIn(0.0, 1.0).let { .5+.5*cos(PI*it) }
            val velocityExponent = if (isolation) VentIsolationSpec.VELOCITY_EXPONENT else .35
            rawInternal[i] = (dc*entry*release*vel.pow(velocityExponent)).toFloat()
            if (isolation) check(rawInternal[i].isFinite() && abs(rawInternal[i]) < 1f) {
                "VENT isolation produced clipped internal audio"
            }
        }
        stopIfUnwanted()
        val raw = Dsp.decimate(rawInternal, Dsp.RATE)
        stopIfUnwanted()
        var peak = 0.0; var sum = 0.0; var squares = 0.0
        for (i in raw.indices) {
            if (i % 8192 == 0) stopIfUnwanted()
            val sample = raw[i].toDouble()
            check(sample.isFinite()) { "VENT produced nonfinite audio" }
            if (isolation) check(abs(sample) < 1.0) { "VENT isolation produced clipped raw audio" }
            peak = max(peak, abs(sample)); sum += sample; squares += sample*sample
        }
        val output = raw.copyOf()
        Dsp.levelTo(output, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        stopIfUnwanted()
        Dsp.fadeTail(output)
        if (isolation) {
            check(orbit.failures == 0) { "VENT isolation required emergency integration resets" }
            check(output.all { it.isFinite() && abs(it) < 1f }) { "VENT isolation produced invalid leveled audio" }
        }
        val diagnostics = VentDiagnostics(peak, sqrt(squares/raw.size), sum/raw.size,
            orbit.maxState, sqrt(stateSquares/rawInternal.size), orbit.failures, resets, period, dt,
            if (isolation) "isolation-linear" else "voice-shaped")
        return VentRender(Snip(output, 1, Dsp.RATE), raw, diagnostics, trace)
    }
}
