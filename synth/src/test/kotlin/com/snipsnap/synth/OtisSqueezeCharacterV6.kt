package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Model 6 restores a loaded chord's draw and vibrato without changing the preferred single key.
 * Shared pressure still controls source level and spectral opening. Its .4 floor no longer
 * almost closes every upper reed. Excess load brings in one player clock for all chord notes,
 * driving a bounded swell, upper colour and small pitch vibrato before the reeds are summed.
 *
 * The nasal spectral weights, unequal ranks, delayed upper attack, choke and seeds stay from
 * model 5. An all-unity pressure trace takes that exact historical source, preserving the
 * reference single note and the independent-pressure bypass. Model 5 itself is frozen.
 *
 * This is a musical bellows analogy, not measured reed/air physics. Shape choices: loaded
 * pulse depth .32 versus .12 unloaded, pitch depth PULSE*(8+14*MUSETTE) cents at full load,
 * and upper opening .30+.70*p*p. A single clock is derived from reference effort, not note
 * velocity, so mixed-strength chord notes retain a common gesture. FM sidebands use the
 * existing180 Hz guard and harmonic taper evaluated at each instantaneous rank frequency.
 * There is no feedback, limiter, rack effect, output leveling or extra noise.
 */
internal object OtisSqueezeCharacterV6 {
    private const val TWO_PI = 2.0 * PI
    private const val HARMONICS = 12
    private const val MODULATION_MARGIN_HZ = 180.0
    private const val SOURCE_GAIN = .24

