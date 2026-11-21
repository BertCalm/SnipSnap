package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

/**
 * The held keys of ROTOR and HOLLOW, read the way the cloud voices' held keys are ([LibrationHeld]): the solved
 * trajectories are traversed out and back over a loop of about four seconds (a raised cosine over the first five
 * seconds of the source, so both turns are smooth and nothing is reversed in the audio), the root is fitted to whole
 * turns of the loop within a tenth of a cent, and every oscillator keeps running across the wrap: each pipe's phase,
 * each HOLLOW breath and ROTOR's turning frame are corrected once to whole turns of the loop, so no phase is reset.
 * The note starts from rest exactly as the one-shot does (the same rounded opening, ROTOR's percussion ping); there is
 * no release, the player's key-up owns it. Two whole loops settle the start (nothing here rings longer than a second)
 * before the loop, and a real preceding and following loop test it. HOLLOW's was kept by ear in round held-cl-1
 * (KA5, 21 Nov 2026) and is offered; ROTOR's was not rated (KA4), so it is not in [LibrationKeys.HELD_VOICES] and no
 * patch can reach it until a verdict.
 */
internal object LibrationCandidateHeld {
    private const val RATE = Dsp.RATE
    private const val SETTLE_CYCLES = 2
    private const val CAPTURE_CYCLES = SETTLE_CYCLES + 2

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("LIBRATION HOLD cancelled")
    }
    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    private class Lane(val particle: Libration.Particle, val rate: Int) {
        val last = particle.path.report.validSamples - 1
        fun at(q: Int, seconds: Double): Double {
            val position = (seconds * rate).coerceIn(0.0, (particle.descriptors.size - 1).toDouble())
            val i = min(position.toInt(), particle.descriptors.size - 2)
            val f = position - i
            return particle.descriptors[i][q] + f * (particle.descriptors[i + 1][q] - particle.descriptors[i][q])
        }
    }

    fun handles(voice: LibrationVoice) = voice == LibrationVoice.ROTOR || voice == LibrationVoice.HOLLOW

    fun probe(patch: LibrationPatch, population: Libration.Population, cancelled: () -> Boolean,
        normalize: Boolean = true, enforceClosure: Boolean = true): LibrationHeld.Probe {
        val started = System.nanoTime()
        stop(cancelled)
        require(handles(patch.voice) && LibrationKeys.supports(patch)) { "LIBRATION ${patch.voice} has no candidate hold" }
        val controls = Libration.settled(patch.voice, patch.macros + ("HOLD" to 0f))
        require(population.key == Libration.populationKey(patch.voice, controls,
            Libration.Options(seconds = LibrationKeys.SOURCE_SECONDS), patch.model)) { "stale LIBRATION held physics" }
        val plan = LibrationHeld.plan(Keys.midiHz(patch.midi).toDouble())
        val frames = plan.frames
        val loopSeconds = frames.toDouble() / RATE
        val hz = plan.hz
        val v = patch.velocity.toDouble()
        val resonance = controls.getValue("RESONANCE").toDouble()
        val lanes = population.particles.map { Lane(it, population.rate) }
        // The traversal, out and back, in source seconds for each frame of the loop.
        val windowEnd = minOf(5.0, population.seconds * .85)
        val tau = DoubleArray(frames) { windowEnd * .5 * (1 - cos(2 * PI * it / frames)) }
        val total = CAPTURE_CYCLES * frames
        val rendered = if (v == 0.0) Raw(DoubleArray(total), 0.0, 0.0) else when (patch.voice) {
            LibrationVoice.ROTOR -> rotor(lanes, hz, v, frames, total, tau, cancelled)
            else -> hollow(lanes, hz, v, frames, total, tau, cancelled)
        }
        val raw = rendered.out
        stop(cancelled)
        var rawPeak = 0.0
        for (x in raw) { check(x.isFinite()) { "LIBRATION HOLD non-finite audio" }; rawPeak = max(rawPeak, abs(x)) }
        val loopStart = SETTLE_CYCLES * frames
        val loopEnd = loopStart + frames
        var gain = 1.0
        if (normalize && v > 0) {
            val loopRaw = FloatArray(frames) { raw[loopStart + it].toFloat() }
            val loudness = Loudness.of(loopRaw, 1, RATE)
            val target = Dsp.MELODIC_LOUDNESS_TARGET * v.pow(.7)
            gain = minOf(if (loudness > 1e-9) target / loudness else 1.0, if (rawPeak > 0) .94 / rawPeak else 1.0)
        }
        val body = FloatArray(total) { (raw[it] * gain).toFloat() }
        val previous = body.copyOfRange(loopStart - frames, loopStart)
        val loop = body.copyOfRange(loopStart, loopEnd)
        val next = body.copyOfRange(loopEnd, loopEnd + frames)
        val seam = if (v == 0.0) 0.0 else Keys.seamError(body.copyOfRange(0, loopEnd), loopStart)
        val cycleError = maxOf(relative(loop, previous), relative(loop, next))
        if (enforceClosure) check(seam < Keys.MAX_SEAM_ERROR && cycleError < 1e-3) {
            "LIBRATION ${patch.voice} HOLD failed recurrence: seam=$seam cycle=$cycleError"
        }
        stop(cancelled)
        return LibrationHeld.Probe(KeyNote(Snip(body.copyOf(loopEnd), 1, RATE), loopStart.toLong()), loop, previous, next,
            seam, cycleError, rendered.phaseError, 0.0, 1200 * log2(hz / Keys.midiHz(patch.midi)), rendered.correction, rawPeak,
            (System.nanoTime() - started) / 1e6, lanes.size, population.particles.maxOf { it.path.report.jacobiDrift })
    }

    /** The trace, the largest phase any oscillator is off by after one loop (turns), and the largest correction (Hz). */
    private class Raw(val out: DoubleArray, val phaseError: Double, val correction: Double)

    /** How far a phase that ran from [start] to [end] over one loop is from whole turns. */
    private fun offTurns(start: Double, end: Double): Double { val d = end - start; return abs(d - d.roundToInt()) }

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

    /** The correction (Hz) that makes [frequency] turn a whole number of times over one loop of [frames]. */
    private fun fit(frames: Int, frequency: (Int) -> Double): Double {
        var turns = 0.0
        for (n in 0 until frames) turns += frequency(n) / RATE
        return (turns.roundToInt() - turns) * RATE / frames
    }

    private fun rotor(lanes: List<Lane>, hz: Double, v: Double, frames: Int, total: Int, tau: DoubleArray,
        cancelled: () -> Boolean): Raw {
        val out = DoubleArray(total)
        val loopSeconds = frames.toDouble() / RATE
        val opening = .075 - .060 * v
        val tilt = 1.15 - .65 * v
        val norm = .5 * v / sqrt(lanes.sumOf { it.particle.weight * it.particle.weight }.coerceAtLeast(1e-12))
        var phaseError = 0.0
        var correctionMax = 0.0
        for (lane in lanes) {
            stop(cancelled)
            val p = lane.particle
            val pipe = LibrationCandidates.rotorPipes[p.id % LibrationCandidates.rotorPipes.size]
            val f0 = hz * pipe
            if (f0 >= LibrationCandidates.TOP_HZ) continue
            val template = LibrationTrajectory.templates.getValue(p.family)
            // The frame turns a whole number of times each loop, at least once: it comes round where it left.
            val clock = 2 * PI * max(1, (template.clock * loopSeconds / (2 * PI)).roundToInt()) / loopSeconds
            val e = LibrationTrajectory.equilibrium(p.family)
            val rest = atan2(e.y, e.x)
            val depth = .40 + .60 * min(1.0, (pipe - 1) / 5.0)
            val bend = 5.0 * depth * min(1.0, template.clock / 18.0)
            val gain = p.weight * norm * LibrationCandidates.air(f0) / pipe.toDouble().pow(tilt)
            fun bearing(n: Int): Double {
                val held = tau[n]
                return clock * n / RATE + rest + 1.4 * lane.at(1, held) + .5 * lane.at(5, held)
            }
            fun frequency(n: Int) = f0 * 2.0.pow(bend * sin(bearing(n)) / 1200)
            val correction = fit(frames, ::frequency)
            correctionMax = max(correctionMax, abs(correction))
            var phase = Random(Dsp.seedFor("LIBRATION_ROTOR", p.family.name, p.id)).nextDouble()
            var start = 0.0
            for (i in 0 until total) {
                if ((i and 8191) == 0) stop(cancelled)
                val n = i % frames
                if (i == SETTLE_CYCLES * frames) start = phase
                if (i == (SETTLE_CYCLES + 1) * frames) phaseError = max(phaseError, offTurns(start, phase))
                val t = i.toDouble() / RATE
                val held = tau[n]
                val state = p.path.states[min((held * lane.rate).toInt(), lane.last)]
                val angle = bearing(n)
                val radius = hypot(state.x, state.y).coerceIn(.5, 1.5)
                val distance = sqrt(radius * radius + 9.0 - 6.0 * radius * cos(angle))
                val near = 1 - depth * (1 - (3.0 - radius) / distance)
                val supply = smooth(t / opening)
                val bright = lane.at(2, held)
                val tone = sin(2 * PI * phase) + (.05 + .16 * bright * v) * sin(4 * PI * phase) * LibrationCandidates.air(2 * f0)
                out[i] += gain * near * supply * lane.at(4, held).coerceAtLeast(.15) * tone
                phase += (f0 * 2.0.pow(bend * sin(angle) / 1200) + correction) / RATE
                if (phase >= 1) phase -= 1.0
            }
        }
        // Touch: a hard key adds a short second-harmonic ping, as on the one-shot.
        val ping = DoubleArray(total)
        LibrationCandidates.ring(ping, 0, hz * 2, .16 * max(0.0, v - .45) * LibrationCandidates.air(hz * 2), .45, .002)
        LibrationCandidates.ring(ping, 0, hz * 3, .08 * max(0.0, v - .45) * LibrationCandidates.air(hz * 3), .30, .002)
        for (i in out.indices) out[i] += ping[i]
        return Raw(out, phaseError, correctionMax)
    }

    private fun hollow(lanes: List<Lane>, hz: Double, v: Double, frames: Int, total: Int, tau: DoubleArray,
        cancelled: () -> Boolean): Raw {
        val out = DoubleArray(total)
        val touch = v - .8
        val norm = .34 / sqrt(lanes.sumOf { it.particle.weight * it.particle.weight }.coerceAtLeast(1e-12))
        fun supply(t: Double) = smooth(t / (.060 - .040 * v))
        var correctionMax = 0.0
        var phaseError = 0.0
        for (lane in lanes) {
            stop(cancelled)
            val p = lane.particle
            val pipe = LibrationCandidates.hollowPipes[p.id % LibrationCandidates.hollowPipes.size]
            if (hz * pipe >= LibrationCandidates.TOP_HZ) continue
            val pitch = fit(frames) { n -> hz * pipe * 2.0.pow(lane.at(0, tau[n]) / 1200) }
            val pace = fit(frames) { n -> .45 * lane.at(3, tau[n]) }
            correctionMax = max(correctionMax, abs(pitch))
            var phase = Random(Dsp.seedFor("LIBRATION_HOLLOW", p.family.name, p.id)).nextDouble()
            var breath = Random(Dsp.seedFor("LIBRATION_HOLLOW_BREATH", p.family.name, p.id)).nextDouble()
            var envelope = 0.0
            val gain = p.weight * norm / pipe.toDouble().pow(.75)
            val a1 = LibrationCandidates.air(hz * pipe)
            val a3 = LibrationCandidates.air(3 * hz * pipe); val a5 = LibrationCandidates.air(5 * hz * pipe)
            val coefficient = 1 - exp(-1 / (RATE * (.030 - .012 * v)))
            var phaseStart = 0.0
            var breathStart = 0.0
            for (i in 0 until total) {
                if ((i and 8191) == 0) stop(cancelled)
                val n = i % frames
                if (i == SETTLE_CYCLES * frames) { phaseStart = phase; breathStart = breath }
                if (i == (SETTLE_CYCLES + 1) * frames)
                    phaseError = maxOf(phaseError, offTurns(phaseStart, phase), offTurns(breathStart, breath))
                val t = i.toDouble() / RATE
                val held = tau[n]
                val bright = lane.at(2, held)
                val upper = (.25 + 1.2 * bright) * (1 + .9 * touch)
                val pulse = .5 + .5 * sin(2 * PI * breath)
                envelope += coefficient * (supply(t) * lane.at(4, held) * (.45 + .55 * pulse) - envelope)
                val x = 2 * PI * phase
                out[i] += gain * a1 * envelope * (sin(x) + .33 * upper * a3 * sin(3 * x) + .16 * upper * a5 * sin(5 * x))
                phase += (hz * pipe * 2.0.pow(lane.at(0, held) / 1200) + pitch) / RATE
                if (phase >= 1) phase -= 1.0
                breath += (.45 * lane.at(3, held) + pace) / RATE
                if (breath >= 1) breath -= 1.0
            }
        }
        var phase = 0.0
        val third = .30 * (1 + .9 * touch) * LibrationCandidates.air(3 * hz)
        val fifth = .13 * (1 + 1.6 * touch) * LibrationCandidates.air(5 * hz)
        for (i in out.indices) {
            val x = 2 * PI * phase
            out[i] += .20 * supply(i.toDouble() / RATE) * (sin(x) + third * sin(3 * x) + fifth * sin(5 * x))
            phase += hz / RATE
            if (phase >= 1) phase -= 1.0
        }
        for (i in out.indices) out[i] *= v
        return Raw(out, phaseError, correctionMax)
    }
}
