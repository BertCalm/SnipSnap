package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An offline listening experiment, not a new FLOCK patch model or a factory sustain mode.
 * A continuous RIBBON simulation is captured once; its last half-second blends toward
 * the source immediately before the loop's start. Playback thereafter repeats that
 * captured movement, rather than claiming that the flock simulation is periodic.
 * HOLD remains a validated companion macro but has no effect here: held, loop and
 * release durations are explicit experiment arguments. The finite renderer is unchanged.
 */
object FlockRibbonSustain {
    const val EXPERIMENT_VERSION = 1
    const val INTRO_SECONDS = 2.0
    const val ATTACK_SECONDS = .045

    data class Plan(
        val sampleRate: Int,
        val attackFrames: Int,
        val loopStartFrame: Int,
        val loopEndExclusive: Int,
        val loopFrames: Int,
        val crossfadeFrames: Int,
        val sourceBlendStartFrame: Int,
        val sourceBlendTargetStartFrame: Int,
        val releaseStartFrame: Int,
        val releaseFrames: Int,
        val totalFrames: Int,
        /** Actual wrap positions in the expanded audition, including any during release. */
        val wrapFrames: List<Int>,
    )

    data class Edge(
        val kind: String,
        val phase: String,
        val outputFrame: Int,
        val jump: Double,
        val slopeChangeBefore: Double,
        val slopeChangeAfter: Double,
        /** Larger adjacent first-difference change, before or after this edge. */
        val slopeChange: Double,
        val localRms: Double,
        val jumpOverLocalRms: Double,
        val slopeChangeOverLocalRms: Double,
        val jumpOverNaturalP99: Double,
        val slopeChangeOverNaturalP99: Double,
    )

    data class Motion(
        val fromSeconds: Double,
        val toSeconds: Double,
        val rmsPitchDepthSemitones: Double,
        val rmsPitchSpeedSemitonesPerSecond: Double,
        val characteristicMotionHz: Double,
        /** Endpoint displacement exposes a captured initialization drift. */
        val rmsEndpointDriftSemitones: Double,
    )

    data class Diagnostics(
        val commonGain: Double,
        val rawReferencePeak: Double,
        val rawLoopedPeak: Double,
        val outputPeak: Double,
        val blendCorrelation: Double,
        /** Each 50 ms blend window versus the average power of its two source partners. */
        val blendMin50msDb: Double,
        val blendMax50msDb: Double,
        val loopRmsVsSourceDb: Double,
        val unmodifiedLoopFraction: Double,
        val naturalStepP99: Double,
        val naturalStepMax: Double,
        val naturalSlopeChangeP99: Double,
        val naturalSlopeChangeMax: Double,
        val capturedMotion: Motion,
        val continuedMotion: Motion,
        val edges: List<Edge>,
    )

    data class Probe(
        /** Same raw synthesis gain and the same attack/release on both comparison files. */
        val rawReference: FloatArray,
        val rawLooped: FloatArray,
        val rawLoopBody: FloatArray,
        /** One fixed linear gain shared by both complete examples and the exported loop. */
        val continuousReference: Snip,
        val loopedAudition: Snip,
        /** A loop-capable sample: attack/introduction followed by one loop, with no end fade. */
        val attackPlusLoop: Snip,
        val loopBody: FloatArray,
        val source: Flock.Probe,
        val plan: Plan,
        val diagnostics: Diagnostics,
    )

