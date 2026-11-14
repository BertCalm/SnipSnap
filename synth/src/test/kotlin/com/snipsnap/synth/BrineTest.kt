package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.*
import kotlin.test.*

class BrineTest {
    private fun probe(seconds: Float = .3f) = Snip(FloatArray((Dsp.RATE * seconds).toInt()) {
        (.3 * sin(2 * PI * 173 * it / Dsp.RATE) * exp(-it / (Dsp.RATE * .08))).toFloat()
    }, 1, Dsp.RATE)

    @Test fun `settings round trip and reject malformed saved controls`() {
        val s = Brine.Settings(.1f, .2f, .3f, .4f, .5f, .6f)
        assertEquals(s, Brine.Settings.fromJsonText(s.toJsonText()))
        for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f))
            assertFailsWith<IllegalArgumentException> { Brine.Settings(splash = v) }
        val invalid = Json.parse(s.toJsonText()).obj().toMutableMap()
        invalid["brine"] = JsonValue.Num(2.0)
        assertFailsWith<IllegalArgumentException> { Brine.Settings.fromJsonText(Json.write(JsonValue.Obj(invalid))) }
    }

    @Test fun `corners are finite bounded deterministic and leave the source intact`() {
        val input = probe()
        val before = input.samples.copyOf()
        for (v in listOf(0f, .5f, 1f)) {
            val s = Brine.Settings(v, v, v, v, v, v)
            val out = Brine.process(input, s)
            assertContentEquals(out.samples, Brine.process(input, s).samples)
            assertEquals(input.frameCount + Dsp.RATE, out.frameCount)
            assertTrue(out.samples.all { it.isFinite() && abs(it) <= .99001f })
            assertTrue(abs(out.samples.average()) < .005)
            assertTrue(abs(out.samples.last()) < 1e-5)
        }
        assertContentEquals(before, input.samples)
        assertSame(input, Brine.process(input, through = Brine.Stage.DRY))
        assertTrue(Brine.process(Snip(FloatArray(100), 1, Dsp.RATE)).samples.all { it == 0f })
        assertFailsWith<IllegalArgumentException> { Brine.process(Snip(floatArrayOf(Float.NaN), 1, Dsp.RATE)) }
    }

    @Test fun `spring dispersion changes the tail and unforced energy decays before any final fade`() {
        val x = FloatArray(Dsp.RATE * 3)
        x[0] = .4f
        val y = Brine.spring(x, 1f)
        val plain = Brine.spring(x, 1f, dispersion = false)
        fun energy(from: Int, until: Int) = (from until until).sumOf { y[it].toDouble().pow(2) }
        assertTrue(energy(Dsp.RATE * 2, Dsp.RATE * 3) < energy(Dsp.RATE / 10, Dsp.RATE / 2) * .001)
        assertTrue(y.maxOf { abs(it) } < 1f)
        assertTrue(y.indices.sumOf { (y[it] - plain[it]).toDouble().pow(2) } > 1e-5)
    }

    @Test fun `each control moves a phrase and downstream drive interacts with splash`() {
        val input = probe(1.5f)
        fun distance(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] - b[it]).toDouble().pow(2) }
        val s = Brine.Settings()
        val base = Brine.process(input, s).samples
        for (changed in listOf(s.copy(room = .7f), s.copy(grit = .7f), s.copy(splash = .7f),
            s.copy(sweep = .7f), s.copy(rate = .7f), s.copy(drive = .7f)))
            assertTrue(distance(base, Brine.process(input, changed).samples) > 1e-5, "$changed")
        fun delta(drive: Float): FloatArray {
            val a = Brine.process(input, s.copy(splash = .1f, drive = drive)).samples
            val b = Brine.process(input, s.copy(splash = .8f, drive = drive)).samples
            return FloatArray(a.size) { b[it] - a[it] }
        }
        assertTrue(distance(delta(.1f), delta(.8f)) > 1e-5)
    }
}
