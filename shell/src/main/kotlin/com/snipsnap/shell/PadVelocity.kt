package com.snipsnap.shell

/**
 * How hard a tap on glass sounds. A phone's screen carries no force, so
 * [PadHit.velocityAt] reads where on the pad the finger landed; this adds the
 * other things a touch does carry and one curve over the result.
 *
 * Every function here is pure: the screens pass in what the touch gave
 * (the position velocity [PadHit.velocityAt] already made, and the pressure
 * the panel reported) and get a velocity back. [PadHit.velocityAt] stays the
 * one position rule; POS is that rule, untouched.
 *
 *  - [Mode.POS]: where the finger landed. Deterministic and learnable.
 *  - [Mode.FULL]: always full level, the MPC's FULL LEVEL. It shows by ear
 *    what POS was doing.
 *  - [Mode.AREA]: how much finger landed. Panels report that as pressure
 *    (many synthesise it from contact size, which is fine: only how it
 *    compares with the player's other taps is used, by [OnsetRange]).
 *
 * The curve ([Curve]) is applied after whichever mode made the number, so
 * the modes stay comparable.
 */
object PadVelocity {

    enum class Mode(val chip: String) {
        POS("POS"),
        FULL("FULL LVL"),
        AREA("AREA"),
    }

    enum class Curve(val chip: String, val exponent: Float) {
        LINEAR("LINEAR", 1f),

        /** Lifts the middle: light touches sound stronger. */
        SOFT("SOFT", 0.6f),

        /** Lowers the middle: only a firm touch reaches full. */
        HARD("HARD", 1.6f),
    }

    /** [Mode.POS] for a missing or unknown name: a saved setting must never leave the pads without a rule. */
    fun parseMode(name: String?): Mode = Mode.entries.firstOrNull { it.name == name } ?: Mode.POS

    fun parseCurve(name: String?): Curve = Curve.entries.firstOrNull { it.name == name } ?: Curve.LINEAR

    /**
     * [velocity] through [curve], keeping the ends where they were: the
     * softest touch still lands on [floor] (so the softest velocity layer
     * stays reachable, see [PadHit.SOFTEST]) and the hardest on 1. The curve
     * bends only what lies between.
     */
    fun curve(velocity: Float, curve: Curve, floor: Float = PadHit.SOFTEST): Float {
        if (!velocity.isFinite()) return floor + (1f - floor) * 0.5f
        val v = velocity.coerceIn(floor, 1f)
        if (curve == Curve.LINEAR || floor >= 1f) return v
        val t = (v - floor) / (1f - floor)
        return floor + (1f - floor) * Math.pow(t.toDouble(), curve.exponent.toDouble()).toFloat()
    }

    /**
     * The velocity of one touch. [position] is what [PadHit.velocityAt]
     * gave for it; [pressure] is the panel's reading at the first contact
     * (0..1, or NaN or 0 when it reports nothing). [range] has seen the
     * player's recent touches; it is what AREA compares against, and it falls
     * back to [position] until it has learned enough to say.
     */
    fun shape(mode: Mode, curve: Curve, range: OnsetRange, position: Float, pressure: Float): Float {
        val base = when (mode) {
            Mode.POS -> position
            Mode.FULL -> 1f
            Mode.AREA -> range.normalised(pressure)?.let { PadHit.SOFTEST + (1f - PadHit.SOFTEST) * it } ?: position
        }
        return curve(base, curve)
    }
}

/**
 * What the player's touches have looked like lately, so AREA can say how a
 * new touch compares: the lightest and the heaviest of the last [capacity]
 * onsets, ignoring the extremes (the 10th and 90th percentile).
 *
 * Phones differ, and so do fingers; nothing here assumes pressure runs from
 * 0 to 1. It needs [MIN_SAMPLES] touches before it will answer, and it will
 * not answer at all on a panel that reports the same value every time (its
 * span stays under [MIN_SPAN]): both return null and the caller uses
 * position, so AREA never makes every pad the same loudness.
 *
 * Main thread only, like the touches that feed it.
 */
class OnsetRange(private val capacity: Int = 32) {

    private val seen = FloatArray(capacity)
    private var count = 0
    private var next = 0

    /** Note one onset. A reading that is not a number, or zero (nothing reported), is not a sample. */
    fun observe(pressure: Float) {
        if (!pressure.isFinite() || pressure <= 0f) return
        seen[next] = pressure
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** How this touch compares with the recent ones, 0 (as light as they get) to 1 (as heavy); null when that can't be said yet. */
    fun normalised(pressure: Float): Float? {
        if (!pressure.isFinite() || pressure <= 0f || count < MIN_SAMPLES) return null
        val sorted = seen.copyOf(count).also { it.sort() }
        val lo = sorted[((count - 1) * 0.1f).toInt()]
        val hi = sorted[((count - 1) * 0.9f + 0.5f).toInt().coerceAtMost(count - 1)]
        if (hi - lo < MIN_SPAN) return null
        return ((pressure - lo) / (hi - lo)).coerceIn(0f, 1f)
    }

    companion object {
        /** Touches before AREA will speak: fewer than this is a guess. */
        const val MIN_SAMPLES = 8

        /** The least spread that is real; below it the panel reports one value and there is nothing to compare. */
        const val MIN_SPAN = 0.03f
    }
}
