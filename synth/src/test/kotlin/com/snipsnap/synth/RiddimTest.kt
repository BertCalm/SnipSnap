package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RIDDIM's probe set: the laws the approved prototype measured, held by the port (docs/RIDDIM.md, round 2). */
class RiddimTest {

    private val one = RiddimVoice.entries.filterNot(Riddim::isRoll)
    private val rolls = RiddimVoice.entries.filter(Riddim::isRoll)

    private fun render(v: RiddimVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0) =
        Riddim.render(v, macros, velocity, take).samples

    private fun rms(x: FloatArray): Double { var s = 0.0; for (v in x) s += v * v; return sqrt(s / max(1, x.size)) }

    /** 20 log10(rms(a) / rms(a - b)) over the shorter length: above 40 dB the two are copies (the house line). */
    private fun distanceDb(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var s = 0.0; var d = 0.0
        for (i in 0 until n) { s += a[i] * a[i]; val e = a[i] - b[i]; d += e * e }
        return 10 * log10(s / max(d, 1e-30))
    }

    /** The share of the first 300 ms above [splitHz], in dB: the brightness the prototype's checks used. */
    private fun upperShareDb(x: FloatArray, splitHz: Float): Double {
        val n = minOf(x.size, (0.3 * Riddim.RATE).toInt())
        val mag = Fft.magnitudeSpectrum(x.copyOf(n), 16384)
        var all = 0.0; var up = 0.0
        for ((i, m) in mag.withIndex()) {
            val p = m.toDouble() * m
            all += p
            if (Fft.binToHz(i, 16384, Riddim.RATE) >= splitHz) up += p
        }
        return 10 * log10(max(up, 1e-30) / all)
    }

    private fun split(v: RiddimVoice) = when (v) {
        RiddimVoice.KICK, RiddimVoice.DEMBOW_KICK, RiddimVoice.BOOM, RiddimVoice.SLIDE -> 150f
        RiddimVoice.HAT_CLOSED, RiddimVoice.HAT_OPEN -> 9000f
        RiddimVoice.WOOD -> 400f      // a wooden bar has nothing above 2 kHz: its upper modes are 736 Hz and 1.8 kHz
        else -> 2000f
    }

    /** The strongest bin between [lo] and [hi] Hz in a window of [x] starting at [fromS]. */
    private fun peakHz(x: FloatArray, fromS: Double, lo: Float, hi: Float, window: Int = 16384): Float {
        val a = (fromS * Riddim.RATE).toInt()
        val mag = Fft.magnitudeSpectrum(x.copyOfRange(a, minOf(x.size, a + window)), 65536)
        var best = 0; var bestM = -1f
        for (i in mag.indices) {
            val f = Fft.binToHz(i, 65536, Riddim.RATE)
            if (f in lo..hi && mag[i] > bestM) { bestM = mag[i]; best = i }
        }
        return Fft.binToHz(best, 65536, Riddim.RATE)
    }

    @Test fun `every voice renders finite, at the house peak, without DC, at its own length`() {
        for (v in RiddimVoice.entries) {
            val x = render(v)
            assertTrue(x.all { it.isFinite() }, "$v finite")
            var p = 0f; var mean = 0.0
            for (s in x) { p = max(p, abs(s)); mean += s }
            assertEquals(0.89f, p, 1e-3f, "$v peak")
            assertTrue(abs(mean / x.size) < 0.01, "$v DC ${mean / x.size}")
            assertEquals((Riddim.secondsFor(v, emptyMap()) * Riddim.RATE).toInt(), x.size, "$v length")
        }
    }

    @Test fun `every voice files as the kit role it plays and the rolls stay percussion`() {
        val byClass = RiddimVoice.entries.groupBy(Riddim::drumClassFor)
        assertEquals(setOf(RiddimVoice.KICK, RiddimVoice.DEMBOW_KICK, RiddimVoice.BOOM, RiddimVoice.SLIDE), byClass[DrumClass.KICK]?.toSet())
        assertEquals(setOf(RiddimVoice.RIMSHOT, RiddimVoice.SNARE), byClass[DrumClass.SNARE]?.toSet())
        assertEquals(listOf(RiddimVoice.CLAP), byClass[DrumClass.CLAP])
        assertEquals(listOf(RiddimVoice.HAT_CLOSED), byClass[DrumClass.HAT_CLOSED])
        assertEquals(listOf(RiddimVoice.HAT_OPEN), byClass[DrumClass.HAT_OPEN])
        for (r in rolls) assertEquals(DrumClass.PERC, Riddim.drumClassFor(r), "$r")
    }

    @Test fun `the kicks and booms fall onto their notes the way the references measured`() {
        // Prototype targets: the punch kick settles near 40 Hz, the dembow kick near 27 Hz, the boom on F1 (43.65 Hz).
        assertEquals(40f, peakHz(render(RiddimVoice.KICK), 0.2, 25f, 120f), 3f)
        assertEquals(27f, peakHz(render(RiddimVoice.DEMBOW_KICK), 0.2, 15f, 120f), 3f)
        assertEquals(43.65f, peakHz(render(RiddimVoice.BOOM), 0.3, 25f, 120f), 1.5f)
        // TUNE moves the boom by semitones: three up is Ab1, 51.9 Hz.
        assertEquals(51.91f, peakHz(render(RiddimVoice.BOOM, mapOf("TUNE" to Riddim.tuneFor(RiddimVoice.BOOM, 3))), 0.3, 25f, 120f), 1.5f)
        // The slide is still gliding at 80 ms and lands on F1 by 300 ms.
        val slide = render(RiddimVoice.SLIDE)
        val early = peakHz(slide, 0.03, 25f, 160f, window = 2048)
        assertTrue(early > 50f, "slide still high early: $early Hz")
        assertEquals(43.65f, peakHz(slide, 0.5, 25f, 120f), 1.5f)
    }

