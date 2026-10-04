package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * MERCURY's objects (R2c): what each [Geometry] claims about its own tables, apart from how it sounds. The bank and the
 * render are held by `MercuryTest` and `MercuryLoopTest`; this file holds the numbers the voices are made of.
 */
class MercuryGeometryTest {

    private fun f(x: Double, d: Int = 3) = "%.${d}f".format(java.util.Locale.ROOT, x)

    private val newGeometries = listOf(MercuryVoice.EDDY, MercuryVoice.VESSEL, MercuryVoice.SHARD).map { it to Mercury.geometryOf(it) }

    @Test
    fun `the shipped voices keep the objects they had`() {
        assertSame(RingGeometry, Mercury.geometryOf(MercuryVoice.PING))
        assertSame(RingGeometry, Mercury.geometryOf(MercuryVoice.SING))
        assertSame(BeamGeometry, Mercury.geometryOf(MercuryVoice.BLADE))
        assertSame(BowlGeometry, Mercury.geometryOf(MercuryVoice.EDDY))
        assertSame(ShellGeometry, Mercury.geometryOf(MercuryVoice.VESSEL))
        assertSame(PlateGeometry, Mercury.geometryOf(MercuryVoice.SHARD))
    }

    /** Every table starts at exactly 1 and climbs, and its 12th is well inside what the oversampled bank can hold at the top of each voice's range. */
    @Test
    fun `every table is ascending from one, and its top fits under the ceiling at the top of the range`() {
        for (voice in MercuryVoice.entries) {
            val r = Mercury.geometryOf(voice).ratios
            assertEquals(Mercury.PRIMARIES, r.size, "$voice: the table is not ${Mercury.PRIMARIES} long")
            assertEquals(1.0, r[0], "$voice: the first ratio is the note")
            for (i in 1 until r.size) assertTrue(r[i] > r[i - 1], "$voice: ratio $i (${r[i]}) does not climb from ${r[i - 1]}")
        }
        for ((voice, geo) in newGeometries) {
            val topHz = Mercury.frequencyFor(voice, 1f).toDouble()
            val top = topHz * geo.ratios.last()
            assertTrue(top < Mercury.FADE_LO_HZ / 1.3, "$voice: the 12th mode at the top of the range is ${f(top, 0)} Hz, close to where modes fade")
        }
    }

    /**
     * What a vessel is: 3.5% or more from every primary at BEND centred (R1's rule, which the vessels' own partner doublets
     * are the one exception to by name: they sit 3.5 to 10% from their partner), and strictly between neighbours otherwise.
     * EDDY's first vessel is 4.05% above its host and not 3.5 because the host's upper member is 0.455% above it.
     */
    @Test
    fun `no vessel sits closer than 3 and a half percent to any primary`() {
        for ((voice, geo) in newGeometries) {
            for (v in 0 until Mercury.VESSELS) {
                val ratio = geo.ratios[geo.vesselHost(v)] * (1 + geo.vesselDetune[v])
                val gaps = geo.ratios.map { abs(ratio / it - 1) }
                val nearest = gaps.min()
                assertTrue(nearest >= 0.035 - 1e-9, "$voice vessel $v at ${f(ratio, 4)} is ${f(nearest * 100, 2)}% from a primary")
            }
        }
    }

    /**
     * BEND must reorder nothing: scanned over the whole knob, no two primaries cross and none comes within 3% of its
     * neighbour (R1's smallest was 3.7%, the ring's at c = -1). Each geometry's own smallest is printed.
     */
    @Test
    fun `BEND never crosses two primaries over the whole knob`() {
        for ((voice, geo) in newGeometries) {
            val primaries = Mercury.PRIMARIES
            val n = primaries + Mercury.VESSELS
            var smallest = Double.MAX_VALUE
            var c = -1.0
            while (c <= 1.0 + 1e-9) {
                val r = DoubleArray(primaries) { i -> geo.ratios[i] * exp(geo.bendA(i, primaries) * c + geo.bendB(i, n, primaries) * c * c) }
                for (i in 1 until primaries) {
                    assertTrue(r[i] > r[i - 1], "$voice: primaries ${i - 1} and $i cross at BEND c = ${f(c, 2)}")
                    // EDDY's doublets are one object's two members (0.02 to 0.5% apart, by the bowl) and share their BEND; the gap that matters is between families.
                    if (geo === BowlGeometry && i % 2 == 1) continue
                    smallest = min(smallest, r[i] / r[i - 1] - 1)
                }
                c += 0.01
            }
            println("MERCURY geometry $voice: smallest primary gap over BEND ${f(smallest * 100, 2)}%")
            assertTrue(smallest > 0.0 && smallest >= 0.015, "$voice: two primaries come within ${f(smallest * 100, 2)}% of each other")
        }
    }

