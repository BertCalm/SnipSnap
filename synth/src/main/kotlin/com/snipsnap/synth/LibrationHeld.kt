package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

/**
 * A continuous source and modal circuit driven by a periodic traversal of actual CR3BP
 * descriptors. Traversal reverses the readout, not the audio or gravitational state.
 * Carrier and excitation phase are fitted once; no oscillator is reset at a loop boundary.
 * Three full periods settle the onset. A real preceding and following period test the
 * exported loop, including the continuously running output filters and decimator.
 */
internal object LibrationHeld {
    private const val RATE = Dsp.RATE
    private const val BLOCK_FRAMES = 64
    private const val SETTLE_CYCLES = 3
    private const val CAPTURE_CYCLES = SETTLE_CYCLES + 2
    private const val FILTER_PAD = 512
    private const val TABLE_SIZE = 32768
    private val sine = DoubleArray(TABLE_SIZE + 1) { sin(2 * PI * it / TABLE_SIZE) }

    data class Probe(
        val note: KeyNote,
        val loop: FloatArray,
        val previousCycle: FloatArray,
        val nextCycle: FloatArray,
        val seamError: Double,
        val cycleError: Double,
        val phaseError: Double,
        val stateError: Double,
        val rootCents: Double,
        val maxCorrectionHz: Double,
        val rawPeak: Double,
        val renderMilliseconds: Double,
        val particleCount: Int,
        val sourceJacobiDrift: Double,
    )

    private data class Plan(val frames: Int, val turns: Int, val hz: Double) {
        val blocks get() = frames / BLOCK_FRAMES
        val seconds get() = frames.toDouble() / RATE
    }

