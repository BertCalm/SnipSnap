package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*

/**
 * The owner-preferred GRAIN and ANCHOR voice laws from the Python listening study.
 * Geometry is immutable and shared across voices; all sound and pressure state is local.
 * The existing normalized modal bank supplies the same passive rotations/junctions as
 * PASSAGE. No old PASSAGE model or source-phase law passes through this renderer.
 */
internal object ApsisPopulation {
    private const val BODIES = 6
    private const val CHANNELS = 4
    private const val TAU = 2 * PI
    private const val MAX_ENTRY = 4L * 1024 * 1024
    private val periodRatios = doubleArrayOf(1.0, 1.2, 1.5, 2.0, 3.0, 6.0)
    private data class MotionKey(val stretch: Int, val distance: Int, val align: Int,
        val held: Boolean, val frames: Int, val rate: Int, val stride: Int, val uniform: Boolean)
    private class Motion(val orbits: Array<ApsisOrbit.Elements>, val data: DoubleArray,
        val frames: Int, val rate: Int, val stride: Int, val uniform: Boolean,
        val residual: Double, val minRadius: Double, val maxSpeed: Double) {
        fun point(body: Int, time: Double) = if (uniform) ApsisOrbit.sampleUniformAngular(orbits[body], time)
            else ApsisOrbit.sample(orbits[body], time)
        fun controls(frame: Int, into: DoubleArray) {
            if (data.isNotEmpty()) {
                val node = if (frame == frames) (frames + stride - 1) / stride else frame / stride
                data.copyInto(into, 0, node * BODIES * CHANNELS, (node + 1) * BODIES * CHANNELS)
            } else for (body in 0 until BODIES) fill(point(body, frame.toDouble() / rate), orbits[body].a, into, body * CHANNELS)
        }
    }
    private val cache = ApsisPreparationCache<MotionKey, Motion>(maxBytes = 16L * 1024 * 1024,
        maxEntryBytes = MAX_ENTRY)
    internal fun clearPreparationCache() = cache.clear()
    internal fun preparationCacheSnapshot() = cache.snapshot()

    private fun stop(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("APSIS voice render cancelled")
    }
    private fun fill(p: ApsisOrbit.Point, axis: Double, values: DoubleArray, offset: Int) {
        val angle = atan2(p.y, p.x)
        val near = 1 / (1 + (p.radius / axis / .7).pow(4))
        val broad = exp((cos(angle + .10) - 1) / (.95 * .95))
        values[offset] = near
        values[offset + 1] = exp((cos(angle + .10) - 1) / (.28 * .28))
        values[offset + 2] = broad
        values[offset + 3] = (.5 + .5 * cos(11 * angle)).pow(6) * broad
    }

    private fun prepare(key: MotionKey, m: Map<String, Float>, inner: Double,
        cancelled: () -> Boolean, dense: Boolean): Motion {
        val eccentricity = .85 * m.getValue("STRETCH")
        val align = m.getValue("ALIGN").toDouble()
        val orbits = Array(BODIES) { body ->
            val period = inner * periodRatios[body]
            val passage = .28 + (1 - align) * (.18 + .35 * body)
            val orientation = (1 - align) * (.25 + body * .58) + align * .06 * body
            ApsisOrbit.Elements((period / .95).pow(2.0 / 3), eccentricity, period, -TAU * passage / period, orientation)
        }
        val nodes = (key.frames + key.stride - 1) / key.stride + 1
        val values = DoubleArray(if (dense) nodes * BODIES * CHANNELS else 0)
        var residual = 0.0; var minRadius = Double.POSITIVE_INFINITY; var maxSpeed = 0.0
        // Oversized diagnostic grids retain the small orbit description, not a rejected large table.
        val checkedNodes = if (dense) nodes else min(nodes, 256)
        for (node in 0 until checkedNodes) {
            if (node % 128 == 0) stop(cancelled)
            val frame = if (dense) min(node * key.stride, key.frames) else
                (node.toDouble() / max(1, checkedNodes - 1) * key.frames).roundToInt()
            for (body in 0 until BODIES) {
                val p = if (key.uniform) ApsisOrbit.sampleUniformAngular(orbits[body], frame.toDouble() / key.rate)
                    else ApsisOrbit.sample(orbits[body], frame.toDouble() / key.rate)
                residual = max(residual, abs(p.eccentricAnomaly - orbits[body].e * sin(p.eccentricAnomaly) - p.meanAnomaly))
                minRadius = min(minRadius, p.radius); maxSpeed = max(maxSpeed, p.speed)
                if (dense) fill(p, orbits[body].a, values, (node * BODIES + body) * CHANNELS)
            }
        }
        return Motion(orbits, values, key.frames, key.rate, key.stride, key.uniform, residual, minRadius, maxSpeed)
    }

