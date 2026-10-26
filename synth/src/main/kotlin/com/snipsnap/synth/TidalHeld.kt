package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reconstructs the post-encounter cohort field, not the finite recording. A cosine traversal
 * revisits a late descriptor window with zero speed at both turns. It does not reverse audio,
 * reset population identity, or claim a periodic gravitational trajectory. All controls are
 * bounded interpolations of the accepted source readout, including its damped tail amplitudes.
 * Each carrier's integrated frequency is fitted to whole turns over the four-second field.
 * Continuous oscillator and conditioning state renders a real preceding and following cycle.
 */
internal object TidalHeld {
    private const val RATE = Dsp.RATE
    private const val OS = Dsp.OVERSAMPLE
    private const val BLOCK_FRAMES = 64
    private const val BLOCK = BLOCK_FRAMES * OS
    private const val INTERNAL_RATE = RATE * OS
    private const val CONTROL_RATE = 240.0
    private const val TAU = 2 * PI
    private const val JOIN_SECONDS = .35

    data class Probe(val note: KeyNote, val loop: FloatArray, val nextCycle: FloatArray,
        val previousCycle: FloatArray, val seamError: Double, val cycleError: Double,
        val rootCents: Double, val maxFrequencyCorrectionHz: Double,
        val rawPeak: Double, val windowStartSeconds: Double, val windowEndSeconds: Double,
        val joinStartFrame: Int, val joinEndFrame: Int, val maxPhaseClosureCycles: Double,
        val amplitudeMotion: Double)

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("TIDAL HOLD cancelled")
    }
    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    private data class Plan(val frames: Int, val cycles: Int, val hz: Double) {
        val blocks get() = frames / BLOCK_FRAMES
        val seconds get() = frames.toDouble() / RATE
    }
    private fun plan(hz: Double): Plan {
        // Search a narrow duration range instead of retuning the played note to an arbitrary bar.
        return ((3.8 * RATE).toInt() / BLOCK_FRAMES..(4.2 * RATE).toInt() / BLOCK_FRAMES)
            .map { blocks ->
                val frames = blocks * BLOCK_FRAMES
                val cycles = (hz * frames / RATE).roundToInt()
                Plan(frames, cycles, cycles * RATE.toDouble() / frames)
            }.minBy { abs(ln(it.hz / hz)) }.also {
                check(abs(1200 * ln(it.hz / hz) / ln(2.0)) < .1) { "TIDAL held root fit exceeded .1 cent" }
            }
    }
    private fun relative(a: FloatArray, b: FloatArray): Double {
        var difference = 0.0; var energy = 0.0
        for (i in a.indices) {
            val d = a[i].toDouble() - b[i]
            difference += d * d; energy += a[i].toDouble() * a[i]
        }
        return if (energy == 0.0) 0.0 else sqrt(difference / energy)
    }

    fun probe(patch: TidalPatch, cancelled: () -> Boolean, keepAttack: Boolean): Probe {
        stop(cancelled)
        require(patch.heldModel == TidalKeys.MODEL_VERSION) { "unsupported TIDAL held model ${patch.heldModel}" }
        val finite = patch.copy(macros = patch.macros + ("HOLD" to 0f))
        val field = Tidal.heldControls(finite, cancelled)
        val nodes = field.nodes
        val modes = nodes.first().modalAmplitude.size
        val carriers = 32 + modes + 1
        val plan = plan(field.hz)
        // Shape constants: retain a post-passage window before the finite supply fades out.
        val earliest = (field.prepared.diagnostics.encounterTimeSeconds + .15).coerceIn(.25, 5.4)
        val peak = nodes.indices.filter { it / CONTROL_RATE in earliest..6.8 }
            .maxByOrNull { nodes[it].tailAllocation } ?: (earliest * CONTROL_RATE).toInt()
        val start = (peak / CONTROL_RATE - .9).coerceIn(earliest, 5.4)
        val end = start + 1.4
        fun read(node: Tidal.Node, carrier: Int, frequency: Boolean): Double = when {
            carrier < 32 -> if (frequency) node.cohortHz[carrier] else node.cohortAmplitude[carrier]
            carrier < 32 + modes -> if (frequency) node.modalHz[carrier - 32] else node.modalAmplitude[carrier - 32]
            else -> if (frequency) field.hz else node.rootAmplitude
        }
        fun at(time: Double, carrier: Int, frequency: Boolean): Double {
            val position = time * CONTROL_RATE
            val k = position.toInt().coerceIn(0, nodes.lastIndex - 1)
            val fraction = (position - k).coerceIn(0.0, 1.0)
            return read(nodes[k], carrier, frequency) * (1 - fraction) + read(nodes[k + 1], carrier, frequency) * fraction
        }
        val amplitudes = Array(carriers) { DoubleArray(plan.blocks + 1) }
        val frequencies = Array(carriers) { DoubleArray(plan.blocks + 1) }
        val contacts = Array(carriers) { DoubleArray(plan.blocks + 1) }
        fun contact(time: Double, carrier: Int): Double {
            if (patch.model == 1 || carrier == carriers - 1) return 0.0
            val position = time * CONTROL_RATE
            val k = position.toInt().coerceIn(0, nodes.lastIndex - 1)
            val f = (position - k).coerceIn(0.0, 1.0)
            val i = if (carrier < 32) carrier else carrier - 32
            return (nodes[k].contactPhase[i] * (1 - f) + nodes[k + 1].contactPhase[i] * f) *
                if (carrier < 32) 1.0 else .60
        }
        for (block in 0..plan.blocks) {
            if (block % 64 == 0) stop(cancelled)
            val time = start + (end - start) * .5 * (1 - cos(TAU * block / plan.blocks))
            for (p in 0 until carriers) {
                amplitudes[p][block] = at(time, p, false)
                frequencies[p][block] = at(time, p, true)
                contacts[p][block] = contact(time, p)
            }
        }
        val motion = amplitudes.sumOf { it.maxOrNull()!! - it.minOrNull()!! } /
            amplitudes.sumOf { it.maxOrNull()!! }.coerceAtLeast(1e-30)
        val correction = DoubleArray(carriers)
        val turns = IntArray(carriers)
        var maxCorrection = 0.0
        for (p in 0 until carriers) {
            var integral = 0.0
            for (block in 0 until plan.blocks) integral +=
                .5 * (frequencies[p][block] + frequencies[p][block + 1]) * BLOCK_FRAMES / RATE
            turns[p] = if (p == carriers - 1 || frequencies[p].all { it == field.hz }) plan.cycles else integral.roundToInt()
            correction[p] = (turns[p] - integral) / plan.seconds
            maxCorrection = maxOf(maxCorrection, abs(correction[p]))
        }
        val initial = Dsp.phases(32 + modes + 2, Dsp.seedFor("TIDAL", patch.model, patch.seed.toString(), patch.midi))
        if (patch.model == 1) {
            val coherent = initial.copyOfRange(0, 16)
            for (i in 0 until 32) initial[i] = coherent[(i / 16) * 8 + i % 8]
        }
        for (i in 0 until 32) initial[i] *= .20
        val offsets = DoubleArray(carriers) { p -> if (p == carriers - 1) initial.last() else initial[p] }
        // Align carriers with the finite field at the start of the join. The frozen source
        // uses a left sample sum; its endpoint correction is retained in this phase estimate.
        for (p in 0 until carriers) {
            var phase = 0.0
            val steps = (start * CONTROL_RATE).toInt()
            for (k in 0 until steps) phase += .5 * (read(nodes[k], p, true) + read(nodes[k + 1], p, true)) / CONTROL_RATE
            val remainder = start - steps / CONTROL_RATE
            phase += .5 * (read(nodes[steps], p, true) + at(start, p, true)) * remainder
            phase -= .5 * (at(start, p, true) - read(nodes[0], p, true)) / INTERNAL_RATE
            offsets[p] += phase
        }
        val prerollBlocks = (RATE / 2 + BLOCK_FRAMES - 1) / BLOCK_FRAMES
        val preroll = prerollBlocks * BLOCK_FRAMES
        val mainFrames = 3 * plan.frames + BLOCK_FRAMES * 4
        val raw = FloatArray((preroll + mainFrames) * OS)
        val phases = offsets.copyOf()
        // Initialize before zero with the actual integral of the final field segment.
        for (p in 0 until carriers) for (block in plan.blocks - prerollBlocks until plan.blocks) {
            phases[p] -= (.5 * (frequencies[p][block] + frequencies[p][block + 1]) + correction[p]) * BLOCK_FRAMES / RATE
        }
        for (p in phases.indices) phases[p] -= floor(phases[p])
        var rawPeak = 0.0
        var maxClosure = 0.0
        val phaseAtZero = DoubleArray(carriers)
        val integrated = DoubleArray(carriers)
        val amplitude = DoubleArray(carriers); val amplitudeStep = DoubleArray(carriers)
        val frequency = DoubleArray(carriers); val frequencyStep = DoubleArray(carriers)
        val phaseContact = DoubleArray(carriers); val contactStep = DoubleArray(carriers)
        for (block in 0 until raw.size / BLOCK) {
            stop(cancelled)
            val local = Math.floorMod(block - prerollBlocks, plan.blocks)
            if (block == prerollBlocks) integrated.copyInto(phaseAtZero)
            for (p in 0 until carriers) {
                amplitude[p] = amplitudes[p][local]
                amplitudeStep[p] = (amplitudes[p][local + 1] - amplitude[p]) / BLOCK
                frequencyStep[p] = (frequencies[p][local + 1] - frequencies[p][local]) / BLOCK
                frequency[p] = frequencies[p][local] + correction[p] + .5 * frequencyStep[p]
                phaseContact[p] = contacts[p][local]
                contactStep[p] = (contacts[p][local + 1] - phaseContact[p]) / BLOCK
            }
            for (offset in 0 until BLOCK) {
                var sum = 0.0
                for (p in 0 until carriers) {
                    sum += amplitude[p] * Tidal.osc(phases[p] + phaseContact[p])
                    val increment = frequency[p] / INTERNAL_RATE
                    phases[p] += increment
                    phases[p] -= floor(phases[p])
                    integrated[p] += increment
                    amplitude[p] += amplitudeStep[p]; frequency[p] += frequencyStep[p]
                    phaseContact[p] += contactStep[p]
                }
                raw[block * BLOCK + offset] = sum.toFloat()
                rawPeak = maxOf(rawPeak, abs(sum))
            }
            if (block + 1 == prerollBlocks + plan.blocks) for (p in 0 until carriers)
                maxClosure = maxOf(maxClosure, abs(integrated[p] - phaseAtZero[p] - turns[p]))
        }
        check(rawPeak < .96) { "TIDAL HOLD raw allocation exceeded: $rawPeak" }
        stop(cancelled)
        Tide.bandLimit(raw, INTERNAL_RATE)
        stop(cancelled)
        val down = Dsp.decimate(raw, RATE)
        val dc = Dsp.FixedOnePole(8f, RATE)
        for (i in down.indices) {
            if (i % 8192 == 0) stop(cancelled)
            down[i] -= dc.lp(down[i])
        }
        val body = down.copyOfRange(preroll, preroll + mainFrames)
        if (patch.velocity > 0f) Dsp.levelTo(body, RATE, Dsp.MELODIC_LOUDNESS_TARGET * patch.velocity.toDouble().let { Math.pow(it, .65).toFloat() })
        val previous = body.copyOfRange(0, plan.frames)
        val loop = body.copyOfRange(plan.frames, 2 * plan.frames)
        val next = body.copyOfRange(2 * plan.frames, 3 * plan.frames)
        val seam = if (patch.velocity == 0f) 0.0 else Keys.seamError(body.copyOfRange(0, 2 * plan.frames), plan.frames)
        val cycle = maxOf(relative(loop, previous), relative(loop, next))
        check(seam < Keys.MAX_SEAM_ERROR && cycle < 1e-3 && maxClosure < 1e-6) {
            "TIDAL held field failed closure: seam=$seam, cycle=$cycle, phase=$maxClosure"
        }
        val joinStart = (start * RATE).toInt()
        val joinEnd = joinStart + (JOIN_SECONDS * RATE).toInt()
        val note = if (keepAttack) {
            stop(cancelled)
            val opening = Tidal.render(finite.voice, finite.macros, finite.midi, finite.velocity, finite.seed, finite.model, cancelled).samples
            val samples = FloatArray(joinStart + 2 * plan.frames)
            opening.copyInto(samples, 0, 0, joinStart)
            body.copyInto(samples, joinStart, 0, 2 * plan.frames)
            for (i in joinStart until joinEnd) {
                val w = smooth((i - joinStart).toDouble() / (joinEnd - joinStart - 1))
                samples[i] = ((1 - w) * opening[i] + w * samples[i]).toFloat()
            }
            KeyNote(Snip(samples, 1, RATE), (joinStart + plan.frames).toLong())
        } else KeyNote(Snip(loop.copyOf(), 1, RATE))
        stop(cancelled)
        return Probe(note, loop, next, previous, seam, cycle, 1200 * ln(plan.hz / field.hz) / ln(2.0),
            maxCorrection, rawPeak, start, end, joinStart, joinEnd, maxClosure, motion)
    }
}
