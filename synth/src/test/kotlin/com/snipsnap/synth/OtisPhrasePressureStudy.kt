package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.math.sin

/** Control-rate descriptors, independent of oscillator phase and the output stage. */
internal data class OtisPhrasePressureTrace(
    val pressure: DoubleArray,
    val demand: DoubleArray,
    val target: DoubleArray,
    val stride: Int,
    val rate: Int,
) {
    val controlRateHz: Double get() = rate.toDouble() / stride

    fun pressureAt(seconds: Double): Double = pressureAtFrame(seconds * rate)

    internal fun pressureAtFrame(frame: Double): Double {
        val position = (frame / stride).coerceIn(0.0, pressure.lastIndex.toDouble())
        val index = position.toInt().coerceAtMost(pressure.lastIndex - 1)
        val fraction = position - index
        return pressure[index] + (pressure[index + 1] - pressure[index]) * fraction
    }
}

/** Both buffers are raw mono at [rate]; the caller conditions and decimates each once. */
internal data class OtisPhrasePressureResult(
    val independent: FloatArray,
    val shared: FloatArray,
    val trace: OtisPhrasePressureTrace,
    val rate: Int,
    val strength: Float,
    val notes: List<OtisPrototypeNote>,
    val settings: OtisAirSettings,
    val seed: Int,
    val requestedControlRateHz: Int,
) {
    /** Complete experimental recipe and scalar evidence for a standalone study manifest. */
    fun manifest(): Map<String, Any> = linkedMapOf(
        "format" to "snipsnap-otis-phrase-pressure-study",
        "experimentVersion" to 1,
        "voice" to "SQUEEZE",
        "baseline" to "Accepted Otis SQUEEZE model-3 raw source",
        "analogy" to "A shared player budget, not a physical bellows or acoustic energy simulation",
        "seed" to seed,
        "strength" to strength,
        "rate" to rate,
        "frames" to shared.size,
        "requestedControlRateHz" to requestedControlRateHz,
        "controlStride" to trace.stride,
        "controlRateHz" to trace.controlRateHz,
        "pressureFloor" to OtisPhrasePressureStudy.PRESSURE_FLOOR,
        "drawSeconds" to OtisPhrasePressureStudy.DRAW_SECONDS,
        "recoverSeconds" to OtisPhrasePressureStudy.RECOVER_SECONDS,
        "referenceVelocity" to OtisPhrasePressureStudy.REFERENCE_VELOCITY,
        "air" to settings.asMap(),
        "notes" to notes.map { linkedMapOf("midi" to it.midi, "startSeconds" to it.startSeconds,
            "gateSeconds" to it.gateSeconds, "velocity" to it.velocity) },
        "sourceChange" to "Available pressure scales the player envelope and upper-harmonic opening before source summation",
        "pitchPolicy" to "Accepted oscillator frequencies, detuning and phase increments are unchanged",
        "diagnostics" to linkedMapOf(
            "minimumPressure" to trace.pressure.min(),
            "maximumDemand" to trace.demand.max(),
            "maximumPressure" to trace.pressure.max(),
            "finalPressure" to trace.pressure.last(),
            "independentPeak" to independent.maxOf { kotlin.math.abs(it) },
            "sharedPeak" to shared.maxOf { kotlin.math.abs(it) },
        ),
    )
}

/**
 * Separate COUPLE experiment: a second key draws on the first key's available player pressure.
 * Requested load is the accepted note envelope, summed in units of one .8-velocity held voice. Excess
 * demand lowers a bounded target; an exact one-pole step follows it, faster on draw than refill.
 * This is a musical control-rate analogy, not a physical reed/bellows or energy-conservation claim.
 *
 * Only the SQUEEZE oscillator mechanics from OtisAirPrototype are repeated below, keeping their
 * seed, noise, pitch, unequal reed ranks and local motion. The accepted source is not edited.
 * Pressure changes the source's harmonic opening as well as its player envelope, so this is not
 * an effect on a finished WAV. The experiment is not part of the prototype model dispatcher.
 */