    fun render(
        macros: Map<String, Float> = Flock.defaults(FlockVoice.RIBBON),
        midi: Int = 60,
        velocity: Float = 1f,
        seed: Long = 42001L,
        heldSeconds: Double = 30.0,
        loopSeconds: Double = 6.0,
        crossfadeSeconds: Double = .5,
        releaseSeconds: Double = 1.5,
        oversample: Int = 4,
        cancelled: () -> Boolean = { false },
    ): Probe {
        require(loopSeconds.isFinite() && loopSeconds in .5..12.0) { "RIBBON audition loop must be .5..12 seconds" }
        require(crossfadeSeconds.isFinite() && crossfadeSeconds in .01..loopSeconds / 2.0 && crossfadeSeconds <= INTRO_SECONDS) {
            "RIBBON audition crossfade must fit the intro and at most half the loop"
        }
        require(heldSeconds.isFinite() && heldSeconds > INTRO_SECONDS + 3.0 * loopSeconds && heldSeconds <= 80.0) {
            "RIBBON audition needs at least three held wraps and at most 80 held seconds"
        }
        require(releaseSeconds.isFinite() && releaseSeconds in .1..6.0) { "RIBBON audition release must be .1..6 seconds" }
        val rate = Dsp.RATE
        val intro = (INTRO_SECONDS * rate).roundToInt()
        val length = (loopSeconds * rate).roundToInt()
        val blend = (crossfadeSeconds * rate).roundToInt()
        val held = (heldSeconds * rate).roundToInt()
        val release = (releaseSeconds * rate).roundToInt()
        val total = held + release
        require(held > intro + 3 * length) { "RIBBON audition needs at least three wraps strictly before note-off" }
        val attack = (ATTACK_SECONDS * rate).roundToInt()
        val m = Flock.settled(macros, FlockVoice.RIBBON)
        val source = Flock.renderContinuousRibbon(total.toDouble() / rate, m, midi, velocity, seed, oversample, cancelled)
        val raw = source.rawSamples
        check(raw.size == total)
        val tailStart = intro + length - blend
        val targetStart = intro - blend
        val correlation = correlation(raw, tailStart, targetStart, blend)
        val loop = raw.copyOfRange(intro, intro + length)
        val r = correlation.coerceIn(0.0, 1.0)
        for (i in 0 until blend) {
            val t = .5 * PI * smooth(i.toDouble() / (blend - 1))
            val scale = sqrt(1.0 + r * sin(2.0 * t))
            loop[length - blend + i] = ((cos(t) * raw[tailStart + i] + sin(t) * raw[targetStart + i]) / scale).toFloat()
        }
        // Exact endpoint weights retain the natural one-sample context on both sides of the wrap.
        loop[length - blend] = raw[tailStart]
        loop[length - 1] = raw[intro - 1]
        val reference = raw.copyOf()
        val played = FloatArray(total) { frame -> if (frame < intro) raw[frame] else loop[(frame - intro) % length] }
        envelope(reference, attack, held, release)
        envelope(played, attack, held, release)
        val rawReferencePeak = peak(reference)
        val rawLoopedPeak = peak(played)
        val measured = Loudness.of(snip(reference)).toDouble()
        val wanted = Dsp.MELODIC_LOUDNESS_TARGET * velocity.toDouble().pow(.65)
        val gain = if (measured > 1e-6) minOf(wanted / measured, .90 / max(rawReferencePeak, rawLoopedPeak)) else 1.0
        val referenceOut = scaled(reference, gain)
        val playedOut = scaled(played, gain)
        val loopOut = scaled(loop, gain)
        val attackLoop = played.copyOfRange(0, intro + length)
        val wraps = generateSequence(intro + length) { it + length }.takeWhile { it < total - 1 }.toList()
        val plan = Plan(rate, attack, intro, intro + length, length, blend, tailStart, targetStart, held, release, total, wraps)
        val natural = raw.copyOfRange(intro, intro + length)
        val steps = DoubleArray(natural.size - 1) { abs(natural[it + 1].toDouble() - natural[it]) }
        val changes = DoubleArray(natural.size - 2) { abs(natural[it + 2].toDouble() - 2.0 * natural[it + 1] + natural[it]) }
        val stepMax = steps.maxOrNull() ?: 0.0
        val changeMax = changes.maxOrNull() ?: 0.0
        val step99 = percentile99(steps)
        val change99 = percentile99(changes)
        val edges = mutableListOf<Edge>()
        edges += edge("intro-to-loop", intro, played, step99, change99, held)
        for (start in generateSequence(intro) { it + length }.takeWhile { it < total }) {
            val blendAt = start + length - blend
            if (blendAt < total - 1) edges += edge("blend-start", blendAt, played, step99, change99, held)
        }
        for (frame in wraps) edges += edge("loop-wrap", frame, played, step99, change99, held)
        edges += edge("note-off", held, played, step99, change99, held)
        val blendLevels = blendLevels(loop, raw, tailStart, targetStart, blend)
        val diagnostics = Diagnostics(gain, rawReferencePeak, rawLoopedPeak, max(peak(referenceOut), peak(playedOut)),
            correlation, blendLevels.minOrNull() ?: 0.0, blendLevels.maxOrNull() ?: 0.0,
            dbRatio(power(loop, 0, loop.size), power(natural, 0, natural.size)),
            (length - blend).toDouble() / length, step99, stepMax, change99, changeMax,
            motion(source.trace, INTRO_SECONDS, INTRO_SECONDS + loopSeconds),
            motion(source.trace, INTRO_SECONDS, heldSeconds), edges.sortedBy { it.outputFrame })
        return Probe(reference, played, loop, snip(referenceOut), snip(playedOut), snip(scaled(attackLoop, gain)),
            loopOut, source, plan, diagnostics)
    }

