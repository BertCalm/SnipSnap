package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Two original synthesis sketches for the organ/reed audition, not historical instrument models. */
enum class RegistersVoice { PIPE, MUSETTE }

/**
 * Conditioned audio before loudness matching. Held evidence comes from separately synthesized
 * adjacent cycles; [previousCycle] is never made by copying the exported [samples].
 */
data class RegistersProbe(
    val samples: FloatArray,
    val midi: Int,
    val requestedHz: Double,
    val effectiveHz: Double,
    val isLoop: Boolean,
    val loopFrames: Int?,
    val rawPeak: Double,
    val cycleError: Double?,
    val seamError: Double?,
    val effectiveSpreadHz: Double,
    val motionHz: Double,
    val previousCycle: FloatArray? = null,
)

/**
 * REGISTERS is an audition-first renderer, deliberately outside the product's engine registry.
 * PIPE blends twelve harmonic partials with registration-inspired dark/principal weighting,
 * staggered speech, chiff and seeded wind. MUSETTE
 * combines three odd-harmonic reeds whose symmetric Hz offsets produce audible beating.
 *
 * TUNE chooses integer MIDI 48..72 when no explicit midi is supplied. An explicit midi fixes
 * the zone pitch and overrides TUNE. HOLD below .975 gives a finite note; its top step exports
 * an onset-free periodic sustain. Held reed offsets, motion and wind frequencies are fitted to
 * the same loop, with the carrier adjusted by substantially less than a tenth of a cent.
 */
object Registers {
    const val MODEL_VERSION = 1
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val LOOP_THRESHOLD = .975f
    const val RAW_PEAK_CEILING = .82
    private const val PARTIALS = 12
    private const val BLOCK = 32
    private const val NOISE_PARTIALS = 28
    private const val TWO_PI = 2.0 * PI

    fun macrosFor(voice: RegistersVoice): List<MacroSpec> = when (voice) {
        RegistersVoice.PIPE -> listOf(MacroSpec("TUNE", .5f), MacroSpec("COLOR", .28f),
            MacroSpec("AIR", .36f), MacroSpec("EDGE", .22f), MacroSpec("SPREAD", .12f),
            MacroSpec("MOTION", .16f), MacroSpec("HOLD", .35f))
        RegistersVoice.MUSETTE -> listOf(MacroSpec("TUNE", .5f), MacroSpec("COLOR", .48f),
            MacroSpec("AIR", .40f), MacroSpec("EDGE", .23f), MacroSpec("SPREAD", .42f),
            MacroSpec("MOTION", .18f), MacroSpec("HOLD", .35f))
    }

