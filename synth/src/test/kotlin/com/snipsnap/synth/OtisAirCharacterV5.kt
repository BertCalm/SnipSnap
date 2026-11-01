package com.snipsnap.synth

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
internal object OtisAirCharacterV5 {
    const val PRESSURE_FLOOR = .40
    const val DRAW_SECONDS = .045
    const val RECOVER_SECONDS = .20
    const val REFERENCE_VELOCITY = .8f
    const val DEMAND_DEPTH = .30

    fun validate(notes: List<OtisPrototypeNote>, rate: Int) {
        require(rate in 8_000..768_000) { "unsupported character source rate $rate" }
        require(notes.isNotEmpty() && notes.size <= 32) { "character source requires 1..32 notes" }
        require(notes.all { 440 * 2.0.pow((it.midi - 69) / 12.0) < min(20_000.0, rate * .45) }) {
            "character source root is outside its render band"
        }
    }

    fun releaseSeconds(voice: OtisPrototypeVoice, settings: OtisAirSettings): Double = when (voice) {
        OtisPrototypeVoice.STEAM -> .035 + .105 * settings.release
        OtisPrototypeVoice.HARP -> .025 + .075 * settings.release
        OtisPrototypeVoice.SQUEEZE -> .025 + .065 * settings.release
        else -> error("Organ does not draw on the air controller")
    }

    fun attackSeconds(voice: OtisPrototypeVoice, settings: OtisAirSettings, velocity: Double): Double {
        val effort = velocity * (.5 + .5 * settings.pressure)
        return when (voice) {
            OtisPrototypeVoice.STEAM -> .025 - .017 * effort
            OtisPrototypeVoice.HARP -> .020 - .014 * effort
            OtisPrototypeVoice.SQUEEZE -> .035 - .030 * effort
            else -> error("Organ does not draw on the air controller")
        }
    }

    fun frameCount(voice: OtisPrototypeVoice, notes: List<OtisPrototypeNote>, settings: OtisAirSettings, rate: Int): Int =
        ceil((notes.maxOf { it.startSeconds + it.gateSeconds } + 16 * releaseSeconds(voice, settings)) * rate).toInt()

    fun trace(voice: OtisPrototypeVoice, notes: List<OtisPrototypeNote>, settings: OtisAirSettings,
        frames: Int, strength: Float, rate: Int): OtisPhrasePressureTrace {
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
        return OtisPhrasePressureTrace(pressure, demand, target, stride, rate)
    }

    fun synthesize(request: OtisPrototypeRequest, rate: Int): FloatArray {
        require(request.voice != OtisPrototypeVoice.ORGAN)
        validate(request.notes, rate)
        val frames = frameCount(request.voice, request.notes, request.air, rate)
        val pressure = trace(request.voice, request.notes, request.air, frames, request.sharedPressure, rate)
        return when (request.voice) {
            OtisPrototypeVoice.STEAM -> OtisSteamCharacterV5.synthesize(request.notes, request.air, request.seed, rate, pressure)
            OtisPrototypeVoice.HARP -> OtisHarpCharacterV5.synthesize(request.notes, request.air, request.seed, rate, pressure)
            OtisPrototypeVoice.SQUEEZE -> OtisSqueezeCharacterV5.synthesize(request.notes, request.air, request.seed, rate, pressure)
            else -> error("Organ does not draw on the air controller")
        }.also { check(it.size == frames) { "character source and pressure clock disagree" } }
    }
}