internal object OtisPhrasePressureStudy {
    const val PRESSURE_FLOOR = .40
    const val DRAW_SECONDS = .045
    const val RECOVER_SECONDS = .20
    const val REFERENCE_VELOCITY = .8f
    private const val DEMAND_DEPTH = .30

    fun notes(): List<OtisPrototypeNote> = listOf(
        OtisPrototypeNote(60, gateSeconds = 1.8, velocity = REFERENCE_VELOCITY),
        OtisPrototypeNote(63, startSeconds = .45, gateSeconds = .65, velocity = REFERENCE_VELOCITY),
    )

    fun render(
        notes: List<OtisPrototypeNote> = notes(),
        strength: Float = .85f,
        settings: OtisAirSettings = OtisAirSettings(),
        seed: Int = 20261014,
        rate: Int = Dsp.RATE * Dsp.OVERSAMPLE,
        controlRateHz: Int = 1000,
    ): OtisPhrasePressureResult {
        require(notes.isNotEmpty() && notes.size <= 32) { "phrase pressure study requires 1..32 finite notes" }
        require(strength.isFinite() && strength in 0f..1f) { "phrase pressure strength must be finite and normalized" }
        require(rate in 8_000..768_000 && controlRateHz in 250..4000 && controlRateHz <= rate) {
            "phrase pressure study clock is unsupported"
        }
        val independent = OtisAirPrototype.synthesize(OtisPrototypeVoice.SQUEEZE, notes, settings, seed, rate)
        val trace = pressureTrace(notes, settings, independent.size, strength, rate, controlRateHz)
        val shared = if (strength == 0f || trace.pressure.all { it == 1.0 }) independent.copyOf()
            else source(notes, settings, seed, rate, independent.size, trace)
        return OtisPhrasePressureResult(independent, shared, trace, rate, strength, notes.toList(), settings,
            seed, controlRateHz)
    }

    internal fun pressureTrace(
        notes: List<OtisPrototypeNote>, settings: OtisAirSettings, frames: Int, strength: Float,
        rate: Int, controlRateHz: Int,
    ): OtisPhrasePressureTrace {
        val stride = (rate.toDouble() / controlRateHz).roundToInt().coerceAtLeast(1)
        val count = ceil(frames.toDouble() / stride).toInt().coerceAtLeast(1) + 1
        val pressure = DoubleArray(count)
        val demand = DoubleArray(count)
        val target = DoubleArray(count)
        pressure[0] = 1.0
        val dt = stride.toDouble() / rate
        val release = .025 + .065 * settings.release
        for (index in 0 until count) {
            val time = index * dt
            demand[index] = notes.sumOf { note ->
                val age = time - note.startSeconds
                if (age <= 0 || note.velocity == 0f) 0.0 else {
                    val effort = note.velocity.toDouble() * (.5 + .5 * settings.pressure)
                    val attack = .035 - .030 * effort
                    val entry = 1 - exp(-min(age, note.gateSeconds) / attack)
                    val leaving = if (age < note.gateSeconds) 1.0 else exp(-(age - note.gateSeconds) / release)
                    (note.velocity.toDouble() / REFERENCE_VELOCITY.toDouble()) * entry * leaving
                }
            }
            target[index] = (1 - DEMAND_DEPTH * strength * max(0.0, demand[index] - 1)).coerceIn(PRESSURE_FLOOR, 1.0)
            if (index < count - 1) {
                val tau = if (target[index] < pressure[index]) DRAW_SECONDS else RECOVER_SECONDS
                // The multiplier is in (0,1); pressure stays between its old value and the
                // bounded target for every step. No audio limiter supplies stability.
                pressure[index + 1] = (target[index] + (pressure[index] - target[index]) * exp(-dt / tau))
                    .coerceIn(PRESSURE_FLOOR, 1.0)
            }
        }
        return OtisPhrasePressureTrace(pressure, demand, target, stride, rate)
    }