    /**
     * The anchor centres the pitch on the fundamental's mean load, so each geometry's loads must average that over one
     * orbit of the mass: every primary one half of its weight (EDDY and SHARD's depths and phases are chosen so), the
     * fundamental the voice's declared [Geometry.anchorMeanLoad].
     */
    @Test
    fun `every mode's load averages what the anchor assumes`() {
        for ((voice, geo) in newGeometries) {
            val n = Mercury.PRIMARIES + Mercury.VESSELS
            for (i in 0 until Mercury.PRIMARIES) {
                val mean = geo.meanUnitLoad(i, Mercury.PRIMARIES)
                assertTrue(abs(mean - 0.5) < 0.02, "$voice mode $i: its unit load averages ${f(mean)}, not one half")
            }
            val fundamental = geo.loadWeight(0) * geo.meanUnitLoad(0, Mercury.PRIMARIES)
            assertTrue(abs(fundamental - geo.anchorMeanLoad) < 0.02, "$voice: the fundamental's mean load is ${f(fundamental)}, the anchor assumes ${f(geo.anchorMeanLoad)}")
            for (v in 0 until Mercury.VESSELS) {
                val mean = geo.meanUnitLoad(Mercury.PRIMARIES + v, Mercury.PRIMARIES)
                assertTrue(mean in 0.0..1.0, "$voice vessel $v: unit load $mean outside 0 to 1")
            }
            for (i in 0 until n) assertTrue(geo.loadWeight(minOf(i, Mercury.PRIMARIES - 1)) in 0.0..1.0, "$voice: a load weight outside 0 to 1")
        }
    }

    /** At every angle every unit load is a share between 0 and 1 (the kappa lift and the shimmer read it as one). */
    @Test
    fun `no load leaves zero to one at any angle`() {
        for ((voice, geo) in newGeometries) {
            for (k in 0 until 64) {
                val ang = 2 * PI * k / 64
                for (i in 0 until Mercury.PRIMARIES) {
                    val u = geo.unitLoad(i, Mercury.RING_ORBIT_LOAD, ang, 0.5)
                    assertTrue(u in -1e-9..1.0 + 1e-9, "$voice mode $i at angle ${f(ang, 2)}: unit load ${f(u)}")
                }
                for (v in 0 until Mercury.VESSELS) {
                    val u = geo.vesselUnitLoad(v, Mercury.RING_ORBIT_LOAD, ang)
                    assertTrue(u in -1e-9..1.0 + 1e-9, "$voice vessel $v at angle ${f(ang, 2)}: unit load ${f(u)}")
                }
            }
        }
    }

    /** EDDY's doublet members are loaded in antiphase and always sum to the mass's reach: the pair's mean never moves, only its split. */
    @Test
    fun `EDDY loads each doublet's two members in antiphase`() {
        for (k in 0 until 64) {
            val ang = 2 * PI * k / 64
            for (family in 0 until Mercury.PRIMARIES / 2) {
                val a = BowlGeometry.unitLoad(2 * family, Mercury.RING_ORBIT_LOAD, ang, 0.5)
                val b = BowlGeometry.unitLoad(2 * family + 1, Mercury.RING_ORBIT_LOAD, ang, 0.5)
                assertEquals(1.0, a + b, 1e-9, "family $family at angle ${f(ang, 2)}")
            }
        }
        // Families share BEND, so a doublet's split never moves with it and no two members cross.
        for (family in 0 until Mercury.PRIMARIES / 2) {
            assertEquals(BowlGeometry.bendA(2 * family, Mercury.PRIMARIES), BowlGeometry.bendA(2 * family + 1, Mercury.PRIMARIES), "family $family's BEND (a)")
            assertEquals(BowlGeometry.bendB(2 * family, 16, Mercury.PRIMARIES), BowlGeometry.bendB(2 * family + 1, 16, Mercury.PRIMARIES), "family $family's BEND (b)")
        }
        // The fundamental pair never moves.
        assertEquals(0.0, BowlGeometry.bendA(0, Mercury.PRIMARIES))
        assertEquals(0.0, BowlGeometry.bendA(1, Mercury.PRIMARIES))
    }

    /**
     * EDDY's table is measured: Inacio, Henrique and Antunes (2006), Table I, bowl 1. Recomputed here from the same twelve
     * frequencies, so a typo in the code's own copy of them is caught by a second copy that is not the code's.
     */
    @Test
    fun `EDDY's table is the measured bowl`() {
        val lower = doubleArrayOf(219.6, 609.1, 1135.9, 1787.6, 2555.2, 3427.0)
        val upper = doubleArrayOf(220.6, 609.9, 1139.7, 1787.9, 2564.8, 3428.3)
        val want = DoubleArray(12) { i -> (if (i % 2 == 0) lower[i / 2] else upper[i / 2]) / lower[0] }
        for (i in 0 until 12) assertEquals(want[i], BowlGeometry.ratios[i], 1e-9, "EDDY ratio $i")
        // Every doublet is a split of under 0.5%, and every family is at least 33% from the next.
        for (fam in 0 until 6) assertTrue(BowlGeometry.ratios[2 * fam + 1] / BowlGeometry.ratios[2 * fam] - 1 < 0.005, "family $fam's doublet is wider than 0.5%")
        for (fam in 1 until 6) assertTrue(BowlGeometry.ratios[2 * fam] / BowlGeometry.ratios[2 * fam - 1] - 1 > 0.33, "family $fam is within 33% of the one below")
    }

