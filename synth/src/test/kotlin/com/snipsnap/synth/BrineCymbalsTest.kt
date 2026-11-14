package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BrineCymbalsTest {
    @Test fun `cymbals replay safely before and after the board`() {
        for (v in BrineCymbals.Voice.entries) {
            val a = BrineCymbals.render(v)
            assertContentEquals(a.samples, BrineCymbals.render(v).samples)
            for (x in listOf(a, Brine.process(a, Brine.Settings(room=0f, grit=0f)))) {
                assertEquals(44100, x.sampleRate); assertEquals(1, x.channels)
                assertTrue(x.peak() > .001f)
                assertTrue(x.samples.all { it.isFinite() && abs(it) <= 1f })
                assertTrue(abs(x.samples.average()) < .005)
                assertTrue(abs(x.samples.last()) < .001)
            }
        }
    }
    @Test fun `half hat decay lands between closed and open`() {
        fun ratio(x: FloatArray): Double = x.drop(4410).sumOf { it.toDouble().pow(2) } /
            x.take(4410).sumOf { it.toDouble().pow(2) }
        val closed = BrineKit.render(solo=2, recorded=false).samples
        val half = BrineCymbals.render(BrineCymbals.Voice.HALF_HAT, recorded=false).samples
        val open = BrineKitExpansion.render(BrineKitExpansion.Voice.OPEN_HAT, recorded=false).samples
        assertTrue(ratio(half) > ratio(closed)*2)
        assertTrue(ratio(half) < ratio(open)*.8)
    }
    @Test fun `choked crash preserves attack and stops source vibration`() {
        val a = BrineCymbals.source(BrineCymbals.Voice.CRASH)
        val b = BrineCymbals.source(BrineCymbals.Voice.CHOKED_CRASH)
        assertContentEquals(a.copyOf(7497), b.copyOf(7497))
        assertTrue(a.drop(8000).sumOf { it.toDouble().pow(2) } > .001)
        assertTrue(b.drop(8000).all { it == 0f })
    }
    @Test fun `balance alternative only lowers cymbal gains and preserves approved tom`() {
        for (style in BrineCymbals.Groove.entries) {
            val normal = BrineCymbals.events(style)
            val quiet = BrineCymbals.events(style, .75f)
            assertEquals(normal.size, quiet.size)
            for ((a,b) in normal.zip(quiet)) {
                assertEquals(a.beat,b.beat); assertEquals(a.velocity,b.velocity)
                assertEquals(a.part.macros,b.part.macros)
                if (a.cymbal == null && a.part.voice !in listOf(SkinVoice.HAT_OPEN,SkinVoice.HAT_CLOSED))
                    assertEquals(a,b)
                else assertEquals(a.part.gain*.75f,b.part.gain)
            }
            assertEquals(BrineKitExpansion.thumpyTom().part, normal.first { it.part.id == "thumpy-floor-tom" }.part)
            val x=BrineCymbals.renderGroove(style)
            assertTrue(x.samples.all { it.isFinite() && abs(it) <= 1f })
        }
    }
}
