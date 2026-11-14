package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.synth.SootGenreKits.Genre
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SOOT's four sibling genre kits (docs/SOOT.md): the layouts, every pad sound and bounded, the pads
 * within a kit audibly different (the owner once heard three snares as one drum), the four bars looping
 * cleanly, and the kits different from each other and from SOOT.
 */
class SootGenreKitsTest {
    private val rate = Dsp.RATE
    private val G = SootGenreKits

    /** A 24-band log spectrum (60 Hz to 16 kHz) of the first [seconds], normalised to its loudest band. */
    private fun bands(x: FloatArray, seconds: Double = .6): DoubleArray {
        val n = Integer.highestOneBit(maxOf(1024, minOf(x.size, (seconds * rate).toInt()))) * 2
        val re = FloatArray(n) { if (it < minOf(x.size, (seconds * rate).toInt())) x[it] else 0f }
        val im = FloatArray(n)
        com.snipsnap.audio.Fft.forward(re, im)
        val edges = DoubleArray(25) { 60.0 * Math.pow(16000.0 / 60.0, it / 24.0) }
        val out = DoubleArray(24) { b ->
            var s = 0.0; var c = 0
            for (k in 1 until n / 2) { val f = k * rate.toDouble() / n; if (f >= edges[b] && f < edges[b + 1]) { s += re[k] * re[k] + im[k] * im[k]; c++ } }
            10 * log10(s / maxOf(1, c) + 1e-24)
        }
        val top = out.max()
        return DoubleArray(24) { out[it] - top }
    }

    private fun distance(a: DoubleArray, b: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - b[it]) * (a[it] - b[it]) } / a.size)

    @Test
    fun `four kits, sixteen pads each, with their roles, classes and chokes`() {
        assertEquals(listOf(80, 72, 76, 96), Genre.entries.map { it.bpm })
        assertEquals(Genre.entries.size, Genre.entries.map { it.bpm }.toSet().size, "every kit has its own tempo")
        assertTrue(Genre.entries.none { it.bpm == SootKits.BPM }, "and none shares SOOT's")
        for (g in Genre.entries) {
            val pads = G.build(g)
            assertEquals(G.PADS, pads.size)
            assertEquals(G.names(g), pads.map { it.displayName })
            assertEquals(G.PADS, pads.map { it.displayName }.toSet().size, "${g.title}: every pad has its own name")
            assertEquals(DrumClass.PERC, pads[G.HITS].drumClass); assertEquals(DrumClass.LOOP, pads[G.HITS + 1].drumClass)
            assertEquals(listOf(2, 2), listOf(pads[G.HITS].muteGroup, pads[G.HITS + 1].muteGroup))
            for (p in pads) {
                assertEquals(3, p.velocityTakes.size); assertTrue(p.velocityTakes.all { it.size == G.TAKES })
                assertEquals(g.id, p.source["kit"])
            }
            // Only Drumless has no kick, by design.
            assertEquals(g != Genre.DRUMLESS, G.pads(g).any { it.voice == SootVoice.KICK }, "${g.title}: kick")
        }
    }

    @Test
    fun `every hit cell is finite, bounded and a one-shot, and the roll and four bars are their length`() {
        for (g in Genre.entries) {
            for (role in 0 until G.HITS) for (v in G.velocities) for (t in 0 until G.TAKES) {
                val x = G.render(g, role, v, t).samples
                assertTrue(x.all { it.isFinite() } && x.maxOf { abs(it) } in .01f..1f, "${g.title} $role v$v t$t")
                assertTrue(x.size < (1.5 * rate).toInt(), "${g.title} $role one-shot")
                assertTrue(abs(x.average()) < .005, "${g.title} $role DC")
            }
            for ((v, t) in listOf(.3f to 0, 1f to 3)) {
                val r = G.roll(g, v, t).samples
                assertEquals(((G.ROLL_BUILD + 1.4) * rate).toInt(), r.size)
                assertTrue(r.all { it.isFinite() } && r.maxOf { abs(it) } in .01f..1f, "${g.title} roll")
                val ph = G.phrase(g, v, t).samples
                assertEquals(G.phraseFrames(g), ph.size, "${g.title}: four bars exactly")
                assertTrue(ph.all { it.isFinite() } && ph.maxOf { abs(it) } in .01f..1f, "${g.title} four bars")
            }
        }
    }

    /**
     * The owner heard SOOT's A02, A11 and A12 as one snare at 0.4 to 2.0 dB apart on this measure.
     * Within each sibling kit, every two hits on the same voice stay at least 4 dB apart.
     */
    @Test
    fun `within a kit, pads on the same voice are different drums`() {
        val failures = mutableListOf<String>()
        for (g in Genre.entries) {
            val pads = G.pads(g)
            val spectra = (0 until G.HITS).map { bands(G.render(g, it, 1f, 0).samples) }
            for (a in 0 until G.HITS) for (b in a + 1 until G.HITS) {
                if (pads[a].voice != pads[b].voice) continue
                val d = distance(spectra[a], spectra[b])
                println("SOOT ${g.title}: ${pads[a].name} / ${pads[b].name} ${"%.1f".format(d)} dB")
                if (d < 4.0) failures += "${g.title}: ${pads[a].name} / ${pads[b].name} ${"%.1f".format(d)} dB"
            }
        }
        assertTrue(failures.isEmpty(), "pads too close: $failures")
    }

    @Test
    fun `the four bars loop without a seam`() {
        for (g in Genre.entries) {
            val x = G.phrase(g, 1f, 0).samples
            val steps = FloatArray(x.size - 1) { abs(x[it + 1] - x[it]) }.sorted()
            val typical = steps[(steps.size * .999).toInt()]
            val seam = abs(x[0] - x[x.size - 1])
            println("SOOT ${g.title} four bars: seam ${"%.4f".format(seam)} against the 99.9th-percentile step ${"%.4f".format(typical)}")
            assertTrue(seam <= typical, "${g.title}: the loop point is no bigger a step than the music's own")
        }
    }

    @Test
    fun `the roll builds, and the four bars and the roll replay byte for byte`() {
        for (g in Genre.entries) {
            val r = G.roll(g, 1f, 0).samples
            fun rms(a: Double, b: Double): Double { var s = 0.0; val i0 = (a * rate).toInt(); val i1 = (b * rate).toInt(); for (i in i0 until i1) s += r[i] * r[i]; return 10 * log10(s / (i1 - i0) + 1e-20) }
            val rise = rms(G.ROLL_BUILD - .4, G.ROLL_BUILD) - rms(0.0, .4)
            println("SOOT ${g.title} roll rises ${"%.1f".format(rise)} dB over the build")
            assertTrue(rise > 6, "${g.title}: the roll rises ($rise)")
            assertContentEquals(r, G.roll(g, 1f, 0).samples)
            assertContentEquals(G.phrase(g, .65f, 2).samples, G.phrase(g, .65f, 2).samples)
        }
    }
}
