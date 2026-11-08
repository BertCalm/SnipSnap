package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/**
 * HUSH — a gate: the pad stops when it gets quiet. The characteristic gesture is: the hit sounds as it is,
 * its tail falls, and when the level drops FLOOR under the pad's peak the sound is shut, over CLOSE, to
 * true silence. The 80s gated snare is a room shut off at a fixed time; a CHUG is cut where its note ends.
 * The rack has nothing that closes on level: SQUASH and COMPRESSOR lift, the pre-amp fade and STOP are
 * fixed in time, and keyed GATE is a tempo chop.
 *
 * The saved key is `hush` (the pedal brainstorm's name), not `choke`: in this app a choke is a choke group,
 * the MPC's mute group in `kit.json` and the kit writers, and one word must not mean two things there.
 *
 * The detector is the loudest sample of any channel over a 15 ms window centred on the frame (a linked
 * detector, one gain for every channel, so the image never narrows; the window looks 7.5 ms ahead, so a
 * reopening gate is open before the next hit lands, and is long enough to span a low E's half period).
 * The gate:
 *  - closes when the level has stayed under the floor for [HOLD_SEC], so a tail hovering at the floor
 *    does not chatter, and reopens only [HYSTERESIS_DB] above it;
 *  - closes on a raised cosine that reaches zero, so a shut gate is silence, not a quiet tail;
 *  - opens on a [OPEN_SEC] raised cosine, fast but with no click;
 *  - never closes before the pad has first reached the opening level, so a late-peaking pad (a swell, a
 *    FADE IN) does not gate its own quiet head.
 *
 * FLOOR is the threshold, in dB under the pad's own peak, so it means the same on a loud and a quiet pad:
 * [FLOOR_TOP_DB] + [FLOOR_SPAN_DB] x (1 - FLOOR) / FLOOR, which is infinitely deep as FLOOR reaches 0 (so
 * the gate fades out into FLOOR 0's exact bypass, with no jump), 12 dB at FLOOR 0.7 (a tap, the owner's
 * pick) and [FLOOR_TOP_DB] at the top (only the loudest part of the hit survives). A straight line in dB
 * could not do both: a tap is 70 percent of the knob, so the 12 dB the owner chose would have needed a
 * bottom only 26 dB deep, and the first notch of AMT would have jumped from no gate to a 26 dB one.
 * CLOSE is the release, [CLOSE_SLOW_SEC]
 * to [CLOSE_FAST_SEC] on a log scale; its neutral is its default, so AMT, which walks a character toward its
 * neutral, moves FLOOR alone.
 *
 * The length is kept: a shut pad ends in silence, not early (the rack's tail cap and the export trim own a
 * pad's end). The peak is matched both ways, which changes nothing unless the gate was closing at the peak.
 *
 * Sits after ROOM and before MOTION: the gate is heard on what the room made, so a recipe with both is the
 * gated reverb, and the tape still stops with the gated sound on it. The pedal brainstorm's verifier put it
 * after CRUNCH, on the chug; measured before this was written, the kit's CHUG holds within 6 dB of its peak
 * for 1.15 s and through DRIVE for 1.9 s, one held note that ends in its own fade, so a gate has nothing to
 * cut there, while a snare through ROOM keeps its tail for 0.75 s and falls 12 to 24 dB in 0.16 to 0.28 s,
 * which is where a gate is a sound. Pad-sheet taps bake in tap order, so a tap still gates whatever came first.
 *
 * Hazards, not hidden: a hat at the top of FLOOR is a stub (that is the pedal); a pad that is all hit and no
 * tail has nothing to shut.
 *
 * For tails. At its gate (2026-11-07) the owner heard it on a snare and a tom through ROOM, a dry snare and an
 * open hat, at 36, 24 and 12 dB under the peak and with a 300 ms and a 25 ms close: the snare through ROOM is
 * the gated 80s snare ("yes, that is it"), best at 12 dB; the dry snare is only a shorter snare and the open hat
 * a stub. The CHUG was left out of the listen because the gate measured as changing nothing on it. The owner
 * chose 12 dB at the default close (about 87 ms) for a tap, and the chip word HUSH, since a choke is already a
 * choke group here. So the pedal sits after ROOM, where the rack puts it, and nothing tries to rescue a dry hit.
 */
