package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
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
 * they differ in, and they render exactly as they did before the hooks existed (R2c's refactor onto Geometry was held
 * to the bit by a hash of 39 renders, before and after; `MercuryTest`'s golden values keep the three voices' defaults and
 * loops from drifting since). The new geometries override what their object needs.
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

    /**
     * Whether the coupled anchor is solved where the water leaves the bank *on average* (every mode at its mean load,
     * every spring lifted by the mean of its two ends) and not at the dry shape. The dry anchor puts the note on pitch
     * with WATER 0 and is all the stock voices need (their COUPLE is .2 to .3 and their WATER .1 to .15); at COUPLE .65
     * and WATER .4 the lifted springs and the loaded modes leave the coupled mode 6 to 9 cents flat on average.
     */
    open val anchorAtMeanWater: Boolean = false

    /** A mode's mean unit load over one orbit of the steady mass (squared radius [Mercury.RING_ORBIT_LOAD]): primary [i], or vessel [i] - [primaries]. */
    internal fun meanUnitLoad(i: Int, primaries: Int): Double {
        val steps = 32
        var sum = 0.0
        for (k in 0 until steps) {
            val ang = 2 * PI * k / steps
            sum += if (i < primaries) unitLoad(i, Mercury.RING_ORBIT_LOAD, ang, 0.5) else vesselUnitLoad(i - primaries, Mercury.RING_ORBIT_LOAD, ang)
        }
        return sum / steps
    }

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

/**
 * EDDY's object: a rubbed singing bowl. Six azimuthal families, (n, 0) for n = 2 to 7, each a cos/sin **doublet** split
 * by the bowl's small asymmetry, so 6 families x 2 = the 12 primaries. The table is measured: Inácio, Henrique and
 * Antunes, "The Dynamics of Tibetan Singing Bowls" (Acta Acustica united with Acustica 92(4), 637-653, 2006), Table I,
 * bowl 1 (180 mm, 934 g), whose partials sit 0, 2, 5, 7, 10 and 12% under the thin ring's (thick-shell lowering).
 *
 * The doublet is the point, and friction locks a pair to one winner, so a rubbed EDDY does not beat steadily (no
 * loop-safe way to make it was found: a weak partner contact or a spinning contact both failed the seam bar at
 * COUPLE .6). It drifts and swells as the mass loads the two members in antiphase, and a struck one beats in its
 * tail at a rate COUPLE sets through the vessel. The vessels hang from the *lower* member of the first four families
 * by one spring each: hosted by both, the anchor fix would calibrate the wrong normal mode and the note would come out
 * 60 cents sharp.
 */
internal object BowlGeometry : Geometry() {
    // Bowl 1's measured doublets, Hz: the lower member of each family, then the upper.
    private val LOWER = doubleArrayOf(219.6, 609.1, 1135.9, 1787.6, 2555.2, 3427.0)
    private val UPPER = doubleArrayOf(220.6, 609.9, 1139.7, 1787.9, 2564.8, 3428.3)

    override val ratios: DoubleArray =
        DoubleArray(Mercury.PRIMARIES) { i -> (if (i % 2 == 0) LOWER[i / 2] else UPPER[i / 2]) / LOWER[0] }

    /** A doublet's own spring, a small share of kappa: its split is the bowl's, and COUPLE moves it only through the vessel. */
    const val PAIR_KAPPA = 0.04

    /** The upper member's grip on the contact against the lower's: a pair that locks must still be excited, a struck one must still beat. */
    const val PARTNER_CONTACT = 0.7

    /** The pickup's angle round the rim, radians. The members must differ here or the water's complementary swing cancels in a locked pair. */
    const val PICKUP_THETA = 0.15

    /**
     * How far the mass swings a pair apart: its loads are 0.5 +/- 0.5 times this times the mass's reach, so the pair's
     * mean stays one half. At 1 the members swing 2.7% apart, leave the friction's lock range and hop, and the loop stops
     * closing. The loop is marginal for this object at every key, and chaotic in it: 0.6 missed the bar at five
     * scattered keys, 0.5 at C4 or at G4 depending on the WATER curve, 0.45 closes all 25 keys (worst seam 2e-4 against
     * the bar's 1e-3). The WATER bar (8 cents at 0.05) wants it as high as it goes.
     */
    const val PAIR_SWING = 0.45

