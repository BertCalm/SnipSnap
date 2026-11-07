package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sampled, continuing exchange with the original entry in front. The pipes render a bounded
 * conversation; capture keeps its entry and a four-to-eight-second middle, with one longer
 * source retry available when the first search finds no safe join. This is
 * a looping recording of the interaction, not a promise of live memory across key presses.
 *
 * Both actual source taps receive the same blend and final gain. The last source-neighbour
 * frames preserve the original continuation into the loop; correlation and independent
 * local energy checks judge the introduced blend. Zero assembled tail error is not a claim
 * that the two physical pipe states have become recurrent.
 */
object ObbligatoHeld {
    const val VERSION = 1
    const val DEFAULT_RELEASE_SECONDS = ObbligatoPatch.DEFAULT_RELEASE_SECONDS
    const val MIN_RELEASE_SECONDS = ObbligatoPatch.MIN_RELEASE_SECONDS
    const val MAX_RELEASE_SECONDS = ObbligatoPatch.MAX_RELEASE_SECONDS
    const val CACHE_MAX_ENTRIES = 8
    const val CACHE_MAX_BYTES = 32 * 1024 * 1024
    const val MIN_LOOP_SECONDS = 4.0
    const val MAX_LOOP_SECONDS = 8.0
    const val BLEND_SECONDS = .12
    const val MIN_CORRELATION = .90
    const val MAX_BLEND_DIP_DB = 1.5
    const val MAX_LEVEL_STEP_DB = 2.5
    private const val MIN_STEM_BLEND_DB = -3.0
    private const val FIRST_LOOP_SECONDS = 1.2
    private const val SOURCE_RELEASE_SECONDS = .75
    private const val END_MARGIN_SECONDS = .05
    private const val MAX_PLANS = 64
    private const val TAIL = 256

    /** Only an otherwise valid capture without a safe loop may request the extra budget. */
    internal class NoSafeLoopException(message: String) : IllegalArgumentException(message)

    data class Diagnostics(
        val sourceSeconds: Double,
        val loopSeconds: Double,
        val blendSeconds: Double,
        val correlation: Double,
        val blendDipDb: Double,
        val levelStepDb: Double,
        val wrapStep: Double,
        val blendPeakStep: Double,
        val seamError: Double,
        val airLeadSeconds: Double,
        val reedLeadSeconds: Double,
        val actorChanges: Int,
        val searchCandidates: Int,
        val gain: Double,
        val outputPeak: Double,
        val sourceDiagnostics: Map<String, Double>,
        val silent: Boolean = false,
    )

    data class Held(
        val snip: Snip,
        val loopStartFrame: Int,
        val loopEndExclusive: Int,
        val diagnostics: Diagnostics,
        val air: FloatArray,
        val reed: FloatArray,
        val conversationEvents: List<ObbligatoConversation.Event>,
    )

    fun supports(patch: ObbligatoPatch): Boolean =
        patch.model == Obbligato.PERFORMANCE_MODEL && patch.playback == ObbligatoPlayback.HELD &&
            patch.heldVersion == VERSION && Obbligato.settled(patch.macros).getValue("HOLD") == 1f

    /** Every minor third in the tested native range, with both end notes included. */
    fun midis(patch: ObbligatoPatch): List<Int> {
        require(supports(patch)) { "OBBLIGATO instruments require held playback with HOLD at 1" }
        return (Obbligato.WIDE_MIDI_MIN..Obbligato.WIDE_MIDI_MAX step 3).toList()
    }

    private data class Key(val macros: List<Int>, val midi: Int, val velocity: Int,
        val seed: Int, val model: Int, val version: Int)
    private class Capture(val held: Held) {
        val bytes = (held.snip.samples.size.toLong() + held.air.size + held.reed.size) * Float.SIZE_BYTES
        fun owned(): Held = held.copy(snip = Snip(held.snip.samples.copyOf(), 1, Dsp.RATE),
            air = held.air.copyOf(), reed = held.reed.copyOf(),
            diagnostics = held.diagnostics.copy(sourceDiagnostics = held.diagnostics.sourceDiagnostics.toMap()),
            conversationEvents = held.conversationEvents.toList())
    }
    private val cache = LinkedHashMap<Key, Capture>(16, .75f, true)
    private var cacheBytes = 0L
    private var generation = 0L
    private var hits = 0L
    private var misses = 0L
    internal data class CacheStats(val entries: Int, val bytes: Long, val hits: Long, val misses: Long)
    internal fun cacheStats(): CacheStats = synchronized(cache) { CacheStats(cache.size, cacheBytes, hits, misses) }
    internal fun clearCache() = synchronized(cache) {
        cache.clear(); cacheBytes = 0; hits = 0; misses = 0; generation++
    }

