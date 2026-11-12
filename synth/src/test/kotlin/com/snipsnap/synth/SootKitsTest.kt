package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SOOT kit (docs/SOOT.md): the approved layout, every cell of every grid sound, the coil roll's
 * build, and the four bars' seamless loop.
 */
class SootKitsTest {
    private val rate = Dsp.RATE

    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * rate).toInt().coerceIn(0, x.size); val b = (to * rate).toInt().coerceIn(a, x.size)
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / maxOf(1, b - a))
    }

    private fun db(x: Double) = 20 * log10(x + 1e-12)

    /** Energy above [hz] (two cascaded one-pole high-passes) against the whole, in dB. */
    private fun highShare(x: FloatArray, hz: Float): Double {
        val a = Dsp.FixedOnePole(hz, rate); val b = Dsp.FixedOnePole(hz, rate)
        var hi = 0.0; var all = 0.0
        for (s in x) { val h1 = s - a.lp(s); val h2 = h1 - b.lp(h1); hi += h2 * h2; all += s * s }
        return 10 * log10(hi / all + 1e-20)
    }

    private fun apart(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val pa = a.maxOf { abs(it) }; val pb = b.maxOf { abs(it) }
        var d = 0.0; var s = 0.0
        for (i in 0 until n) { val e = a[i] / pa - b[i] / pb; d += e * e; s += (a[i] / pa) * (a[i] / pa) }
        return 10 * log10(d / s + 1e-20)
    }

    @Test
    fun `the layout the owner approved, with its classes and chokes`() {
        val pads = SootKits.build()
        assertEquals(SootKits.PADS, pads.size)
        assertEquals(listOf("Kick", "Hot Snare", "Closed Hat", "Open Hat", "Heavy Kick", "Dry Crack", "Tension Rod", "Metal Cup",
            "Steel Plate", "Coil", "Bench Snare", "Coil Snare", "Narrow Closed Hat", "Narrow Open Hat", "Coil Roll", "Four Bars"), pads.map { it.displayName })
        assertEquals(DrumClass.KICK, pads[0].drumClass); assertEquals(DrumClass.SNARE, pads[1].drumClass)
        assertEquals(DrumClass.HAT_CLOSED, pads[2].drumClass); assertEquals(DrumClass.HAT_OPEN, pads[3].drumClass)
        assertEquals(DrumClass.PERC, pads[14].drumClass); assertEquals(DrumClass.LOOP, pads[15].drumClass)
        assertEquals(listOf(1, 1), listOf(pads[2].muteGroup, pads[3].muteGroup))
        assertEquals(listOf(3, 3), listOf(pads[12].muteGroup, pads[13].muteGroup))
        assertEquals(listOf(2, 2), listOf(pads[14].muteGroup, pads[15].muteGroup))
        for (p in pads) {
            assertEquals(3, p.velocityTakes.size, p.displayName)
            assertTrue(p.velocityTakes.all { it.size == SootKits.TAKES }, p.displayName)
            assertEquals("SOOT", p.source["engine"])
        }
    }

    @Test
    fun `every cell is finite, bounded and the length it says`() {
        for (role in 0 until SootKits.PADS) for (v in SootKits.velocities) for (t in 0 until SootKits.TAKES) {
            val x = SootKits.render(role, v, t).samples
            assertTrue(x.all { it.isFinite() }, "role $role v$v t$t finite")
            val peak = x.maxOf { abs(it) }
            assertTrue(peak in .01f..1f, "role $role v$v t$t peak $peak")
            assertTrue(abs(x.average()) < .005, "role $role DC")
            when (role) {
                in 0 until SootKits.HITS -> assertTrue(x.size < (1.5 * rate).toInt(), "role $role stays a one-shot")
                SootKits.HITS -> assertEquals(((SootKits.ROLL_BUILD + 1.4) * rate).toInt(), x.size)
                else -> assertEquals(SootKits.phraseFrames, x.size, "four bars exactly")
            }
        }
    }

    @Test
    fun `the roll builds into the coil and the coil rings after it`() {
        val x = SootKits.roll(1f, 0).samples
        val start = db(rms(x, 0.0, .4)); val end = db(rms(x, SootKits.ROLL_BUILD - .4, SootKits.ROLL_BUILD))
        val after = db(rms(x, SootKits.ROLL_BUILD + .2, SootKits.ROLL_BUILD + .4)) - end
        println("SOOT roll: first 400 ms ${"%.1f".format(start)} dB, last 400 ms of the build ${"%.1f".format(end)} dB; 200-400 ms after it ${"%.1f".format(after)} dB re the build's end")
        assertTrue(end > start + 6, "the roll rises ($start -> $end)")
        assertTrue(after > -40, "the coil rings on after the last strike ($after)")
    }

    @Test
    fun `the four bars loop without a seam`() {
        for (t in listOf(0, 3)) {
            val x = SootKits.phrase(1f, t).samples
            val steps = FloatArray(x.size - 1) { abs(x[it + 1] - x[it]) }.sorted()
            val typical = steps[(steps.size * .999).toInt()]
            val seam = abs(x[0] - x[x.size - 1])
            println("SOOT four bars, take $t: seam step ${"%.4f".format(seam)} against the 99.9th-percentile step ${"%.4f".format(typical)}")
            assertTrue(seam <= typical, "take $t: the loop point is no bigger a step than the music's own ($seam > $typical)")
        }
    }

    @Test
    fun `a soft zone is duller strikes, and a take is fresh strikes`() {
        for (role in listOf(SootKits.HITS, SootKits.HITS + 1)) {
            val soft = highShare(SootKits.render(role, .3f, 0).samples, 3000f)
            val hard = highShare(SootKits.render(role, 1f, 0).samples, 3000f)
            val takes = apart(SootKits.render(role, 1f, 0).samples, SootKits.render(role, 1f, 1).samples)
            println("SOOT role $role: share above 3 kHz soft / hard ${"%.1f".format(soft)} / ${"%.1f".format(hard)} dB; takes apart ${"%.1f".format(takes)} dB")
            assertTrue(hard > soft, "role $role: the hard zone is brighter ($soft -> $hard)")
            assertTrue(takes > -40, "role $role: takes differ ($takes)")
        }
    }

    @Test
    fun `the roll and the four bars replay byte for byte`() {
        assertContentEquals(SootKits.roll(.65f, 2).samples, SootKits.roll(.65f, 2).samples)
        assertContentEquals(SootKits.phrase(.65f, 2).samples, SootKits.phrase(.65f, 2).samples)
    }
}