    fun synthesize(
        notes: List<OtisPrototypeNote>,
        settings: OtisAirSettings,
        seed: Int,
        rate: Int,
        pressure: OtisPhrasePressureTrace,
    ): FloatArray {
        OtisAirCharacterV5.validate(notes, rate)
        require(pressure.rate == rate && pressure.stride > 0 && pressure.pressure.size >= 2 &&
            pressure.pressure.all { it.isFinite() && it in 0.0..1.0 }) {
            "SQUEEZE needs a finite normalized pressure trace at the synthesis clock"
        }
        if (pressure.pressure.all { it == 1.0 })
            return OtisSqueezeCharacterV5.synthesize(notes, settings, seed, rate, pressure)
        val frames = OtisAirCharacterV5.frameCount(OtisPrototypeVoice.SQUEEZE, notes, settings, rate)
        val out = FloatArray(frames)
        val release = OtisAirCharacterV5.releaseSeconds(OtisPrototypeVoice.SQUEEZE, settings)
        val upperRelease = .012 + .025 * settings.release
        val limit = min(20_000.0, rate * .45) - MODULATION_MARGIN_HZ
        val referenceEffort = OtisAirCharacterV5.REFERENCE_VELOCITY.toDouble() * (.5 + .5 * settings.pressure)
        val playerRate = 2.5 + 1.5 * referenceEffort
        val maxVibratoCents = settings.bellowsPulse * (8 + 14 * settings.musette)
        val scorePhase = Dsp.Noise(Dsp.seedFor(seed, "OTIS_SQUEEZE5_SCORE")).next().toDouble() * PI
        for (note in notes) {
            if (note.velocity == 0f) continue
            val start = ceil(note.startSeconds * rate).toInt()
            val end = ceil((note.startSeconds + note.gateSeconds + release * 16) * rate).toInt().coerceAtMost(frames)
            val velocity = note.velocity.toDouble()
            val effort = velocity * (.5 + .5 * settings.pressure)
            val work = velocity.pow(.65) * (.55 + .75 * settings.pressure)
            val attack = OtisAirCharacterV5.attackSeconds(OtisPrototypeVoice.SQUEEZE, settings, velocity)
            val color = Dsp.Noise(Dsp.seedFor(seed, "OTIS_SQUEEZE5_NOTE", note.midi,
                note.startSeconds, note.gateSeconds, note.velocity))
            val upperAttack = .024 + .020 * (1 - effort) + .004 * (1 + color.next())
            val roughPhase = color.next().toDouble() * PI
            val root = 440 * 2.0.pow((note.midi - 69) / 12.0)
            val detune = (7 + 17 * effort) * settings.musette
            val a = root * 2.0.pow(-detune / 2400)
            val b = root * 2.0.pow(detune / 2400)
            val stepA = TWO_PI * a / rate
            val stepB = TWO_PI * b / rate
            val gainsA = gains(a, effort, rankB = false, Double.POSITIVE_INFINITY)
            val gainsB = gains(b, effort, rankB = true, Double.POSITIVE_INFINITY)
            val harmonicA = DoubleArray(HARMONICS + 1)
            val harmonicB = DoubleArray(HARMONICS + 1)
            var phaseA = 0.0
            var phaseB = 0.0
            for (frame in start until end) {
                if (frame % 8192 == 0 && Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("OTIS squeeze render no longer wanted")
                val absoluteTime = frame.toDouble() / rate
                val t = absoluteTime - note.startSeconds
                val heldAge = min(t, note.gateSeconds)
                // Freeze each unfinished attack at key-up; a short gate must not bloom later.
                val entry = 1 - exp(-heldAge / attack)
                val upperEntry = 1 - exp(-heldAge / upperAttack)
                val released = (t - note.gateSeconds).coerceAtLeast(0.0)
                val leaving = exp(-released / release)
                val upperLeaving = exp(-released / upperRelease)
                val available = pressure.pressureAtFrame(frame.toDouble())
                val load = ((1 - available) / (1 - OtisAirCharacterV5.PRESSURE_FLOOR)).coerceIn(0.0, 1.0)
                val localBellows = sin(TWO_PI * (2.5 + 1.5 * effort) * absoluteTime + scorePhase)
                val sharedBellows = sin(TWO_PI * playerRate * absoluteTime + scorePhase)
                val bellows = localBellows + load * (sharedBellows - localBellows)
                val player = entry * leaving * available *
                    (1 + (.12 + .20 * load) * settings.bellowsPulse * bellows)
                // Pressure darkens a chord without stripping its moving reed bank away.
                val opening = .30 + .70 * available * available
                val vibratoEntry = 1 - exp(-heldAge / .08)
                val cents = maxVibratoCents * load * vibratoEntry * sharedBellows
                val pitchRatio = if (cents == 0.0) 1.0 else 2.0.pow(cents / 1200)
                // These slow sidebands roughen only the upper reeds, never the pitch anchor.
                // They are generated at the source and close with the available pressure.
                val rasp = 1 + (.045 + .055 * effort) * available *
                    (sin(TWO_PI * (31 + 7 * effort) * t + roughPhase) +
                        .33 * sin(TWO_PI * 53 * t + .73 * roughPhase))
                val bite = 1 + .24 * effort * exp(-heldAge / .055)
                fillHarmonics(phaseA, harmonicA)
                fillHarmonics(phaseB, harmonicB)
                var reeds = .68 * harmonicA[1] + .32 * harmonicB[1]
                for (harmonic in 2..HARMONICS) {
                    val authority = min(1.0, (harmonic - 1) / 4.0)
                    val opened = 1 + (opening - 1) * authority
                    val pulseColor = 1 + (.09 + .24 * load) * settings.bellowsPulse * bellows * authority
                    val gainA = gainsA[harmonic] * band(a * pitchRatio * harmonic / limit)
                    val gainB = gainsB[harmonic] * band(b * pitchRatio * harmonic / limit)
                    val upper = .68 * gainA * harmonicA[harmonic] +
                        .32 * gainB * harmonicB[harmonic]
                    reeds += upper * opened * upperEntry * upperLeaving * rasp * bite * pulseColor
                }
                out[frame] += (SOURCE_GAIN * work * player * reeds).toFloat()
                phaseA = advance(phaseA, stepA * pitchRatio)
                phaseB = advance(phaseB, stepB * pitchRatio)
            }
        }
        return out
    }

    /** Fixed-Hz case coloration is a bounded spectral weight, not a simulated body resonance. */
    private fun gains(hz: Double, effort: Double, rankB: Boolean, limit: Double): DoubleArray =
        DoubleArray(HARMONICS + 1) { h ->
            if (h < 2) 0.0 else {
                val frequency = hz * h
                val lowCase = 1 / (1 + ((frequency - 850) / 300).pow(2))
                val highCase = 1 / (1 + ((frequency - 1750) / 520).pow(2))
                val case = .74 + .40 * lowCase + .25 * highCase
                val parity = if (h % 2 == 0) {
                    if (rankB) -.31 else .68
                } else if (rankB) .81 else 1.12
                val spectral = h.toDouble().pow(if (rankB) -1.20 else -1.04) *
                    case * (.62 + .70 * effort) * parity
                spectral.coerceIn(-.55, .55) * band(frequency / limit)
            }
        }

    private fun band(ratio: Double): Double = when {
        ratio >= 1 -> 0.0
        ratio <= .92 -> 1.0
        else -> .5 + .5 * cos(PI * (ratio - .92) / .08)
    }

    /** Angle addition restarts each frame; numerical error cannot accumulate tuning drift. */
    private fun fillHarmonics(phase: Double, out: DoubleArray) {
        val s = sin(phase)
        val c = cos(phase)
        var sn = s
        var cn = c
        out[1] = sn
        for (h in 2..HARMONICS) {
            val next = sn * c + cn * s
            cn = cn * c - sn * s
            sn = next
            out[h] = sn
        }
    }

    private fun advance(phase: Double, step: Double): Double {
        val next = phase + step
        return if (next >= TWO_PI) next - TWO_PI else next
    }
}
