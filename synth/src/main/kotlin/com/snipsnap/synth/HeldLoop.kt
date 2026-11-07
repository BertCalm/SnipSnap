package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An attack-plus-loop key cut from one held render, for an engine whose
 * held note is not periodic (a breathing duet, a bowed string): the key
 * plays from the render's first frame, and the stretch from
 * [Cut.loopStart] to the end repeats.
 *
 * The join is at the loop's end, not its start. Its last [Cut.blendFrames]
 * frames cross from the loop's own material into the material that came
 * just before [Cut.loopStart] in the render, and its last [TAIL] frames are
 * that material. Played through, the jump from the last frame back to
 * [Cut.loopStart] is therefore the render's own continuation, so the attack
 * runs into the loop untouched and the wrap is seamless by the render's
 * own physics ([Keys.seamError] reads it as zero). What can go wrong is
 * the crossfade, so that is what is measured and refused:
 * - the length is chosen, within the range asked, where the two waves the
 *   crossfade blends correlate best ([Cut.correlation]), the rule BORE's
 *   held loop uses, read here from the loop's end;
 * - the fade holds power for that correlation (weights `cos t / k` and
 *   `sin t / k`, `k = sqrt(1 + r sin 2t)`), so two partly correlated waves
 *   do not dip at the middle ([Cut.blendDipDb]);
 * - the loop's level at its start and just before its blend is compared
 *   ([Cut.levelStepDb]): a held note still swelling or fading would breathe
 *   once a loop.
 *
 * Kept apart from BORE's own loop code on purpose: BORE's cut is a
 * loop-only buffer rotated onto a zero crossing, which cannot keep the
 * attack in front of it.
 */
internal object HeldLoop {
    /** The frames at the loop's end that are the render's material from just before the loop start; [Keys.seamError]'s window. */
    const val TAIL = 256

    /** The house bars for a join, BORE's ([Bore.LOOP_MIN_CORRELATION], [Bore.LOOP_MAX_DIP_DB]), plus a level step a held note may not breathe by. */
    const val MIN_CORRELATION = 0.9
    const val MAX_DIP_DB = 1.0
    const val MAX_LEVEL_STEP_DB = 1.5

    class Cut(
        /** The key: the render up to the loop's end, with the loop's end blended back into what came before [loopStart]. */
        val audio: FloatArray,
        val loopStart: Int,
        val lengthFrames: Int,
        val blendFrames: Int,
        val correlation: Double,
        val blendDipDb: Double,
        val levelStepDb: Double,
    ) {
        /** Every bar held. */
        val holds: Boolean
            get() = correlation >= MIN_CORRELATION && blendDipDb >= -MAX_DIP_DB && abs(levelStepDb) <= MAX_LEVEL_STEP_DB

        /** Why it does not hold, for a refusal that names the zone; null when it does. */
        fun fault(): String? = when {
            correlation < MIN_CORRELATION -> "the waves blended at the join correlate %.3f, floor %.2f".format(java.util.Locale.ROOT, correlation, MIN_CORRELATION)
            blendDipDb < -MAX_DIP_DB -> "the level dips %.2f dB inside the blend, limit %.1f".format(java.util.Locale.ROOT, -blendDipDb, MAX_DIP_DB)
            abs(levelStepDb) > MAX_LEVEL_STEP_DB -> "the loop's level moves %.2f dB from start to end, limit %.1f".format(java.util.Locale.ROOT, levelStepDb, MAX_LEVEL_STEP_DB)
            else -> null
        }
    }

    /**
     * The key cut from [x] (mono at [RATE]) with its loop starting at
     * [loopStart], a length searched over [lengthLow]..[lengthHigh] frames,
     * and a crossfade of [blend] frames. The loop must end inside [x]:
     * `loopStart + lengthHigh <= x.size`. Deterministic: ties go to the
     * shorter length.
     */
    fun close(x: FloatArray, loopStart: Int, lengthLow: Int, lengthHigh: Int, blend: Int): Cut {
        require(blend > 0 && lengthLow in (blend + TAIL + 1)..lengthHigh) { "a loop of $lengthLow..$lengthHigh frames cannot hold a $blend-frame blend and a $TAIL-frame tail" }
        require(loopStart >= blend + TAIL) { "no room before the loop start ($loopStart) for the material the blend crosses into" }
        require(loopStart + lengthHigh <= x.size) { "the longest loop ends past the render: ${loopStart + lengthHigh} > ${x.size}" }
        // A, the material just before the loop start that the blend crosses into.
        val a0 = loopStart - TAIL - blend
        var aa = 0.0
        for (j in 0 until blend) aa += x[a0 + j].toDouble() * x[a0 + j]
        // B(L), the loop's own material over the same span before its end, for each length.
        var bb = 0.0
        val b0Low = loopStart + lengthLow - TAIL - blend
        for (j in 0 until blend) bb += x[b0Low + j].toDouble() * x[b0Low + j]
        var best = Double.NEGATIVE_INFINITY
        var bestLength = lengthLow
        for (length in lengthLow..lengthHigh) {
            val b0 = loopStart + length - TAIL - blend
            if (length > lengthLow) {
                val gone = x[b0 - 1].toDouble()
                val came = x[b0 + blend - 1].toDouble()
                bb += came * came - gone * gone
            }
            var dot = 0.0
            for (j in 0 until blend) dot += x[a0 + j].toDouble() * x[b0 + j]
            val den = aa * bb
            val r = if (den > 1e-18) dot / sqrt(den) else 0.0
            if (r > best) {
                best = r
                bestLength = length
            }
        }
        val end = loopStart + bestLength
        val y = x.copyOf(end)
        val r = best.coerceIn(0.0, 1.0)
        val fadeStart = end - TAIL - blend
        for (j in 0 until blend) {
            val t = 0.5 * PI * j / blend
            val k = sqrt(1.0 + r * sin(2.0 * t))
            val i = fadeStart + j
            y[i] = ((cos(t) / k) * x[i] + (sin(t) / k) * x[i - bestLength]).toFloat()
        }
        for (i in end - TAIL until end) y[i] = x[i - bestLength]
        return Cut(y, loopStart, bestLength, blend, best, dipDb(y, loopStart, fadeStart, blend), levelStepDb(y, loopStart, fadeStart))
    }