    private fun smooth(u: Double): Double = u.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun snip(x: FloatArray) = Snip(x, channels = 1, sampleRate = Dsp.RATE)
    private fun peak(x: FloatArray) = x.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
    private fun scaled(x: FloatArray, gain: Double) = FloatArray(x.size) { (x[it] * gain).toFloat() }

    private fun envelope(x: FloatArray, attack: Int, held: Int, release: Int) {
        for (i in x.indices) {
            val g = smooth(i.toDouble() / attack) * if (i < held) 1.0 else 1.0 - smooth((i - held).toDouble() / (release - 1))
            x[i] = (x[i] * g).toFloat()
        }
        x[0] = 0f
        x[x.lastIndex] = 0f
    }

    private fun correlation(x: FloatArray, a: Int, b: Int, n: Int): Double {
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until n) {
            val av = x[a + i].toDouble(); val bv = x[b + i].toDouble()
            dot += av * bv; aa += av * av; bb += bv * bv
        }
        return if (aa * bb > 1e-24) (dot / sqrt(aa * bb)).coerceIn(-1.0, 1.0) else 0.0
    }

    private fun power(x: FloatArray, from: Int, count: Int): Double {
        var sum = 0.0
        for (i in from until from + count) sum += x[i].toDouble() * x[i]
        return sum / count
    }

    private fun dbRatio(a: Double, b: Double) = 10.0 * log10((a + 1e-24) / (b + 1e-24))

    private fun blendLevels(loop: FloatArray, source: FloatArray, a: Int, b: Int, n: Int): List<Double> {
        val window = minOf(n, (Dsp.RATE * .05).roundToInt())
        val result = mutableListOf<Double>()
        for (i in 0..n - window step max(1, window / 2)) {
            result += dbRatio(power(loop, loop.size - n + i, window),
                .5 * (power(source, a + i, window) + power(source, b + i, window)))
        }
        return result
    }

    private fun percentile99(x: DoubleArray): Double {
        x.sort()
        return x[((x.size - 1) * .99).roundToInt()]
    }

    private fun edge(kind: String, frame: Int, x: FloatArray, step99: Double, change99: Double, held: Int): Edge {
        val jump = abs(x[frame].toDouble() - x[frame - 1])
        val before = abs((x[frame].toDouble() - x[frame - 1]) - (x[frame - 1].toDouble() - x[frame - 2]))
        val after = abs((x[frame + 1].toDouble() - x[frame]) - (x[frame].toDouble() - x[frame - 1]))
        val slope = max(before, after)
        val from = max(0, frame - Dsp.RATE / 20)
        val end = minOf(x.size, frame + Dsp.RATE / 20)
        val rms = sqrt(power(x, from, end - from))
        return Edge(kind, if (frame < held) "held" else "release", frame, jump, before, after, slope, rms,
            jump / max(rms, 1e-12), slope / max(rms, 1e-12),
            jump / max(step99, 1e-12), slope / max(change99, 1e-12))
    }

    private fun motion(trace: List<Flock.TraceFrame>, from: Double, to: Double): Motion {
        val frames = trace.filter { it.seconds in from..to }
        val centers = DoubleArray(Flock.PARTICLES) { i -> frames.sumOf { it.pitchSemitones[i] } / frames.size }
        val depth = sqrt(frames.sumOf { f -> centers.indices.sumOf { i -> (f.pitchSemitones[i] - centers[i]).pow(2) } } /
            (frames.size * Flock.PARTICLES))
        var speedTime = 0.0; var time = 0.0
        for ((a, b) in frames.zipWithNext()) {
            val dt = b.seconds - a.seconds
            speedTime += centers.indices.sumOf { i -> ((b.pitchSemitones[i] - a.pitchSemitones[i]) / dt).pow(2) } * dt
            time += dt
        }
        val speed = sqrt(speedTime / (time * Flock.PARTICLES))
        val drift = sqrt(centers.indices.sumOf { i -> (frames.last().pitchSemitones[i] - frames.first().pitchSemitones[i]).pow(2) } / Flock.PARTICLES)
        return Motion(from, to, depth, speed, if (depth > 1e-12) speed / (2.0 * PI * depth) else 0.0, drift)
    }
}
