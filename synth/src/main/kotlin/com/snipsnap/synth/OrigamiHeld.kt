package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A periodic oscillator field for the accepted R4 sources. Every carrier completes an integer
 * number of turns in one loop, including the integral of each moving reflected frequency.
 * The finite attack is retained verbatim and joins a phase-aligned field before the loop marker.
 * The marker and exported end are never crossfaded, faded, or repaired. An independently
 * synthesized next cycle verifies both the oscillator field and the conditioning state.
 */
internal object OrigamiHeld {
    private const val RATE = Dsp.RATE
    private const val OS = Dsp.OVERSAMPLE
    private const val INTERNAL_RATE = RATE * OS
    private const val BLOCK = 64
    private const val FRAME_GRID = BLOCK / OS
    private const val TAU = 2.0 * PI

    data class Probe(
        val note: KeyNote,
        val nextCycle: FloatArray,
        /** Post-decimation/high-pass, before gain or the onset join. */
        val rawLoop: FloatArray,
        val rawNextCycle: FloatArray,
        val midi: Int,
        val requestedHz: Double,
        val effectiveHz: Double,
        val rootCents: Double,
        val loopFrames: Int,
        val loopStartFrame: Int,
        val joinStartFrame: Int,
        val joinEndFrame: Int,
        val finitePrefixFrames: Int,
        /** Relative RMS error between two independently synthesized cycles. */
        val cycleError: Double,
        /** Maximum normalized continuation error over the first 256 frames of the wrap. */
        val seamError: Double,
        val maxFrequencyCorrectionHz: Double,
        val motionPrimaryHz: Double,
        val motionSecondaryHz: Double,
        val rawPeak: Double,
        val bodyGain: Float,
        /** Unwrapped frequency-integral residual before any modulo phase lookup. */
        val maxPhaseClosureCycles: Double = 0.0,
    )

    private data class Plan(val frames: Int, val rootCycles: Int, val rootHz: Double) {
        val seconds get() = frames.toDouble() / RATE
        val blocks get() = frames / FRAME_GRID
    }

    private data class Track(val source: Int, val wet: Boolean, val ratio: Double,
        val amplitude: Double, val seedPhase: Double)

    private data class Shape(val crease: Double, val turn: Double, val spacing: Double)