object Hush {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("FLOOR", 0.5f),                  // the threshold: off, then from far under the peak up to FLOOR_TOP_DB under it
        MacroSpec("CLOSE", 0.5f, neutral = 0.5f),  // how fast it shuts: CLOSE_SLOW_SEC .. CLOSE_FAST_SEC; its own neutral, so AMT never moves it
    )

    /** The threshold at the top of FLOOR, in dB under the pad's peak. */
    const val FLOOR_TOP_DB = 6f

    /** How fast the threshold deepens as FLOOR falls: set so FLOOR 0.7, a tap, is 12 dB (6 + 14 x 0.3 / 0.7). */
    const val FLOOR_SPAN_DB = 14f

    /** CLOSE's ends: the release time of the raised cosine that shuts the gate. */
    const val CLOSE_SLOW_SEC = 0.3f
    const val CLOSE_FAST_SEC = 0.025f

    /** How long the level must stay under the floor before the gate shuts. */
    const val HOLD_SEC = 0.02f

    /** How far above the floor the level must climb to reopen a shut gate. */
    const val HYSTERESIS_DB = 4f

    /** How long the gate takes to open. */
    const val OPEN_SEC = 0.001f

    /** The detector's window, centred on the frame. */
    const val WINDOW_SEC = 0.015f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The threshold in dB under the pad's peak for a FLOOR macro above 0 (infinite at 0, where the gate is off). */
    fun floorDb(floor: Float): Float {
        val f = floor.coerceIn(0f, 1f)
        return if (f <= 0f) Float.POSITIVE_INFINITY else FLOOR_TOP_DB + FLOOR_SPAN_DB * (1f - f) / f
    }

    /** The FLOOR macro whose threshold is [db] under the peak (the inverse of [floorDb]). */
    fun floorFor(db: Float): Float =
        if (db <= FLOOR_TOP_DB) 1f else (FLOOR_SPAN_DB / (db - FLOOR_TOP_DB + FLOOR_SPAN_DB)).coerceIn(0f, 1f)

    /** The release in seconds for a CLOSE macro (log scale). */
    fun closeSec(close: Float): Float = CLOSE_SLOW_SEC * (CLOSE_FAST_SEC / CLOSE_SLOW_SEC).pow(close.coerceIn(0f, 1f))

    /** The CLOSE macro whose release is [sec] seconds (the inverse of [closeSec]). */
    fun closeFor(sec: Float): Float =
        (kotlin.math.ln(sec / CLOSE_SLOW_SEC) / kotlin.math.ln(CLOSE_FAST_SEC / CLOSE_SLOW_SEC)).coerceIn(0f, 1f)

    /** The gate's gain for every frame of [snip] (1 open, 0 shut). */
    internal fun gains(snip: Snip, floor: Float, close: Float): FloatArray {
        val frames = snip.frameCount
        val ch = snip.channels
        val rate = snip.sampleRate
        val level = FloatArray(frames)
        for (f in 0 until frames) {
            var a = 0f
            for (c in 0 until ch) { val v = abs(snip.samples[f * ch + c]); if (v > a) a = v }
            level[f] = a
        }
        val env = slidingMax(level, (WINDOW_SEC * rate).toInt().coerceAtLeast(1))
        val peak = level.maxOrNull() ?: 0f
        val gain = FloatArray(frames) { 1f }
        if (peak <= 1e-9f) return gain

        val shutBelow = peak * 10f.pow(-floorDb(floor) / 20f)
        val openAbove = shutBelow * 10f.pow(HYSTERESIS_DB / 20f)
        val hold = (HOLD_SEC * rate).toInt().coerceAtLeast(1)
        val closeFrames = (closeSec(close) * rate).toInt().coerceAtLeast(1)
        val openFrames = (OPEN_SEC * rate).toInt().coerceAtLeast(1)

        var armed = false     // the pad has reached the opening level once
        var open = true       // where the gate is heading
        var under = 0         // frames the level has spent under the floor
        var from = 1f         // the gain the current ramp started at
        var k = 0             // frames into the current ramp
        var g = 1f
        for (f in 0 until frames) {
            val e = env[f]
            if (e >= openAbove) armed = true
            if (open) {
                under = if (armed && e < shutBelow) under + 1 else 0
                if (under >= hold) { open = false; from = g; k = 0 }
            } else if (e >= openAbove) {
                open = true; under = 0; from = g; k = 0
            }
            g = if (open) {
                if (k >= openFrames) 1f else from + (1f - from) * 0.5f * (1f - cos(PI * k / openFrames).toFloat())
            } else {
                if (k >= closeFrames) 0f else from * 0.5f * (1f + cos(PI * k / closeFrames).toFloat())
            }
            k++
            gain[f] = g
        }
        return gain
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        // A value that is not a number is left at the default: a NaN would otherwise ride through the clamp into the gain.
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        val floor = m.getValue("FLOOR")
        if (floor <= 0f || snip.frameCount < 2) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val gain = gains(snip, floor, m.getValue("CLOSE"))
        val ch = snip.channels
        val out = FloatArray(snip.samples.size) { i -> snip.samples[i] * gain[i / ch] }

        // Shape, not loudness: matched both ways. The gate is open at the peak unless it was closing there.
        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val s = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * s).coerceIn(-1f, 1f)
        }
        return Snip(out, ch, snip.sampleRate)
    }

    /** The largest value of [x] over a [window]-frame window centred on each frame (a monotonic deque, O(n)). SUB's envelope reads it too. */
    internal fun slidingMax(x: FloatArray, window: Int): FloatArray {
        val n = x.size
        val out = FloatArray(n)
        val half = window / 2
        val dq = IntArray(n)
        var head = 0
        var tail = 0
        var next = 0 // the next index to push
        for (f in 0 until n) {
            val hi = minOf(n - 1, f + half)
            while (next <= hi) {
                while (tail > head && x[dq[tail - 1]] <= x[next]) tail--
                dq[tail++] = next++
            }
            val lo = f - half
            while (dq[head] < lo) head++
            out[f] = x[dq[head]]
        }
        return out
    }
}
