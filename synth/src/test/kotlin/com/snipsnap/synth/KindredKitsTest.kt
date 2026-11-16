package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.synth.KindredKits.Kit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The four KINDRED kits (round K2): every grid sound, soft zones a different strike, takes never copies, kits apart. */
class KindredKitsTest {
    private val rate = Dsp.RATE

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** RMS difference of two level-matched third-octave spectra (40 Hz to 10 kHz), dB: a change of shape, not of level. */
    private fun shapeDistanceDb(a: FloatArray, b: FloatArray): Float {
        val size = 1 shl 15
        fun bands(x: FloatArray): DoubleArray {
            val mag = Fft.magnitudeSpectrum(x.copyOf(size), size)
            val out = DoubleArray(24)
            var total = 0.0
            for (bin in mag.indices) {
                val hz = Fft.binToHz(bin, size, rate)
                if (hz < 40f || hz >= 10_000f) continue
                val band = (3.0 * kotlin.math.ln(hz / 40.0) / kotlin.math.ln(2.0)).toInt().coerceIn(0, 23)
                val p = mag[bin].toDouble() * mag[bin]
                out[band] += p; total += p
            }
            return DoubleArray(24) { 10 * log10(out[it] / (total + 1e-18) + 1e-12) }
        }
        val x = bands(a); val y = bands(b)
        var s = 0.0
        for (i in x.indices) s += (x[i] - y[i]) * (x[i] - y[i])
        return kotlin.math.sqrt(s / x.size).toFloat()
    }

    /** How far apart two takes are: the energy of one over the energy of their difference, dB. A copy reads over 40. */
    private fun apartDb(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var e = 0.0; var d = 0.0
        for (i in 0 until n) { e += a[i].toDouble() * a[i]; val x = a[i] - b[i]; d += x.toDouble() * x }
        return (10 * log10(e / (d + 1e-30))).toFloat()
    }

    @Test fun `every strike of every grid is finite, under the ceiling and sounds`() {
        for (kit in Kit.entries) for (role in 0 until KindredKits.PADS) for (v in KindredKits.velocities) for (t in 0 until KindredKits.TAKES) {
            val x = KindredKits.render(kit, role, v, t).samples
            assertTrue(x.all { it.isFinite() }, "$kit $role $v $t non-finite")
            assertTrue(peak(x) in .05f..0.98f, "$kit $role $v $t peak ${peak(x)}")
        }
    }

    @Test fun `a soft zone is a different strike, not the hard one turned down`() {
        val d = Kit.entries.flatMap { kit -> (0 until KindredKits.PADS).map { role ->
            "$kit/$role" to shapeDistanceDb(KindredKits.render(kit, role, .3f, 0).samples, KindredKits.render(kit, role, 1f, 0).samples)
        } }
        println("KINDRED kits soft against hard, shape dB, least first: " + d.sortedBy { it.second }.take(10).joinToString { "${it.first}=${"%.2f".format(it.second)}" })
        for ((id, v) in d) assertTrue(v > MIN_ZONE_SHAPE_DB, "$id soft against hard: $v dB of shape")
    }

    @Test fun `no two takes in a zone are copies`() {
        var closest = -1e9f to ""
        for (kit in Kit.entries) for (role in 0 until KindredKits.PADS) for (v in KindredKits.velocities) {
            val takes = List(KindredKits.TAKES) { KindredKits.render(kit, role, v, it).samples }
            for (i in takes.indices) for (j in i + 1 until takes.size) {
                val a = apartDb(takes[i], takes[j])
                if (a > closest.first) closest = a to "$kit/$role v$v takes $i,$j"
                assertTrue(a < COPY_DB, "$kit $role v$v takes $i and $j are $a dB apart: a copy")
            }
        }
        println("KINDRED kits closest takes: ${closest.second} ${"%.1f".format(closest.first)} dB")
    }

    @Test fun `the four kits are four kits, not one at four settings`() {
        val kits = Kit.entries
        for (a in kits.indices) for (b in a + 1 until kits.size) {
            val d = (0 until KindredKits.HITS).map { role ->
                shapeDistanceDb(KindredKits.render(kits[a], role, 1f, 0).samples, KindredKits.render(kits[b], role, 1f, 0).samples)
            }
            println("KINDRED ${kits[a]} against ${kits[b]}: slots over 2 dB ${d.count { it > 2f }}, least ${"%.2f".format(d.min())}")
            assertTrue(d.count { it > 2f } >= MIN_DIFFERENT_SLOTS, "${kits[a]} and ${kits[b]} differ on only ${d.count { it > 2f }} slots")
            assertTrue(kits[a].bpm != kits[b].bpm)
        }
    }

    @Test fun `the phrase is two bars of the kit's voice and the riser is the kit's riser`() {
        for (kit in Kit.entries) {
            val phrase = KindredKits.render(kit, KindredKits.HITS, 1f, 0).samples
            val bars = KindredKits.PHRASE_BARS * 4 * 60f / kit.bpm
            assertTrue(phrase.size / rate.toFloat() in bars..bars + .6f, "$kit phrase ${phrase.size / rate.toFloat()} s for $bars s of bars")
            val riser = KindredKits.render(kit, KindredKits.HITS + 1, 1f, 0).samples
            assertTrue(riser.size / rate.toFloat() in kit.spec.riserSeconds.toFloat()..kit.spec.riserSeconds.toFloat() + .6f, "$kit riser ${riser.size / rate.toFloat()} s")
            assertEquals(DrumClass.LOOP, KindredKits.drumClass(kit, KindredKits.HITS))
        }
    }

    @Test fun `pad levels carry the round-2 groove balance`() {
        for (kit in Kit.entries) {
            val levels = (0 until KindredKits.PADS).map { KindredKits.level(kit, it) }
            println("KINDRED $kit levels: " + levels.joinToString { "%.2f".format(it) })
            assertTrue(levels.all { it > 0f && it <= KindredKits.TOP_LEVEL })
            assertEquals(KindredKits.TOP_LEVEL, levels.take(KindredKits.HITS).max())
        }
    }

    @Test fun `the kits are built from the recipes, fourteen each, in slot order`() {
        for (kit in Kit.entries) {
            assertEquals((1..14).map { "${kit.spec.name}_A%02d".format(it) }, KindredKits.recipes(kit).map { it.id })
            assertEquals(KindredKits.PADS, KindredKits.roles(kit).size)
        }
    }

    private companion object {
        /** Floor under the least soft-against-hard change of any pad: 1.74 dB measured at K2 (ELECTRO A04, TECHNO A11). */
        const val MIN_ZONE_SHAPE_DB = 1.25f
        const val COPY_DB = 40f
        /** Slots of fourteen that must differ by over 2 dB between any two kits: all fourteen did at K2 (least 2.90 dB). */
        const val MIN_DIFFERENT_SLOTS = 12
    }
}
