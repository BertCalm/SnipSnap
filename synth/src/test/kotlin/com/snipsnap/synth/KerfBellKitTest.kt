package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** KERF BELLS ([KerfBellKit]): fourteen bell strikes low to high, a roll and a riser that build, every pad a fresh strike. */
class KerfBellKitTest {
    @Test
    fun `fourteen strikes, low to high, each named once and rung within the cap`() {
        val hits = KerfBank.bells.second
        assertEquals(KerfBellKit.HITS, hits.size)
        assertEquals(hits.map { it.rootMidi }.sorted(), hits.map { it.rootMidi }, "low to high")
        val names = KerfBellKit.names()
        assertEquals(KerfBellKit.PADS, names.size); assertEquals(names.size, names.toSet().size, "each name once")
        for (p in 0 until KerfBellKit.HITS) {
            val x = KerfBellKit.render(p, 1f, 0).samples
            assertTrue(x.all { it.isFinite() } && x.size <= ((KerfRender.RING_MAX_SECONDS + .01) * Dsp.RATE).toInt(), names[p])
        }
        assertEquals(KerfBank.bells.first.toFloat(), KerfBellKit.bpm().toFloat())
    }

    @Test
    fun `every pad is levelled, softer when struck softer, and a fresh strike each take`() {
        for (pad in KerfBellKit.build(listOf(0, KerfBellKit.HITS - 1))) {
            assertEquals(DrumClass.PERC, pad.drumClass); assertTrue(pad.oneShot)
            val grid = pad.velocityTakes
            assertEquals(Kerf.PEAK, grid.flatten().maxOf { s -> s.samples.maxOf { abs(it) } }, 1e-5f, "${pad.displayName} level")
            assertTrue(grid.first().first().samples.maxOf { abs(it) } < grid.last().first().samples.maxOf { abs(it) } * .8f, "${pad.displayName} soft")
            val a = grid.last()[0].samples; val b = grid.last()[1].samples; val m = minOf(a.size, b.size)
            var d = 0.0; var e = 0.0
            for (i in 0 until m) { val x = (a[i] - b[i]).toDouble(); d += x * x; e += a[i].toDouble() * a[i] }
            assertTrue(10 * log10(d / e) > -60, "${pad.displayName} takes differ")
        }
    }

    @Test
    fun `the roll and the riser are one bar of strikes that build`() {
        val bar = 4 * 60.0 / KerfBellKit.bpm()
        for (role in listOf(KerfBellKit.HITS, KerfBellKit.HITS + 1)) {
            val x = KerfBellKit.render(role, 1f, 0).samples
            assertEquals(((bar + KerfBellKit.TAIL_SECONDS) * Dsp.RATE).toInt(), x.size, "role $role length")
            val q = (bar / 4 * Dsp.RATE).toInt()
            val first = x.copyOfRange(0, q).maxOf { abs(it) }; val last = x.copyOfRange(3 * q, 4 * q).maxOf { abs(it) }
            assertTrue(last > first * 1.3f, "role $role builds: first quarter $first, last $last")
        }
    }
}
