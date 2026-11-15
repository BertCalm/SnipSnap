package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * EMBER round 1: the house probes on every voice, and the design's claims turned into tests (BUZZ 0 takes the
 * snares off; WARMTH 0 is an exact bypass; the swell rises and the press roll crescendos; the club is built
 * once). Distances use the prototype's measure (prototype/ember/measures.py, from CLUTTER's): the RMS difference
 * of level-matched third-octave spectrograms, every band floored 80 dB under the loudest. Renders run in
 * parallel: EMBER's 27 voices take 0.1 to 1.3 s each.
 */
class EmberTest {

    private val all = EmberVoice.entries

    private fun render(v: EmberVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0) =
        Ember.render(v, macros, velocity, take).samples

    private fun <T> each(voices: List<EmberVoice> = all, f: (EmberVoice) -> T): List<Pair<EmberVoice, T>> =
        voices.parallelStream().map { it to f(it) }.toList()

    @Test fun `every voice is finite, peak-matched, centred and as long as it says`() {
        for ((v, y) in each { render(it) }) {
            assertTrue(y.all { it.isFinite() }, "$v finite")
            assertEquals(Ember.PEAK, y.maxOf { abs(it) }, 1e-4f, "$v peak")
            assertTrue(abs(y.average()) < 0.005, "$v DC ${y.average()}")
            assertEquals((Ember.secondsFor(v) * Ember.RATE).toInt(), y.size, "$v length")
        }
        // The sweep's length follows its circle: SPEED 0 is a ballad's slow stir.
        for (speed in listOf(0f, 1f)) {
            val m = mapOf("SPEED" to speed)
            assertEquals((Ember.secondsFor(EmberVoice.BRUSH_SWEEP, m) * Ember.RATE).toInt(), render(EmberVoice.BRUSH_SWEEP, m).size, "sweep at SPEED $speed")
        }
        assertTrue(Ember.secondsFor(EmberVoice.BRUSH_SWEEP, mapOf("SPEED" to 0f)) > Ember.secondsFor(EmberVoice.BRUSH_SWEEP, mapOf("SPEED" to 1f)) + 1)
    }

    @Test fun `every take replays byte for byte and the four takes are four strikes`() {
        for ((v, closest) in each { v ->
            val takes = (0..Ember.MAX_TAKE).map { render(v, take = it) }
            for ((t, y) in takes.withIndex()) assertContentEquals(y, render(v, take = t), "$v take $t replays")
            var c = Double.MAX_VALUE
            for (a in takes.indices) for (b in a + 1 until takes.size) c = min(c, distanceDb(takes[a], takes[b]))
            c
        }) {
            // ETCH's takes that the owner heard as one strike measured 0.5 to 0.8 dB; EMBER's closest Python pair is 1.6.
            assertTrue(closest >= 1.0, "$v closest takes $closest dB")
        }
    }

    @Test fun `velocity changes the stroke, not only the level`() {
        for ((v, d) in each { distanceDb(render(it, velocity = 0.35f), render(it, velocity = 1f)) }) {
            // FIRED BRONZE's bar: every softer layer at least 1.8 dB in shape at matched loudness. The bow, whose
            // first 150 ms is mostly its swell, is the closest (2.1 dB in the round-1 measure).
            assertTrue(d >= 1.8, "$v soft against hard at matched level: $d dB")
        }
    }

    @Test fun `every control moves every voice and an unknown control or a bad value changes nothing`() {
        for ((v, moved) in each { v ->
            val plain = render(v)
            assertContentEquals(plain, render(v, mapOf("NOPE" to 0.9f)), "$v unknown control")
            assertContentEquals(plain, render(v, mapOf(v.controls.first() to Float.NaN)), "$v NaN control")
            assertContentEquals(plain, render(v, velocity = Float.NaN), "$v NaN velocity")
            v.controls.associateWith { fullDistanceDb(render(v, mapOf(it to 0f)), render(v, mapOf(it to 1f))) }
        }) {
            for ((mac, d) in moved) {
                // The foot chick is short and its plates touch only lightly: its BUZZ chatter measured 0.6 to 1.0 dB
                // over four takes in the Python prototype too. Everything else moves at least 1 dB.
                val bar = if (v == EmberVoice.HAT_FOOT && mac == "BUZZ") 0.5 else 1.0
                assertTrue(d >= bar, "$v $mac moved it $d dB between 0 and 1")
            }
        }
    }

    @Test fun `BUZZ 0 takes the snares off and the cymbals start as recorded`() {
        val head = DoubleArray(4000) { kotlin.math.sin(it * 0.01) }
        assertTrue(Ember.wires(head, 0.0, EmberDsp.rngFor("t"), head.size).all { it == 0.0 }, "no wires at BUZZ 0")
        val off = render(EmberVoice.SNARE, mapOf("BUZZ" to 0f))
        val on = render(EmberVoice.SNARE)
        assertTrue(fullDistanceDb(off, on) >= 2.0, "snares off against on")
        for (v in listOf(EmberVoice.RIDE, EmberVoice.RIDE_BELL, EmberVoice.BRUSH_RIDE, EmberVoice.BOWED_CYMBAL,
                         EmberVoice.CYMBAL_SWELL, EmberVoice.HAT_FOOT, EmberVoice.HAT_CLOSED, EmberVoice.HAT_OPEN)) {
            assertEquals(0f, Ember.defaults(v).getValue("BUZZ"), "$v starts as recorded")
        }
    }

