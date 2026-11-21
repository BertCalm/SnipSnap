package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The held key (HOLD 1) of BOWL, SCATTER and TONGUE: the one-shot's own strike, repeated by a hand.
 *
 * The first strike is the finite note's strike exactly (the same contacts at the same fractional delays on the same
 * body), so a held key starts as the kept one-shot does. Afterwards the same mallet, beads or thumb strike again, in time
 * with the note: BOWL once per period of the still hold (a felt mallet keeping a bowl singing), TONGUE twice (two thumbs
 * taking turns), SCATTER four times (a trickle of small spills). Each later strike is (1 - r) as strong as the first, r
 * the root's decay over the gap between strikes, so the ring holds the level the first strike gave it instead of
 * building up. The hand varies as the kept voices' held hand does ([CausticSustain.Human], the stronger amount the owner
 * kept): each strike its own strength and a few milliseconds late, a firmer strike also shorter (a SCATTER spill a little
 * quicker), under a slow drift of pressure that returns at the wrap. The body's modes are not touched: no pitch drifts.
 *
 * MOTION moves the lit arc (SOURCE and FOCUS, as on the kept voices: .24 and .18 times MOTION) once round the loop, so
 * each strike reads a different part of the rim (BOWL), of the arrival times (SCATTER) or of the reception (TONGUE). The
 * body is the unmoved one, so the pitch and BOWL's beating pairs stay where they are.
 *
 * The loop is four periods of the still hold, like every varied CAUSTIC hold. A linear body driven by a periodic force
 * settles to a periodic ring whatever its mode frequencies, so the inharmonic partials and BOWL's beats need no fitting;
 * the trace starts from rest and runs whole loops until the ring has settled (the same energy and state tests as
 * [CausticSustain.held]); no audio is copied to make the seam.
 */
internal object CausticCandidateHeld {
    private const val TAU = 2.0 * PI

    /** Strikes per period of the still hold. */
    fun strikesPerPeriod(voice: CausticVoice): Int = when (voice) {
        CausticVoice.BOWL -> 1
        CausticVoice.TONGUE -> 2
        CausticVoice.SCATTER -> 4
        else -> error("$voice is not a CAUSTIC candidate")
    }

    private fun stop(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("CAUSTIC held render no longer wanted")
    }

    private fun value(m: Map<String, Float>, key: String, default: Double): Double =
        m[key]?.takeIf { it.isFinite() }?.toDouble() ?: default

    /** One strike of the loop: when, how hard, how much shorter, and the contacts it lands. */
    private class Planned(val seconds: Double, val strength: Double, val widthScale: Double, val spread: Double,
        val contacts: List<CausticCandidates.Contact>, val width: Double)

