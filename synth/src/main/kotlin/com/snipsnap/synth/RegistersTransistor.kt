package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Model 4's dry electronic organ: keyed, PolyBLEP-corrected square divider ranks, rather than
 * the harmonic pipe/reed bank. COLOR introduces 4'/2' ranks and opens their tone filter;
 * AIR is a brief electronic key-click, never simulated wind. EDGE drives the transistor-like
 * saturator, SPREAD detunes two quiet rank banks and MOTION adds bounded pitch vibrato.
 * This is an original Farfisa-inspired sound, not a circuit or historical-instrument model.
 */
internal object RegistersTransistor {
    private const val TWO_PI = 2.0 * PI
    private const val RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val ATTACK_SECONDS = .0015
    private const val CLICK_SECONDS = .012

    private data class Loop(val frames: Int, val hz: Double) {
        val seconds get() = frames.toDouble() / Dsp.RATE
        fun fit(hz: Double): Double = (hz * seconds).roundToInt() / seconds
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw CancellationException("REGISTERS render cancelled")
    }

    private fun loopFor(hz: Double): Loop {
        val center = (1.85 * hz).roundToInt().coerceAtLeast(8)
        var best = Loop((center * Dsp.RATE / hz).roundToInt(), hz)
        var score = Double.POSITIVE_INFINITY
        for (periods in (center - 24).coerceAtLeast(8)..center + 24) {
            val frames = (periods * Dsp.RATE / hz).roundToInt().coerceAtLeast(1024)
            val fitHz = periods * Dsp.RATE.toDouble() / frames
            val candidate = abs(1200.0 * ln(fitHz / hz) / ln(2.0)) +
                .0001 * abs(frames.toDouble() / Dsp.RATE - 1.85)
            if (candidate < score) { score = candidate; best = Loop(frames, fitHz) }
        }
        return best
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun fraction(x: Double): Double = x - floor(x)

    private fun polyBlep(phase: Double, step: Double): Double = when {
        phase < step -> { val x = phase / step; x + x - x * x - 1.0 }
        phase > 1.0 - step -> { val x = (phase - 1.0) / step; x * x + x + x + 1.0 }
        else -> 0.0
    }

    private fun square(phase: Double, step: Double): Double {
        val shifted = if (phase < .5) phase + .5 else phase - .5
        return (if (phase < .5) 1.0 else -1.0) + polyBlep(phase, step) - polyBlep(shifted, step)
    }

    /** Produce a continuous preroll and two further cycles, not copies of an output buffer. */
    private fun synthesize(m: Map<String, Float>, midi: Int, velocity: Double, hz: Double,
        spread: Double, motionHz: Double, frames: Int, loop: Loop?, cancelled: () -> Boolean): FloatArray {
        val out = FloatArray(frames * Dsp.OVERSAMPLE)
        if (velocity == 0.0) return out
        val color = m.getValue("COLOR").toDouble()
        val air = m.getValue("AIR").toDouble()
        val edge = m.getValue("EDGE").toDouble()
        val motion = m.getValue("MOTION").toDouble()
        val duration = finiteSeconds(m)
        val releaseSeconds = .075 + .095 * m.getValue("HOLD")
        val releaseAt = duration - releaseSeconds
        val vibratoRatio = 2.0.pow(22.0 * motion / 1200.0) - 1.0
        val phaseDepth = vibratoRatio / (TWO_PI * motionHz)
        val ranks = doubleArrayOf(1.0, .75 * color, .30 * color * color)
        val rankSum = ranks.sum()
        val ensemble = .22 * m.getValue("SPREAD")
        val bankGains = doubleArrayOf(ensemble, 1.0 - 2.0 * ensemble, ensemble)
        val cutoff = ((hz * (7.0 + 24.0 * color)).coerceIn(1_400.0, 14_000.0) *
            (.40 + .60 * velocity)).coerceAtLeast(500.0)
        val coefficient = 1.0 - exp(-TWO_PI * cutoff / RATE)
        val drive = 1.0 + 4.8 * edge * (.35 + .65 * velocity)
        val driveNorm = tanh(drive)
        val velocityGain = velocity.pow(.72)
        val noise = Dsp.Noise(Dsp.seedFor("REGISTERS", 4, RegistersVoice.TRANSISTOR.name, midi) xor 0x4b455934)
        val clickCoefficient = 1.0 - exp(-TWO_PI * 2_400.0 / RATE)
        var clickLow = 0.0
        var toneOne = 0.0
        var toneTwo = 0.0
        for (i in out.indices) {
            if (i % 4096 == 0) checkCancelled(cancelled)
            val t = i.toDouble() / RATE
            val angle = TWO_PI * motionHz * t
            val modulation = phaseDepth * sin(angle)
            val speed = 1.0 + vibratoRatio * cos(angle)
            var body = 0.0
            for (bank in 0..2) {
                if (bankGains[bank] == 0.0) continue
                val bankHz = hz + (bank - 1) * spread
                val cycles = bankHz * (t + modulation)
                var sum = 0.0
                for (rank in ranks.indices) {
                    if (ranks[rank] == 0.0) continue
                    val ratio = (1 shl rank).toDouble()
                    val step = bankHz * ratio * speed / RATE
                    sum += ranks[rank] * square(fraction(cycles * ratio + .25), step)
                }
                body += bankGains[bank] * sum / rankSum
            }
            // A fixed two-pole voice filter precedes the nonlinear stage. The 4x PolyBLEP
            // source and shared eighth-order output filter keep generated upper harmonics
            // from folding through the final 44.1 kHz conversion.
            toneOne += coefficient * (body - toneOne)
            toneTwo += coefficient * (toneOne - toneTwo)
            val tone = tanh(toneTwo * drive) / driveNorm
            val envelope = if (loop != null) 1.0 else
                smooth(t / ATTACK_SECONDS) * (1.0 - smooth((t - releaseAt) / releaseSeconds))
            var click = 0.0
            if (loop == null && air > 0.0 && t < CLICK_SECONDS) {
                val n = noise.next().toDouble()
                clickLow += clickCoefficient * (n - clickLow)
                val contour = smooth(t / .0006) * (1.0 - smooth((t - .006) / .006)) * exp(-t / .0035)
                click = .14 * air * (n - clickLow) * contour
            }
            out[i] = (velocityGain * (.56 * tone * envelope + click)).toFloat()
        }
        return out
    }

    private fun finiteSeconds(m: Map<String, Float>): Double = 2.05 + .72 * m.getValue("HOLD")

    private fun error(a: FloatArray, b: FloatArray, from: Int = 0): Double {
        var energy = 0.0
        var difference = 0.0
        for (i in from until a.size) {
            val d = a[i] - b[i].toDouble()
            difference += d * d
            energy += a[i].toDouble() * a[i]
        }
        return if (energy <= 1e-30) 0.0 else difference / energy
    }

    fun probe(m: Map<String, Float>, midi: Int, velocity: Double, cancelled: () -> Boolean): RegistersProbe {
        checkCancelled(cancelled)
        val requestedHz = Keys.midiHz(midi).toDouble()
        val loop = if (Registers.isLoop(m.getValue("HOLD"))) loopFor(requestedHz) else null
        val hz = loop?.hz ?: requestedHz
        val desiredSpread = m.getValue("SPREAD").toDouble().pow(1.3) * minOf(2.5, hz * .009)
        val spread = loop?.fit(desiredSpread) ?: desiredSpread
        val desiredMotion = 5.5
        val motion = loop?.fit(desiredMotion) ?: desiredMotion
        val frames = loop?.let { 3 * it.frames + 512 } ?: (finiteSeconds(m) * Dsp.RATE).roundToInt()
        val raw = synthesize(m, midi, velocity, hz, spread, motion, frames, loop, cancelled)
        val peak = raw.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        check(raw.all { it.isFinite() } && peak <= Registers.RAW_PEAK_CEILING) {
            "REGISTERS TRANSISTOR raw signal exceeded bound: $peak"
        }
        checkCancelled(cancelled)
        Tide.bandLimit(raw, RATE)
        checkCancelled(cancelled)
        val down = Dsp.decimate(raw, Dsp.RATE)
        val previous = loop?.let { down.copyOfRange(it.frames, 2 * it.frames) }
        val samples = loop?.let { down.copyOfRange(2 * it.frames, 3 * it.frames) } ?: down
        if (loop == null) Dsp.fadeTail(samples)
        val cycleError = previous?.let { error(it, samples) }
        val seamError = previous?.let { error(it, samples, (it.size - 256).coerceAtLeast(0)) }
        check(cycleError == null || cycleError < 1e-5) { "REGISTERS TRANSISTOR cycle did not recur: $cycleError" }
        check(seamError == null || seamError < Keys.MAX_SEAM_ERROR) { "REGISTERS TRANSISTOR loop did not close: $seamError" }
        val from = if (loop != null) 0 else (.300 * Dsp.RATE).roundToInt()
        val until = if (loop != null) samples.size else
            ((finiteSeconds(m) - (.075 + .095 * m.getValue("HOLD"))) * Dsp.RATE).roundToInt()
        val energy = (from until until).sumOf { samples[it].toDouble() * samples[it] }
        val rms = sqrt(energy / (until - from).coerceAtLeast(1))
        checkCancelled(cancelled)
        return RegistersProbe(samples, midi, requestedHz, hz, loop != null, loop?.frames,
            peak, cycleError, seamError, spread, motion, previous, bodyRms = rms,
            airflowAnalysisStartFrame = from, airflowAnalysisEndFrame = until)
    }
}