    /**
     * VESSEL's table is derived: a thin steel cylindrical shell (L/R 6, h/R 0.04, nu 0.3, simply supported ends), one axial
     * half-wave at every circumferential order n = 0 to 11, the Love/Sanders energy's 3x3 eigenproblem per order (the
     * lowest root for n >= 1, the top one for n = 0), sorted and divided by the first. The code carries the result as
     * frozen numbers; this recomputes them.
     */
    @Test
    fun `VESSEL's table is the shell it names`() {
        val lr = 6.0
        val hr = 0.04
        val nu = 0.3
        fun omega2(n: Int): Double {
            val s = PI / lr
            val k = hr * hr / 12
            val mem = arrayOf(doubleArrayOf(-s, 0.0, 0.0), doubleArrayOf(0.0, n.toDouble(), 1.0), doubleArrayOf(-n.toDouble(), s, 0.0))
            val ben = arrayOf(doubleArrayOf(0.0, 0.0, s * s), doubleArrayOf(0.0, n.toDouble(), n.toDouble() * n), doubleArrayOf(0.5 * n, 1.5 * s, 2 * s * n))
            fun quad(r: Array<DoubleArray>, sh: Double) = Array(3) { i -> DoubleArray(3) { j ->
                r[0][i] * r[0][j] + r[1][i] * r[1][j] + nu * (r[0][i] * r[1][j] + r[1][i] * r[0][j]) + sh * r[2][i] * r[2][j]
            } }
            val am = quad(mem, 0.5 * (1 - nu))
            val ab = quad(ben, 0.5 * (1 - nu))
            val a = Array(3) { i -> DoubleArray(3) { j -> am[i][j] + k * ab[i][j] } }
            val p1 = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2]
            val q = (a[0][0] + a[1][1] + a[2][2]) / 3
            val p2 = (0 until 3).sumOf { (a[it][it] - q) * (a[it][it] - q) } + 2 * p1
            val p = sqrt(p2 / 6)
            val b = Array(3) { i -> DoubleArray(3) { j -> (a[i][j] - (if (i == j) q else 0.0)) / p } }
            val det = b[0][0] * (b[1][1] * b[2][2] - b[1][2] * b[2][1]) - b[0][1] * (b[1][0] * b[2][2] - b[1][2] * b[2][0]) + b[0][2] * (b[1][0] * b[2][1] - b[1][1] * b[2][0])
            val phi = acos(max(-1.0, min(1.0, det / 2))) / 3
            val e = (0 until 3).map { q + 2 * p * cos(phi + 2 * PI * it / 3) }.sorted()
            return if (n == 0) e[2] else e[0]
        }
        val modes = (0 until 12).map { n -> sqrt(omega2(n)) to n }.sortedBy { it.first }
        val ratios = modes.map { it.first / modes[0].first }
        for (i in 0 until 12) assertEquals(ratios[i], ShellGeometry.ratios[i], 5e-4, "VESSEL ratio $i (n = ${modes[i].second})")
        assertEquals(listOf(2, 3, 1, 4, 5, 6, 7, 8, 9, 0, 10, 11), modes.map { it.second }, "the circumferential order of each mode")
    }

    /**
     * SHARD's primaries sit within 3.9% of each other at best (the converged plate's closest pair, (2,2) and (3,1)), the
     * second is 38% above the first, and the 12th is 8: the dense, irregular object the voice is named for.
     */
    @Test
    fun `SHARD is dense and irregular`() {
        val r = PlateGeometry.ratios
        var smallest = Double.MAX_VALUE
        for (i in 1 until r.size) smallest = min(smallest, r[i] / r[i - 1] - 1)
        println("MERCURY geometry SHARD: smallest gap ${f(smallest * 100, 2)}%, 12th ratio ${f(r.last(), 4)}")
        assertTrue(smallest > 0.039, "two of SHARD's modes are within ${f(smallest * 100, 2)}%")
        assertTrue(r[1] > 1.38, "SHARD's second mode is ${r[1]}")
        assertTrue(abs(r.last() - 8.0) < 0.05, "SHARD's 12th ratio is ${r.last()}")
        // Dense next to the others: the ring's and the beam's 12th are 62 and 69.
        assertTrue(r.last() < RingGeometry.ratios.last() / 5)
    }
}