    private fun plan(hz: Double): Plan =
        ((3.8 * RATE).toInt() / BLOCK_FRAMES..(4.2 * RATE).toInt() / BLOCK_FRAMES).map { blocks ->
            val frames = blocks * BLOCK_FRAMES
            val turns = (hz * frames / RATE).roundToInt()
            Plan(frames, turns, turns * RATE.toDouble() / frames)
        }.minBy { abs(ln(it.hz / hz)) }.also {
            check(abs(1200 * log2(it.hz / hz)) < .1) { "LIBRATION held root fit exceeded .1 cent" }
        }

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("LIBRATION HOLD cancelled")
    }
    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    private fun wave(phase: Double): Double {
        val p = (phase - floor(phase)) * TABLE_SIZE
        val i = p.toInt()
        return sine[i] + (sine[i + 1] - sine[i]) * (p - i)
    }
    private fun relative(a: FloatArray, b: FloatArray): Double {
        var difference = 0.0
        var energy = 0.0
        for (i in a.indices) {
            val d = a[i].toDouble() - b[i]
            difference += d * d
            energy += a[i].toDouble() * a[i]
        }
        return if (energy == 0.0) 0.0 else sqrt(difference / energy)
    }

    fun probe(patch: LibrationPatch, population: Libration.Population, cancelled: () -> Boolean,
        oversample: Int = 4, normalize: Boolean = true, fitPhases: Boolean = true,
        enforceClosure: Boolean = true): Probe {
        val started = System.nanoTime()
        stop(cancelled)
        require(LibrationKeys.supports(patch)) { "LIBRATION held source model must be 3" }
        require(oversample == 4 || oversample == 16) { "LIBRATION held diagnostic oversample must be 4 or 16" }
        val controls = Libration.settled(patch.voice, patch.macros + ("HOLD" to 0f))
        require(population.key == Libration.populationKey(patch.voice, controls,
            Libration.Options(seconds = LibrationKeys.SOURCE_SECONDS), patch.model)) { "stale LIBRATION held physics" }
        val particles = population.particles
        val plan = plan(Keys.midiHz(patch.midi).toDouble())
        val internalRate = RATE * oversample
        val blockSize = BLOCK_FRAMES * oversample
        val resonance = controls.getValue("RESONANCE").toDouble()
        val velocity = patch.velocity.toDouble()
        val touch = (patch.velocity - .8f).toDouble()
        val harmonics = IntArray(particles.size) { j ->
            if (particles[j].glass) 3 + particles[j].id % 4 else 1 + particles[j].id % 3
        }

        // Shape window: retain initialization, departures and their changing material balance.
        // A raised-cosine traversal makes both turns smooth, without reversing any audio state.
        val windowEnd = minOf(5.0, population.seconds * .85)
        val fields = Array(particles.size) { j ->
            Array(plan.blocks + 1) { b ->
                val position = windowEnd * .5 * (1 - cos(2 * PI * b / plan.blocks)) * population.rate
                val k = position.toInt().coerceIn(0, particles[j].descriptors.lastIndex - 1)
                val f = (position - k).coerceIn(0.0, 1.0)
                DoubleArray(6) { q -> particles[j].descriptors[k][q] * (1 - f) + particles[j].descriptors[k + 1][q] * f }
            }
        }
        val frequencies = Array(particles.size) { j ->
            DoubleArray(plan.blocks + 1) { b -> plan.hz * harmonics[j] * 2.0.pow(fields[j][b][0] / 1200) }
        }
        val correction = DoubleArray(particles.size)
        val pulseCorrection = DoubleArray(particles.size)
        val turns = IntArray(particles.size)
        val pulseTurns = IntArray(particles.size)
        for (j in particles.indices) {
            var integral = 0.0
            var pulseIntegral = 0.0
            for (b in 0 until plan.blocks) {
                integral += .5 * (frequencies[j][b] + frequencies[j][b + 1]) * BLOCK_FRAMES / RATE
                pulseIntegral += .5 * (fields[j][b][3] + fields[j][b + 1][3]) * BLOCK_FRAMES / RATE
            }
            turns[j] = integral.roundToInt()
            pulseTurns[j] = pulseIntegral.roundToInt()
            if (fitPhases) {
                correction[j] = (turns[j] - integral) / plan.seconds
                pulseCorrection[j] = (pulseTurns[j] - pulseIntegral) / plan.seconds
            }
        }

        val phases = DoubleArray(particles.size) { j ->
            Random(Dsp.seedFor("LIBRATION_PHASE", particles[j].family.name, particles[j].id)).nextDouble()
        }
        val pulsePhases = DoubleArray(particles.size) { j ->
            Random(Dsp.seedFor("LIBRATION_EXCITE", particles[j].family.name, particles[j].id)).nextDouble()
        }
        val wholeTurns = IntArray(particles.size)
        val wholePulseTurns = IntArray(particles.size)
        val phaseStart = DoubleArray(particles.size)
        val pulseStart = DoubleArray(particles.size)
        val turnStart = IntArray(particles.size)
        val pulseTurnStart = IntArray(particles.size)
        val envelope = DoubleArray(particles.size)
        val energyScale = .23 / sqrt(particles.sumOf { it.weight * it.weight }.coerceAtLeast(1e-12))
        val modes = (1..14).filter { it * plan.hz < 17_000 }
        val bank = Modes.Bank(modes.size, internalRate)
        val t60 = .25 + 5.75 * resonance
        val participation = DoubleArray(modes.size) { j ->
            val h = modes[j].toDouble()
            val damping = 6.907755278982137 / (t60 / h.pow(.22))
            2 * damping / sqrt(h)
        }
        val pickup = DoubleArray(modes.size) { .90 / modes[it].toDouble().pow(.7) }
        for (j in modes.indices) bank.tune(j, modes[j] * plan.hz, t60 / modes[j].toDouble().pow(.22))
        val memorySend = 1.6 * sqrt(resonance)
        fun state(): DoubleArray = envelope + DoubleArray(modes.size * 2) { i ->
            val j = i / 2
            if (i % 2 == 0) bank.displacement(j) * (2 * PI * modes[j] * plan.hz) else bank.velocity(j)
        }
        var stateStart = DoubleArray(0)
        var stateEnd = DoubleArray(0)
        var phaseError = 0.0
        var rootPhase = 0.0
        var rawPeak = 0.0
        val d = Array(particles.size) { DoubleArray(6) }
        val slope = Array(particles.size) { DoubleArray(6) }
        val frequency = DoubleArray(particles.size)
        val frequencyStep = DoubleArray(particles.size)
        val pulseFrequency = DoubleArray(particles.size)
        val pulseStep = DoubleArray(particles.size)
        val partials = Array(particles.size) { DoubleArray(4) }
        val attackCoefficient = DoubleArray(particles.size)
        val totalFrames = CAPTURE_CYCLES * plan.frames + FILTER_PAD
        val raw = FloatArray(totalFrames * oversample)
        val loopBlock = SETTLE_CYCLES * plan.blocks
        for (block in 0 until totalFrames / BLOCK_FRAMES) {
            stop(cancelled)
            val local = block % plan.blocks
            if (block == loopBlock) {
                phases.copyInto(phaseStart)
                pulsePhases.copyInto(pulseStart)
                wholeTurns.copyInto(turnStart)
                wholePulseTurns.copyInto(pulseTurnStart)
                stateStart = state()
            }
            for (j in particles.indices) {
                val a = fields[j][local]
                val b = fields[j][local + 1]
                for (q in 0..5) {
                    d[j][q] = a[q]
                    slope[j][q] = (b[q] - a[q]) / blockSize
                }
                frequencyStep[j] = (frequencies[j][local + 1] - frequencies[j][local]) / blockSize
                frequency[j] = frequencies[j][local] + correction[j] + .5 * frequencyStep[j]
                pulseStep[j] = slope[j][3]
                pulseFrequency[j] = a[3] + pulseCorrection[j] + .5 * pulseStep[j]
                val brightness = a[2]
                for (k in 0..3) {
                    val base = if (particles[j].glass) doubleArrayOf(.70, .40, .20, .09)[k]
                        else doubleArrayOf(1.0, .31, .10, .035)[k]
                    val balance = if (k < 2) 1 - .55 * a[1] else 1 + .85 * a[1]
                    val spectrum = if (k == 0) 1.0 else (.30 + 1.4 * brightness) * (1 + .60 * touch)
                    val maxHz = (plan.hz * harmonics[j] * 2.0.pow(Libration.PITCH_CEILING_CENTS / 1200) +
                        abs(correction[j])) * (k + 1)
                    partials[j][k] = base * balance * spectrum * (1 - smooth((maxHz - 14_000) / 4_000))
                }
                val total = partials[j].sum().coerceAtLeast(1e-12)
                for (k in 0..3) partials[j][k] /= total
                val attack = if (particles[j].glass) .0025 + .0035 * (1 - brightness) else .012 + .016 * (1 - brightness)
                attackCoefficient[j] = 1 - exp(-1 / (internalRate * attack * (1 - .55 * touch)))
            }
            for (offset in 0 until blockSize) {
                val frame = block * blockSize + offset
                val time = frame.toDouble() / internalRate
                val supply = smooth(time / if (patch.voice == LibrationVoice.DEPARTURE) .018 else .035)
                val root = .19 * (1 - .12 * resonance) * velocity * supply *
                    (wave(rootPhase) + .15 * (1 + .40 * touch) * wave(2 * rootPhase) +
                        .055 * (1 + .40 * touch) * wave(3 * rootPhase))
                var cloud = 0.0
                for (j in particles.indices) {
                    val pulse = .5 + .5 * wave(pulsePhases[j] + .25)
                    val pulseShape = if (particles[j].glass) pulse * pulse * pulse * pulse else pulse * pulse
                    val target = supply * d[j][4] * (.12 + .88 * pulseShape)
                    envelope[j] += attackCoefficient[j] * (target - envelope[j])
                    var tone = 0.0
                    for (k in 0..3) tone += partials[j][k] * wave((k + 1) * phases[j])
                    cloud += tone * envelope[j] * particles[j].weight
                    phases[j] += frequency[j] / internalRate
                    val wraps = floor(phases[j])
                    phases[j] -= wraps
                    wholeTurns[j] += wraps.toInt()
                    pulsePhases[j] += pulseFrequency[j] / internalRate
                    val pulseWraps = floor(pulsePhases[j])
                    pulsePhases[j] -= pulseWraps
                    wholePulseTurns[j] += pulseWraps.toInt()
                    frequency[j] += frequencyStep[j]
                    pulseFrequency[j] += pulseStep[j]
                    for (q in 0..5) d[j][q] += slope[j][q]
                }
                cloud *= energyScale * velocity * (1 - .35 * resonance)
                var memory = 0.0
                if (resonance > 0) {
                    bank.step()
                    bank.drive(participation, (cloud + root * (.55 + .45 * exp(-time / .24))) * memorySend)
                    for (j in modes.indices) memory += pickup[j] * bank.velocity(j)
                }
                val sample = root + cloud + memory
                check(sample.isFinite()) { "LIBRATION HOLD non-finite audio" }
                raw[frame] = sample.toFloat()
                rawPeak = max(rawPeak, abs(sample))
                rootPhase += plan.hz / internalRate
                rootPhase -= floor(rootPhase)
            }
            if (block + 1 == loopBlock + plan.blocks) {
                stateEnd = state()
                for (j in particles.indices) {
                    phaseError = maxOf(phaseError,
                        abs(wholeTurns[j] - turnStart[j] - turns[j] + phases[j] - phaseStart[j]),
                        abs(wholePulseTurns[j] - pulseTurnStart[j] - pulseTurns[j] + pulsePhases[j] - pulseStart[j]))
                }
            }
        }
        stop(cancelled)
        Tide.bandLimit(raw, internalRate)
        stop(cancelled)
        val down = if (oversample == 4) Dsp.decimate(raw, RATE)
            else Dsp.decimate(Dsp.decimate(raw, RATE * 4), RATE)
        val mean = down.sumOf { it.toDouble() } / down.size
        val dc = Dsp.FixedOnePole(20f, RATE)
        for (i in down.indices) {
            if (i % 8192 == 0) stop(cancelled)
            val sample = (down[i] - mean).toFloat()
            down[i] = sample - dc.lp(sample)
        }
        val previousStart = (SETTLE_CYCLES - 1) * plan.frames
        val loopStart = SETTLE_CYCLES * plan.frames
        val loopEnd = loopStart + plan.frames
        val loopRaw = down.copyOfRange(loopStart, loopEnd)
        var gain = 1.0
        if (normalize && velocity > 0) {
            val loudness = Loudness.of(loopRaw, 1, RATE)
            val target = Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.7)
            val peak = down.maxOf { abs(it) }.toDouble()
            gain = minOf(if (loudness > 1e-9) target / loudness else 1.0, if (peak > 0) .94 / peak else 1.0)
        }
        val body = FloatArray(CAPTURE_CYCLES * plan.frames) { (down[it] * gain).toFloat() }
        val previous = body.copyOfRange(previousStart, loopStart)
        val loop = body.copyOfRange(loopStart, loopEnd)
        val next = body.copyOfRange(loopEnd, loopEnd + plan.frames)
        val seam = if (velocity == 0.0) 0.0 else Keys.seamError(body.copyOfRange(0, loopEnd), loopStart)
        val cycleError = maxOf(relative(loop, previous), relative(loop, next))
        var stateDifference = 0.0
        var stateEnergy = 0.0
        for (i in stateStart.indices) {
            stateDifference += (stateStart[i] - stateEnd[i]).pow(2)
            stateEnergy += stateStart[i].pow(2)
        }
        val stateError = sqrt(stateDifference / stateEnergy.coerceAtLeast(1e-30))
        if (enforceClosure) check(seam < Keys.MAX_SEAM_ERROR && cycleError < 1e-3 && phaseError < 1e-6 && stateError < 1e-3) {
            "LIBRATION HOLD failed recurrence: seam=$seam cycle=$cycleError phase=$phaseError state=$stateError"
        }
        stop(cancelled)
        return Probe(KeyNote(Snip(body.copyOf(loopEnd), 1, RATE), loopStart.toLong()), loop, previous, next,
            seam, cycleError, phaseError, stateError, 1200 * log2(plan.hz / Keys.midiHz(patch.midi)),
            correction.maxOfOrNull { abs(it) } ?: 0.0, rawPeak, (System.nanoTime() - started) / 1e6,
            particles.size, particles.maxOf { it.path.report.jacobiDrift })
    }
}
