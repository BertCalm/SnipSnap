package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

/**
 * REWIND (saved key `rewind`): a reverse delay. The characteristic gesture is: the hit plays forward, then
 * the same hit comes back backwards, swelling up out of its own tail into its attack, and, with REPEAT, the
 * attack it swells into is struck again forward, then backwards again, each pass darker and quieter.
 *
 * Nothing in the rack makes that. REVERSE flips the whole pad, so the hit itself is lost; SWELL puts a
 * reversed head in front of the hit; ECHO's repeats play forward. A reverse snare today needs a second pad
 * and a hand-reversed copy.
 *
 * How it works: the pad is cut into windows of TIME's length, the first starting at the hit's onset (the
 * first frame within [ONSET_DB] of its peak, SUB's onset, moved [EDGE_SEC] earlier, into the silence before the
 * pad if need be, so the window's edge fade lands before the attack, not on it). Each window is played backwards during the next one. So the first
 * window, which holds the hit, comes back reversed starting one window after the hit, and its last frame,
 * the hit's attack, lands two windows after the hit (less 10 ms, the two edges): TIME sets when the backwards
 * attack arrives, 240 ms to 1 s after the hit. Each reversed window is faded in and out over [EDGE_SEC] (a raised cosine), so a
 * window cut in the middle of a held note does not click; on a held note that leaves a dip of about 10 ms at
 * each window, which is the stepping a reverse delay is known for.
 *
 * REPEAT feeds the reversed output back into the windows, through a one-pole low-pass at [LOOP_HZ], so a
 * repeat of a reversed window is reversed again and plays forward: backwards, forwards, backwards, each pass
 * darker, at feedback 0 to [MAX_FEEDBACK]. The first reversed copy is not darkened, because the swell into
 * the attack is the point. MIX is the wet level against the untouched dry, and the sum is matched to the
 * pad's peak, as ECHO's is.
 *
 * Why the grid starts at the onset and TIME tops out at [TIME_MAX_SEC]: the pedal brainstorm measured that a
 * reverse delay with 700 ms windows reaches 1.4 s past a short hit, past the rack's one-second tail budget,
 * which would fade the swell away just as it arrives. With windows anchored at the onset, the backwards attack
 * lands at twice TIME; at 500 ms that is one second after the hit, so on a snare (about 0.3 s long) the whole
 * swell ends inside the budget, before its 300 ms fade begins. Anchoring at the pad's end instead, as the
 * brainstorm proposed, would keep the tail short but would land the backwards attack at twice the pad's
 * length whatever TIME said, so TIME would do nothing on a drum. Repeats after the first still run into the
 * budget at long TIME: the rack fades them, as it fades ECHO's.
 *
 * Linked across channels: one onset and one grid for every channel, so the image never moves. Mono stays
 * mono.
 *
 * Sits after ECHO and before SPRING, where the brainstorm put it: after the amp, where a reverse delay sits
 * on a board, beside ECHO, and in front of the spring and the room, so a reversed swell has the same space
 * on it as the hit.
 *
 * For drums and held sounds both. At its gate (2026-11-11) the owner heard it on ONSET's snare and tom, JANGLE
 * and the choir, beside the whole pad flipped by REVERSE: the reverse snare works, JANGLE reads as backwards
 * guitar, and it is not too close to FLIP. They chose the middle TIME and some repeats (REPEAT 0.5), so the
 * REWIND chip's tap is TIME 0.5, REPEAT 0.5 and MIX 0.7 (the `rewound` character).
 */
