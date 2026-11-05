package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Blues-harmonica direction for the finite model-5 audition, not an acoustic reconstruction.
 * Gesture: tongue releases -> breath opens the reeds and mouth focus -> delayed vibrato -> release.
 *
 * Why it is built this way:
 * - A retained fundamental and an asymmetric harmonic reed supply a sustained note, not a pluck.
 * - Broad, Hz-based mouth/hand focus weights the reed harmonics before notes are summed. Pressure
 *   changes those weights as well as player level; a crowded chord cannot be a post-mix gain trick.
 * - A short tongue constriction, pressure-following air and restrained throat modulation articulate
 *   the source. These are musical analogies, not a reed/tract feedback solver or recorded samples.
 * - Every note owns its phase, harmonic smoother and noise stores. One absolute-time pressure trace
 *   and player vibrato clock bind a chord without introducing a detuned rank or a chorus effect.
 *
 * All mouth centres, widths, harmonic weights and tongue/growl amounts below are proposed sound
 * shapes. The roughly .7--1.8 kHz and 2--3 kHz focus is not a measured player's transfer function.
 * Twenty-four additive partials taper to zero below min(18 kHz, .42*rate); there is no clipping
 * or nonlinear feedback. The caller still owns the house band limit/decimation/DC stage, including
 * band limiting the shaped breath noise. Work is bounded by 24 partials per active note/sample;
 * spectral targets update near 2.75 kHz and smooth over .8 ms rather than allocating per sample.
 * Models 1--4 never enter this source.
 */
internal object OtisHarpCharacterV5 {
    private const val PARTIALS = 24
    private const val TWO_PI = 2 * PI

