package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrikeFxBankTest {

    @Test fun `layers carry the knob values and the fixed macros`() {
        val l = StrikeFxBank.layers(0.42f, 0.9f)
        assertEquals(listOf("DRY", "ROOM", "ECHO"), l.map { it.name })
        assertNull(l[0].fx)
        assertEquals(mapOf("SIZE" to 0.42f, "TONE" to 0.7f, "MIX" to 1f), l[1].fx!!.spring)
        assertEquals(mapOf("TIME" to 0.9f, "REPEAT" to 0.55f, "TONE" to 0.8f, "MIX" to 0.9f), l[2].fx!!.echo)
        assertEquals(4f, l[1].terminalFadeMs); assertEquals(4f, l[2].terminalFadeMs)
    }

    @Test fun `knob values read back from a recipe exactly`() {
        val r = PadRecipe(layers = StrikeFxBank.layers(0.13f, 0.77f))
        assertEquals(0.13f, StrikeFxBank.roomOf(r)); assertEquals(0.77f, StrikeFxBank.slapOf(r))
        assertTrue(StrikeFxBank.isStrikeFx(r))
        assertFalse(StrikeFxBank.isStrikeFx(PadRecipe(fx = FxChain(spring = mapOf("SIZE" to 0.5f)))))
        assertNull(StrikeFxBank.roomOf(PadRecipe(fx = FxChain(spring = mapOf("SIZE" to 0.5f)))))
    }

    @Test fun `render keeps the source format and bounds the tails`() {
        val stereo48k = Snip(FloatArray(4800 * 2) { if (it < 2) 0.7f else 0f }, 2, 48000)
        val out = StrikeFxBank.render(stereo48k, 0.42f, 0.9f)
        assertEquals(3, out.size)
        out.forEach {
            assertEquals(2, it.channels); assertEquals(48000, it.sampleRate)
            assertTrue(it.samples.all { s -> s.isFinite() && abs(s) <= 1f })
        }
        assertContentEquals(stereo48k.samples, out[0].samples)
        assertTrue(out[1].frameCount <= stereo48k.frameCount + 48000 && out[2].frameCount <= stereo48k.frameCount + 48000)
    }

    @Test fun `a 20 ms source still yields three finite layers`() {
        val tiny = Snip(FloatArray(882) { if (it == 0) 0.5f else 0f }, 1, 44100)
        StrikeFxBank.render(tiny, 0f, 0f).forEach { assertTrue(it.frameCount > 0 && it.samples.all { s -> s.isFinite() }) }
    }

    @Test fun `knobs outside the unit range are refused`() {
        assertFailsWith<IllegalArgumentException> { StrikeFxBank.layers(1.2f, 0.5f) }
        assertFailsWith<IllegalArgumentException> { StrikeFxBank.layers(0.5f, -0.1f) }
    }

    @Test fun `the same knobs render the same bytes twice`() {
        val src = Snip(FloatArray(2205) { if (it == 0) 0.6f else 0f }, 1, 44100)
        val a = StrikeFxBank.render(src, 0.42f, 0.9f); val b = StrikeFxBank.render(src, 0.42f, 0.9f)
        a.zip(b).forEach { (x, y) -> assertContentEquals(x.samples, y.samples) }
    }
}