object Rewind {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("MIX", 0.8f),                    // the reversed copy's level against the dry: 0 off .. 1 the copy at the pad's own level
        MacroSpec("TIME", 0.5f, neutral = 0.5f),   // the window, TIME_MIN_SEC .. TIME_MAX_SEC (log); its own neutral
        MacroSpec("REPEAT", 0.25f),                // feedback, 0 one reversed copy .. MAX_FEEDBACK
    )

    /** TIME's ends: the window's length, seconds. The backwards attack lands at twice this after the hit. */
    const val TIME_MIN_SEC = 0.12f
    const val TIME_MAX_SEC = 0.5f

    /** REPEAT 1's feedback: each repeat 4.4 dB under the last. */
    const val MAX_FEEDBACK = 0.6f

    /** The feedback path's low-pass, so each repeat after the first is a little darker (ECHO's darkening). */
    const val LOOP_HZ = 5000f

    /** Each reversed window's raised-cosine edge, or an eighth of the window if that is shorter. */
    const val EDGE_SEC = 0.005f

    /** How far under the pad's peak its onset is found (SUB's). */
    const val ONSET_DB = 40f

    /** How far under the pad's peak its end is found: past this the pad holds nothing a window would carry. */
    const val END_DB = 60f

    /** The most tail this section may add, seconds, before the rack's own budget (ECHO's). */
    const val MAX_TAIL_SECONDS = 1.1f

    /** At or below this MIX the section is a bypass, returning a copy of the pad. */
    const val MIX_OFF = 0.001f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The window's length for a TIME setting, in frames at [sampleRate]. */
    fun windowFrames(time: Float, sampleRate: Int): Int =
        (Dsp.expMap(time, TIME_MIN_SEC, TIME_MAX_SEC) * sampleRate).toInt().coerceAtLeast(8)

    /** REPEAT's feedback, 0 .. [MAX_FEEDBACK]. */
    fun feedback(repeat: Float): Float = MAX_FEEDBACK * repeat.coerceIn(0f, 1f)

    /** The raised-cosine edge at position [j] of a reversed window of [w] frames with [edge]-frame fades. */
    internal fun edge(j: Int, w: Int, edge: Int): Float = when {
        j < edge -> (0.5 - 0.5 * cos(PI * (j + 1) / (edge + 1))).toFloat()
        j >= w - edge -> (0.5 - 0.5 * cos(PI * (w - j) / (edge + 1))).toFloat()
        else -> 1f
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        // A value that is not a number is left at the default: a NaN would otherwise ride through the clamp into the mix.
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        val mix = m.getValue("MIX")
        if (mix <= MIX_OFF || snip.frameCount < 2) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val ch = snip.channels
        val rate = snip.sampleRate
        val frames = snip.frameCount
        val x = snip.samples
        val level = FloatArray(frames) { f -> var a = 0f; for (c in 0 until ch) a = maxOf(a, abs(x[f * ch + c])); a }
        val onset = Octaver.onset(level)
        if (onset < 0) return Snip(x.copyOf(), ch, rate)
        val peak = level.maxOrNull() ?: 0f
        val endAt = peak * 10f.pow(-END_DB / 20f)
        val end = level.indexOfLast { it >= endAt }

        val w = windowFrames(m.getValue("TIME"), rate)
        val edge = minOf((EDGE_SEC * rate).toInt(), w / 8).coerceAtLeast(1)
        // One edge before the onset, even when that is before the pad's first frame (silence there): the
        // reversed copy's closing fade then lands on the silence before the attack, never on the attack itself.
        // A drum starts within a frame or two of its first sample and peaks about 1 ms later, so clamping this to
        // 0 faded the backwards snare's attack 6 dB down.
        val start = onset - edge
        val fb = feedback(m.getValue("REPEAT"))

        // Windows that hold the pad, then the windows the repeats need to fall 60 dB, plus the one each last
        // window is reversed into; never more than MAX_TAIL_SECONDS past the pad.
        val held = (end - start) / w + 1
        val taps = if (fb > 1e-4f) ceil(-6.9078 / ln(fb.toDouble())).toInt() else 0
        val wanted = start + (held + 1 + taps).toLong() * w
        val outFrames = minOf(maxOf(frames.toLong(), wanted), frames + (MAX_TAIL_SECONDS * rate).toLong()).toInt()

        val out = FloatArray(outFrames * ch)
        for (c in 0 until ch) {
            val bus = FloatArray(outFrames)  // what the windows carry: the pad, and from REPEAT the darkened reversed output
            val y = FloatArray(outFrames)    // the reversed output
            val lp = Dsp.FixedOnePole(LOOP_HZ, rate)
            for (n in 0 until outFrames) {
                val dry = if (n < frames) x[n * ch + c] else 0f
                // Window k = (n - start) / w is reversed into window k + 1: frame n of window k + 1 at offset j
                // reads window k at offset w - 1 - j. Frames before the grid carry the pad and no output.
                if (n >= start + w) {
                    val j = (n - start) % w
                    val k = (n - start) / w
                    val from = start + (k - 1) * w + (w - 1 - j)
                    y[n] = if (from >= 0) bus[from] * edge(j, w, edge) else 0f
                }
                // The loop's darkening runs on every frame in time order, so its state never jumps.
                val back = lp.lp(y[n])
                bus[n] = dry + fb * back
                out[n * ch + c] = dry + mix * y[n]
            }
        }

        // The sum at the pad's own peak, as ECHO matches its own.
        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        if (outPeak > 1e-9f && peak > 1e-9f) {
            val g = peak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, ch, rate)
    }
}
