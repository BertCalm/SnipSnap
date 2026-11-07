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
 * Valve opening -> root-led harmonic pipe body -> shared pressure draw -> finite release.
 * Model 5 puts the extra power in coherent harmonics, not a louder gain or more air noise.
 * The model-4 pitch clocks and bounded steam drift remain; this is a musical pipe sketch,
 * not a steam-flow simulation. Available phrase pressure changes both each pipe's level and
 * upper-partial opening before pipes are summed. No output effect supplies that coupling.
 */
internal object OtisSteamCharacterV5 {
    private val body = doubleArrayOf(0.0, 1.0, .38, .20, .09, .06, .025, .018, .010)
    private val effortGain = doubleArrayOf(0.0, 0.0, .38, .28, .21, .14, .10, .055, .035)
    private val loadGain = doubleArrayOf(0.0, 0.0, .22, .18, .12, .09, .06, .04, .025)
    private val valveEdge = doubleArrayOf(0.0, 0.0, .14, .10, .10, .06, .05, .03, .02)
    private const val TWO_PI = 2.0 * PI

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
            "steam pressure trace must have a matching clock and finite pressure in 0..1"
        }
        val frames = OtisAirCharacterV5.frameCount(OtisPrototypeVoice.STEAM, notes, settings, rate)
        val out = FloatArray(frames)
        val limit = min(20_000.0, rate * .45)
        val release = OtisAirCharacterV5.releaseSeconds(OtisPrototypeVoice.STEAM, settings)
        val globalPhase = Random(seed xor 0x51EAD).nextDouble(0.0, TWO_PI)
        for (note in notes) {
            if (note.velocity == 0f) continue
            val start = ceil(note.startSeconds * rate).toInt()
            val end = ceil((note.startSeconds + note.gateSeconds + 16 * release) * rate).toInt().coerceAtMost(frames)
            // Preserve model 4's deterministic pressure clocks across this character comparison.
            val noteSeed = seed xor (note.midi * 1009) xor (note.startSeconds * 1_000_000).roundToInt()
            val random = Random(noteSeed)
            val wobbleRate = .8 + 2.0 * random.nextDouble()
            val wobblePhase = random.nextDouble(0.0, TWO_PI)
            val driftPhase = random.nextDouble(0.0, TWO_PI)
            val noise = Dsp.Noise(Dsp.seedFor("OTIS_STEAM_V5", noteSeed, "AIR"))
            val velocity = note.velocity.toDouble()
            val effort = velocity * (.5 + .5 * settings.pressure)
            val work = velocity.pow(.65) * (.55 + .75 * settings.pressure)
            val attack = OtisAirCharacterV5.attackSeconds(OtisPrototypeVoice.STEAM, settings, velocity)
            val root = 440.0 * 2.0.pow((note.midi - 69) / 12.0)
            val instability = settings.steamInstability.toDouble().pow(.55) * (44 + 20 * effort)
            val harmonics = DoubleArray(9)
            var phase = 0.0
            var fastNoise = 0.0
            var slowNoise = 0.0
            val fastA = 1 - exp(-TWO_PI * min(8_000.0, rate * .30) / rate)
            val slowA = 1 - exp(-TWO_PI * min(950.0, rate * .08) / rate)
            for (frame in start until end) {
                if (frame % 8192 == 0 && Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("OTIS steam character render no longer wanted")
                val absoluteTime = frame.toDouble() / rate
                val t = absoluteTime - note.startSeconds
                val entry = 1 - exp(-min(t, note.gateSeconds) / attack)
                val leaving = if (t < note.gateSeconds) 1.0 else exp(-(t - note.gateSeconds) / release)
                val player = entry * leaving
                val available = pressure.pressureAtFrame(frame.toDouble())
                val opening = .35 + .65 * available * available
                val global = sin(TWO_PI * 1.7 * absoluteTime + globalPhase)
                val local = sin(TWO_PI * wobbleRate * t + wobblePhase + .65 * sin(TWO_PI * .63 * t + driftPhase))
                val drift = sin(TWO_PI * .39 * t + driftPhase)
                val cents = (instability * (.44 * local + .34 * global + .22 * drift) +
                    18 * effort * exp(-t / .045)).coerceIn(-60.0, 60.0)
                val frequency = root * 2.0.pow(cents / 1200)
                val pressureRise = effort * exp(-t / .060)
                val pipeLoad = settings.pressure * (.4 + .6 * velocity) * player
                val steamEnvelope = player * available *
                    (1 + settings.steamInstability * (.12 * global + .08 * local)) * (1 + .12 * pressureRise)

                fillHarmonics(phase, harmonics)
                // A full harmonic series projects the pipe body. Keeping the root at unity
                // and every upper stop below it avoids a sub-octave or octave substitution.
                var source = partial(harmonics, frequency, 1, 1.0, limit)
                for (harmonic in 2..8) {
                    val gain = (body[harmonic] + effortGain[harmonic] * effort +
                        loadGain[harmonic] * pipeLoad + valveEdge[harmonic] * pressureRise).coerceAtMost(.95) * opening
                    source += partial(harmonics, frequency, harmonic, gain, limit)
                }

                // Air keeps the finite opening chuff; it is not increased to supply power.
                val grain = noise.next().toDouble()
                fastNoise += fastA * (grain - fastNoise)
                slowNoise += slowA * (grain - slowNoise)
                val air = fastNoise - slowNoise
                source += settings.breath * (.012 + .045 * effort +
                    (.25 + .30 * effort) * exp(-t / .045) * (.7 + .3 * sin(TWO_PI * 35 * t))) * air
                phase += TWO_PI * frequency / rate
                if (phase >= TWO_PI) phase -= TWO_PI
                out[frame] += (.32 * work * steamEnvelope * source).toFloat()
            }
        }
        return out
    }

    /** Recur over harmonic number, restarting every sample to avoid accumulated pitch drift. */
    private fun fillHarmonics(phase: Double, out: DoubleArray) {
        val fundamentalSine = sin(phase)
        val fundamentalCosine = cos(phase)
        var sine = fundamentalSine
        var cosine = fundamentalCosine
        out[1] = sine
        for (harmonic in 2..8) {
            val nextSine = sine * fundamentalCosine + cosine * fundamentalSine
            cosine = cosine * fundamentalCosine - sine * fundamentalSine
            sine = nextSine
            out[harmonic] = sine
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