    private data class Field(
        val plan: Plan,
        val tracks: List<Track>,
        val frequencies: Array<DoubleArray>,
        val phaseCycles: Array<DoubleArray>,
        val corrections: DoubleArray,
        val offsets: DoubleArray,
        val macros: Map<String, Float>,
        val voice: OrigamiVoice,
    )

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) {
            throw CancellationException("ORIGAMI key sustain cancelled")
        }
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun aligned(frames: Int): Int = ((frames + FRAME_GRID - 1) / FRAME_GRID) * FRAME_GRID

    private fun plan(voice: OrigamiVoice, requestedHz: Double): Plan {
        val target = if (voice == OrigamiVoice.FAN) 1.0 / .35 else 10.0
        val center = (target * requestedHz / 2.0).roundToInt() * 2
        val span = maxOf(16, ceil(.12 * requestedHz / 2.0).toInt() * 2)
        var best: Plan? = null
        var score = Double.POSITIVE_INFINITY
        for (cycles in (center - span).coerceAtLeast(2)..center + span step 2) {
            val frames = (cycles * RATE / requestedHz / FRAME_GRID).roundToInt() * FRAME_GRID
            val fit = cycles * RATE.toDouble() / frames
            val cents = abs(1200.0 * ln(fit / requestedHz) / ln(2.0))
            val candidate = cents + .00001 * abs(frames.toDouble() / RATE - target)
            if (candidate < score) { score = candidate; best = Plan(frames, cycles, fit) }
        }
        return requireNotNull(best).also {
            check(abs(1200.0 * ln(it.rootHz / requestedHz) / ln(2.0)) < .1) {
                "ORIGAMI held root could not be fitted within .1 cent"
            }
        }
    }

    private fun centered(macro: Float, low: Double, center: Double, high: Double): Double =
        if (macro < .5f) low * (center / low).pow(macro.toDouble() * 2.0)
        else center * (high / center).pow((macro.toDouble() - .5) * 2.0)

    private fun shape(voice: OrigamiVoice, m: Map<String, Float>, time: Double, loop: Plan?): Shape {
        val movement = if (voice == OrigamiVoice.FAN) {
            sin(TAU * (loop?.let { 1.0 / it.seconds } ?: .35) * time)
        } else {
            val primary = loop?.let { 1.0 / it.seconds } ?: .10
            val secondary = loop?.let { 2.0 / it.seconds } ?: .17
            .75 * sin(TAU * primary * time) + .25 * sin(TAU * secondary * time)
        }
        val base = if (voice == OrigamiVoice.FAN) centered(m.getValue("CREASE"), 1.6, 2.25, 3.4)
            else centered(m.getValue("CREASE"), 2.6, 3.25, 4.4)
        val travel = if (voice == OrigamiVoice.FAN) .015 else .010
        return Shape(base + travel * m.getValue("MOTION") * movement,
            (m.getValue("TURN") - .5) * 4.0, 2.0.pow((m.getValue("WARP") - .5) * 1.8))
    }

    private fun reflected(ratio: Double, crease: Double): Double = if (ratio <= crease) ratio else 2.0 * crease - ratio

    private fun target(voice: OrigamiVoice, ratio: Double, shape: Shape): Double {
        var r = reflected(ratio, shape.crease)
        if (voice == OrigamiVoice.STILL) r = reflected(r, shape.crease - .5)
        return 1.0 + (r + shape.turn - 1.0) * shape.spacing
    }

    private fun band(hz: Double, root: Double): Double = smooth((hz - .95 * root) / (.45 * root)) *
        (1.0 - smooth((hz - 15_500.0) / 2_500.0))

    private fun pickup(voice: OrigamiVoice, hz: Double, root: Double): Double {
        val scale = if (voice == OrigamiVoice.FAN) 10.0 else 7.5
        return 1.0 / sqrt(1.0 + (hz.coerceAtLeast(0.0) / root / scale).pow(4))
    }

    private fun tracks(patch: OrigamiPatch, m: Map<String, Float>, midi: Int, requestedHz: Double): List<Track> {
        val source = Origami.partials(patch.voice, m + ("FOLD" to 0f), midi, patch.seed, model = 4)
        val random = Random(Dsp.seedFor("ORIGAMI", 1, patch.voice.name, patch.seed))
        // R4 STILL consumes two 64-value source-definition blocks before its oscillator phases.
        if (patch.voice == OrigamiVoice.STILL) repeat(128) { random.nextDouble() }
        val phases = DoubleArray(source.size) { random.nextDouble() * TAU }
        val fold = m.getValue("FOLD").toDouble()
        val out = ArrayList<Track>(1 + source.size * 2)
        out += Track(-1, false, 1.0, if (patch.voice == OrigamiVoice.FAN) .12 else .09, 0.0)
        fun append(wet: Boolean, share: Double) {
            source.forEachIndexed { index, partial ->
                val sourceBand = band(partial.sourceHz, requestedHz)
                val amplitude = if (sourceBand > 0.0) partial.sourceAmplitude / sourceBand else 0.0
                out += Track(index, wet, partial.sourceHz / requestedHz, amplitude * share, phases[index])
            }
        }
        if (fold < 1.0) append(false, 1.0 - fold)
        if (fold > 0.0) append(true, fold)
        return out
    }

    private fun boundedFrequency(track: Track, root: Double, voice: OrigamiVoice, shape: Shape): Double =
        (root * if (track.wet) target(voice, track.ratio, shape) else track.ratio).coerceIn(0.0, 18_000.0)

    private fun makeField(patch: OrigamiPatch, m: Map<String, Float>, midi: Int, requestedHz: Double,
        plan: Plan, alignFrame: Int, cancelled: () -> Boolean): Field {
        val tracks = tracks(patch, m, midi, requestedHz)
        // Static carriers need one value, not a table for every block of a long texture.
        val frequency = Array(tracks.size) { p ->
            if (tracks[p].wet) DoubleArray(plan.blocks + 1)
            else doubleArrayOf((tracks[p].ratio * plan.rootHz).coerceIn(0.0, 18_000.0))
        }
        for (block in 0 until plan.blocks) {
            if (block % 64 == 0) stop(cancelled)
            val shape = shape(patch.voice, m, block.toDouble() * BLOCK / INTERNAL_RATE, plan)
            for (p in tracks.indices) if (tracks[p].wet) {
                frequency[p][block] = boundedFrequency(tracks[p], plan.rootHz, patch.voice, shape)
            }
        }
        for (p in tracks.indices) if (tracks[p].wet) {
            stop(cancelled)
            frequency[p][plan.blocks] = frequency[p][0]
            val sourceBand = band(tracks[p].ratio * plan.rootHz, plan.rootHz)
            // Material rejected for the entire cycle cannot reappear through frequency fitting.
            if (sourceBand == 0.0 || frequency[p].none { band(it, plan.rootHz) > 0.0 }) {
                frequency[p] = doubleArrayOf(0.0)
            } else if (frequency[p].all { it == frequency[p][0] }) {
                frequency[p] = doubleArrayOf(frequency[p][0])
            }
        }
        val cycles = Array(tracks.size) { DoubleArray(if (frequency[it].size == 1) 1 else plan.blocks + 1) }
        val correction = DoubleArray(tracks.size)
        for (p in tracks.indices) {
            stop(cancelled)
            val values = frequency[p]
            var total = if (values.size == 1) values[0] * plan.seconds else 0.0
            if (values.size > 1) for (block in 0 until plan.blocks) total +=
                .5 * (values[block] + values[block + 1]) * BLOCK / INTERNAL_RATE
            val turns = if (p == 0) plan.rootCycles else total.roundToInt()
            correction[p] = (turns - total) / plan.seconds
            if (values.size > 1) for (block in 0 until plan.blocks) cycles[p][block + 1] = cycles[p][block] +
                (.5 * (values[block] + values[block + 1]) + correction[p]) * BLOCK / INTERNAL_RATE
        }
        val finiteCycles = DoubleArray(tracks.size)
        val alignOs = alignFrame * OS
        for (start in 0 until alignOs step BLOCK) {
            if (start % 4096 == 0) stop(cancelled)
            val length = minOf(BLOCK, alignOs - start)
            val a = shape(patch.voice, m, start.toDouble() / INTERNAL_RATE, null)
            val b = shape(patch.voice, m, (start + length).toDouble() / INTERNAL_RATE, null)
            for (p in tracks.indices) finiteCycles[p] += .5 *
                (boundedFrequency(tracks[p], requestedHz, patch.voice, a) +
                    boundedFrequency(tracks[p], requestedHz, patch.voice, b)) * length / INTERNAL_RATE
        }
        val field = Field(plan, tracks, frequency, cycles, correction, DoubleArray(tracks.size), m, patch.voice)
        for (p in tracks.indices) field.offsets[p] = tracks[p].seedPhase + TAU *
            (finiteCycles[p] - cyclesAt(field, p, alignOs.toLong()))
        return field
    }

    private fun cyclesAt(field: Field, p: Int, sample: Long): Double {
        val local = Math.floorMod(sample, field.plan.frames.toLong() * OS).toInt()
        val values = field.frequencies[p]
        if (values.size == 1) return (values[0] + field.corrections[p]) * local / INTERNAL_RATE
        val block = local / BLOCK
        val offset = local % BLOCK
        val start = values[block] + field.corrections[p]
        val slope = (values[block + 1] - values[block]) / BLOCK
        return field.phaseCycles[p][block] + (start * offset + .5 * slope * offset * offset) / INTERNAL_RATE
    }

    private fun gain(track: Track, field: Field, frequency: Double): Double {
        if (track.source < 0) return track.amplitude
        val sourceBand = band(track.ratio * field.plan.rootHz, field.plan.rootHz)
        val taper = if (track.wet) minOf(sourceBand, band(frequency, field.plan.rootHz)) *
            pickup(field.voice, frequency, field.plan.rootHz) else sourceBand
        return track.amplitude * taper
    }

    private data class Body(val audio: FloatArray, val rawPeak: Double)

    private fun synthesize(field: Field, velocity: Float, loopStart: Int, cancelled: () -> Boolean): Body {
        // The 8 Hz high-pass settles exponentially during a half-second preroll. At the first
        // exported loop it has had at least .78 seconds; sinc taps also have real context.
        val preroll = aligned(RATE / 2)
        val mainFrames = loopStart + 2 * field.plan.frames + 512
        val raw = FloatArray((preroll + mainFrames) * OS)
        val count = field.tracks.size
        val oscillatorSin = DoubleArray(count)
        val oscillatorCos = DoubleArray(count)
        val stepSin = DoubleArray(count)
        val stepCos = DoubleArray(count)
        val chirpSin = DoubleArray(count)
        val chirpCos = DoubleArray(count)
        val gains = DoubleArray(count)
        val increments = DoubleArray(count)
        var peak = 0.0
        for (start in raw.indices step BLOCK) {
            stop(cancelled)
            val length = minOf(BLOCK, raw.size - start)
            val absolute = start.toLong() - preroll.toLong() * OS
            val local = Math.floorMod(absolute, field.plan.frames.toLong() * OS).toInt() / BLOCK
            for (p in 0 until count) {
                val values = field.frequencies[p]
                val f0 = values[if (values.size == 1) 0 else local] + field.corrections[p]
                val f1 = values[if (values.size == 1) 0 else local + 1] + field.corrections[p]
                val delta = TAU * (f1 - f0) / (INTERNAL_RATE * BLOCK)
                val step = TAU * f0 / INTERNAL_RATE + .5 * delta
                val phase = field.offsets[p] + TAU * cyclesAt(field, p, absolute)
                oscillatorSin[p] = sin(phase); oscillatorCos[p] = cos(phase)
                stepSin[p] = sin(step); stepCos[p] = cos(step)
                chirpSin[p] = sin(delta); chirpCos[p] = cos(delta)
                gains[p] = gain(field.tracks[p], field, f0) * velocity
                increments[p] = (gain(field.tracks[p], field, f1) * velocity - gains[p]) / BLOCK
            }
            for (offset in 0 until length) {
                var sample = 0.0
                for (p in 0 until count) {
                    sample += oscillatorSin[p] * gains[p]
                    val s = oscillatorSin[p] * stepCos[p] + oscillatorCos[p] * stepSin[p]
                    oscillatorCos[p] = oscillatorCos[p] * stepCos[p] - oscillatorSin[p] * stepSin[p]
                    oscillatorSin[p] = s
                    val ds = stepSin[p] * chirpCos[p] + stepCos[p] * chirpSin[p]
                    stepCos[p] = stepCos[p] * chirpCos[p] - stepSin[p] * chirpSin[p]
                    stepSin[p] = ds
                    gains[p] += increments[p]
                }
                peak = maxOf(peak, abs(sample))
                raw[start + offset] = sample.toFloat()
            }
        }
        stop(cancelled)
        val down = decimate(raw, cancelled)
        stop(cancelled)
        val hp = Dsp.OnePole(RATE)
        for (i in down.indices) {
            if (i % 4096 == 0) stop(cancelled)
            down[i] -= hp.lp(down[i], 8f)
        }
        return Body(down.copyOfRange(preroll, preroll + mainFrames), peak)
    }

    /**
     * Cascaded 2:1 sinc support reaches 48 oversampled samples from an output center. Real
     * 64-sample context on each side makes each central chunk identical to Dsp.decimate's
     * full-buffer result, while checking cancellation between small bounded conversions.
     */
    internal fun decimate(raw: FloatArray, cancelled: () -> Boolean = { false }): FloatArray {
        require(raw.size % OS == 0) { "ORIGAMI oversampled audio must contain complete output frames" }
        val frames = raw.size / OS
        val out = FloatArray(frames)
        for (first in 0 until frames step 4096) {
            stop(cancelled)
            val length = minOf(4096, frames - first)
            val from = (first * OS - 64).coerceAtLeast(0)
            val until = ((first + length) * OS + 64).coerceAtMost(raw.size)
            val chunk = Dsp.decimate(raw.copyOfRange(from, until), RATE)
            chunk.copyInto(out, first, (first * OS - from) / OS, (first * OS - from) / OS + length)
            stop(cancelled)
        }
        return out
    }

    /**
     * The frozen finite renderer polls thread interruption rather than a cancellation callback.
     * A short-lived worker bridges that contract without changing its accepted arithmetic.
     * Only the caller evaluates cancelled(); cancelling its Future interrupts the worker.
     */
    internal fun reference(voice: OrigamiVoice, midi: Int, cancelled: () -> Boolean, render: () -> FloatArray): FloatArray {
        stop(cancelled)
        val task = FutureTask<FloatArray> { render() }
        val worker = Thread(task, "origami-held-reference-$voice-$midi").apply { isDaemon = true }
        worker.start()
        try {
            while (true) {
                stop(cancelled)
                try {
                    val result = task.get(10, TimeUnit.MILLISECONDS)
                    stop(cancelled)
                    return result
                } catch (_: TimeoutException) {
                    // Recheck the caller's cancellation signal while the finite engine runs.
                } catch (e: ExecutionException) {
                    throw (e.cause ?: e)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("ORIGAMI finite reference cancelled")
                }
            }
        } finally {
            task.cancel(true)
            // A frozen resampler pass cannot be interrupted internally. Keep this IO job in its
            // cancelling state until that bounded finite worker really exits, so replacement
            // previews cannot accumulate abandoned audio buffers. Preserve caller interruption.
            var interrupted = Thread.interrupted()
            while (worker.isAlive) {
                try { worker.join(10) } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun finiteReference(patch: OrigamiPatch, midi: Int, cancelled: () -> Boolean): FloatArray =
        reference(patch.voice, midi, cancelled) {
            if (patch.gesture == OrigamiGesture.STEADY) {
                Origami.renderRaw(patch.voice, patch.macros, patch.velocity, midi, patch.seed, model = 4)
            } else {
                OrigamiGestureRenderer.renderRaw(patch, midi)
            }
        }

    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        var error = 0.0; var energy = 0.0
        for (i in a.indices) { val d = a[i] - b[i].toDouble(); error += d * d; energy += a[i].toDouble() * a[i] }
        return if (energy <= 1e-30) 0.0 else sqrt(error / energy)
    }

    fun probe(recipe: OrigamiPatch, midi: Int, cancelled: () -> Boolean): Probe {
        stop(cancelled)
        // Resolve touch once so the accepted attack and periodic body share the same fold depth.
        val patch = OrigamiExpressions.appliedPatch(recipe)
        val bodyPatch = OrigamiGestures.settledPatch(patch)
        val m = Origami.defaults(patch.voice).mapValues { (key, default) ->
            (bodyPatch.macros[key]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f)
        }
        val rawReference = finiteReference(patch, midi, cancelled)
        val reference = rawReference.copyOf()
        if (patch.velocity > 0f) Dsp.levelTo(reference, RATE, Dsp.MELODIC_LOUDNESS_TARGET * patch.velocity.pow(.65f))
        stop(cancelled)
        val requested = Keys.midiHz(midi).toDouble()
        val plan = plan(patch.voice, requested)
        val acceptedPrefix = ((if (patch.voice == OrigamiVoice.FAN) .12 else .58) * RATE).roundToInt()
        val joinStart = if (patch.gesture == OrigamiGesture.STEADY) acceptedPrefix else maxOf(acceptedPrefix,
            ceil((OrigamiGestures.endSeconds(patch) + .04) * RATE).toInt())
        val joinEnd = aligned(joinStart + ((if (patch.voice == OrigamiVoice.FAN) .16 else .24) * RATE).roundToInt())
        val releaseAt = if (patch.voice == OrigamiVoice.FAN) 2.2 else 3.0
        check(joinEnd < releaseAt * RATE) { "ORIGAMI gesture must settle before the finite release" }
        val field = makeField(bodyPatch, m, midi, requested, plan, (joinStart + joinEnd) / 2, cancelled)
        val phaseClosure = field.tracks.indices.maxOf { p ->
            val values = field.frequencies[p]
            val turns = if (values.size == 1) (values[0] + field.corrections[p]) * plan.seconds
                else field.phaseCycles[p].last()
            abs(turns - turns.roundToInt())
        }
        check(phaseClosure < 1e-6) { "ORIGAMI unwrapped oscillator phase did not close: $phaseClosure cycles" }
        val body = synthesize(field, patch.velocity, joinEnd, cancelled)
        check(body.rawPeak.isFinite() && body.rawPeak < .31) { "ORIGAMI held raw field exceeded its amplitude budget" }
        val rawLoop = body.audio.copyOfRange(joinEnd, joinEnd + plan.frames)
        val rawNext = body.audio.copyOfRange(joinEnd + plan.frames, joinEnd + 2 * plan.frames)
        val error = relativeError(rawLoop, rawNext)
        val peak = rawLoop.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        var seam = 0.0
        for (i in 0 until minOf(256, plan.frames)) seam = maxOf(seam, abs(rawNext[i] - rawLoop[i].toDouble()))
        seam = if (peak <= 1e-20) 0.0 else seam / peak
        check(error < 1e-5 && seam < 1e-5) { "ORIGAMI periodic field did not close: cycle=$error seam=$seam" }

        // Recover the finite note's uniform level gain. Its accepted prefix is copied verbatim;
        // only the field is adjusted if a different crest factor needs a peak guard.
        var numerator = 0.0; var denominator = 0.0
        for (i in rawReference.indices) {
            if (i % 4096 == 0) stop(cancelled)
            numerator += rawReference[i].toDouble() * reference[i]
            denominator += rawReference[i].toDouble() * rawReference[i]
        }
        var bodyGain = if (denominator <= 1e-30) 1f else (numerator / denominator).toFloat()
        val bodyPeak = body.audio.maxOfOrNull { abs(it) } ?: 0f
        if (bodyPeak * bodyGain > .99f) bodyGain = .99f / bodyPeak
        val audio = FloatArray(joinEnd + plan.frames)
        for (i in audio.indices) {
            if (i % 4096 == 0) stop(cancelled)
            val periodic = body.audio[i] * bodyGain
            audio[i] = when {
                i < joinStart -> reference[i]
                i >= joinEnd -> periodic
                else -> {
                    val blend = smooth((i - joinStart).toDouble() / (joinEnd - joinStart))
                    (reference[i] * (1.0 - blend) + periodic * blend).toFloat()
                }
            }
        }
        val next = FloatArray(plan.frames) { rawNext[it] * bodyGain }
        stop(cancelled)
        return Probe(KeyNote(Snip(audio, 1, RATE), joinEnd.toLong()), next, rawLoop, rawNext, midi,
            requested, plan.rootHz, 1200.0 * ln(plan.rootHz / requested) / ln(2.0), plan.frames,
            joinEnd, joinStart, joinEnd, joinStart, error, seam,
            field.corrections.maxOfOrNull { abs(it) } ?: 0.0,
            1.0 / plan.seconds, if (patch.voice == OrigamiVoice.STILL) 2.0 / plan.seconds else 0.0,
            body.rawPeak, bodyGain, phaseClosure)
    }
}