    /**
     * The longest loop [close] finds in [x] that holds: for each of
     * [lengths] (±[slack] frames), longest first, the starts in
     * [from]..[until] minus the loop (every [hop] frames) are ranked by how
     * little the level moves between the loop's start and its end (read
     * off the render before any blend), the [tries] steadiest are closed,
     * and the best-correlated one that holds is taken. The first length
     * with any holding loop wins, because a longer loop repeats less
     * audibly; a shorter one is only tried when no longer one holds. Null
     * when none holds; [closest] then names the nearest miss for a refusal.
     * Deterministic: ties go to the earlier start, then the shorter length.
     */
    fun search(
        x: FloatArray, from: Int, until: Int, lengths: List<Int>, slack: Int, blend: Int,
        hop: Int = (0.05 * RATE).toInt(), tries: Int = 12,
    ): Cut? {
        for (length in lengths.sortedDescending()) {
            candidates(x, from, until, listOf(length), slack, blend, hop, tries).filter { it.holds }.maxByOrNull { it.correlation }?.let { return it }
        }
        return null
    }

    /** The nearest miss of [search] over every length (the highest-correlated candidate closed), for a refusal's message. */
    fun closest(
        x: FloatArray, from: Int, until: Int, lengths: List<Int>, slack: Int, blend: Int,
        hop: Int = (0.05 * RATE).toInt(), tries: Int = 12,
    ): Cut? = lengths.flatMap { candidates(x, from, until, listOf(it), slack, blend, hop, tries) }.maxByOrNull { it.correlation }

    private fun candidates(x: FloatArray, from: Int, until: Int, lengths: List<Int>, slack: Int, blend: Int, hop: Int, tries: Int): List<Cut> {
        require(hop > 0 && tries > 0 && slack >= 0) { "hop $hop, tries $tries, slack $slack" }
        val window = (0.2 * RATE).toInt()
        data class Plan(val start: Int, val length: Int, val step: Double)
        val plans = ArrayList<Plan>()
        for (length in lengths) {
            val low = length - slack
            val high = length + slack
            var start = maxOf(from, blend + TAIL)
            while (start + high <= minOf(until, x.size)) {
                val fade = start + length - TAIL - blend
                val n = minOf(window, (fade - start) / 2)
                if (n > 0 && low > blend + TAIL) plans += Plan(start, length, abs(levelDb(x, fade - n, n) - levelDb(x, start, n)))
                start += hop
            }
        }
        return plans.sortedWith(compareBy<Plan> { it.step }.thenBy { it.start }.thenBy { it.length }).take(tries)
            .map { close(x, it.start, it.length - slack, it.length + slack, blend) }
    }

    private fun levelDb(x: FloatArray, from: Int, n: Int): Double {
        var sum = 0.0
        for (i in from until from + n) sum += x[i].toDouble() * x[i]
        return 10.0 * log10(sum / n + 1e-20)
    }

    /** The quietest 50 ms window inside the blend minus the median 50 ms window of the loop outside it, in dB. */
    private fun dipDb(y: FloatArray, loopStart: Int, fadeStart: Int, blend: Int): Double {
        val window = (0.05 * RATE).toInt()
        val hop = window / 2
        val inside = ArrayList<Double>()
        var p = fadeStart
        while (p + window <= fadeStart + blend) { inside.add(levelDb(y, p, window)); p += hop }
        val outside = ArrayList<Double>()
        p = loopStart
        while (p + window <= fadeStart) { outside.add(levelDb(y, p, window)); p += hop }
        if (inside.isEmpty() || outside.isEmpty()) return 0.0
        outside.sort()
        return inside.min() - outside[outside.size / 2]
    }

    /** The loop's level over its last 200 ms before the blend minus its first 200 ms, in dB. */
    private fun levelStepDb(y: FloatArray, loopStart: Int, fadeStart: Int): Double {
        val n = minOf((0.2 * RATE).toInt(), (fadeStart - loopStart) / 2)
        return levelDb(y, fadeStart - n, n) - levelDb(y, loopStart, n)
    }
}
