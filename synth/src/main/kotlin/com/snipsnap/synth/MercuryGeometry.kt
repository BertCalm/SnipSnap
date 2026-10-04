package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * What differs from one MERCURY object to the next (design doc, R2c). Every voice is the same architecture, 12
 * primary modes and 4 vessel modes on one [Modes.Bank], one contact, one moving mass; a [Geometry] is the part that
 * is the object: its mode table, where the contact and the pickup sit on it, how the mass loads each mode, how BEND
 * deforms it, and how its vessel is hung. [Mercury.sound] asks it, and nothing else about a voice, so a new voice is a
 * new geometry and not a new code path.
 *
 * Every default here is the R1 object's, so [RingGeometry] (PING, SING) and [BeamGeometry] (BLADE) say only what
 * they differ in, and they render exactly as they did before the hooks existed (`MercuryTest` and the committed
 * kit and instrument renders hold it to the bit). The new geometries override what their object needs.
 *
 * Every table in here is **designed**, in the design doc's sense (decision 9): seeded from a source where one opened,
 * frozen as numbers, and labelled with where they came from. `Modes.Material` stays sourced-only.
 */
internal abstract class Geometry {

    /** The [Mercury.PRIMARIES] primary mode ratios to the fundamental: ascending, the first exactly 1. */
    abstract val ratios: DoubleArray

    // ---- the vessel -------------------------------------------------------------

    /** The primary vessel mode [v] hangs from: its ratio is that primary's times (1 + its detune), and its springs go there. */
    open fun vesselHost(v: Int): Int = v

    /** Each vessel's distance from its host, as a share of the host's ratio. Near enough for COUPLE to trade energy with it. */
    open val vesselDetune: DoubleArray = STOCK_VESSEL_DETUNE

    /** Whether a vessel is also sprung to the primary after its host (the R1 graph), or to its host alone. */
    open val vesselSpringsToNext: Boolean = true

    /** A vessel's springs, as a share of the primary-to-primary kappa. */
    open val vesselKappaScale: Double = 1.0

    /** The spring between primaries [i] and i + 1, as a share of the kappa. */
    open fun neighbourScale(i: Int): Double = 1.0

    /** A vessel's t60 at [glass], given its host's, seconds. */
    open fun vesselT60(glass: Double, hostT60: Double): Double =
        Mercury.VESSEL_T60_LOW + (Mercury.VESSEL_T60_HIGH - Mercury.VESSEL_T60_LOW) * glass

    /** What the pickup takes from vessel [v]. */
    open fun vesselPickup(v: Int): Double = 0.5 * (if (v % 2 == 0) 1 else -1)

    /** The load vessel [v] reads off the mass, before its host's [loadWeight]. The mass is at squared radius [rho2] and angle [ang]. */
    open fun vesselUnitLoad(v: Int, rho2: Double, ang: Double): Double = 0.5 * rho2

    // ---- the primaries ----------------------------------------------------------

    /** How hard the contact grips primary [i], whose settled ratio is [ratio]: the patch averages a mode's shape over its width. */
    open fun contact(i: Int, ratio: Double): Double = ratio.pow(-Mercury.CONTACT_TAPER)

    /** What the pickup takes from primary [i]: its shape at the pickup, and the GLASS [tilt]. */
    abstract fun pickup(i: Int, ratio: Double, tilt: Double): Double

    /** The load, 0 to 1, that primary [i] reads off the mass, at squared radius [rho2], angle [ang] and x position [xm]. */
    abstract fun unitLoad(i: Int, rho2: Double, ang: Double, xm: Double): Double

    /** A mode's share of the load: the fluid's added mass falls with the circumferential order. A vessel takes its host's. */
    open fun loadWeight(primary: Int): Double = 1.0

    /** The fundamental's mean load: the pitch is centred there ([Mercury.sound]'s `anchorNominal`), so the water bends the note about it and not off it. */
    open val anchorMeanLoad: Double = 0.5

    // ---- BEND, the gesture, the strike and the water ------------------------------

    /**
     * BEND's signed per-mode deformation, exp(a·c + b·c²): [i] is the mode's index in the bank, which for a vessel
     * is [primaries] + its number, [n] the bank's size. The first mode never moves.
     */
    abstract fun bendA(i: Int, primaries: Int): Double
    abstract fun bendB(i: Int, n: Int, primaries: Int): Double

    /** The largest signed pitch excursion of the bend gesture, semitones. */
    open val bendExcursionSemitones: Double = Mercury.BEND_EXCURSION_SEMITONES

    /** The strike's half-sine, ms, at GLASS 1 and at GLASS 0: a short pulse is bright, a long one dark. */
    open val strikeMsHard: Double = Mercury.STRIKE_MS_HARD
    open val strikeMsSoft: Double = Mercury.STRIKE_MS_SOFT

    /** WATER's depth is `WATER_DEPTH · WATER^this`. */
    open val waterCurve: Double = Mercury.WATER_CURVE

    protected companion object {
        val STOCK_VESSEL_DETUNE = doubleArrayOf(0.035, -0.045, 0.06, -0.07)

        /** The thin ring's and the beam's BEND: the first mode never moves; the others drift up or down. Designed. */
        fun stockBendA(i: Int) = if (i == 0) 0.0 else 0.12 * sin(1.3 * i + 0.4)
        fun stockBendB(i: Int, n: Int) = if (i == 0) 0.0 else 0.05 * i / n

        fun beamShape(k: Int, x: Double) = cos((k + 1.5) * PI * x)
    }
}

/**
 * PING's and SING's object: a glass rim, Rayleigh's thin ring's inextensional bending modes, f_k ∝ k(k²−1)/√(k²+1).
 * A mode k reads the mass at its own angle, [Mercury.RING_LOAD_PHASE] times k round.
 */
internal object RingGeometry : Geometry() {
    override val ratios: DoubleArray = Mercury.ringRatios(Mercury.PRIMARIES)

    override fun pickup(i: Int, ratio: Double, tilt: Double) = cos((i + 2) * 0.4) * ratio.pow(-tilt)

    override fun unitLoad(i: Int, rho2: Double, ang: Double, xm: Double) =
        min(1.0, rho2 / Mercury.RING_ORBIT_LOAD) * cos(ang - Mercury.RING_LOAD_PHASE * i).let { it * it }

    override fun bendA(i: Int, primaries: Int) = stockBendA(i)
    override fun bendB(i: Int, n: Int, primaries: Int) = stockBendB(i, n)
}

/** BLADE's object: the free-free beam, β_k², whose first four ratios are `Modes.METAL_BAR`'s. A mode k reads the mass at its x position. */
internal object BeamGeometry : Geometry() {
    override val ratios: DoubleArray = Mercury.beamRatios(Mercury.PRIMARIES)

    override fun pickup(i: Int, ratio: Double, tilt: Double) = beamShape(i, 0.3) * ratio.pow(-tilt)

    override fun unitLoad(i: Int, rho2: Double, ang: Double, xm: Double) = beamShape(i, xm).let { it * it }

    override fun bendA(i: Int, primaries: Int) = stockBendA(i)
    override fun bendB(i: Int, n: Int, primaries: Int) = stockBendB(i, n)
}
