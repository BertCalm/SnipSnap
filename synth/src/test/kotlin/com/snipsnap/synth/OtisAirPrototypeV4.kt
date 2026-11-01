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

/**
 * Model 4 source optimization of the frozen model-3 pipe and reed sketches.
 * Each rank computes one sine/cosine pair from its unchanged current phase, then derives
 * its harmonic sines by angle addition. The recurrence restarts each sample, so it cannot
 * accumulate tuning drift. Reusable per-note buffers avoid allocation in the sample loop.
 * Gains, bandwidth taper, phase advancement, pressure clocks and random draws retain the
 * model-3 formulas. The caller owns band limiting, decimation and DC conditioning.
 */
object OtisAirPrototypeV4 {
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
            val wobbleRate = .8 + 2.0 * random.nextDouble()
            val wobblePhase = random.nextDouble(0.0, 2 * PI)
            val driftPhase = random.nextDouble(0.0, 2 * PI)
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
            val detune = (7 + 17 * effort) * settings.musette
            var phaseA = 0.0
            var phaseB = 0.0
            val harmonicsA = DoubleArray(12)
            val harmonicsB = DoubleArray(12)
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
                val bellowsWave = sin(2 * PI * (2.5 + 1.5 * effort) * t) +
                    .20 * sin(2 * PI * .57 * t)
                val cents: Double
                val pressure: Double
                when (voice) {
                    OtisPrototypeVoice.STEAM -> {
                        val global = sin(2 * PI * 1.7 * absoluteTime + globalPhase)
                        val local = sin(2 * PI * wobbleRate * t + wobblePhase +
                            .65 * sin(2 * PI * .63 * t + driftPhase))
                        val drift = sin(2 * PI * .39 * t + driftPhase)
                        val depth = settings.steamInstability.toDouble().pow(.55) * (44 + 20 * effort)
                        // Different pressure clocks wander against one another. The valve's
                        // opening chirp is finite; even the most unstable pipe stays within 60c.
                        cents = (depth * (.44 * local + .34 * global + .22 * drift) +
                            18 * effort * exp(-t / .045)).coerceIn(-60.0, 60.0)
                        pressure = player * (1 + settings.steamInstability * (.12 * global + .08 * local)) *
                            (1 + .12 * pressureRise)
                    }
                    OtisPrototypeVoice.HARP -> {
                        // A bent entrance rises into the saved note; it does not transpose the body.
                        val scoop = -(70 + 150 * effort) * settings.harpBend * exp(-t / (.110 - .035 * effort))
                        val vibrato = (1 - exp(-maxOf(0.0, t - .18) / .10)) *
                            settings.harpVibrato * (5 + 15 * effort) * sin(2 * PI * 5.5 * t)
                        cents = scoop + vibrato
                        pressure = player * (1 + .18 * pressureRise)
                    }
                    else -> {
                        cents = 0.0
                        pressure = player * (1 + settings.bellowsPulse * (.07 + .24 * effort) * bellowsWave)
                    }
                }
                val frequency = root * 2.0.pow(cents / 1200)
                val source: Double
                when (voice) {
                    OtisPrototypeVoice.STEAM -> {
                        // Pressure opens coherent pipe harmonics throughout the held body,
                        // rather than supplying only an opening chirp. Keep the fundamental,
                        // drift, noise and final gain unchanged so power survives level matching.
                        fillHarmonics(phaseA, harmonicsA, 5)
                        val pipeLoad = settings.pressure * (.4 + .6 * velocity) * player
                        source = partial(harmonicsA, frequency, 1, 1.0, limit) +
                            partial(harmonicsA, frequency, 2, .25 + .18 * effort + .10 * pipeLoad + .12 * pressureRise, limit) +
                            partial(harmonicsA, frequency, 3, .10 + .15 * effort + .08 * pipeLoad + .07 * pressureRise, limit) +
                            partial(harmonicsA, frequency, 4, .035 + .08 * effort + .05 * pipeLoad + .10 * pressureRise, limit) +
                            partial(harmonicsA, frequency, 5, .020 + .045 * effort + .025 * pipeLoad, limit) +
                            settings.breath * (.012 + .045 * effort +
                                (.25 + .30 * effort) * exp(-t / .045) *
                                (.7 + .3 * sin(2 * PI * 35 * t))) * air
                        phaseA = advance(phaseA, frequency, rate)
                    }
                    OtisPrototypeVoice.HARP -> {
                        fillHarmonics(phaseA, harmonicsA, 8)
                        var reed = 0.0
                        for (harmonic in 1..8) {
                            val gain = when (harmonic) {
                                1 -> 1.0
                                2 -> .30 + .58 * effort + .32 * pressureRise +
                                    .30 * ((effort - .8) / .2).coerceIn(0.0, 1.0)
                                3 -> .24 + .42 * effort + .24 * pressureRise
                                4 -> .10 + .26 * effort + .18 * pressureRise
                                5 -> .075 + .20 * effort + .13 * pressureRise
                                6 -> .035 + .12 * effort + .10 * pressureRise
                                7 -> .035 + .10 * effort + .08 * pressureRise
                                else -> .020 + .08 * effort + .06 * pressureRise
                            }
                            reed += partial(harmonicsA, frequency, harmonic, gain, limit)
                        }
                        // Roughness is breath work at the reed, and leaves with that pressure.
                        source = reed * (1 + settings.breath * (.03 + .10 * effort) * air) +
                            settings.breath * (.08 + .55 * effort * effort) * (1 + .8 * pressureRise) * air *
                            (.65 + .35 * harmonicsA[1])
                        phaseA = advance(phaseA, frequency, rate)
                    }
                    else -> {
                        val a = root * 2.0.pow(-detune / 2400)
                        val b = root * 2.0.pow(detune / 2400)
                        fillHarmonics(phaseA, harmonicsA, 11)
                        fillHarmonics(phaseB, harmonicsB, 11)
                        var reeds = 0.0
                        for (harmonic in 1..11 step 2) {
                            val opening = 1 + .12 * settings.bellowsPulse * bellowsWave * (harmonic - 1) / 10
                            val gainA = harmonic.toDouble().pow(-1.02) * (1 + .85 * effort * (harmonic - 1) / 10)
                            val gainB = harmonic.toDouble().pow(-1.18) * (1 + .45 * effort * (harmonic - 1) / 10)
                            // Real reed ranks need not have identical level or spectra. Unequal
                            // ranks retain a body when the detuned fundamentals oppose each other.
                            reeds += opening * (.62 * partial(harmonicsA, a, harmonic, gainA, limit) +
                                .38 * partial(harmonicsB, b, harmonic, gainB, limit))
                        }
                        val snap = (.14 + .22 * effort) * exp(-t / (.025 - .012 * effort)) *
                            (air + .3 * harmonicsA[2])
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

    /** Angle addition over the harmonic number, not over time: phase advancement stays exact. */
    private fun fillHarmonics(phase: Double, out: DoubleArray, highest: Int) {
        val fundamentalSin = sin(phase)
        val fundamentalCos = cos(phase)
        var s = fundamentalSin
        var c = fundamentalCos
        out[1] = s
        for (harmonic in 2..highest) {
            val nextSin = s * fundamentalCos + c * fundamentalSin
            c = c * fundamentalCos - s * fundamentalSin
            s = nextSin
            out[harmonic] = s
        }
    }

    private fun partial(sines: DoubleArray, hz: Double, harmonic: Int, gain: Double, limit: Double): Double {
        val ratio = hz * harmonic / limit
        val band = when {
            ratio >= 1 -> 0.0
            ratio <= .92 -> 1.0
            else -> .5 + .5 * cos(PI * (ratio - .92) / .08)
        }
        return gain * band * sines[harmonic]
    }
}