    internal fun probe(voice: ApsisVoice, macros: Map<String, Float>, midi: Int, velocity: Float,
        config: ApsisConfig, options: Apsis.ProbeOptions, cancelled: () -> Boolean,
        onInternalMix: ((FloatArray) -> Unit)? = null): Apsis.Probe {
        val began = System.nanoTime()
        require(voice != ApsisVoice.PASSAGE && Apsis.supportsModel(voice, config.modelVersion)) { "unsupported APSIS $voice model ${config.modelVersion}" }
        require(midi in Apsis.MIDI_MIN..Apsis.MIDI_MAX)
        require(velocity.isFinite() && velocity in 0f..1f)
        require(options.oversample in listOf(4, 8, 16) && options.controlStride > 0)
        require(options.seconds == null || options.seconds.isFinite() && options.seconds in .1f..30f)
        require(options.ablation != Apsis.Ablation.POST_OUTPUT) { "GRAIN/ANCHOR use internal field excitation; POST_OUTPUT is a PASSAGE diagnostic" }
        require(options.directExcitationDisabled == null || options.directExcitationDisabled in 0 until BODIES)
        require(options.soloBody == null || options.soloBody in -1 until BODIES)
        stop(cancelled)
        val m = Apsis.settled(voice, macros)
        val held = Apsis.isLoop(m.getValue("HOLD"))
        val exportedLoop = held && options.seconds == null
        val grainVoice = voice == ApsisVoice.GRAIN
        val rate = Dsp.RATE * options.oversample
        val dt = 1.0 / rate
        val requestedHz = Keys.midiHz(midi).toDouble()
        val wantedInner = .6 * (2 / .6).pow(m.getValue("DISTANCE").toDouble())
        val rootCycles = (6 * wantedInner * requestedHz).roundToInt().coerceAtLeast(1)
        val loopFrames = (rootCycles * Dsp.RATE / requestedHz).roundToInt()
        val loopSeconds = loopFrames.toDouble() / Dsp.RATE
        val hz = if (held) rootCycles / loopSeconds else requestedHz
        val inner = if (held) loopSeconds / 6 else wantedInner
        val duration = options.seconds?.toDouble() ?: if (held) loopSeconds else 6.0 + 1.8 * m.getValue("HOLD")
        val sourceEnd = if (held) -1.0 else min(3.6 + 1.8 * m.getValue("HOLD"),
            if (options.seconds == null) duration else max(duration - min(2.2, duration * .25), duration * .5))
        // Model-three population voices use the study's 128-frame grid at 4x.
        val stride = max(1, options.controlStride * 2 * options.oversample / Dsp.OVERSAMPLE)
        val motionFrames = ((if (held) loopSeconds else duration) * rate).roundToInt()
        val key = MotionKey(m.getValue("STRETCH").toBits(), m.getValue("DISTANCE").toBits(),
            m.getValue("ALIGN").toBits(), held, motionFrames, rate, stride,
            options.ablation == Apsis.Ablation.UNIFORM_ANGULAR)
        val denseBytes = ((motionFrames + stride - 1L) / stride + 1) * BODIES * CHANNELS * 8
        val entryBytes = if (denseBytes <= MAX_ENTRY - 512) denseBytes + 512 else 512L
        val build = { prepare(key, m, inner, cancelled, denseBytes <= MAX_ENTRY - 512) }
        val lookup = if (options.usePreparationCache) cache.getOrPrepare(key, entryBytes, cancelled, build) else null
        val motion = lookup?.value ?: build()
        val participation = DoubleArray(BODIES) { (1.0 + 5 * m.getValue("BODIES") - it).coerceIn(0.0, 1.0) }
        val allocation = sqrt(participation.sum().coerceAtLeast(1.0))
        val ratios = if (grainVoice) doubleArrayOf(1.0, 3.17, 6.43, 10.1) else doubleArrayOf(1.0, 3.0, 5.0, 7.0)
        val frequencies = DoubleArray(27) { i ->
            if (i < 3) hz * (i + 1) else {
                val body = (i - 3) / 4; val mode = (i - 3) % 4
                hz * ratios[mode] * if (grainVoice && mode > 0) 1 + .014 * body else 1.0
            }
        }
        val bank = Apsis.Bank(rate, hz, DoubleArray(6) { ratios[3] }, frequencies)
        val decays = if (grainVoice) doubleArrayOf(.16, .065, .04, .025) else doubleArrayOf(.55, .28, .16, .09)
        val radii = DoubleArray(27) { i -> exp(-ln(1000.0) / (rate * if (i < 3) doubleArrayOf(.65, .32, .22)[i] else decays[(i - 3) % 4])) }
        val pickup = if (grainVoice) doubleArrayOf(.8, 1.8, 1.3, 1.0) else doubleArrayOf(.9, 1.8, 1.6, 1.1)
        val pressure = DoubleArray(BODIES)
        val charge = 1 - exp(-dt / .32); val release = 1 - exp(-dt / .035)
        val phaseStep = TAU * hz / rate
        val injection = .0008 * Dsp.RATE * 4 / rate
        val coupling = if (options.ablation == Apsis.Ablation.NO_COUPLING) 0.0 else m.getValue("COUPLE").toDouble()
        val traces = ArrayList<Apsis.OrbitFrame>()
        val counts = IntArray(BODIES)
        var maxNorm = 0.0; var maxRise = 0.0
        val preparationNanos = System.nanoTime() - began
        val synthBegan = System.nanoTime()
        data class Block(val mix: FloatArray, val center: FloatArray, val bodies: Array<FloatArray>)
        fun run(seconds: Double, capture: Boolean, stems: Boolean = options.recordStems): Block {
            val frames = (seconds * rate).roundToInt()
            val mix = FloatArray(frames)
            val centerStem = FloatArray(if (stems) frames else 0)
            val bodyStems = Array(BODIES) { FloatArray(if (stems) frames else 0) }
            val seed = Dsp.seedFor("APSIS_VOICE_STUDY", midi, config.seedContext)
            var noise = Dsp.Noise(seed)
            val c0 = DoubleArray(BODIES * CHANNELS); val c1 = DoubleArray(BODIES * CHANNELS)
            val inContact = BooleanArray(BODIES)
            motion.controls(0, c0); motion.controls(min(stride, motionFrames), c1)
            for (frame in 0 until frames) {
                if (frame % 2048 == 0) stop(cancelled)
                val wrapped = if (held) frame % motionFrames else frame
                if (held && wrapped == 0 && frame > 0) noise = Dsp.Noise(seed)
                if (wrapped % stride == 0) {
                    motion.controls(wrapped, c0); motion.controls(min(wrapped + stride, motionFrames), c1)
                }
                val fraction = (wrapped % stride).toDouble() / stride
                val time = frame.toDouble() / rate
                val gate = if (held) 1.0 else ((sourceEnd - time) / .25).coerceIn(0.0, 1.0) * (time / .012).coerceIn(0.0, 1.0)
                val before = if (options.recordDiagnostics) bank.energy() else 0.0
                for (i in 0 until 27) bank.rotate(i, radii[i])
                val n = if (grainVoice) noise.next().toDouble() else 0.0
                val phase = phaseStep * if (held) wrapped else frame
                val root = sin(phase)
                val third = if (grainVoice) 0.0 else sin(3 * phase)
                val fifth = if (grainVoice) 0.0 else sin(5 * phase)
                val seventh = if (grainVoice) 0.0 else sin(7 * phase)
                var centerDrive = 0.0
                for (body in 0 until BODIES) {
                    val part = participation[body]
                    if (part <= 0) continue
                    val offset = body * CHANNELS
                    val near = c0[offset] * (1 - fraction) + c1[offset] * fraction
                    val broad = c0[offset + 2] * (1 - fraction) + c1[offset + 2] * fraction
                    val grains = c0[offset + 3] * (1 - fraction) + c1[offset + 3] * fraction
                    val field = if (grainVoice) grains else broad
                    if (capture && field > .1 && !inContact[body]) counts[body]++
                    inContact[body] = field > .1
                    val target = 1 - broad
                    pressure[body] += (target - pressure[body]) * if (target > pressure[body]) charge else release
                    val amp = if (body == options.directExcitationDisabled) 0.0 else velocity * part / allocation * gate
                    val base = 3 + 4 * body
                    if (grainVoice) {
                        val force = near * grains * (.30 * root + n * (1 + velocity)) * .5
                        val material = near * grains * n * (12 + 12 * velocity)
                        bank.v[base] += injection * amp * force
                        bank.v[base + 1] += injection * amp * material
                        bank.v[base + 2] += injection * amp * material * .8
                        bank.v[base + 3] += injection * amp * material * .5
                        centerDrive += amp * grains * root * .8
                    } else {
                        val releasing = broad * pressure[body]
                        val force = releasing * (root + (.12 + .55 * velocity) * third + .22 * velocity * fifth)
                        bank.v[base] += injection * amp * force
                        bank.v[base + 1] += injection * amp * releasing * third * (.3 + velocity)
                        bank.v[base + 2] += injection * amp * releasing * fifth * (.1 + .7 * velocity)
                        bank.v[base + 3] += injection * amp * releasing * seventh * velocity * .5
                        centerDrive += amp * broad * root * .4
                    }
                    val angle = 48 * dt * coupling * part * (.15 + near)
                    bank.exchange(2, base + 1, angle)
                    bank.exchange(1, base + 2, angle)
                    if (capture && options.recordTraces && frame % stride == 0) {
                        val p = motion.point(body, time)
                        traces += Apsis.OrbitFrame(time, body, p.x, p.y, p.radius, p.speed,
                            field, coupling * (.15 + near))
                    }
                }
                bank.v[0] += injection * centerDrive
                if (options.recordDiagnostics) {
                    val energy = bank.energy()
                    check(energy.isFinite()) { "APSIS $voice non-finite state" }
                    maxNorm = max(maxNorm, sqrt(2 * energy))
                    if (gate == 0.0) maxRise = max(maxRise, energy - before)
                }
                val centerValue = .45 * bank.v[0] + (if (grainVoice) .8 else .18) * bank.v[1] +
                    (if (grainVoice) 1.1 else .12) * bank.v[2]
                var total = if (options.soloBody == null || options.soloBody == -1) centerValue else 0.0
                if (stems) centerStem[frame] = centerValue.toFloat()
                for (body in 0 until BODIES) {
                    val base = 3 + 4 * body
                    var value = 0.0
                    for (mode in 0 until 4) value += pickup[mode] * bank.radiation[base + mode] * bank.v[base + mode]
                    if (stems) bodyStems[body][frame] = value.toFloat()
                    if (options.soloBody == null || options.soloBody == body) total += value
                }
                mix[frame] = total.toFloat()
            }
            return Block(mix, centerStem, bodyStems)
        }
        fun state() = bank.snapshot() + pressure
        var stateError = 0.0; var preroll = 0
        if (exportedLoop) {
            var previous = state()
            var stable = false
            for (cycle in 1..12) {
                run(loopSeconds, false, false)
                val next = state()
                stateError = next.indices.maxOf { abs(next[it] - previous[it]) }
                previous = next; preroll = cycle
                if (cycle >= 3 && stateError < 1e-10 && stable) break
                stable = cycle >= 2 && stateError < 1e-10
            }
            check(stateError < 1e-8) { "APSIS $voice complete held state failed to converge: $stateError" }
        }
        val blocks = if (exportedLoop) listOf(run(loopSeconds, false), run(loopSeconds, false),
            run(loopSeconds, true), run(loopSeconds, false)) else listOf(run(duration, true))
        val kept = blocks[if (exportedLoop) 2 else 0]
        onInternalMix?.invoke(kept.mix)
        val synthesisNanos = System.nanoTime() - synthBegan
        val outputBegan = System.nanoTime()
        fun finish(raw: FloatArray): FloatArray {
            if (raw.isEmpty()) return raw
            var out = raw.copyOf()
            Tide.bandLimit(out, rate)
            var os = options.oversample
            while (os > 1) {
                out = Resampler.resample(Snip(out, 1, Dsp.RATE * os), Dsp.RATE * os / 2).samples
                os /= 2
            }
            val mean = out.sumOf { it.toDouble() } / out.size
            val hp = Dsp.OnePole(Dsp.RATE)
            for (i in out.indices) {
                val centered = (out[i] - mean).toFloat()
                out[i] = centered - hp.lp(centered, 20f)
            }
            if (!held) Dsp.fadeTail(out)
            return out
        }
        fun conditioned(select: (Block) -> FloatArray): FloatArray {
            val arrays = blocks.map(select)
            val joined = FloatArray(arrays.sumOf { it.size })
            var offset = 0
            for (a in arrays) { a.copyInto(joined, offset); offset += a.size }
            return finish(joined)
        }
        val all = conditioned { it.mix }
        val rawMix = if (exportedLoop) all.copyOfRange(2 * loopFrames, 3 * loopFrames) else all
        val previousCycle = if (exportedLoop) all.copyOfRange(loopFrames, 2 * loopFrames) else FloatArray(0)
        check(rawMix.all { it.isFinite() }) { "APSIS $voice non-finite conditioned audio" }
        val samples = rawMix.copyOf()
        val peak = rawMix.maxOfOrNull { abs(it).toDouble() } ?: 0.0
        if (options.normalize) Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.70f))
        if (options.normalize && peak > 0 && previousCycle.isNotEmpty()) {
            val gain = samples.maxOf { abs(it) } / peak
            for (i in previousCycle.indices) previousCycle[i] = (previousCycle[i] * gain).toFloat()
        }
        val seam = if (exportedLoop && peak > 0) Keys.seamError(previousCycle + samples, loopFrames) else 0.0
        check(!exportedLoop || seam < Keys.MAX_SEAM_ERROR) { "APSIS $voice held seam failed: $seam" }
        val periods = DoubleArray(BODIES) { motion.orbits[it].period }
        val axes = DoubleArray(BODIES) { motion.orbits[it].a }
        val diagnostics = Apsis.Diagnostics(rawPeak = peak,
            rawRms = sqrt(rawMix.sumOf { it.toDouble() * it } / rawMix.size.coerceAtLeast(1)),
            rawDc = rawMix.sumOf { it.toDouble() } / rawMix.size.coerceAtLeast(1),
            maxStateNorm = maxNorm, maxUnforcedEnergyRise = maxRise, finalEnergy = bank.energy(),
            sourceEndSeconds = sourceEnd, transientCounts = counts, preparationNanos = preparationNanos,
            synthesisNanos = synthesisNanos, outputNanos = System.nanoTime() - outputBegan,
            converged = !exportedLoop || stateError < 1e-8, prerollCycles = preroll,
            solverResidual = motion.residual,
            commonPeriodLawResidual = periods.indices.maxOf { abs(periods[it].pow(2) / axes[it].pow(3) / (.95 * .95) - 1) },
            minDistance = motion.minRadius, peakSpeed = motion.maxSpeed, axes = axes, periods = periods,
            sourcePhaseClosureError = if (held) abs(sin(TAU * rootCycles)) else 0.0,
            controlPhaseClosureError = if (held) periods.maxOf { abs(TAU * loopSeconds / it - TAU * (loopSeconds / it).roundToInt()) } else 0.0,
            eventStateClosureError = 0.0, preparationCacheHit = lookup?.hit == true,
            preparationCacheRetainedBytes = cache.snapshot().bytes, preparationEntryBytes = entryBytes,
            diagnosticsRecorded = options.recordDiagnostics)
        fun stem(select: (Block) -> FloatArray): FloatArray {
            if (!options.recordStems) return FloatArray(0)
            val result = conditioned(select)
            return if (exportedLoop) result.copyOfRange(2 * loopFrames, 3 * loopFrames) else result
        }
        return Apsis.Probe(samples, rawMix, if (options.recordStems) FloatArray(samples.size) else FloatArray(0),
            stem { it.center }, Array(BODIES) { body -> stem { it.bodies[body] } }, traces, diagnostics,
            previousCycle, seam, stateError, if (exportedLoop) loopFrames else 0, hz, emptyList())
    }
}
