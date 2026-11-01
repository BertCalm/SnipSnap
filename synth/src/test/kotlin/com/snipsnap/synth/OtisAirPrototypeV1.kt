package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Frozen first-audition renderer. Preserve model-1 recipes exactly. */
internal object OtisAirPrototypeV1 {
    fun synthesize(
        voice: OtisPrototypeVoice,
        notes: List<OtisPrototypeNote>,
        settings: OtisAirSettings = OtisAirSettings(),
        seed: Int = 20261014,
        rate: Int = Dsp.RATE * Dsp.OVERSAMPLE,
    ): FloatArray {
        require(voice != OtisPrototypeVoice.ORGAN) { "Use the tonewheel prototype for ORGAN" }
        require(rate in 8_000..768_000) { "OTIS air render rate is unsupported: $rate" }
        require(notes.size <= 64) { "OTIS air prototype accepts at most 64 notes" }
        val limit = min(20_000.0, rate * .45)
        for (note in notes) {
            require(note.midi in 0..127 && hz(note.midi) < limit) { "OTIS air note is above the supported render band: ${note.midi}" }
            require(note.startSeconds.isFinite() && note.startSeconds in 0.0..30.0 &&
                note.gateSeconds.isFinite() && note.gateSeconds in 0.0..30.0 &&
                note.startSeconds + note.gateSeconds <= 30.0) { "OTIS air finite score must fit thirty seconds" }
            require(note.velocity.isFinite() && note.velocity in 0f..1f) { "OTIS air velocity must be finite and normalized" }
        }
        if (notes.isEmpty()) return FloatArray(0)
        val tail = releaseTime(voice, settings) * 16
        val seconds = notes.maxOf { it.startSeconds + it.gateSeconds } + tail
        val out = FloatArray(ceil(seconds * rate).toInt())
        val globalPhase = Random(seed xor 0x51EAD).nextDouble(0.0, 2 * PI)
        for (note in notes) {
            if (note.velocity == 0f || note.gateSeconds == 0.0) continue
            val start = ceil(note.startSeconds * rate).toInt()
            val end = ceil((note.startSeconds + note.gateSeconds + tail) * rate).toInt().coerceAtMost(out.size)
            // The source's identity does not change when the score list is reordered or a new
            // pipe is added. Steam's global pressure uses absolute score time below.
            val noteSeed = seed xor (note.midi * 1009) xor (note.startSeconds * 1_000_000).roundToInt()
            val random = Random(noteSeed)
            val wobbleRate = 1.5 + 3.5 * random.nextDouble()
            val wobblePhase = random.nextDouble(0.0, 2 * PI)
            val noise = Random(noteSeed xor 0x3B9ACA07)
            val velocity = note.velocity.toDouble()
            val effort = velocity * (.5 + .5 * settings.pressure)
            val work = velocity.pow(.65) * (.55 + .75 * settings.pressure)
            val attack = when (voice) {
                OtisPrototypeVoice.STEAM -> .025 - .017 * effort
                OtisPrototypeVoice.HARP -> .020 - .014 * effort
                else -> .035 - .030 * effort
            }
            val release = releaseTime(voice, settings)
            val root = hz(note.midi)
            val detune = (3 + 9 * effort) * settings.musette
            var phaseA = 0.0
            var phaseB = 0.0
            var fastNoise = 0.0
            var slowNoise = 0.0
            val fastA = 1 - exp(-2 * PI * min(8_000.0, rate * .30) / rate)
            val slowA = 1 - exp(-2 * PI * min(950.0, rate * .08) / rate)
            for (i in start until end) {
                if (i % 8192 == 0 && Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("OTIS air render no longer wanted")
                val absoluteTime = i.toDouble() / rate
                val t = absoluteTime - note.startSeconds
                val entry = 1 - exp(-min(t, note.gateSeconds) / attack)
                val leaving = if (t < note.gateSeconds) 1.0 else exp(-(t - note.gateSeconds) / release)
                val player = entry * leaving
                val grain = noise.nextDouble(-1.0, 1.0)
                fastNoise += fastA * (grain - fastNoise)
                slowNoise += slowA * (grain - slowNoise)
                val air = fastNoise - slowNoise
                val pressureRise = effort * exp(-t / .060)
                val cents: Double
                val pressure: Double
                when (voice) {
                    OtisPrototypeVoice.STEAM -> {
                        val global = sin(2 * PI * 1.7 * absoluteTime + globalPhase)
                        val local = sin(2 * PI * wobbleRate * t + wobblePhase)
                        cents = settings.steamInstability * ((2 + 10 * effort) * local + (1 + 5 * effort) * global) +
                            6 * effort * exp(-t / .025)
                        pressure = player * (1 + .035 * settings.steamInstability * global + .018 * local * settings.steamInstability)
                    }
                    OtisPrototypeVoice.HARP -> {
                        // A bent entrance rises into the saved note; it does not transpose the body.
                        val scoop = -(35 + 100 * effort) * settings.harpBend * exp(-t / (.070 - .040 * effort))
                        val vibrato = (1 - exp(-maxOf(0.0, t - .12) / .08)) *
                            settings.harpVibrato * (2 + 8 * effort) * sin(2 * PI * 5.5 * t)
                        cents = scoop + vibrato
                        pressure = player * (1 + .18 * pressureRise)
                    }
                    else -> {
                        cents = 0.0
                        pressure = player * (1 + settings.bellowsPulse * (.025 + .12 * effort) *
                            sin(2 * PI * (2.5 + 1.5 * effort) * t))
                    }
                }
                val frequency = root * 2.0.pow(cents / 1200)
                val source: Double
                when (voice) {
                    OtisPrototypeVoice.STEAM -> {
                        source = partial(phaseA, frequency, 1, 1.0, limit) +
                            partial(phaseA, frequency, 2, .10 + .10 * effort, limit) +
                            partial(phaseA, frequency, 3, .025 + .035 * effort, limit) +
                            settings.breath * (.012 + .035 * effort) * air
                        phaseA = advance(phaseA, frequency, rate)
                    }
                    OtisPrototypeVoice.HARP -> {
                        var reed = 0.0
                        for (harmonic in 1..6) {
                            val gain = when (harmonic) {
                                1 -> 1.0
                                2 -> .25 + .40 * effort + .16 * pressureRise +
                                    .25 * ((effort - .8) / .2).coerceIn(0.0, 1.0)
                                3 -> .20 + .20 * effort + .12 * pressureRise
                                4 -> .08 + .14 * effort + .10 * pressureRise
                                5 -> .06 + .10 * effort + .07 * pressureRise
                                else -> .025 + .05 * effort + .04 * pressureRise
                            }
                            reed += partial(phaseA, frequency, harmonic, gain, limit)
                        }
                        // Roughness is breath work at the reed, and leaves with that pressure.
                        source = reed + settings.breath * (.025 + .20 * effort * effort) * air *
                            (.65 + .35 * sin(phaseA))
                        phaseA = advance(phaseA, frequency, rate)
                    }
                    else -> {
                        val a = root * 2.0.pow(-detune / 2400)
                        val b = root * 2.0.pow(detune / 2400)
                        var reeds = 0.0
                        for (harmonic in 1..9 step 2) {
                            val gain = harmonic.toDouble().pow(-1.15) * (1 + .65 * effort * (harmonic - 1) / 8)
                            reeds += .5 * (partial(phaseA, a, harmonic, gain, limit) +
                                partial(phaseB, b, harmonic, gain, limit))
                        }
                        val snap = (.045 + .10 * effort) * exp(-t / (.015 - .010 * effort)) * air
                        source = reeds + snap
                        phaseA = advance(phaseA, a, rate)
                        phaseB = advance(phaseB, b, rate)
                    }
                }
                out[i] += (.32 * work * pressure * source).toFloat()
            }
        }
        return out
    }

    private fun hz(midi: Int): Double = 440 * 2.0.pow((midi - 69) / 12.0)
    private fun releaseTime(voice: OtisPrototypeVoice, settings: OtisAirSettings): Double = when (voice) {
        OtisPrototypeVoice.STEAM -> .035 + .105 * settings.release
        OtisPrototypeVoice.HARP -> .025 + .075 * settings.release
        else -> .025 + .065 * settings.release
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