    fun synthesize(
        notes: List<OtisPrototypeNote>,
        settings: OtisAirSettings,
        seed: Int,
        rate: Int,
        pressure: OtisPhrasePressureTrace,
    ): FloatArray {
        OtisAirCharacterV5.validate(notes, rate)
        require(pressure.rate == rate && pressure.stride > 0 && pressure.pressure.size >= 2) {
            "HARP pressure must use the synthesis clock and contain at least two control points"
        }
        require(pressure.pressure.all { it.isFinite() && it in 0.0..1.0 }) {
            "HARP pressure must be finite and bounded to 0..1"
        }
        val voice = OtisPrototypeVoice.HARP
        val out = FloatArray(OtisAirCharacterV5.frameCount(voice, notes, settings, rate))
        val release = OtisAirCharacterV5.releaseSeconds(voice, settings)
        val tail = 16 * release
        val bandLimit = min(18_000.0, .42 * rate)
        val controlStride = (rate / 2750.0).roundToInt().coerceAtLeast(1)
        val gainApproach = 1 - exp(-1.0 / (.0008 * rate))
        val fastCoefficient = 1 - exp(-TWO_PI * min(6500.0, .18 * rate) / rate)
        val slowCoefficient = 1 - exp(-TWO_PI * min(850.0, .045 * rate) / rate)
        val playerPhase = TWO_PI * unitSeed(Dsp.seedFor("OTIS HARP PLAYER", 5, seed))
        val bases = DoubleArray(PARTIALS + 1) { if (it == 0) 0.0 else it.toDouble().pow(-1.07) }

        for (note in notes) {
            if (note.velocity == 0f || note.gateSeconds == 0.0) continue
            val start = ceil(note.startSeconds * rate).toInt()
            val end = ceil((note.startSeconds + note.gateSeconds + tail) * rate).toInt().coerceAtMost(out.size)
            val velocity = note.velocity.toDouble()
            val effort = velocity * (.5 + .5 * settings.pressure)
            val work = velocity.pow(.65) * (.55 + .75 * settings.pressure)
            val attack = OtisAirCharacterV5.attackSeconds(voice, settings, velocity)
            val attackAtGate = 1 - exp(-note.gateSeconds / attack)
            val root = hz(note.midi)
            val noise = Dsp.Noise(Dsp.seedFor("OTIS HARP AIR", 5, seed, note.midi, note.startSeconds))
            val gains = DoubleArray(PARTIALS + 1)
            val targets = DoubleArray(PARTIALS + 1)
            var phase = 0.0
            var fastNoise = 0.0
            var fastNoise2 = 0.0
            var slowNoise = 0.0
            var slowNoise2 = 0.0

            for (frame in start until end) {
                if (frame % 8192 == 0 && Thread.currentThread().isInterrupted)
                    throw CancellationException("OTIS harmonica render no longer wanted")
                val absoluteTime = frame.toDouble() / rate
                val t = absoluteTime - note.startSeconds
                val entry = if (t < note.gateSeconds) 1 - exp(-t / attack) else attackAtGate
                val leaving = if (t < note.gateSeconds) 1.0 else exp(-(t - note.gateSeconds) / release)
                // Absolute internal frame time matters: a later note must draw the same player's air.
                val available = pressure.pressureAtFrame(frame.toDouble())
                val opening = (effort * available).coerceIn(0.0, 1.0)
                val tongue = exp(-t / (.012 + .008 * (1 - effort)))
                val player = entry * leaving * available * (1 - .55 * tongue)
                val scoop = -(45 + 130 * effort) * settings.harpBend * exp(-t / (.085 - .030 * effort))
                val vibratoEntry = 1 - exp(-maxOf(0.0, t - .18) / .10)
                val vibrato = vibratoEntry * settings.harpVibrato * (4 + 12 * effort) *
                    sin(TWO_PI * 5.2 * absoluteTime + playerPhase)
                val frequency = root * 2.0.pow((scoop + vibrato) / 1200)

                if ((frame - start) % controlStride == 0) {
                    // Hand opening is a shared slow gesture, while the initial cup belongs to this note.
                    val hand = settings.breath * (.12 * sin(TWO_PI * 1.15 * absoluteTime + playerPhase) -
                        .22 * exp(-t / .11))
                    val voicing = (.30 + .62 * opening + hand).coerceIn(.05, 1.0)
                    val mouthHz = 650 + 1150 * voicing
                    val upperMouthHz = 1900 + 1150 * voicing
                    val mouthWidth = 270 + .13 * mouthHz
                    for (harmonic in 1..PARTIALS) {
                        val partialHz = frequency * harmonic
                        val taper = taper(partialHz, bandLimit)
                        if (harmonic == 1) {
                            targets[harmonic] = .82 * taper
                        } else {
                            val parity = if (harmonic % 2 == 0) .58 + .50 * opening else 1.0
                            val breathOpening = .16 + 1.16 * opening.pow(1.35)
                            val mouth = .24 + 2.8 * focus(partialHz, mouthHz, mouthWidth) +
                                .95 * focus(partialHz, upperMouthHz, 650.0)
                            targets[harmonic] = bases[harmonic] * parity * breathOpening * mouth * taper
                        }
                    }
                }

                // Angle addition restarts from the carrier each sample: no harmonic phase drift.
                val fundamentalSin = sin(phase)
                val fundamentalCos = cos(phase)
                var harmonicSin = fundamentalSin
                var harmonicCos = fundamentalCos
                var reed = 0.0
                for (harmonic in 1..PARTIALS) {
                    gains[harmonic] += gainApproach * (targets[harmonic] - gains[harmonic])
                    reed += gains[harmonic] * harmonicSin
                    val nextSin = harmonicSin * fundamentalCos + harmonicCos * fundamentalSin
                    harmonicCos = harmonicCos * fundamentalCos - harmonicSin * fundamentalSin
                    harmonicSin = nextSin
                }
                val grain = noise.next().toDouble()
                fastNoise += fastCoefficient * (grain - fastNoise)
                fastNoise2 += fastCoefficient * (fastNoise - fastNoise2)
                slowNoise += slowCoefficient * (grain - slowNoise)
                slowNoise2 += slowCoefficient * (slowNoise - slowNoise2)
                val air = fastNoise2 - slowNoise2
                val airLevel = settings.breath * (.012 + .016 * opening + .22 * effort * tongue)
                val growl = .05 * settings.breath * opening * opening *
                    sin(TWO_PI * (31 + 3 * effort) * absoluteTime + playerPhase)
                out[frame] += (.36 * work * player * (reed * (1 + growl) + airLevel * air)).toFloat()
                phase += TWO_PI * frequency / rate
                if (phase >= TWO_PI) phase -= TWO_PI
            }
        }
        return out
    }

    private fun hz(midi: Int) = 440 * 2.0.pow((midi - 69) / 12.0)
    private fun unitSeed(seed: Int) = (seed.toLong() and 0xffffffffL) / 4294967296.0
    private fun focus(hz: Double, centre: Double, width: Double): Double {
        val distance = (hz - centre) / width
        return 1 / (1 + distance * distance)
    }
    private fun taper(hz: Double, limit: Double): Double = when {
        hz >= limit -> 0.0
        hz <= .88 * limit -> 1.0
        else -> .5 + .5 * cos(PI * (hz / limit - .88) / .12)
    }
}
