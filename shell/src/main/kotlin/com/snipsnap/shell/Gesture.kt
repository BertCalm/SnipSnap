package com.snipsnap.shell

import kotlin.math.floor

/**
 * A recorded finger: where it was on SURFACE's pad over [bars] bars,
 * sampled onto a fixed grid of [POINTS_PER_BAR] points a bar, so a
 * gesture is the same length in points whatever the tempo it was played
 * at or the frame rate it was recorded at. Played back by a MOD slot
 * whose SHAPE is GESTURE ([Modulator.Shape.GESTURE]): aimed at X it is
 * the finger's X, at Y its Y, at any other target its Y (the knob axis) -
 * as a nudge, like every modulator, so at rest and full DEPTH it
 * reproduces the finger exactly, around a held finger it moves relative
 * to it, and under a finger down it adds. It loops on the bar from the
 * modulators' own origin, so a gesture recorded off the downbeat plays
 * back on it. A performance that repeats on its own: the last piece of
 * the sequencer SURFACE never had to grow.
 *
 * Kept in `surface.json` beside the corners ([SurfaceStore.Settings.gesture]),
 * each point to a thousandth of the pad - a gesture is a performance the
 * kit keeps, the way a corner is.
 */
class Gesture(val bars: Int, xs: FloatArray, ys: FloatArray) {

    val xs: FloatArray = xs.copyOf()
    val ys: FloatArray = ys.copyOf()

    init {
        require(bars in 1..MAX_BARS) { "a gesture is 1..$MAX_BARS bars, got $bars" }
        require(xs.size == bars * POINTS_PER_BAR) { "a $bars-bar gesture has ${bars * POINTS_PER_BAR} points, got ${xs.size}" }
        require(ys.size == xs.size) { "x and y have the same number of points, got ${xs.size} and ${ys.size}" }
        for (v in xs) require(v.isFinite() && v in 0f..1f) { "every point is on the pad, 0..1, got $v" }
        for (v in ys) require(v.isFinite() && v in 0f..1f) { "every point is on the pad, 0..1, got $v" }
    }

    /** How many points the gesture holds, over all its bars. */
    val points: Int get() = xs.size

    /** The finger's X at [phase] 0..1 of the whole gesture, between points and round the loop. */
    fun x(phase: Float): Float = at(xs, phase)

    /** The finger's Y at [phase] 0..1 of the whole gesture. */
    fun y(phase: Float): Float = at(ys, phase)

    private fun at(arr: FloatArray, phase: Float): Float {
        val p = if (phase.isFinite()) phase.mod(1f) else 0f
        val pos = p * arr.size
        val i = floor(pos).toInt().mod(arr.size)
        val j = (i + 1).mod(arr.size)
        val frac = pos - floor(pos)
        return arr[i] + (arr[j] - arr[i]) * frac
    }

    override fun equals(other: Any?): Boolean =
        other is Gesture && other.bars == bars && other.xs.contentEquals(xs) && other.ys.contentEquals(ys)

    override fun hashCode(): Int = 31 * (31 * bars + xs.contentHashCode()) + ys.contentHashCode()

    override fun toString(): String = "Gesture($bars bars, $points points)"

    /**
     * Records the finger against the bar clock; the surface feeds it once
     * a frame from the touch that started it. Every grid point up to the
     * moment fed takes the finger as it is then (a sample-and-hold, at most
     * a frame late), so frames need not land on the grid. While the finger
     * is lifted the last place it touched is what is recorded - a lift is
     * "stay there", not "go home". [done] once every point is written;
     * [finish] fills whatever is left with the last place and hands the
     * gesture over, or nothing if the pad was never touched.
     */
    class Recorder(val bars: Int) {
        init {
            require(bars in 1..MAX_BARS) { "a gesture is 1..$MAX_BARS bars, got $bars" }
        }

        private val xs = FloatArray(bars * POINTS_PER_BAR)
        private val ys = FloatArray(bars * POINTS_PER_BAR)
        private var lastX = Float.NaN
        private var lastY = Float.NaN

        /** Grid points written so far. */
        var filled: Int = 0
            private set

        val done: Boolean get() = filled >= xs.size

        /** One frame: [barsElapsed] since the start, the finger at ([x], [y]) if [touching]. */
        fun add(barsElapsed: Float, x: Float, y: Float, touching: Boolean) {
            if (touching && x.isFinite() && y.isFinite()) {
                lastX = x.coerceIn(0f, 1f)
                lastY = y.coerceIn(0f, 1f)
            }
            if (lastX.isNaN() || done) return
            val elapsed = if (barsElapsed.isFinite() && barsElapsed > 0f) barsElapsed else 0f
            // The +1 inside the float, not after toInt(): a frame absurdly
            // late (the screen was off for a year) saturates to Int.MAX
            // and coerces to the end, rather than wrapping past it to
            // nothing and stalling the recording for ever.
            val upTo = floor(elapsed * POINTS_PER_BAR + 1f).toInt().coerceIn(0, xs.size)
            while (filled < upTo) {
                xs[filled] = lastX
                ys[filled] = lastY
                filled++
            }
        }

        fun finish(): Gesture? {
            if (lastX.isNaN() || filled == 0) return null
            while (filled < xs.size) {
                xs[filled] = lastX
                ys[filled] = lastY
                filled++
            }
            return Gesture(bars, xs, ys)
        }
    }

    companion object {
        /** Points a bar: 24 a beat in 4/4, fine enough that a fast flick keeps its shape and a bar is under a kilobyte on disk. */
        const val POINTS_PER_BAR = 96

        /** The longest gesture: PRINT's own longest BARS, so the two agree on what a phrase can be. */
        val MAX_BARS: Int = PrintLength.BARS.max()
    }
}
