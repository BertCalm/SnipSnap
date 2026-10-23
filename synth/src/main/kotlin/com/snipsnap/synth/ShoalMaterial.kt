package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Resonating body, independent of the GLASS/RIPPLE population's motion. Saved IDs are stable. */
enum class ShoalMaterial {
    HARMONIC, BOWL, WOOD, WIRE;

    internal fun ratio(index: Int): Double = when (this) {
        HARMONIC -> index + 2.0
        BOWL -> BOWL_RATIOS[index / 3] * (1.0 + (index % 3 - 1) * .0018)
        WOOD -> WOOD_RATIOS[index / 3] * WOOD_SPLITS[index % 3]
        WIRE -> {
            val harmonic = index / 2 + 2.0
            val stiffness = if (index % 2 == 0) .0060 else .0065
            harmonic * sqrt((1 + stiffness * harmonic * harmonic) / (1 + stiffness)) *
                (if (index % 2 == 0) .999 else 1.001)
        }
    }

    internal fun modeT60(index: Int): Double = when (this) {
        HARMONIC -> 0.0
        BOWL -> .5 + 4.5 / (1 + .12 * (index / 3))
        WOOD -> .045 + .65 / (1 + .75 * (index / 3))
        WIRE -> .24 + 4.0 / (1 + .09 * (index / 2 + 2.0).pow(2))
    }

    internal fun strike(index: Int): Double = when (this) {
        HARMONIC -> 1.0
        BOWL -> .45 + .55 * abs(sin((index / 3 + 1.0) * 1.37))
        WOOD -> .25 + .75 * abs(sin((index / 3 + 1.0) * .92))
        WIRE -> .12 + .88 * sin(PI * (index / 2 + 2.0) * .18).pow(2)
    }

    internal val rootT60 get() = when (this) { BOWL -> 4.5; WOOD -> .65; WIRE -> 2.8; HARMONIC -> 0.0 }
    internal val rootGain get() = when (this) { BOWL -> .20; WOOD -> .30; WIRE -> .14; HARMONIC -> 0.0 }
    internal val contact get() = when (this) { BOWL -> .0012; WOOD -> .0008; WIRE -> .0025; HARMONIC -> 0.0 }
    internal val drive get() = when (this) { BOWL -> .65; WOOD -> 1.6; WIRE -> .90; HARMONIC -> 0.0 }

    /** Soft contact damps upper-mode excitation. Normalization in Shoal preserves strike energy. */
    internal fun contactCutoff(velocity: Double): Double {
        val low = when (this) { BOWL -> 1_800.0; WOOD -> 1_000.0; WIRE -> 1_400.0; HARMONIC -> 18_000.0 }
        val high = when (this) { BOWL -> 14_000.0; WOOD -> 16_000.0; WIRE -> 12_000.0; HARMONIC -> 18_000.0 }
        return low * (high / low).pow(velocity.coerceIn(0.0, 1.0))
    }

    companion object {
        private val BOWL_RATIOS = doubleArrayOf(2.71, 4.97, 7.97, 11.65, 16.0, 21.07, 26.85, 33.31)
        private val WOOD_RATIOS = doubleArrayOf(2.756, 5.404, 8.933, 13.35, 18.64, 24.92, 32.18, 40.42)
        private val WOOD_SPLITS = doubleArrayOf(.988, 1.0, 1.014)
    }
}
