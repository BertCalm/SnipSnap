package com.snipsnap.synth

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How hard a struck modal drum was hit, as three physical consequences
 * rather than one filter over a finished render ([Velocity.soften]).
 *
 * The characteristic gesture: harder hit → shorter contact → more upper
 * modes and a deeper pitch drop → the same drum body.
 *
 * 1. **Contact.** A mallet, beater or palm stays on the head for a time
 *    that shortens as the hit gets harder, so its force pulse reaches
 *    higher in frequency. Each mode is weighted by a smooth contact
 *    spectrum, `H(r) = 1 / (1 + (r / (R v^a))^2)` at mode ratio `r`,
 *    divided by its own value at full velocity and by the root's, so the
 *    upper modes lose level against the root as the hit softens
 *    ([modeFactor]). [modeFactors] then rescales the whole set so the
 *    modes' summed gain is what it was: the balance moves, the body's
 *    level beside its strike pulse does not.
 * 2. **Tension.** A struck membrane is stretched tighter for an instant
 *    and settles back: the pitch drop. The extra tension grows with the
 *    square of the displacement (the membrane's own geometric
 *    nonlinearity), so the drop depth scales with `v^2` ([droopFactor]).
 *    A soft hit barely bends.
 * 3. **The strike.** A softer hit lands with less grit on its contact
 *    pulse ([gritFactor]), or a quieter ([pulseLevel], `sqrt(v)`) and
 *    duller ([cornerFactor]) beater click. Nonlinear stages that react to
 *    level (a buzz gate, a soft clip) see the hit's own level
 *    ([levelSeen]).
 *
 * Why the summed gain is held ([modeFactors]). With only the root held,
 * the modes' sum falls on a soft hit, so a strike pulse mixed beside them
 * (TERRA's `0.35 * exciter + 0.65 * modes`) is relatively louder. In the
 * Python harness, with the pulse's grit unscaled, that made a low drum's
 * soft hit read brighter (130 Hz membrane, first 30 ms centroid 692 Hz at
 * v = 0.25 against 630 Hz at v = 1). In Kotlin, with [gritFactor] in place,
 * holding the root alone also brightens monotonically
 * (StrikeVelocityTest fails only its two summed-gain checks when the hold
 * is reverted), so the hold is a design choice, not a measured fix: a
 * velocity moves the modes' balance, never the body's level beside its
 * strike. Turning the pulse down with `sqrt(v)` was tried for TERRA and
 * dropped: its palm pulse is a smooth thump below ~300 Hz, darker than a
 * 440 Hz head, so the hardest hit on Tabla Tin, Dumbek Tek and Djembe Slap
 * read darker than a medium one.
 *
 * Velocity does not ride an engine's own hardness macro. TERRA's FORCE is
 * not monotone in brightness on its rim pads (Kotlin, magnitude centroid
 * of the first 30 ms, Djembe Slap 2292 Hz at FORCE 0.475 against 1630 Hz
 * at its own 0.95), so scaling FORCE by the velocity made the hardest hit
 * duller than a medium one. Lengthening the contact pulse on a soft hit
 * was tried and dropped for the same family of reason: a longer pulse
 * carries more energy, which against a low drum is bright, and soft
 * membrane hits came out brighter (default membrane 780 Hz at v = 0.25
 * against 601 Hz at v = 1).
 *
 * Every factor is exactly `1f` at full velocity, so `v = 1` is the
 * engine's existing render, bit for bit, and the existing goldens stand.
 * [CONTACT_RATIO] and [CONTACT_EXPONENT] are design shapes, first tried in
 * a Python prototype (not Kotlin measurements), to be replaced by
 * listening; StrikeVelocityTest holds what was measured with them.
 */
internal object StrikeVelocity {
    /** The contact corner at full velocity, as a ratio of the root: modes above ~2.5x the root roll off on a soft hit. Shape. */
    const val CONTACT_RATIO = 2.5f

    /** How fast the contact corner falls with velocity: `R v^a`; 0.8 puts v = 0.25 at about a third of the full-velocity corner. Shape. */
    const val CONTACT_EXPONENT = 0.8f

    /** The softest velocity the laws are evaluated at; below it the hit is treated as this soft. */
    const val MIN_VELOCITY = 0.05f

    /** [velocity] clamped to [MIN_VELOCITY]..1, NaN as full velocity (today's sound). */
    fun settled(velocity: Float): Float =
        if (velocity.isNaN()) 1f else velocity.coerceIn(MIN_VELOCITY, 1f)

    /** True when [velocity] is the engine's existing render, which callers take without computing anything. */
    fun isFull(velocity: Float): Boolean = settled(velocity) >= 1f

    private fun contact(ratio: Float, v: Float): Double {
        val corner = CONTACT_RATIO * v.toDouble().pow(CONTACT_EXPONENT.toDouble())
        val x = ratio / corner
        return 1.0 / (1.0 + x * x)
    }

    /**
     * The level factor for a mode at [ratio] (to the drum's own root at
     * [rootRatio]) struck at [velocity]: 1 for the root, below 1 above it
     * on a soft hit, and exactly 1 for every mode at full velocity.
     */
    fun modeFactor(ratio: Float, velocity: Float, rootRatio: Float = 1f): Float {
        val v = settled(velocity)
        if (v >= 1f) return 1f
        val soft = contact(ratio, v) / contact(rootRatio, v)
        val full = contact(ratio, 1f) / contact(rootRatio, 1f)
        return (soft / full).toFloat()
    }

    /**
     * [modeFactor] for every mode at [ratios] (the first is the root) with
     * table gains [gains], rescaled so `sum(gains * factors)` equals
     * `sum(gains)`: the balance tilts toward the root on a soft hit while
     * the modes' summed level holds. Exactly 1 for every mode at full
     * velocity, and when the gains sum to nothing.
     */
    fun modeFactors(ratios: FloatArray, gains: FloatArray, velocity: Float): FloatArray {
        require(ratios.size == gains.size) { "a gain per ratio: ${ratios.size} vs ${gains.size}" }
        val v = settled(velocity)
        if (v >= 1f || ratios.isEmpty()) return FloatArray(ratios.size) { 1f }
        val raw = FloatArray(ratios.size) { modeFactor(ratios[it], v, ratios[0]) }
        var before = 0.0
        var after = 0.0
        for (k in ratios.indices) {
            before += gains[k]
            after += gains[k] * raw[k]
        }
        if (!(before > 0.0) || !(after > 0.0)) return FloatArray(ratios.size) { 1f }
        val keep = (before / after).toFloat()
        return FloatArray(ratios.size) { raw[it] * keep }
    }

    /** The pitch drop's depth factor: `v^2`, exactly 1 at full velocity. */
    fun droopFactor(velocity: Float): Float {
        val v = settled(velocity)
        return if (v >= 1f) 1f else v * v
    }

    /** A beater click's level: `sqrt(v)`, exactly 1 at full velocity. */
    fun pulseLevel(velocity: Float): Float {
        val v = settled(velocity)
        return if (v >= 1f) 1f else sqrt(v)
    }

    /**
     * How far a strike's own brightness corner (a click's low-pass, a
     * contact pulse's length) moves with the hit: `v^a`, the same
     * exponent as the contact corner, exactly 1 at full velocity.
     */
    fun cornerFactor(velocity: Float): Float {
        val v = settled(velocity)
        return if (v >= 1f) 1f else v.toDouble().pow(CONTACT_EXPONENT.toDouble()).toFloat()
    }

    /** The grit (contact noise) on a strike pulse: `v`, exactly 1 at full velocity. */
    fun gritFactor(velocity: Float): Float {
        val v = settled(velocity)
        return if (v >= 1f) 1f else v
    }

    /**
     * The level a nonlinear stage would see from a hit at [velocity] when
     * the engine renders every hit at one level (a buzz gate, a soft
     * clip): `v`, exactly 1 at full velocity. A stage applies it as
     * `f(x * seen) / seen`, so a soft hit drives it less and comes back at
     * the same level.
     */
    fun levelSeen(velocity: Float): Float {
        val v = settled(velocity)
        return if (v >= 1f) 1f else v
    }
}