    /**
     * EDDY's WATER curve is steeper at the start than the other voices' (0.25): the pair's centre never moves, so the
     * cents come only from the members hopping, and at 0.25 WATER 0.05 added 6 cents against the 8 a listener needs
     * (0.04 gives 9.2 cents and 3.4 dB). WATER 1 is unchanged by any curve.
     */
    override val waterCurve = 0.04

    override fun vesselHost(v: Int) = 2 * v

    /** The first vessel sits 4.05% above its host, not 3.5: the host's upper member is 0.455% above it, and the rule is 3.5% from every primary. */
    override val vesselDetune = doubleArrayOf(0.0405, -0.045, 0.06, -0.07)
    override val vesselSpringsToNext = false
    override val vesselKappaScale = 0.5

    override fun neighbourScale(i: Int) = if (i % 2 == 0) PAIR_KAPPA else 1.0

    override fun contact(i: Int, ratio: Double) = ratio.pow(-Mercury.CONTACT_TAPER) * (if (i % 2 == 1) PARTNER_CONTACT else 1.0)

    override fun pickup(i: Int, ratio: Double, tilt: Double): Double {
        val n = i / 2 + 2
        return (if (i % 2 == 0) cos(n * PICKUP_THETA) else sin(n * PICKUP_THETA)) * ratio.pow(-tilt)
    }

    override fun unitLoad(i: Int, rho2: Double, ang: Double, xm: Double): Double {
        val reach = min(1.0, rho2 / Mercury.RING_ORBIT_LOAD)
        val swing = 0.5 * PAIR_SWING * reach * cos(2 * (ang - Mercury.RING_LOAD_PHASE * (i / 2)))
        return if (i % 2 == 0) 0.5 + swing else 0.5 - swing
    }

    // A family's BEND, shared by its two members so a doublet's split never moves with BEND and no identity crosses.
    // The fundamental pair is pinned. A vessel follows its host's family.
    private fun familyOf(i: Int, primaries: Int) = (if (i < primaries) i else vesselHost(i - primaries)) / 2

    override fun bendA(i: Int, primaries: Int): Double {
        val f = familyOf(i, primaries)
        return if (f == 0) 0.0 else 0.12 * sin(1.3 * f + 0.4)
    }

    override fun bendB(i: Int, n: Int, primaries: Int): Double {
        val f = familyOf(i, primaries)
        return if (f == 0) 0.0 else 0.05 * f / 6
    }
}

/**
 * VESSEL's object: a thin steel cylindrical shell, a short fat pipe or tank (L/R 6, h/R 0.04, nu 0.3, simply supported
 * ends), struck on the side. The 12 primaries are one axial half-wave at every circumferential order n = 0 to 11,
 * sorted by frequency (n = 2, 3, 1, 4, 5, 6, 7, 8, 9, 0, 10, 11), so one object holds the ring modes, the tube's own
 * flexure (n = 1) and the breathing mode (n = 0). The four vessel modes are the sin(n theta) **quadrature partners** of
 * the first four, the other half of each doublet: a strike at theta 0 drives only the cos member, so COUPLE is the one
 * way the partner is fed.
 *
 * The ratios are from our own Love/Sanders energy derivation (a 3x3 u, v, w eigenproblem per order, the lowest
 * root for n >= 1 and the top one for n = 0), frozen here as numbers and recomputed by `MercuryGeometryTest`.
 * The source family is Leissa, *Vibration of Shells* (NASA SP-288, 1973) and Soedel; the long-shell limit reproduces
 * [Mercury.ringRatios] for k = 2 to 7, and the n = 1 mode lands within 2.3% of a Timoshenko tube. The 1/n weight of the
 * water's added mass is the long-wavelength limit of the fluid-loaded shell (Lindholm, Kana and Abramson 1962).
 * Designed, in the design doc's sense.
 */
internal object ShellGeometry : Geometry() {
    /** Each sorted primary's circumferential order. */
    private val ORDER = intArrayOf(2, 3, 1, 4, 5, 6, 7, 8, 9, 0, 10, 11)

    override val ratios = doubleArrayOf(1.0, 1.4797, 2.2144, 2.6993, 4.3255, 6.3219, 8.6835, 11.4091, 14.4983, 15.9969, 17.9511, 21.7673)