    @Test fun `WARMTH 0 is an exact bypass`() {
        val x = DoubleArray(2000) { kotlin.math.sin(it * 0.02) * 0.5 }
        assertSame(x, Ember.warmthChain(x, 0.0, 1.0))
        assertFalse(x.contentEquals(Ember.warmthChain(x, 0.5, 1.0)))
    }

    /** The percussion gate: a riser and two rolls. The swell grows from nothing to a wash; the press roll crescendos into its accent. */
    @Test fun `the swell rises and the rolls crescendo`() {
        val swell = render(EmberVoice.CYMBAL_SWELL)
        val rise = rmsDb(swell, 2.0, 2.5) - rmsDb(swell, 0.0, 0.5)
        assertTrue(rise >= 20.0, "the swell rises $rise dB")
        for (v in listOf(EmberVoice.PRESS_ROLL, EmberVoice.BRUSH_ROLL)) {
            val y = render(v)
            val grow = rmsDb(y, 1.8, 2.3) - rmsDb(y, 0.0, 0.5)
            assertTrue(grow >= 3.0, "$v grows $grow dB")
            assertTrue(y.size >= (2.5 * Ember.RATE).toInt(), "$v runs past 2.5 s")
        }
    }

    /**
     * Round 1b (owner: "Swell cuts abruptly"): the long voices end once they have died away, not while they still
     * sound. Before, the swell was cut 2.2 dB under its peak and the bow and rolls 12 to 16 dB under. Now the last
     * 100 ms before the closing 60 ms fade sit at least 40 dB under the loudest 100 ms, hard and soft, every take.
     */
    @Test fun `the long voices ring out instead of being cut`() {
        val long = listOf(EmberVoice.CYMBAL_SWELL, EmberVoice.BOWED_CYMBAL, EmberVoice.PRESS_ROLL, EmberVoice.BRUSH_ROLL)
        val cases = long.flatMap { v -> listOf(1f, 0.35f).flatMap { vel -> (0..Ember.MAX_TAKE).map { Triple(v, vel, it) } } }
        for ((case, under) in cases.parallelStream().map { (v, vel, t) ->
            val y = render(v, velocity = vel, take = t)
            val end = y.size.toDouble() / Ember.RATE
            var peak = -999.0
            var t0 = 0.0
            while (t0 + 0.1 <= end) { peak = max(peak, rmsDb(y, t0, t0 + 0.1)); t0 += 0.05 }
            Triple(v, vel, t) to peak - rmsDb(y, end - 0.17, end - 0.07)
        }.toList()) {
            assertTrue(under >= 40.0, "${case.first} velocity ${case.second} take ${case.third} ends only $under dB under its peak")
        }
    }

    @Test fun `the patch carries velocity, take and model and refuses what it cannot render`() {
        val p = EmberPatch("Brush", EmberVoice.BRUSH_TAP, mapOf("BUZZ" to 0.8f), velocity = 0.6f, take = 2)
        val back = EmberPatch.fromJsonText(p.toJsonText())
        assertEquals(p, back)
        assertEquals("EMBER", back.engine)
        assertContentEquals(p.render().samples, back.render().samples)
        assertFailsWith<IllegalArgumentException> { EmberPatch("x", EmberVoice.KICK, emptyMap(), take = Ember.MAX_TAKE + 1) }
        assertFailsWith<IllegalArgumentException> { EmberPatch("x", EmberVoice.KICK, emptyMap(), velocity = 1.5f) }
        assertFailsWith<IllegalArgumentException> { EmberPatch("x", EmberVoice.KICK, emptyMap(), model = Ember.MODEL_VERSION + 1) }
    }

    @Test fun `every voice files as the kit role it plays`() {
        assertEquals(setOf(EmberVoice.KICK), all.filter { Ember.drumClassFor(it) == DrumClass.KICK }.toSet())
        assertEquals(setOf(EmberVoice.SNARE, EmberVoice.RIMSHOT, EmberVoice.BRUSH_TAP), all.filter { Ember.drumClassFor(it) == DrumClass.SNARE }.toSet())
        assertEquals(DrumClass.HAT_OPEN, Ember.drumClassFor(EmberVoice.HAT_OPEN))
        // Past 1.5 s, PERC, as BREAKER files its riser and crash.
        for (v in all) if (Ember.secondsFor(v) > 1.5) assertEquals(DrumClass.PERC, Ember.drumClassFor(v), "$v")
    }

    @Test fun `the club is built once per DISTANCE and only a few are kept`() {
        assertSame(Ember.roomIr(0.3), Ember.roomIr(0.3))
        for (i in 0 until 3 * Ember.ROOMS_KEPT) Ember.roomIr(0.5 + i / 1000.0)
        assertTrue(Ember.roomsHeld() <= Ember.ROOMS_KEPT, "rooms held ${Ember.roomsHeld()}")
    }

    @Test fun `no voice carries a maker's name`() {
        val makers = listOf("ZILDJIAN", "LUDWIG", "GRETSCH", "ROGERS", "SLINGERLAND", "PAISTE", "SABIAN", "LEEDY", "K_", "AVEDIS")
        for (v in all) for (m in makers) assertFalse(v.name.contains(m), "$v")
    }

    private fun distanceDb(a: FloatArray, b: FloatArray) = EmberMeasures.distanceDb(a, b)
    private fun fullDistanceDb(a: FloatArray, b: FloatArray) = EmberMeasures.fullDistanceDb(a, b)
    private fun rmsDb(x: FloatArray, t0: Double, t1: Double) = EmberMeasures.rmsDb(x, t0, t1)
}