    fun defaults(voice: RegistersVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(voice: RegistersVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (name, default) ->
            (macros[name]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f)
        }

    fun midiFor(tune: Float): Int = ROOT_MIDI +
        (((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f)) * TUNE_SEMITONES).roundToInt()

    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD

    private data class LoopPlan(val frames: Int, val hz: Double) {
        val seconds get() = frames.toDouble() / Dsp.RATE
        fun fit(hz: Double): Double = (hz * seconds).roundToInt() / seconds
    }

    private fun planLoop(hz: Double): LoopPlan {
        val center = (1.85 * hz).roundToInt().coerceAtLeast(8)
        var best = LoopPlan((center * Dsp.RATE / hz).roundToInt(), hz)
        var score = Double.POSITIVE_INFINITY
        for (periods in (center - 24).coerceAtLeast(8)..center + 24) {
            val frames = (periods * Dsp.RATE / hz).roundToInt().coerceAtLeast(1024)
            val fitHz = periods * Dsp.RATE.toDouble() / frames
            val cents = abs(1200.0 * ln(fitHz / hz) / ln(2.0))
            val candidate = cents + .0001 * abs(frames.toDouble() / Dsp.RATE - 1.85)
            if (candidate < score) { score = candidate; best = LoopPlan(frames, fitHz) }
        }
        return best
    }

    private data class Bank(val hz: DoubleArray, val weights: DoubleArray, val harmonics: IntArray,
        val noiseStart: Int, val phases: DoubleArray)

    private fun bank(voice: RegistersVoice, m: Map<String, Float>, hz: Double, spread: Double,
        velocity: Double, seed: Int, loop: LoopPlan?): Bank {
        val color = m.getValue("COLOR").toDouble()
        val edge = m.getValue("EDGE").toDouble()
        val reedGains = if (voice == RegistersVoice.PIPE) {
            val ensemble = .20 * m.getValue("SPREAD")
            doubleArrayOf(ensemble, 1.0 - 2 * ensemble, ensemble)
        } else doubleArrayOf(.26, .48, .26)
        val frequencies = ArrayList<Double>()
        val weights = ArrayList<Double>()
        val harmonics = ArrayList<Int>()
        val spectral = DoubleArray(PARTIALS) { p ->
            val h = if (voice == RegistersVoice.PIPE) p + 1 else 2 * p + 1
            val amp = if (voice == RegistersVoice.PIPE) {
                val dark = exp(-.66 * (h - 1)) / h.toDouble().pow(.28)
                val principal = 1.0 / h.toDouble().pow(1.05 + .65 * (1.0 - edge))
                dark * (1.0 - color) + principal * color
            } else {
                exp(-h * (.035 + .14 * (1.0 - color))) / h.toDouble().pow(.85 + .5 * (1.0 - color))
            }
            // Soft playing loses upper-reed excitation, not only level.
            amp * if (h == 1) 1.0 else (.40 + .60 * velocity).pow(
                if (voice == RegistersVoice.PIPE) .28 else .65)
        }
        val spectralSum = spectral.sum().coerceAtLeast(1e-12)
        for (reed in 0..2) for (p in 0 until PARTIALS) {
            val h = if (voice == RegistersVoice.PIPE) p + 1 else 2 * p + 1
            val frequency = (hz + (reed - 1) * spread) * h
            if (frequency > 17_000.0 || reedGains[reed] == 0.0) continue
            frequencies += frequency
            weights += spectral[p] / spectralSum * reedGains[reed]
            harmonics += h
        }
        val noiseStart = frequencies.size
        val random = Random(seed xor 0x1357)
        for (p in 0 until NOISE_PARTIALS) {
            // Incommensurate-looking, seeded wind components; held wind is a genuine periodic field.
            val noiseHz = 350.0 + 7_500.0 * random.nextDouble().pow(.72)
            frequencies += loop?.fit(noiseHz) ?: noiseHz
            weights += (1.0 / NOISE_PARTIALS) * (.45 + .55 * random.nextDouble())
            harmonics += 0
        }
        val phases = Dsp.phases(frequencies.size, seed)
        // Keep the three reed fundamentals constructive at the initial breath. Their subsequent
        // interference comes from SPREAD rather than arbitrary cancellation on note-on.
        for (p in 0 until noiseStart) phases[p] = .0
        return Bank(frequencies.toDoubleArray(), weights.toDoubleArray(), harmonics.toIntArray(), noiseStart, phases)
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    private fun synthesize(voice: RegistersVoice, m: Map<String, Float>, velocity: Double,
        bank: Bank, hz: Double, motionHz: Double, frames: Int, loop: LoopPlan?): FloatArray {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val out = FloatArray(frames * Dsp.OVERSAMPLE)
        if (velocity == 0.0) return out
        val air = m.getValue("AIR").toDouble()
        val edge = m.getValue("EDGE").toDouble()
        val motion = m.getValue("MOTION").toDouble()
        val pipe = voice == RegistersVoice.PIPE
        val finiteSeconds = finiteSeconds(m)
        val releaseSeconds = .28 + .16 * air
        val releaseAt = finiteSeconds - releaseSeconds
        val attackSeconds = if (pipe) .025 + .22 * air else .012 + .13 * air
        val vibratoHz = hz * (if (pipe) .0017 else .0031) * motion
        val phaseDepth = vibratoHz / motionHz.coerceAtLeast(.1)
        val oscillatorSin = DoubleArray(bank.hz.size)
        val oscillatorCos = DoubleArray(bank.hz.size)
        val stepSin = DoubleArray(bank.hz.size)
        val stepCos = DoubleArray(bank.hz.size)
        val amplitude = DoubleArray(bank.hz.size)
        val increment = DoubleArray(bank.hz.size)
        val velocityGain = velocity.pow(.72)

        fun phase(p: Int, t: Double): Double = TWO_PI * (bank.phases[p] + bank.hz[p] * t) +
            if (p < bank.noiseStart) bank.harmonics[p] * phaseDepth * sin(TWO_PI * motionHz * t) else 0.0

        fun bodyEnvelope(t: Double, h: Int): Double {
            val periodic = 1.0 + (if (pipe) .10 else .16) * motion * sin(TWO_PI * motionHz * t + .31)
            if (loop != null) {
                // AIR continues to affect the held timbre through periodic wind/pressure breathing.
                val breath = 1.0 + (if (pipe) .04 else .18) * air * sin(TWO_PI * t / loop.seconds - .7)
                val upperPressure = if (h > 1) 1.0 + .15 * air * sin(TWO_PI * t / loop.seconds + .5) else 1.0
                return periodic * breath * upperPressure
            }
            val delay = if (pipe) .0007 * h * air else 0.0
            val onset = smooth((t - delay) / (attackSeconds + if (pipe) .002 * h * air else 0.0))
            val release = 1.0 - smooth((t - releaseAt) / releaseSeconds)
            val swell = if (pipe) 1.0 - .22 * air * exp(-t / .30) else
                1.0 - .52 * air * exp(-t / (.22 + .48 * air))
            val pressureBrightness = if (!pipe && h > 1) 1.0 - .30 * air * exp(-t / .36) else 1.0
            return onset * release * swell * pressureBrightness * periodic
        }

        fun noiseEnvelope(t: Double): Double {
            val env = if (loop != null) 1.0 else
                smooth(t / attackSeconds) * (1.0 - smooth((t - releaseAt) / releaseSeconds))
            val wind = if (pipe) .017 * air else .006 * air
            val chiff = if (pipe && loop == null) .13 * edge * smooth(t / .002) * exp(-t / (.022 + .036 * edge)) else 0.0
            val breath = if (loop != null) 1.0 + .30 * air * sin(TWO_PI * t / loop.seconds) else 1.0
            return (wind * env + chiff) * breath
        }

        for (i in out.indices) {
            if (i % BLOCK == 0) {
                val t = i.toDouble() / rate
                val next = (i + BLOCK).toDouble() / rate
                for (p in bank.hz.indices) {
                    val nowPhase = phase(p, t)
                    val step = (phase(p, next) - nowPhase) / BLOCK
                    oscillatorSin[p] = sin(nowPhase)
                    oscillatorCos[p] = cos(nowPhase)
                    stepSin[p] = sin(step)
                    stepCos[p] = cos(step)
                    val a = if (p < bank.noiseStart) bodyEnvelope(t, bank.harmonics[p]) else noiseEnvelope(t)
                    val b = if (p < bank.noiseStart) bodyEnvelope(next, bank.harmonics[p]) else noiseEnvelope(next)
                    amplitude[p] = a
                    increment[p] = (b - a) / BLOCK
                }
            }
            var body = 0.0
            var wind = 0.0
            for (p in bank.hz.indices) {
                val sample = bank.weights[p] * amplitude[p] * oscillatorSin[p]
                if (p < bank.noiseStart) body += sample else wind += sample
                val s = oscillatorSin[p] * stepCos[p] + oscillatorCos[p] * stepSin[p]
                oscillatorCos[p] = oscillatorCos[p] * stepCos[p] - oscillatorSin[p] * stepSin[p]
                oscillatorSin[p] = s
                amplitude[p] += increment[p]
            }
            if (!pipe) {
                // Bellows pressure and EDGE change harmonic generation before level matching.
                val t = i.toDouble() / rate
                val pressure = if (loop != null) 1.0 + .18 * air * sin(TWO_PI * t / loop.seconds - .7)
                    else 1.0 - .38 * air * exp(-t / .42)
                val drive = 1.0 + (2.7 * edge + .30 * air) * pressure * (.35 + .65 * velocity)
                body = tanh(body * drive) / tanh(drive)
            }
            out[i] = (.58 * velocityGain * (body + wind)).toFloat()
        }
        return out
    }

    private fun finiteSeconds(m: Map<String, Float>): Double = 2.05 + .72 * m.getValue("HOLD") + .12 * m.getValue("AIR")

    private fun relativeError(a: FloatArray, b: FloatArray, start: Int = 0): Double {
        var error = 0.0
        var energy = 0.0
        for (i in start until a.size) {
            val d = a[i] - b[i].toDouble()
            error += d * d
            energy += a[i].toDouble() * a[i]
        }
        return if (energy <= 1e-30) 0.0 else error / energy
    }

    fun probe(
        voice: RegistersVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = midiFor(macros["TUNE"] ?: .5f),
        velocity: Float = 1f,
    ): RegistersProbe {
        require(midi in 24..96) { "REGISTERS audition supports MIDI 24..96, got $midi" }
        val m = settled(voice, macros)
        val v = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f).toDouble()
        val requestedHz = Keys.midiHz(midi).toDouble()
        val loop = if (isLoop(m.getValue("HOLD"))) planLoop(requestedHz) else null
        val hz = loop?.hz ?: requestedHz
        val desiredSpread = m.getValue("SPREAD").toDouble().pow(1.22) *
            if (voice == RegistersVoice.PIPE) .85 else minOf(5.2, hz * .019)
        val spread = loop?.fit(desiredSpread) ?: desiredSpread
        val desiredMotion = 4.1 + 2.2 * m.getValue("MOTION")
        val motion = loop?.fit(desiredMotion) ?: desiredMotion
        val seed = Dsp.seedFor("REGISTERS", MODEL_VERSION, voice.name, midi)
        val b = bank(voice, m, hz, spread, v, seed, loop)
        // Continuous synthesis provides preroll + measured previous cycle + exported cycle.
        // Postroll keeps resampler edges outside the evidence windows.
        val frames = loop?.let { 3 * it.frames + 512 } ?: (finiteSeconds(m) * Dsp.RATE).roundToInt()
        val raw = synthesize(voice, m, v, b, hz, motion, frames, loop)
        val peak = raw.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        check(raw.all { it.isFinite() } && peak <= RAW_PEAK_CEILING) { "REGISTERS raw signal exceeded bound: $peak" }
        Tide.bandLimit(raw, Dsp.RATE * Dsp.OVERSAMPLE)
        val down = Dsp.decimate(raw, Dsp.RATE)
        val previous = loop?.let { down.copyOfRange(it.frames, 2 * it.frames) }
        val samples = loop?.let { down.copyOfRange(2 * it.frames, 3 * it.frames) } ?: down
        if (loop == null) Dsp.fadeTail(samples)
        val cycleError = previous?.let { relativeError(it, samples) }
        val seamError = previous?.let { relativeError(it, samples, (it.size - 256).coerceAtLeast(0)) }
        check(cycleError == null || cycleError < 1e-5) { "REGISTERS $voice cycle did not recur: $cycleError" }
        check(seamError == null || seamError < Keys.MAX_SEAM_ERROR) { "REGISTERS $voice loop did not close: $seamError" }
        return RegistersProbe(samples, midi, requestedHz, hz, loop != null, loop?.frames,
            peak, cycleError, seamError, spread, motion, previous)
    }

    /** Match to SnipSnap's melodic reference while preserving the velocity gesture. */
    fun render(
        voice: RegistersVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = midiFor(macros["TUNE"] ?: .5f),
        velocity: Float = 1f,
    ): Snip {
        val result = probe(voice, macros, midi, velocity)
        val v = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
        if (v > 0f) Dsp.levelTo(result.samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * v.pow(.65f))
        return Snip(result.samples, channels = 1, sampleRate = Dsp.RATE)
    }
}