    /**
     * Every vessel above its partner, the physical sign (a seam or the water lowers the struck cos mode), ascending so
     * the beat rates climb: 5.25, 6.75, 8.25 and 9.75%, wider than the other voices' 3.5 to 7%. At A2 the object is low
     * and rings long, and with the vessels at 3.5 to 6.5% the rub sustained the fundamental and the hybrid together at
     * the four lowest keys, two unrelated lines that no loop closes (seams of 3 to 5). At 1.5 times the gaps every one
     * of the 25 keys closes un-nudged (worst seam 1e-5 at the defaults; `MercuryLoopTest` sweeps them).
     */
    override val vesselDetune = doubleArrayOf(0.0525, 0.0675, 0.0825, 0.0975)

    /** A vessel rings as long as its partner, no less: a lossier one is a damper on the partner it is coupled to, and at the bottom of the range it also let the loop fail. */
    override fun vesselT60(glass: Double, hostT60: Double) = VESSEL_T60_FACTOR * hostT60

    /** The pickup hears the sin partners at the angle it sits at, 0.35 rad round the shell. */
    override fun vesselPickup(v: Int) = 0.4 * sin(PICKUP_THETA * ORDER[v])

    override fun pickup(i: Int, ratio: Double, tilt: Double) = cos(PICKUP_THETA * ORDER[i]) * ratio.pow(-tilt)

    override fun unitLoad(i: Int, rho2: Double, ang: Double, xm: Double): Double {
        val reach = min(1.0, rho2 / Mercury.RING_ORBIT_LOAD)
        // The breathing mode has no angle to read the mass at.
        return if (ORDER[i] == 0) 0.5 * reach else reach * cos(ang - Mercury.RING_LOAD_PHASE * i).let { it * it }
    }

    /** The partner's exact complement: the pair's loads sum to the mass's reach, so the doublet's split swings with the water. */
    override fun vesselUnitLoad(v: Int, rho2: Double, ang: Double) =
        min(1.0, rho2 / Mercury.RING_ORBIT_LOAD) * sin(ang - Mercury.RING_LOAD_PHASE * v).let { it * it }

    /** The fluid's added mass falls as about 1/n, scaled to 0.75 at the fundamental so its drift matches SING's. */
    override fun loadWeight(primary: Int) = 1.5 / max(ORDER[primary], 2)

    /** The fundamental's mean load: its weight times the half every unit load averages. */
    override val anchorMeanLoad = 0.375

    override val anchorAtMeanWater = true

    // The signs alternate so BEND reshapes the spacing between neighbours (the object changes) and does not stretch
    // the table. Order-preserving by construction (the smallest successive gap over BEND is 6.3%); the top four share
    // one sign so the 10 and 12% gaps cannot cross. A vessel follows its partner's, plus a little, which opens its split.
    private val SIGN = doubleArrayOf(0.0, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, 1.0, 1.0, 1.0)

    private fun hostOf(i: Int, primaries: Int) = if (i < primaries) i else vesselHost(i - primaries)

    override fun bendA(i: Int, primaries: Int) = 0.09 * SIGN[hostOf(i, primaries)]

    override fun bendB(i: Int, n: Int, primaries: Int) = 0.02 * hostOf(i, primaries) / 11 + (if (i >= primaries) 0.015 else 0.0)

    const val PICKUP_THETA = 0.35

    /** A vessel rings this share of its partner's t60. */
    const val VESSEL_T60_FACTOR = 1.0
}

/**
 * SHARD's object: a free rectangular plate (a Chladni plate), a/b 1.13, Poisson 0.33, all four edges free, cut a little
 * off square so the square plate's degenerate pairs split into irregular, unequal partners. Dense (the 12th ratio is
 * 8, against 62 for the ring and 69 for the beam), irregular, and with a loose skeleton of near-harmonics (the (0,2)
 * mode 43 cents under an octave) that carries the pitch.
 *
 * The ratios are a converged Rayleigh-Ritz on Legendre polynomials, which reproduces the published free-square-plate
 * values (13.468, 19.596, 24.270, 34.801 at nu 0.3: Narita 2022, EPI Int. J. Eng. 5(1) 26-36, Tables 1 and 17; 13.169,
 * 19.224, 24.423, 34.233 at 0.333: its Table 2, after Gorman; Leissa's NASA SP-160 tabulates slightly higher upper-bound
 * values, 0.2 to 0.6% above). The mode labels are the (nodal lines in x, in y) of each. The
 * 12-mode truncation cuts through the near-degenerate (4,0) and (2,3), 2% apart, which cross near a/b 1.11.
 *
 * The contact, pickup and load tables below are **designed**: a product of free-free beam functions at a contact
 * point, a pickup point and round the mass's orbit, with the mean load held at one half for every mode so the pitch
 * anchor still centres. The exact Ritz fields differ from the product shape at those points, by a lot for two modes.
 */