    @Test fun `the wood and the metal sound their notes`() {
        assertEquals(185f, peakHz(render(RiddimVoice.WOOD), 0.0, 100f, 400f), 4f)                // F#3
        assertEquals(587.3f, peakHz(render(RiddimVoice.METAL), 0.05, 400f, 800f), 8f)            // D5 (the pair beats)
        assertEquals(370f, peakHz(render(RiddimVoice.WOOD, mapOf("TUNE" to Riddim.tuneFor(RiddimVoice.WOOD, 12))), 0.0, 250f, 600f), 6f)
    }

    /**
     * Softer is darker at every zone. METAL is checked at the notes the kits play it (G4, F5, Ab5, C6): at its
     * default D5 the mallet's spectral dip lands on the second mode and soft and mid measure alike, in the approved
     * prototype too (-50.8 and -50.5 dB above 2 kHz, four takes); disclosed in docs/RIDDIM.md.
     */
    @Test fun `a softer hit is darker, for every one-shot voice`() {
        for (v in one) {
            val tunings = if (v == RiddimVoice.METAL) listOf(-7, 3, 6, 10).map { mapOf("TUNE" to Riddim.tuneFor(v, it)) } else listOf(emptyMap())
            for (m in tunings) {
                val zones = listOf(.35f, .65f, 1f).map { vel -> (0..Riddim.MAX_TAKE).map { upperShareDb(render(v, m, vel, it), split(v)) }.average() }
                assertTrue(zones[0] < zones[1] && zones[1] < zones[2], "$v $m brightness soft, mid, hard: $zones")
            }
        }
    }

    @Test fun `no two takes are copies`() {
        for (v in RiddimVoice.entries) {
            for (vel in listOf(.35f, 1f)) {
                val takes = (0..Riddim.MAX_TAKE).map { render(v, velocity = vel, take = it) }
                for (i in takes.indices) for (j in i + 1 until takes.size) {
                    val d = distanceDb(takes[i], takes[j])
                    assertTrue(d < 40, "$v at $vel: takes $i and $j are $d dB apart, a copy")
                }
            }
        }
    }

    @Test fun `every control changes the sound and an unknown one does not`() {
        for (v in RiddimVoice.entries) {
            val base = render(v)
            for (spec in Riddim.macrosFor(v)) {
                val moved = mapOf(spec.name to if (spec.default > .5f) 0f else 1f)
                val y = render(v, moved)
                val same = y.size == base.size && distanceDb(base, y) > 60
                assertTrue(!same, "$v ${spec.name} changes nothing")
            }
            assertContentEquals(base, render(v, mapOf("NOT_A_CONTROL" to 1f)), "$v unknown key")
        }
    }

    @Test fun `a non-finite control falls back to its default`() {
        for (v in listOf(RiddimVoice.KICK, RiddimVoice.METAL, RiddimVoice.RIM_ROLL)) {
            assertContentEquals(render(v), render(v, mapOf("TUNE" to Float.NaN, "DECAY" to Float.POSITIVE_INFINITY)), "$v")
        }
    }

    @Test fun `a roll lasts one bar at its tempo and SPEED sets the tempo`() {
        for (bpm in listOf(95.0, 100.0, 102.0)) {
            val speed = Riddim.speedFor(bpm)
            assertEquals(bpm, Riddim.bpmOf(speed), 1e-3)
            for (r in rolls) {
                val x = render(r, mapOf("SPEED" to speed))
                assertEquals(((4 * 60.0 / Riddim.bpmOf(speed) + 0.6) * Riddim.RATE).toInt(), x.size, "$r at $bpm")
            }
        }
        // Strokes accelerate and crescendo: the last stroke is the loudest, and the gaps only shrink.
        for (r in rolls) {
            val s = Riddim.rollStrokes(r)
            assertTrue(s.zipWithNext().all { (a, b) -> b.second >= a.second }, "$r crescendo")
            val gaps = s.zipWithNext { a, b -> b.first - a.first }
            assertTrue(gaps.zipWithNext().all { (a, b) -> b <= a + 1e-9 }, "$r accelerates")
        }
    }

    /** Each stroke is peak-matched (the prototype's way), so a roll grows by density and brightness, not stroke level. */
    @Test fun `a roll gets louder toward its end`() {
        for (r in rolls) {
            val x = render(r, mapOf("SPEED" to Riddim.speedFor(100.0)))
            val bar = (2.4 * Riddim.RATE).toInt()
            val first = rms(x.copyOfRange(0, bar / 4))
            val last = rms(x.copyOfRange(bar * 3 / 4, bar))
            assertTrue(last > first * 1.2, "$r first quarter ${"%.3f".format(first)}, last ${"%.3f".format(last)}")
        }
    }

    @Test fun `renders repeat byte for byte`() {
        for (v in RiddimVoice.entries) assertContentEquals(render(v, velocity = .6f, take = 2), render(v, velocity = .6f, take = 2), "$v")
    }
}
