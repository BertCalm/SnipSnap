package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.json.JsonException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrawTest {

    // ---------- the pen ----------

    @Test
    fun `one stroke corner to corner is a ramp and touches nothing else than it crossed`() {
        val ramp = Draw.stroke(Draw.blank(), 0f, 0f, 1f, 1f)
        assertEquals(0, ramp.first())
        assertEquals(255, ramp.last())
        for (i in 1 until ramp.size) assertTrue(ramp[i] >= ramp[i - 1], "not monotone at $i")

        // A short stroke in the middle leaves both ends at rest.
        val mid = Draw.stroke(Draw.blank(), 0.4f, 1f, 0.6f, 1f)
        assertEquals(Draw.REST, mid[0])
        assertEquals(Draw.REST, mid[255])
        assertEquals(255, mid[128])
        assertTrue(mid.count { it == 255 } in 50..53, "a fifth of the line: ${mid.count { it == 255 }}")
    }

    @Test
    fun `a stroke drawn right to left is the same line as left to right`() {
        val forward = Draw.stroke(Draw.blank(), 0.2f, 0.1f, 0.8f, 0.9f)
        val backward = Draw.stroke(Draw.blank(), 0.8f, 0.9f, 0.2f, 0.1f)
        assertTrue(forward.contentEquals(backward))
    }

    @Test
    fun `a fast finger still draws a solid line, and a still one sets a point`() {
        // Two touch samples forty points apart: every point between is set.
        val fast = Draw.stroke(Draw.blank(), 0.1f, 0.2f, 0.25f, 0.2f)
        for (i in 26..63) assertEquals(51, fast[i], "gap at $i")
        val dot = Draw.stroke(Draw.blank(), 0.5f, 0.7f, 0.5f, 0.7f)
        assertEquals(1, dot.count { it != Draw.REST })
        // Off the panel is clamped to its edge, not dropped.
        val off = Draw.stroke(Draw.blank(), -1f, 2f, 2f, -1f)
        assertEquals(255, off.first())
        assertEquals(0, off.last())
    }

    @Test
    fun `the pen never writes into the array it was given`() {
        val before = Draw.blank()
        val copy = before.copyOf()
        Draw.stroke(before, 0f, 0f, 1f, 1f)
        Draw.smooth(before, circular = true)
        assertTrue(before.contentEquals(copy))
    }

    @Test
    fun `smoothing takes the shake out and keeps a shape's ends in place`() {
        val jagged = IntArray(Snap.TABLE_SIZE) { if (it % 2 == 0) 40 else 220 }
        val rough = Draw.roughness(jagged, circular = true)
        val calmer = Draw.smooth(jagged, circular = true, passes = 3)
        assertTrue(Draw.roughness(calmer, circular = true) < rough / 4, "$rough -> ${Draw.roughness(calmer, circular = true)}")

        // A shape's first and last points are the note's start and end.
        val shape = Draw.shape(Draw.Shape.PLUCK)
        val smoothed = Draw.smooth(shape, circular = false, passes = 2)
        assertEquals(shape.first(), smoothed.first())
        assertEquals(shape.last(), smoothed.last())
        // Circular smoothing of a saw pulls its ends together; linear leaves them.
        val saw = Draw.wave(Draw.Wave.SAW)
        val ring = Draw.smooth(saw, circular = true)
        assertTrue(abs(ring.first() - ring.last()) < abs(saw.first() - saw.last()))
    }

    // ---------- the starting shapes ----------

    @Test
    fun `every starting wave plays, in tune, and every starting shape opens`() {
        for (wave in Draw.Wave.entries) {
            val table = Draw.wave(wave)
            assertTrue(!Snap.isFlat(table), "$wave is flat")
            assertTrue(table.all { it in 0..255 })
            val snip = Snap.render(table, mapOf("BRIGHT" to 0.8f, "DECAY" to 0.8f, "GRIT" to 0f))
            assertTrue(snip.peak() > 0.5f && snip.samples.all { it.isFinite() }, "$wave")
        }
        // The sine lands on the note: the cleanest possible pitch test.
        val expected = Snap.frequencyFor(0.5f)
        val measured = TestPitch.estimate(Snap.render(Draw.wave(Draw.Wave.SINE), mapOf("TUNE" to 0.5f, "DECAY" to 0.8f, "GRIT" to 0f)))
        assertTrue(measured > expected * 0.94f && measured < expected * 1.06f, "expected ~$expected, got $measured")
        // A square swings the whole range; a sine rests on the middle.
        assertEquals(255, Draw.wave(Draw.Wave.SQUARE).max())
        assertEquals(0, Draw.wave(Draw.Wave.SQUARE).min())
        assertEquals(128, Draw.wave(Draw.Wave.SINE)[0])

        for (shape in Draw.Shape.entries) {
            val env = Draw.shape(shape)
            assertEquals(Draw.ENVELOPE_SIZE, env.size)
            assertTrue(env.any { it > 0 } && env.all { it in 0..255 }, "$shape")
        }
        assertTrue(Draw.shape(Draw.Shape.HOLD).all { it == 255 })
        assertEquals(255, Draw.shape(Draw.Shape.FALL).first())
    }

    // ---------- the drawn volume ----------

    @Test
    fun `a drawn shape replaces the decay - a hold rings to the end and a swell arrives late`() {
        val table = Draw.wave(Draw.Wave.TRIANGLE)
        val plain = FeatureExtractor.extract(Snap.render(table))
        val held = FeatureExtractor.extract(Snap.render(table, envelope = Draw.shape(Draw.Shape.HOLD)))
        assertTrue(held.decayMs > plain.decayMs * 2f, "HOLD ${held.decayMs}ms vs plain ${plain.decayMs}ms")

        val swell = Snap.render(table, envelope = Draw.shape(Draw.Shape.SWELL))
        var peakAt = 0
        var peak = 0f
        for ((i, v) in swell.samples.withIndex()) if (abs(v) > peak) { peak = abs(v); peakAt = i }
        assertTrue(peakAt > swell.frameCount * 0.4f, "a swell peaks late: ${peakAt.toFloat() / swell.frameCount}")

        // A HOLD's start still ramps: no click on the first sample.
        val hold = Snap.render(table, envelope = Draw.shape(Draw.Shape.HOLD))
        assertTrue(abs(hold.samples[0]) < 0.1f)
        // The wrong number of points is refused.
        assertFailsWith<IllegalArgumentException> { Snap.render(table, envelope = IntArray(10) { 255 }) }
    }

    @Test
    fun `envelopeAt interpolates between points and holds the last`() {
        val env = IntArray(Draw.ENVELOPE_SIZE) { 0 }.also { it[0] = 255; it[1] = 0 }
        assertEquals(1f, Snap.envelopeAt(env, 0f))
        assertTrue(abs(Snap.envelopeAt(env, 0.5f / (Draw.ENVELOPE_SIZE - 1)) - 0.5f) < 0.01f)
        assertEquals(0f, Snap.envelopeAt(env, 1f))
        assertEquals(0f, Snap.envelopeAt(env, 7f))
    }

    // ---------- the recipe ----------

    @Test
    fun `a drawn pad round-trips with its shape, and a pad without one reads as it always did`() {
        val drawn = SnapPatch("Pen", SnapVoice.DRAWN, mapOf("GRIT" to 0.3f), Draw.wave(Draw.Wave.SAW), Draw.shape(Draw.Shape.BOUNCE))
        val back = Patches.fromJsonText(drawn.toJsonText())
        assertEquals(drawn, back)
        assertTrue(back.render().samples.contentEquals(drawn.render().samples))
        assertTrue(drawn.toJsonText().contains("\"envelope\""))

        val plain = SnapPatch("Photo", SnapVoice.ORBIT, emptyMap(), Draw.wave(Draw.Wave.SINE))
        assertNull((Patches.fromJsonText(plain.toJsonText()) as SnapPatch).envelope)
        assertTrue(!plain.toJsonText().contains("envelope"), "no shape, no field")
        // Two pads with the same line and different shapes are different pads.
        assertTrue(drawn != drawn.copy(envelope = null))
        assertTrue(drawn != drawn.copy(envelope = Draw.shape(Draw.Shape.HOLD)))
        // And a recipe through the chain regenerates the same audio.
        val recipe = PadRecipe(patch = drawn, fx = FxChain(echo = mapOf("MIX" to 0.3f)))
        assertTrue(PadRecipe.fromJsonText(recipe.toJsonText()).render().samples.contentEquals(recipe.render().samples))
    }

    @Test
    fun `a shape that never opens, or has the wrong count, is refused at both doors`() {
        val table = Draw.wave(Draw.Wave.SINE)
        assertFailsWith<IllegalArgumentException> { SnapPatch("X", SnapVoice.DRAWN, emptyMap(), table, IntArray(Draw.ENVELOPE_SIZE)) }
        assertFailsWith<IllegalArgumentException> { SnapPatch("X", SnapVoice.DRAWN, emptyMap(), table, IntArray(5) { 255 }) }
        assertFailsWith<IllegalArgumentException> { SnapPatch("X", SnapVoice.DRAWN, emptyMap(), table, IntArray(Draw.ENVELOPE_SIZE) { 999 }) }
        val ramp = (0 until Snap.TABLE_SIZE).joinToString(",")
        fun json(env: String) = """{"engine":"SNAP","version":1,"name":"?","voice":"DRAWN","macros":{},"table":[$ramp],"envelope":$env}"""
        for (bad in listOf("[" + List(Draw.ENVELOPE_SIZE) { "0" }.joinToString(",") + "]", "[255,255]", "[" + List(Draw.ENVELOPE_SIZE) { "300" }.joinToString(",") + "]")) {
            assertFailsWith<JsonException>(bad.take(20)) { Patches.fromJsonText(json(bad)) }
        }
        assertTrue(Patches.fromJsonText(json("[" + List(Draw.ENVELOPE_SIZE) { "200" }.joinToString(",") + "]")) is SnapPatch)
    }

    @Test
    fun `the patch keeps its own copy of the shape`() {
        val env = Draw.shape(Draw.Shape.HOLD)
        val patch = SnapPatch("Own", SnapVoice.DRAWN, emptyMap(), Draw.wave(Draw.Wave.SAW), env)
        env.fill(0)
        assertTrue(patch.envelope!!.all { it == 255 })
    }
}