    fun held(voice: CausticVoice, macros: Map<String, Float>, midi: Int, velocity: Double, oversample: Int,
        cancelled: () -> Boolean, human: CausticSustain.Human?): CausticSustain.HeldRaw {
        stop(cancelled)
        val started = System.nanoTime()
        require(CausticCandidates.handles(voice))
        require(midi in 36..84 && velocity.isFinite() && velocity in 0.0..1.0)
        require(oversample == 4 || oversample == 16)
        val m = CausticInstrument.defaults(voice) + macros.filterValues { it.isFinite() }
        val length = value(m, "LENGTH", 1.0).coerceIn(0.0, 1.0)
        val baseFrames = CausticSustain.loopFrameCount(midi, length.toFloat())
        val repeats = CausticSustain.HUMAN_REPEATS
        val frames = baseFrames * repeats
        val count = frames * oversample
        val rate = Dsp.RATE * oversample
        val hz = CausticSustain.periods(midi, length) * Dsp.RATE.toDouble() / baseFrames
        val nominal = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val source = value(m, "SOURCE", .5).coerceIn(0.0, 1.0)
        val focus = value(m, "FOCUS", .19).coerceIn(0.0, 1.0)
        val motion = value(m, "MOTION", 0.0).coerceIn(0.0, 1.0)
        val base = CausticGeometry.prepare(source, focus)
        val first = CausticCandidates.strike(voice, m, hz, velocity, base, source, focus)
        val body = first.body
        val events = strikesPerPeriod(voice) * repeats
        val loopSeconds = count.toDouble() / rate
        val gap = loopSeconds / events
        // Later strikes hold the level the first one gave the root, rather than piling up on it.
        val keep = 1.0 - exp(-ln(1000.0) * gap / body.t60[0])

        // The hand: seeded per voice and note, nothing drawn while rendering. The first strike is the note's own.
        val touch = DoubleArray(events)
        val late = DoubleArray(events)
        val drift = HoldDrift(Dsp.seedFor("CAUSTIC_CANDIDATE_HOLD", voice.name, midi, "pressure"), intArrayOf(1, 2, 3))
        if (human != null) {
            val random = kotlin.random.Random(Dsp.seedFor("CAUSTIC_CANDIDATE_HOLD", voice.name, midi, "strikes"))
            for (e in 1 until events) {
                touch[e] = 2.0 * random.nextDouble() - 1.0
                late[e] = random.nextDouble() * human.lateSeconds
            }
        }
        val stroke = human?.stroke ?: 0.0
        val swing = if (human == null) 0.0 else 10.0.pow(human.pressureDb / 20.0) - 1.0
        var sourceMin = Double.POSITIVE_INFINITY; var sourceMax = Double.NEGATIVE_INFINITY
        var focusMin = Double.POSITIVE_INFINITY; var focusMax = Double.NEGATIVE_INFINITY
        var collectionMin = Double.POSITIVE_INFINITY; var collectionMax = Double.NEGATIVE_INFINITY
        var maxDelay = 0.0
        val strikes = List(events) { e ->
            stop(cancelled)
            val phase = TAU * e / events
            val s = if (motion == 0.0) source else (source + .24 * motion * sin(phase)).coerceIn(0.0, 1.0)
            val f = if (motion == 0.0) focus else (focus + .18 * motion * sin(phase)).coerceIn(0.0, 1.0)
            val geometry = if (motion == 0.0) base else CausticGeometry.prepare(s, f)
            sourceMin = min(sourceMin, s); sourceMax = max(sourceMax, s)
            focusMin = min(focusMin, f); focusMax = max(focusMax, f)
            collectionMin = min(collectionMin, geometry.densities.sum()); collectionMax = max(collectionMax, geometry.densities.sum())
            maxDelay = max(maxDelay, geometry.paths.maxOfOrNull { it.delayR } ?: 0.0)
            val planned = if (geometry === base) first else CausticCandidates.strike(voice, m, hz, velocity, geometry, s, f)
            val strength = (1.0 + stroke * touch[e]) * (1.0 + swing * drift.at(e.toDouble() / events))
            Planned(e * gap + late[e], if (e == 0) 1.0 else strength, 1.0 - .5 * stroke * touch[e],
                1.0 + stroke * touch[e], planned.contacts, planned.width)
        }
        val modes = body.frequencies.size
        if (velocity == 0.0) {
            val diag = diagnostics(hz, nominal, frames, 1, 0.0, 0.0, 0.0, sourceMin, sourceMax, focusMin, focusMax,
                collectionMin, collectionMax, maxDelay, events, System.nanoTime() - started, 0L, 0.0)
            return CausticSustain.HeldRaw(FloatArray(4 * count + 128 * oversample), frames, frames, 2 * frames,
                body.frequencies.copyOf(), diag)
        }
        val effort = velocity.pow(.9)

        // Each mode's force over one loop, wrapped ([steady]), and what the first loop and the onset strike differ by
        // ([adjust]): nothing reaches the first loop from loops before it, and the onset strike is at full strength.
        fun drives(mode: Int): Pair<DoubleArray, DoubleArray> {
            val steady = DoubleArray(count)
            var adjust = DoubleArray(count)
            fun adjustAt(i: Int, x: Double) {
                if (i >= adjust.size) adjust = adjust.copyOf(max(i + 1, adjust.size * 2))
                adjust[i] += x
            }
            for ((e, strike) in strikes.withIndex()) {
                val width = strike.width * strike.widthScale
                val force = CausticCandidates.FORCE * .001 / width
                val scale = effort * force * strike.strength * keep
                val onsetExtra = if (e == 0) effort * force * (1.0 - keep) else 0.0
                for (contact in strike.contacts) {
                    val amplitude = contact.amplitudes[mode]
                    if (amplitude == 0.0) continue
                    val at = strike.seconds + contact.seconds * (if (voice == CausticVoice.SCATTER) strike.spread else 1.0)
                    val from = max(0, floor(at * rate).toInt())
                    val to = ceil((at + width) * rate).toInt()
                    for (i in from..to) {
                        val t = i.toDouble() / rate - at
                        if (t <= 0.0 || t >= width) continue
                        val shape = amplitude * (.5 - .5 * cos(TAU * t / width))
                        val x = shape * scale
                        val wraps = i / count
                        steady[i - wraps * count] += x
                        // Absent from history before the strike happened: the loops before this one.
                        for (k in 0 until wraps) adjustAt(i - wraps * count + k * count, -x)
                        if (onsetExtra != 0.0) adjustAt(i, shape * onsetExtra)
                    }
                }
            }
            return steady to adjust
        }

        // One causal run from rest, long enough for the slowest mode's start-up to have fallen well under the loop. Every
        // shorter settle is a prefix of it, so the loop starts at the first whole loop where the ring has settled
        // (the energy and state tests of [CausticSustain.held]); a run that has not settled by then is run longer.
        var cycles = max(1, ceil(1.2 * body.t60.max() / loopSeconds).toInt())
        while (true) {
            stop(cancelled)
            val out = FloatArray((cycles + 3) * count + 128 * oversample)
            // stateError[k]: the largest change of any mode's state over loop k, against that mode's peak in it.
            val stateError = DoubleArray(cycles + 2)
            for (mode in 0 until modes) {
                stop(cancelled)
                if (body.frequencies[mode] >= 20_000.0) continue
                val (steady, adjust) = drives(mode)
                val radius = exp(-ln(1000.0) / (body.t60[mode] * rate))
                val cr = radius * cos(TAU * body.frequencies[mode] / rate)
                val a1 = -2.0 * cr
                val a2 = radius * radius
                val b0 = 1.0 / rate
                val b1 = -cr / rate
                check(radius > 0.0 && radius < 1.0)
                var z1 = 0.0
                var z2 = 0.0
                var boundary1 = 0.0
                var boundary2 = 0.0
                var peak = 0.0
                val gain = body.gains[mode]
                var phase = 0
                for (n in out.indices) {
                    if ((n and 8191) == 0) stop(cancelled)
                    val x = steady[phase] + if (n < adjust.size) adjust[n] else 0.0
                    val y = b0 * x + z1
                    z1 = b1 * x - a1 * y + z2
                    z2 = -a2 * y
                    out[n] += (gain * y).toFloat()
                    peak = max(peak, abs(y))
                    phase++
                    if (phase == count) {
                        phase = 0
                        val k = n / count
                        if (k in 1 until stateError.size) stateError[k] = max(stateError[k],
                            hypot(z1 - boundary1, z2 - boundary2) / peak.coerceAtLeast(1e-20))
                        boundary1 = z1; boundary2 = z2; peak = 0.0
                    }
                }
            }
            for (x in out) check(x.isFinite() && abs(x) < 8f) { "CAUSTIC candidate held raw bound exceeded: $x" }
            for (prefix in 1..cycles) {
                val seam = error(out, (prefix - 1) * count, prefix * count, count)
                val continuation = error(out, prefix * count, (prefix + 1) * count, count)
                if (stateError[prefix] < 1e-5 && seam < 1e-7 && continuation < 1e-7) {
                    val trace = out.copyOf((prefix + 3) * count + 128 * oversample)
                    val diag = diagnostics(hz, nominal, frames, prefix, stateError[prefix], continuation,
                        trace.maxOf { abs(it).toDouble() }, sourceMin, sourceMax, focusMin, focusMax,
                        collectionMin, collectionMax, maxDelay, events, System.nanoTime() - started, 0L, seam)
                    return CausticSustain.HeldRaw(trace, prefix * frames, frames, (prefix + 1) * frames,
                        body.frequencies.copyOf(), diag)
                }
            }
            check(cycles < 24) { "CAUSTIC candidate held state did not settle in $cycles loops" }
            cycles = min(24, cycles * 2)
        }
    }

    private fun error(a: FloatArray, first: Int, second: Int, count: Int): Double {
        var diff = 0.0; var energy = 0.0
        for (i in 0 until count) { val d = a[first + i] - a[second + i].toDouble(); diff += d * d; energy += a[first + i].toDouble() * a[first + i] }
        return diff / energy.coerceAtLeast(1e-30)
    }

    private fun diagnostics(hz: Double, nominal: Double, frames: Int, prefix: Int, state: Double, continuation: Double,
        peak: Double, sourceMin: Double, sourceMax: Double, focusMin: Double, focusMax: Double, collectionMin: Double,
        collectionMax: Double, delay: Double, snapshots: Int, prepare: Long, render: Long, seam: Double) =
        CausticSustainDiagnostics(hz, 1200 * ln(hz / nominal) / ln(2.0), frames, prefix, state, continuation, peak,
            sourceMin, sourceMax, focusMin, focusMax, collectionMin, collectionMax, delay, snapshots, 0.0, prepare, render,
            state, seam)
}
