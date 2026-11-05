package com.snipsnap.synth

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Explicit register-following audition, with no change to accepted recipes.
 *
 * The contact bound keeps the finite half-sine on the first lobe of each
 * instrument's actual fundamental. Its shorter hard-contact bound also leaves
 * STRIKE a gesture at high notes instead of pinning most of the knob to one width.
 * The old fixed minimum contact additionally follows the note above C4. Keeping
 * that minimum in periods preserves excitation of identifying body modes, such
 * as the bar's 3.96-times-root mode, rather than protecting only the fundamental.
 *
 * Above C4, boundary modal frequencies follow the requested note. This is a
 * musical adaptation that keeps the material response in the playable register,
 * not a literal claim that moving a key retunes a physical room. The geometric
 * propagation delays and losses do not change. A sympathetic requested-root mode
 * additionally fades into each boundary between C4 and C5. Its positive T60
 * follows that boundary's convex material mixture and existing decay scale; it
 * receives no extra primary excitation. The enlarged modal port is normalized
 * by Bank just like the original port. This bounded addition costs ten modes
 * above C4 and allocates none at or below the unchanged anchor. Participation
 * .35 is an audition shape rather than a measured building-material property.
 * The triangle's source port also
 * gives its fundamental up to 25% more weight over that same register transition;
 * normalization preserves its energy accounting and identifying upper modes.
 * The surface and late listener pickups additionally follow sqrt(note scale),
 * bounded at 2. This musical voicing keeps the restored answer present beside
 * the higher direct hit; direct and early pickups remain unchanged. Listener
 * calibration adds no excitation or feedback energy. The factor is likewise an
 * audition shape rather than a measured acoustic law.
 * All listener pressure additionally scales by 1/sqrt(note scale), reserving raw
 * headroom for the shorter high-note transient. This prepared calibration is
 * independent of the rendered peak, never a runtime limiter or master leveling.
 * It leaves the relative surface/late voicing above intact. No pressure gain is
 * part of the passive network or its energy proof.
 * All preparation happens before the sample loop.
 */
internal object ChamberRegister {
    fun boundaryFrequencyScale(midi: Int): Double =
        if (midi <= Chamber.DEFAULT_MIDI) 1.0 else 2.0.pow((midi - Chamber.DEFAULT_MIDI) / 12.0)

    fun minimumContactFrames(legacyMinimum: Int, midi: Int): Int =
        if (midi <= Chamber.DEFAULT_MIDI) legacyMinimum
        else max(2, floor(legacyMinimum / boundaryFrequencyScale(midi)).toInt())

    fun sympatheticWeight(midi: Int): Double = .35 * sqrt(activation(midi))

    fun triangleRootWeight(midi: Int): Double = 1.0 + .25 * activation(midi)

    private fun activation(midi: Int): Double {
        val progress = ((midi - Chamber.DEFAULT_MIDI) / 12.0).coerceIn(0.0, 1.0)
        return progress * progress * (3.0 - 2.0 * progress)
    }

    fun roomPickupScale(midi: Int): Double = sqrt(boundaryFrequencyScale(midi)).coerceIn(1.0, 2.0)

    fun pressureScale(midi: Int): Double = 1.0 / roomPickupScale(midi)

    fun contactFrames(wideFrames: Int, sourceHz: Double, rate: Int, contactScale: Double): Int {
        val cycles = .80 * min(1.0, contactScale)
        val maximum = max(2, floor(cycles * rate / sourceHz).toInt())
        return min(wideFrames, maximum)
    }
}
