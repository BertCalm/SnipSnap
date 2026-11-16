package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SERVO's house probes, and the claims its design rests on: the rail is bounded, switchable and keeps the root. */
class ServoTest {
    private val rate = Dsp.RATE

    private fun raw(voice: ServoVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0) =
        Servo.renderRaw(voice, macros, velocity, take)

    /** Distance between two renders once level is taken out: the same audio measures 0. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        val ea = sqrt(a.sumOf { it.toDouble() * it })
        val eb = sqrt(b.sumOf { it.toDouble() * it })
        var d = 0.0
        for (i in 0 until n) { val v = a[i] / ea - b[i] / eb; d += v * v }
        return sqrt(d)
    }

    /** The strongest partial between [lo] and [hi] Hz over the stretch [from]..[to] of the render (fractions of its length). */
    private fun peakHz(x: FloatArray, from: Double, to: Double, lo: Float, hi: Float): Float {
        val a = (x.size * from).toInt(); val b = (x.size * to).toInt()
        val size = 1 shl 17
        val seg = FloatArray(size)
        for (i in a until b) seg[i - a] = x[i] * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * (i - a) / (b - a)).toFloat())
        val mag = Fft.magnitudeSpectrum(seg, size)
        var best = 0; var v = -1f
        for (bin in mag.indices) { val hz = Fft.binToHz(bin, size, rate); if (hz in lo..hi && mag[bin] > v) { v = mag[bin]; best = bin } }
        return Fft.binToHz(best, size, rate)
    }

    @Test fun `every voice is finite centred shorter than a loop and ends in silence`() {
        val corners = listOf(emptyMap(), Servo.defaults(ServoVoice.KICK).mapValues { 0f }, Servo.defaults(ServoVoice.KICK).mapValues { 1f })
        for (voice in ServoVoice.entries) for (m in corners) {
            val x = raw(voice, m, 1f, 2)
            assertTrue(x.all { it.isFinite() }, "$voice $m finite")
            assertTrue(x.maxOf { abs(it) } in .01f..2f, "$voice $m peak ${x.maxOf { abs(it) }}")
            assertTrue(abs(x.average()) < .005, "$voice $m DC ${x.average()}")
            assertTrue(x.size < 1.5 * rate, "$voice $m is ${x.size} frames")
            assertTrue(abs(x.last()) == 0f, "$voice $m tail ${x.last()}")
        }
    }

    @Test fun `a render is peak matched and files under its voice's class`() {
        for (voice in ServoVoice.entries) {
            val s = Servo.render(voice)
            assertEquals(Servo.PEAK, s.samples.maxOf { abs(it) }, 1e-4f, "$voice")
            assertEquals(voice.drumClass, Servo.drumClassFor(voice))
        }
        assertEquals(DrumClass.KICK, Servo.drumClassFor(ServoVoice.KICK))
    }

    @Test fun `every control changes every voice and an unknown one changes nothing`() {
        for (voice in ServoVoice.entries) {
            for (spec in Servo.macrosFor(voice)) {
                val d = shapeDistance(raw(voice, mapOf(spec.name to 0f)), raw(voice, mapOf(spec.name to 1f)))
                assertTrue(d > .02, "$voice ${spec.name} moved the sound by only $d")
            }
            assertContentEquals(raw(voice), raw(voice, mapOf("BOGUS" to .9f)), "$voice unknown key")
            assertContentEquals(raw(voice), raw(voice, mapOf("SAG" to Float.NaN)), "$voice NaN control")
            assertContentEquals(raw(voice), raw(voice, velocity = Float.NaN), "$voice NaN velocity")
        }
    }

    @Test fun `SAG at zero switches the rail off so GROWL does nothing`() {
        for (voice in ServoVoice.entries) {
            assertContentEquals(raw(voice, mapOf("SAG" to 0f, "GROWL" to 0f)), raw(voice, mapOf("SAG" to 0f, "GROWL" to 1f)), "$voice")
            // With the rail on, GROWL must matter: the pair interacts.
            val on = shapeDistance(raw(voice, mapOf("SAG" to .8f, "GROWL" to 0f)), raw(voice, mapOf("SAG" to .8f, "GROWL" to 1f)))
            assertTrue(on > .02, "$voice: GROWL did nothing with the rail on ($on)")
        }
    }

    @Test fun `the rail stays inside its bound at the worst corner`() {
        for (voice in ServoVoice.entries) for (take in 0..Servo.MAX_TAKE) {
            val d = Servo.Diagnostics()
            Servo.renderRaw(voice, mapOf("SAG" to 1f, "GROWL" to 1f, "HEAT" to 1f, "DECAY" to 1f), 1f, take, diagnostics = d)
            assertTrue(d.railMax < Servo.R_MAX && d.railMin > -Servo.R_MAX, "$voice take $take rail ${d.railMin}..${d.railMax}")
            assertTrue(d.railMax > .3, "$voice take $take: the rail barely moved (${d.railMax})")
        }
        // Reverting the guard: without SAG the rail does not move at all.
        val off = Servo.Diagnostics()
        Servo.renderRaw(ServoVoice.KICK, mapOf("SAG" to 0f), 1f, 0, diagnostics = off)
        assertEquals(0.0, off.railMax)
    }

    @Test fun `the root survives the rail at every corner`() {
        // The rail has recovered by the tail, so the tail's pitch is the TUNE root whatever SAG and GROWL did.
        val ranges = mapOf(ServoVoice.KICK to (38.0 to 78.0), ServoVoice.TOM to (70.0 to 260.0))
        for ((voice, range) in ranges) for (tune in listOf(.2f, .5f, .8f)) {
            val nominal = range.first * (range.second / range.first).pow(tune.toDouble())
            for (sag in listOf(0f, .45f, 1f)) for (growl in listOf(0f, .35f, 1f)) {
                val x = raw(voice, mapOf("TUNE" to tune, "SAG" to sag, "GROWL" to growl, "DECAY" to .85f, "HEAT" to 0f))
                val hz = peakHz(x, .55, .9, 20f, 700f)
                val cents = 1200 * ln(hz / nominal) / ln(2.0)
                assertTrue(abs(cents) < 30, "$voice TUNE $tune SAG $sag GROWL $growl: tail at $hz Hz, $cents cents from $nominal")
            }
        }
        // And the rail does pull the pitch down while it is sagging: the body sits lower with SAG than without.
        val dry = peakHz(raw(ServoVoice.TOM, mapOf("SAG" to 0f, "DECAY" to .85f, "HEAT" to 0f, "ZAP" to 0f)), .05, .3, 20f, 700f)
        val sagged = peakHz(raw(ServoVoice.TOM, mapOf("SAG" to 1f, "GROWL" to 0f, "DECAY" to .85f, "HEAT" to 0f, "ZAP" to 0f)), .05, .3, 20f, 700f)
        assertTrue(sagged < dry * .98f, "the sag did not lower the body: $dry -> $sagged Hz")
    }

    @Test fun `a softer hit is a different hit and not only a quieter one`() {
        for (voice in ServoVoice.entries) {
            val d = shapeDistance(raw(voice, velocity = 1f), raw(voice, velocity = .35f))
            assertTrue(d > .05, "$voice: soft is a scaled copy of hard ($d)")
            assertTrue(raw(voice, velocity = .35f).maxOf { abs(it) } < raw(voice, velocity = 1f).maxOf { abs(it) }, "$voice: soft is not quieter before the peak match")
        }
        assertTrue(raw(ServoVoice.KICK, velocity = 0f).all { it == 0f }, "velocity 0 is silent")
    }

    @Test fun `four takes are four hits and replay byte for byte`() {
        for (voice in ServoVoice.entries) {
            val takes = List(Servo.MAX_TAKE + 1) { raw(voice, take = it) }
            for (a in takes.indices) {
                assertContentEquals(takes[a], raw(voice, take = a), "$voice take $a")
                for (b in a + 1 until takes.size) {
                    val d = shapeDistance(takes[a], takes[b])
                    assertTrue(d > .01, "$voice: takes $a and $b differ by only $d")
                }
            }
        }
    }

    @Test fun `scramble stays in bounds and names only the engine's controls`() {
        val random = kotlin.random.Random(7)
        for (voice in ServoVoice.entries) repeat(20) {
            val m = Servo.scramble(voice, random)
            assertEquals(Servo.defaults(voice).keys, m.keys)
            assertTrue(m.values.all { it in 0f..1f })
        }
    }

    @Test fun `no voice or control borrows a maker's name or the engine's own`() {
        for (name in ServoVoice.entries.map { it.name } + Servo.macrosFor(ServoVoice.KICK).map { it.name }) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
            assertFalse(name == ServoPatch.ENGINE, name)
        }
    }
}
