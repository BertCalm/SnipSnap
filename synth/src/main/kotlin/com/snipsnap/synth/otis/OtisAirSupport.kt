package com.snipsnap.synth.otis

import com.snipsnap.synth.*

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Model 5 character sources share a bounded player budget before source summation.
 * This is the accepted Shared control analogy, not a physical air reservoir. The controller
 * follows the finite reference key envelopes at approximately 1 kHz; oscillators stay audio-rate.
 * Its cost is O(notes * control frames), with no solver or geometry to cache. Sources allocate
 * their finite buffers once and reuse per-note harmonic arrays inside the sample loop.
 */
internal object OtisAirSupport {
    const val PRESSURE_FLOOR = .40
    const val DRAW_SECONDS = .045
    const val RECOVER_SECONDS = .20
    const val REFERENCE_VELOCITY = .8f
    const val DEMAND_DEPTH = .30

    fun validate(notes: List<OtisNote>, rate: Int) {
        require(rate in 8_000..768_000) { "unsupported character source rate $rate" }
        require(notes.isNotEmpty() && notes.size <= 32) { "character source requires 1..32 notes" }
        require(notes.all { 440 * 2.0.pow((it.midi - 69) / 12.0) < min(20_000.0, rate * .45) }) {
            "character source root is outside its render band"
        }
    }

    fun releaseSeconds(voice: OtisVoice, settings: OtisAirProfile): Double = when (voice) {
        OtisVoice.STEAM -> .035 + .105 * settings.release
        OtisVoice.HARP -> .025 + .075 * settings.release
        OtisVoice.SQUEEZE -> .025 + .065 * settings.release
        else -> error("Organ does not draw on the air controller")
    }

    fun attackSeconds(voice: OtisVoice, settings: OtisAirProfile, velocity: Double): Double {
        val effort = velocity * (.5 + .5 * settings.pressure)
        return when (voice) {
            OtisVoice.STEAM -> .025 - .017 * effort
            OtisVoice.HARP -> .020 - .014 * effort
            OtisVoice.SQUEEZE -> .035 - .030 * effort
            else -> error("Organ does not draw on the air controller")
        }
    }

    fun frameCount(voice: OtisVoice, notes: List<OtisNote>, settings: OtisAirProfile, rate: Int): Int =
        ceil((notes.maxOf { it.startSeconds + it.gateSeconds } + 16 * releaseSeconds(voice, settings)) * rate).toInt()

    fun trace(voice: OtisVoice, notes: List<OtisNote>, settings: OtisAirProfile,
        frames: Int, strength: Float, rate: Int): OtisPressureTrace {
        validate(notes, rate)
        require(frames > 0 && strength.isFinite() && strength in 0f..1f)
        val stride = (rate / 1000.0).roundToInt().coerceAtLeast(1)
        val count = ceil(frames.toDouble() / stride).toInt() + 1
        val pressure = DoubleArray(count)
        val demand = DoubleArray(count)
        val target = DoubleArray(count)
        pressure[0] = 1.0
        val dt = stride.toDouble() / rate
        val release = releaseSeconds(voice, settings)
        for (index in 0 until count) {
            val time = index * dt
            demand[index] = notes.sumOf { note ->
                val age = time - note.startSeconds
                if (age <= 0 || note.velocity == 0f) 0.0 else {
                    val velocity = note.velocity.toDouble()
                    val attack = attackSeconds(voice, settings, velocity)
                    val entry = 1 - exp(-min(age, note.gateSeconds) / attack)
                    val leaving = if (age < note.gateSeconds) 1.0 else exp(-(age - note.gateSeconds) / release)
                    velocity / REFERENCE_VELOCITY.toDouble() * entry * leaving
                }
            }
            target[index] = (1 - DEMAND_DEPTH * strength * max(0.0, demand[index] - 1))
                .coerceIn(PRESSURE_FLOOR, 1.0)
            if (index < count - 1) {
                val tau = if (target[index] < pressure[index]) DRAW_SECONDS else RECOVER_SECONDS
                // Exact one-pole step is a convex combination of two bounded values.
                pressure[index + 1] = target[index] + (pressure[index] - target[index]) * exp(-dt / tau)
            }
        }
        return OtisPressureTrace(pressure, demand, target, stride, rate)
    }

}