    private fun source(notes: List<OtisPrototypeNote>, settings: OtisAirSettings, seed: Int,
        rate: Int, frames: Int, trace: OtisPhrasePressureTrace): FloatArray {
        val out = FloatArray(frames)
        val limit = min(20_000.0, rate * .45)
        val release = .025 + .065 * settings.release
        for (note in notes) {
            if (note.velocity == 0f) continue
            val start = ceil(note.startSeconds * rate).toInt()
            val end = ceil((note.startSeconds + note.gateSeconds + release * 16) * rate).toInt().coerceAtMost(frames)
            val noteSeed = seed xor (note.midi * 1009) xor (note.startSeconds * 1_000_000).roundToInt()
            val noise = Random(noteSeed xor 0x3B9ACA07)
            val velocity = note.velocity.toDouble()
            val effort = velocity * (.5 + .5 * settings.pressure)
            val work = velocity.pow(.65) * (.55 + .75 * settings.pressure)
            val attack = .035 - .030 * effort
            val root = 440 * 2.0.pow((note.midi - 69) / 12.0)
            val detune = (7 + 17 * effort) * settings.musette
            val a = root * 2.0.pow(-detune / 2400)
            val b = root * 2.0.pow(detune / 2400)
            var phaseA = 0.0
            var phaseB = 0.0
            var fastNoise = 0.0
            var slowNoise = 0.0
            val fastA = 1 - exp(-2 * PI * min(8_000.0, rate * .30) / rate)
            val slowA = 1 - exp(-2 * PI * min(950.0, rate * .08) / rate)
            for (frame in start until end) {
                if (frame % 8192 == 0 && Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("OTIS pressure experiment no longer wanted")
                val t = frame.toDouble() / rate - note.startSeconds
                val entry = 1 - exp(-min(t, note.gateSeconds) / attack)
                val leaving = if (t < note.gateSeconds) 1.0 else exp(-(t - note.gateSeconds) / release)
                val player = entry * leaving
                val grain = noise.nextDouble(-1.0, 1.0)
                fastNoise += fastA * (grain - fastNoise)
                slowNoise += slowA * (grain - slowNoise)
                val air = fastNoise - slowNoise
                val bellowsWave = sin(2 * PI * (2.5 + 1.5 * effort) * t) + .20 * sin(2 * PI * .57 * t)
                val available = trace.pressureAtFrame(frame.toDouble())
                val pressure = player * (1 + settings.bellowsPulse * (.07 + .24 * effort) * bellowsWave) * available
                var reeds = 0.0
                for (harmonic in 1..11 step 2) {
                    val opening = 1 + .12 * settings.bellowsPulse * bellowsWave * (harmonic - 1) / 10
                    val sharedOpening = if (harmonic == 1 || available == 1.0) opening else
                        opening * (.35 + .65 * available * available)
                    val gainA = harmonic.toDouble().pow(-1.02) * (1 + .85 * effort * (harmonic - 1) / 10)
                    val gainB = harmonic.toDouble().pow(-1.18) * (1 + .45 * effort * (harmonic - 1) / 10)
                    reeds += sharedOpening * (.62 * partial(phaseA, a, harmonic, gainA, limit) +
                        .38 * partial(phaseB, b, harmonic, gainB, limit))
                }
                val snap = (.14 + .22 * effort) * exp(-t / (.025 - .012 * effort)) * (air + .3 * sin(2 * phaseA))
                val source = reeds + snap
                phaseA = advance(phaseA, a, rate)
                phaseB = advance(phaseB, b, rate)
                out[frame] += (.32 * work * pressure * source).toFloat()
            }
        }
        return out
    }

    private fun advance(phase: Double, hz: Double, rate: Int): Double {
        val next = phase + 2 * PI * hz / rate
        return if (next >= 2 * PI) next - 2 * PI else next
    }

    private fun partial(phase: Double, hz: Double, harmonic: Int, gain: Double, limit: Double): Double {
        val ratio = hz * harmonic / limit
        val band = when {
            ratio >= 1 -> 0.0
            ratio <= .92 -> 1.0
            else -> .5 + .5 * cos(PI * (ratio - .92) / .08)
        }
        return gain * band * sin(harmonic * phase)
    }
}