    private fun stop(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled())
            throw CancellationException("OBBLIGATO held capture no longer wanted")
    }

    /** Caller-owned audio even on a cache hit. Name and release never change captured sound. */
    fun render(patch: ObbligatoPatch, midi: Int = patch.midi,
        cancelled: () -> Boolean = { false }): Held {
        require(supports(patch)) { "OBBLIGATO held capture requires the expanded renderer and HOLD at 1" }
        Obbligato.validateNote(midi, patch.velocity, patch.model)
        stop(cancelled)
        val macros = Obbligato.settled(patch.macros)
        val key = Key(macros.values.map { it.toBits() }, midi, patch.velocity.toBits(),
            patch.seed, patch.model, patch.heldVersion)
        val (observed, hit) = synchronized(cache) {
            val existing = cache[key]
            if (existing == null) misses++ else hits++
            generation to existing
        }
        if (hit != null) return hit.owned().also { stop(cancelled) }
        val report = Obbligato.heldReport(macros, midi, patch.velocity, patch.seed, patch.model, cancelled)
        stop(cancelled)
        val captured = try {
            Capture(capture(report, midi, macros.getValue("BOND"), cancelled))
        } catch (_: NoSafeLoopException) {
            // Soft low-register pipes can need more source time to reach a matching
            // pair of phases. Successful ordinary captures retain their exact samples.
            // Validation, non-finite audio and cancellation never enter this retry.
            stop(cancelled)
            val extended = Obbligato.heldReport(macros, midi, patch.velocity, patch.seed,
                patch.model, sourceSeconds = Obbligato.EXTENDED_HELD_SOURCE_SECONDS, cancelled = cancelled)
            stop(cancelled)
            Capture(capture(extended, midi, macros.getValue("BOND"), cancelled))
        }
        stop(cancelled)
        synchronized(cache) {
            stop(cancelled)
            if (generation == observed && captured.bytes <= CACHE_MAX_BYTES) {
                cache.put(key, captured)?.let { cacheBytes -= it.bytes }
                cacheBytes += captured.bytes
                while (cache.size > CACHE_MAX_ENTRIES || cacheBytes > CACHE_MAX_BYTES) {
                    val old = cache.entries.iterator()
                    cacheBytes -= old.next().value.bytes
                    old.remove()
                }
            }
        }
        val owned = captured.owned()
        try { stop(cancelled) } catch (e: CancellationException) {
            synchronized(cache) {
                if (cache[key] === captured) { cache.remove(key); cacheBytes -= captured.bytes }
            }
            throw e
        }
        return owned
    }

    private data class Plan(val start: Int, val length: Int, val score: Double)
    private data class Match(val length: Int, val correlation: Double)
    private data class Coverage(val air: Double, val reed: Double)
    private data class Choice(val plan: Plan, val match: Match, val audio: FloatArray,
        val air: FloatArray, val reed: FloatArray, val dip: Double, val airDip: Double,
        val reedDip: Double, val step: Double, val coverage: Coverage, val changes: Int)

    private fun capture(report: Obbligato.Report, midi: Int, bond: Float, cancelled: () -> Boolean): Held {
        val x = report.snip.samples
        require(x.size == report.air.size && x.size == report.reed.size)
        require(x.all { it.isFinite() } && report.air.all { it.isFinite() } && report.reed.all { it.isFinite() }) {
            "OBBLIGATO held MIDI $midi: source capture contains non-finite audio"
        }
        val blend = (BLEND_SECONDS * Dsp.RATE).roundToInt()
        val minimum = (MIN_LOOP_SECONDS * Dsp.RATE).roundToInt()
        val maximum = (MAX_LOOP_SECONDS * Dsp.RATE).roundToInt()
        val start = (FIRST_LOOP_SECONDS * Dsp.RATE).roundToInt()
        val ending = report.conversationEvents.firstOrNull { it.kind == "SETTLE" }?.seconds
            ?: (report.snip.durationSeconds.toDouble() - SOURCE_RELEASE_SECONDS)
        val until = minOf(x.size, ((ending - END_MARGIN_SECONDS) * Dsp.RATE).toInt())
        require(start + minimum <= until) { "OBBLIGATO held MIDI $midi: insufficient voiced source before settling" }
        if (x.maxOf { abs(it) } <= 1e-9f) {
            val audio = FloatArray(start + minimum)
            return Held(Snip(audio, 1, Dsp.RATE), start, audio.size,
                Diagnostics(report.snip.durationSeconds.toDouble(), MIN_LOOP_SECONDS, BLEND_SECONDS,
                    1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 1.0, 0.0,
                    report.diagnostics.toMap(), silent = true), audio.copyOf(), audio.copyOf(), emptyList())
        }
        val power = prefixPower(x, cancelled)
        val airPower = prefixPower(report.air, cancelled)
        val reedPower = prefixPower(report.reed, cancelled)
        val trading = report.conversationEvents.any { it.kind == "ANSWER" || it.kind == "COUNTER" }
        // Near the neighboring relationships, deliberately shallow expression need not
        // make both sources dominant. Full Play/Fight still must retain audible trades.
        val position = bond * (ObbligatoRelationship.entries.size - 1)
        val fullTrading = trading && minOf(abs(position - 1f), abs(position - 3f)) <= .35f
        val plans = plans(report, power, airPower, reedPower, start, until, minimum, maximum, blend, trading)
        var attempted = 0
        var nearest = "no candidate contains a complete exchange before settling"
        var bestCorrelation = -1.0
        var chosen: Choice? = null
        for (plan in plans) {
            stop(cancelled)
            attempted++
            val low = minimum
            val high = minOf(maximum, until - plan.start)
            if (low > high) continue
            val matches = bestMatches(x, report.air, report.reed, power, plan.start, low, high, blend, cancelled)
            if (matches.isEmpty()) {
                nearest = "no eligible joint-phase proposal at %.3f s: needs mixed correlation %.2f, "
                    .format(java.util.Locale.ROOT, plan.start.toDouble() / Dsp.RATE, MIN_CORRELATION) +
                    "non-opposed source phases and an endpoint level change within %.1f dB".format(
                        java.util.Locale.ROOT, MAX_LEVEL_STEP_DB)
            }
            for (match in matches) {
                if (match.correlation > bestCorrelation) {
                    bestCorrelation = match.correlation
                    nearest = "best local correlation %.3f (needs %.2f)".format(java.util.Locale.ROOT,
                        bestCorrelation, MIN_CORRELATION)
                }
                if (match.correlation < MIN_CORRELATION) continue
                val end = plan.start + match.length
                val fade = end - TAIL - blend
                val changes = actorChanges(report, plan.start, fade)
                val coverage = coverage(airPower, reedPower, plan.start, fade)
                if (trading && (changes < 2 || fullTrading && (coverage.air < .05 || coverage.reed < .05))) {
                    nearest = "candidate lacks two heard foreground leaders; try a stronger Play or Fight relationship"
                    continue
                }
                val audio = assemble(x, plan.start, match.length, blend, match.correlation, cancelled)
                val air = assemble(report.air, plan.start, match.length, blend, match.correlation, cancelled)
                val reed = assemble(report.reed, plan.start, match.length, blend, match.correlation, cancelled)
                // The published mixture is always the sum of these same two actual taps.
                for (i in audio.indices) audio[i] = air[i] + reed[i]
                val dip = localBlendDip(x, audio, plan.start, match.length, blend)
                val airDip = localBlendDip(report.air, air, plan.start, match.length, blend)
                val reedDip = localBlendDip(report.reed, reed, plan.start, match.length, blend)
                val step = levelStep(power, plan.start, fade)
                if (dip < -MAX_BLEND_DIP_DB || airDip < MIN_STEM_BLEND_DB || reedDip < MIN_STEM_BLEND_DB || abs(step) > MAX_LEVEL_STEP_DB) {
                    nearest = "blend changes local energy (mix %.2f dB, air %.2f dB, reed %.2f dB; endpoint %.2f dB)".format(
                        java.util.Locale.ROOT, dip, airDip, reedDip, step)
                    continue
                }
                chosen = Choice(plan, match, audio, air, reed, dip, airDip, reedDip, step, coverage, changes)
                break
            }
            if (chosen != null) break
        }
        if (chosen == null) throw NoSafeLoopException(
            "OBBLIGATO held MIDI $midi: no safe four-to-eight-second phrase loop in the " +
                "%.0f-second capture ($nearest). ".format(java.util.Locale.ROOT,
                    report.snip.durationSeconds - SOURCE_RELEASE_SECONDS) +
                "Try more BREATH, a different relationship or seed, or finite playback.")
        stop(cancelled)
        val y = chosen.audio
        val loudness = Loudness.of(y, 1, Dsp.RATE)
        val rawPeak = y.maxOf { abs(it) }.toDouble()
        val gain = if (loudness > 1e-6f) minOf(Dsp.MELODIC_LOUDNESS_TARGET / loudness.toDouble(), .99 / rawPeak) else 1.0
        val scalar = gain.toFloat()
        for (i in y.indices) {
            if (i and 4095 == 0) stop(cancelled)
            chosen.air[i] *= scalar
            chosen.reed[i] *= scalar
            y[i] = chosen.air[i] + chosen.reed[i]
        }
        val loopStart = chosen.plan.start
        val fade = y.size - TAIL - blend
        var blendPeakStep = 0.0
        for (i in maxOf(1, fade) until y.size) blendPeakStep = max(blendPeakStep, abs(y[i].toDouble() - y[i - 1]))
        val extra = report.diagnostics.toMutableMap().apply {
            this["heldAirBlendDipDb"] = chosen.airDip
            this["heldReedBlendDipDb"] = chosen.reedDip
        }
        val diagnostics = Diagnostics(report.snip.durationSeconds.toDouble(), chosen.match.length.toDouble() / Dsp.RATE,
            BLEND_SECONDS, chosen.match.correlation, chosen.dip, chosen.step,
            abs(y.last().toDouble() - y[loopStart]), blendPeakStep, Keys.seamError(y, loopStart),
            chosen.coverage.air, chosen.coverage.reed, chosen.changes, attempted, gain,
            y.maxOf { abs(it) }.toDouble(), extra.toMap())
        require(diagnostics.seamError < Keys.MAX_SEAM_ERROR) { "OBBLIGATO held MIDI $midi: assembled continuation failed" }
        return Held(Snip(y, 1, Dsp.RATE), loopStart, y.size, diagnostics, chosen.air, chosen.reed,
            report.conversationEvents.filter { it.seconds * Dsp.RATE < y.size }.toList())
    }

    private fun prefixPower(x: FloatArray, cancelled: () -> Boolean): DoubleArray {
        val out = DoubleArray(x.size + 1)
        for (i in x.indices) {
            if (i and 4095 == 0) stop(cancelled)
            out[i + 1] = out[i] + x[i].toDouble() * x[i]
        }
        return out
    }
    private fun energy(prefix: DoubleArray, from: Int, until: Int): Double =
        ((prefix[until] - prefix[from]) / (until - from)).coerceAtLeast(0.0)
    private fun levelStep(power: DoubleArray, start: Int, fade: Int): Double {
        val n = minOf((.12 * Dsp.RATE).toInt(), (fade - start) / 2)
        return 10.0 * log10((energy(power, fade - n, fade) + 1e-30) / (energy(power, start, start + n) + 1e-30))
    }

    private fun plans(report: Obbligato.Report, power: DoubleArray, air: DoubleArray,
        reed: DoubleArray, from: Int, until: Int, minimum: Int, maximum: Int, blend: Int, trading: Boolean): List<Plan> {
        val candidates = linkedMapOf<Pair<Int, Int>, Plan>()
        fun add(start: Int, length: Int) {
            if (start < from || length !in minimum..maximum || start + length > until) return
            val fade = start + length - TAIL - blend
            if (trading && actorChanges(report, start, fade) < 2) return
            val head = start - TAIL - blend
            fun mismatch(p: DoubleArray): Double = abs(log10((energy(p, head, head + blend) + 1e-20) /
                (energy(p, fade, fade + blend) + 1e-20)))
            // Preserve the endpoint's source balance as well as the heard mixed level.
            val score = mismatch(power) + .5 * (mismatch(air) + mismatch(reed)) +
                .015 * (maximum - length) / Dsp.RATE
            candidates[start to length] = Plan(start, length, score)
        }
        val hop = (.05 * Dsp.RATE).roundToInt()
        var start = from
        while (start + minimum <= until) {
            for (seconds in listOf(8.0, 7.5, 7.0, 6.5, 6.0, 5.5, 5.0, 4.5, 4.0)) add(start, (seconds * Dsp.RATE).roundToInt())
            start += hop
        }
        // A matching actor and body age make better phrase boundaries than a metronomic
        // cut. The fine lag search still chooses the actual carrier alignment.
        val entries = report.conversationEvents.filter { it.kind != "SETTLE" }
        for (a in entries) for (b in entries) {
            if (a.actor == b.actor) {
                val offset = (.36 * Dsp.RATE).roundToInt()
                add((a.seconds * Dsp.RATE).roundToInt() + offset,
                    ((b.seconds - a.seconds) * Dsp.RATE).roundToInt())
            }
        }
        val ranked = candidates.values.sortedWith(compareBy<Plan> { it.score }.thenByDescending { it.length }.thenBy { it.start })
            .distinctBy { it.start }
        // Early support transitions can have a narrow matching window: Protect's low
        // native root needs 1.55 s, which the original 150 ms grid skipped. Retain every
        // 50 ms opening candidate as well as the broad later grid, rather than letting
        // a level-only ranking discard the only phase-coherent start before FFT analysis.
        val early = ranked.filter { it.start <= from + Dsp.RATE }.sortedBy { it.start }
        val broad = ranked.filter { (it.start - from) % (3 * hop) == 0 }
        return (ranked.take(8) + early + broad + ranked).distinctBy { it.start }.take(MAX_PLANS)
    }

    private fun actorChanges(report: Obbligato.Report, start: Int, end: Int): Int {
        var previous = ObbligatoConversation.Player.AIR
        var count = 0
        for (event in report.conversationEvents) {
            if (event.actor != previous && event.kind != "SETTLE" && event.seconds * Dsp.RATE in start.toDouble()..<end.toDouble()) count++
            previous = event.actor
        }
        return count
    }

    private fun coverage(air: DoubleArray, reed: DoubleArray, from: Int, until: Int): Coverage {
        val window = (.10 * Dsp.RATE).toInt()
        val hop = (.025 * Dsp.RATE).toInt()
        var airFrames = 0
        var reedFrames = 0
        var i = from
        while (i + window <= until) {
            val a = energy(air, i, i + window)
            val r = energy(reed, i, i + window)
            if (a > r * 1.3225 && a > 1e-14) airFrames += hop
            if (r > a * 1.3225 && r > 1e-14) reedFrames += hop
            i += hop
        }
        return Coverage(airFrames.toDouble() / Dsp.RATE, reedFrames.toDouble() / Dsp.RATE)
    }

    /**
     * Box-reduced FFTs propose joint source alignments over the complete four-to-eight
     * second interval. The reduction changes analysis only. Full-rate double dot products
     * refine every proposal by at least one complete coarse hop before any audio is cut.
     */
    private fun bestMatches(x: FloatArray, air: FloatArray, reed: FloatArray,
        power: DoubleArray, start: Int, low: Int, high: Int, blend: Int,
        cancelled: () -> Boolean): List<Match> {
        val a0 = start - TAIL - blend
        val stride = 8
        val count = blend / stride
        val width = (high - low) / stride + 1
        fun correlations(source: FloatArray): DoubleArray {
            val bLength = count + width - 1
            var n = 1
            while (n < count + bLength - 1) n = n shl 1
            val ar = FloatArray(n)
            val ai = FloatArray(n)
            val br = FloatArray(n)
            val bi = FloatArray(n)
            var aa = 0.0
            val bp = DoubleArray(bLength + 1)
            fun average(from: Int): Float {
                var v = 0.0
                for (i in 0 until stride) v += source[from + i]
                return (v / stride).toFloat()
            }
            for (i in 0 until count) {
                val a = average(a0 + i * stride)
                ar[count - 1 - i] = a
                aa += a.toDouble() * a
            }
            for (i in 0 until bLength) {
                if (i and 511 == 0) stop(cancelled)
                val b = average(a0 + low + i * stride)
                br[i] = b
                bp[i + 1] = bp[i] + b.toDouble() * b
            }
            stop(cancelled); Fft.forward(ar, ai)
            stop(cancelled); Fft.forward(br, bi)
            for (i in 0 until n) {
                val re = ar[i].toDouble() * br[i] - ai[i].toDouble() * bi[i]
                val im = ar[i].toDouble() * bi[i] + ai[i].toDouble() * br[i]
                ar[i] = re.toFloat(); ai[i] = im.toFloat()
            }
            stop(cancelled); Fft.inverse(ar, ai)
            return DoubleArray(width) { j ->
                val bb = bp[j + count] - bp[j]
                if (aa * bb > 1e-30) (ar[count - 1 + j] / sqrt(aa * bb)).coerceIn(-1.0, 1.0) else -1.0
            }
        }
        val mix = correlations(x)
        val a = correlations(air)
        val r = correlations(reed)
        val score = DoubleArray(width) { j ->
            val length = low + j * stride
            val step = levelStep(power, start, start + length - TAIL - blend)
            // Keep slightly wider proposal bounds: only exact full-rate metrics decide.
            if (mix[j] < .87 || a[j] < -.2 || r[j] < -.2 || abs(step) > MAX_LEVEL_STEP_DB + .15) -100.0
            else mix[j] + .25 * (a[j] + r[j]) - .025 * abs(step)
        }
        val peaks = score.indices.filter { j ->
            score[j] > -100.0 && (j == 0 || score[j] >= score[j - 1]) &&
                (j == width - 1 || score[j] >= score[j + 1])
        }.sortedByDescending { score[it] }.take(16)
        val exact = linkedMapOf<Int, Pair<Match, Double>>()
        fun correlation(source: FloatArray, length: Int): Double {
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (j in 0 until blend) {
                val first = source[a0 + j].toDouble()
                val second = source[a0 + length + j].toDouble()
                dot += first * second; aa += first * first; bb += second * second
            }
            return if (aa * bb > 1e-30) (dot / sqrt(aa * bb)).coerceIn(-1.0, 1.0) else -1.0
        }
        for (peak in peaks) {
            val centre = low + peak * stride
            for (length in maxOf(low, centre - stride)..minOf(high, centre + stride)) {
                if (length in exact) continue
                stop(cancelled)
                val mixed = correlation(x, length)
                val ac = correlation(air, length)
                val rc = correlation(reed, length)
                val step = levelStep(power, start, start + length - TAIL - blend)
                if (mixed >= MIN_CORRELATION && ac >= 0.0 && rc >= 0.0 && abs(step) <= MAX_LEVEL_STEP_DB) {
                    exact[length] = Match(length, mixed) to (mixed + .25 * (ac + rc) - .025 * abs(step))
                }
            }
        }
        return exact.values.sortedWith(compareByDescending<Pair<Match, Double>> { it.second }.thenBy { it.first.length })
            .map { it.first }.take(8)
    }

    private fun assemble(x: FloatArray, start: Int, length: Int, blend: Int, correlation: Double,
        cancelled: () -> Boolean): FloatArray {
        val end = start + length
        val y = x.copyOf(end)
        val fade = end - TAIL - blend
        for (j in 0 until blend) {
            if (j and 1023 == 0) stop(cancelled)
            val t = .5 * PI * j / blend
            val k = sqrt(1.0 + correlation.coerceIn(0.0, 1.0) * sin(2.0 * t))
            val i = fade + j
            y[i] = ((cos(t) * x[i] + sin(t) * x[i - length]) / k).toFloat()
        }
        for (i in end - TAIL until end) y[i] = x[i - length]
        return y
    }

    /** Compare the new blend with both unmodified local source envelopes, not the phrase median. */
    private fun localBlendDip(source: FloatArray, blended: FloatArray, start: Int, length: Int, blend: Int): Double {
        val fade = start + length - TAIL - blend
        val window = (.025 * Dsp.RATE).toInt()
        val hop = maxOf(1, window / 2)
        var worst = 0.0
        var j = 0
        while (j + window <= blend) {
            var a = 0.0; var b = 0.0; var y = 0.0
            for (k in j until j + window) {
                val old = source[fade + k].toDouble()
                val next = source[fade + k - length].toDouble()
                a += old * old; b += next * next
                y += blended[fade + k].toDouble() * blended[fade + k]
            }
            val t = .5 * PI * (j + window * .5) / blend
            val f = sin(t) / (cos(t) + sin(t))
            val expected = a * (1.0 - f) + b * f
            if (expected > 1e-24) worst = minOf(worst, 10.0 * log10((y + 1e-30) / expected))
            j += hop
        }
        return worst
    }
}