internal object PlateGeometry : Geometry() {
    override val ratios = doubleArrayOf(1.0, 1.3849, 1.951, 2.4916, 2.7251, 4.1032, 4.7801, 4.9697, 5.2128, 6.1156, 7.6833, 7.9962)

    /** Each mode's grip on a finger pad near the corner, (0.06, 0.07): no dead mode, and the fundamental largest so the rub locks on it. */
    private val CONTACT = doubleArrayOf(1.0, 0.636, 0.595, 0.947, 0.907, 0.467, 0.858, 0.696, 0.399, 0.609, 0.631, 0.306)

    /** Each mode's shape at the pickup, on the plate's right edge (1.00, 0.33). The fundamental's low weight keeps the cluster audible over the rub. */
    private val PICKUP = doubleArrayOf(-0.451, 0.884, -0.320, 0.521, 0.554, -0.884, -0.639, -0.521, -0.577, 1.0, 0.639, 0.884)

    /** The load's second angular harmonic as the mass circles the centre: its depth, and its phase. Modes in x and in y are loaded in antiphase. */
    private val DEPTH = doubleArrayOf(0.6, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
    private val PHASE = doubleArrayOf(PI / 2, PI, 0.0, PI, 0.0, 0.0, 3 * PI / 2, PI, PI, 0.0, 0.0, 0.0)

    /** BEND is a saddle bend: the sign of a mode's A follows (nodal lines in x) - (in y). Gaps stay over 3% for BEND over the whole knob. */
    private val BEND_A = doubleArrayOf(0.0, 0.072, -0.040, 0.019, -0.005, 0.108, 0.0, 0.002, -0.007, -0.076, 0.108, 0.118)

    /** Vessels hang from modes 1, 3, 5 and 9, clear of the fundamental: a vessel on it would put a beating doublet on the note. */
    private val HOSTS = intArrayOf(1, 3, 5, 9)
    override fun vesselHost(v: Int) = HOSTS[v]

    /** A finger pad averages a mode's shape over an area, so its grip falls as ratio^-1, where a line patch's falls as ratio^-0.5. */
    override fun contact(i: Int, ratio: Double) = CONTACT[i] * ratio.pow(-1.0)

    /** The pickup acts like an accelerometer: acceleration is w^2 times displacement, so the 4-8x partials are heard. */
    override fun pickup(i: Int, ratio: Double, tilt: Double) = PICKUP[i] * ratio.pow(1 - tilt)

    override fun unitLoad(i: Int, rho2: Double, ang: Double, xm: Double) =
        min(1.0, rho2 / Mercury.RING_ORBIT_LOAD) * (0.5 + 0.5 * DEPTH[i] * cos(2 * ang - PHASE[i]))

    private fun hostOf(i: Int, primaries: Int) = if (i < primaries) i else vesselHost(i - primaries)

    override fun bendA(i: Int, primaries: Int) = BEND_A[hostOf(i, primaries)]

    /** A uniform 2% stiffening of every upper mode against the anchor: a common factor leaves the gaps alone. */
    override fun bendB(i: Int, n: Int, primaries: Int) = if (hostOf(i, primaries) == 0) 0.0 else 0.02

    override val anchorAtMeanWater = true

    /** A bigger excursion than the other voices' two semitones, as the external spec allows a shard (a plate is struck hard and bends hard), and a quick gesture. */
    override val bendExcursionSemitones = 4.0

    /** A shorter strike pulse: a plate is struck by something hard, and the half-sine's first null at 1.5 over its length would otherwise take the top. */
    override val strikeMsHard = 0.10
    override val strikeMsSoft = 0.50
}
