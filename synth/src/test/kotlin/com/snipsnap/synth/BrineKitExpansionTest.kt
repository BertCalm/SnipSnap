package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BrineKitExpansionTest {
    @Test fun `resonant tom adds sustained body and replays safely through the board`() {
        val before = BrineKitExpansion.render(BrineKitExpansion.Voice.FLOOR_TOM)
        val after = BrineKitExpansion.renderThumpyTom()
        fun tailRatio(x: FloatArray): Double {
            val attack = x.take(4410).sumOf { it.toDouble().pow(2) }
            val tail = x.sliceArray(8820 until 26460).sumOf { it.toDouble().pow(2) }
            return tail / attack
        }
        assertTrue(tailRatio(after.samples) > tailRatio(before.samples)*1.5)
        assertContentEquals(after.samples, BrineKitExpansion.renderThumpyTom().samples)
        for (x in listOf(after, BrineKitExpansion.renderThumpyTom(inGroove=true),
            Brine.process(after, Brine.Settings(room=0f, grit=0f)))) {
            assertTrue(x.samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(abs(x.samples.average()) < .005)
            assertTrue(abs(x.samples.last()) < .001)
        }
    }

    @Test fun `new articulations replay deterministically and survive the full board`() {
        for (v in BrineKitExpansion.Voice.entries) {
            val a = BrineKitExpansion.render(v)
            assertContentEquals(a.samples, BrineKitExpansion.render(v).samples, v.name)
            assertEquals(44100, a.sampleRate)
            assertEquals(1, a.channels)
            for (s in listOf(a, Brine.process(a, Brine.Settings(room=0f, grit=0f)))) {
                assertTrue(s.peak() > .001f, v.name)
                assertTrue(s.samples.all { it.isFinite() && abs(it) <= 1f }, v.name)
                assertTrue(abs(s.samples.average()) < .005, v.name)
                assertTrue(abs(s.samples.last()) < .001, v.name)
            }
        }
    }
    @Test fun `ghost strike stays quieter and rimshot changes the snare`() {
        val ghost = BrineKitExpansion.render(BrineKitExpansion.Voice.GHOST_SNARE)
        val main = BrineKit.render(solo=1)
        val rim = BrineKitExpansion.render(BrineKitExpansion.Voice.RIMSHOT)
        fun energy(x: FloatArray) = x.take(22050).sumOf { it.toDouble().pow(2) }
        assertTrue(energy(ghost.samples) < energy(main.samples)*.7)
        assertTrue(main.samples.take(22050) != rim.samples.take(22050))
    }
    @Test fun `open hat has sustain and choke removes it with a smooth release`() {
        val open = BrineKitExpansion.render(BrineKitExpansion.Voice.OPEN_HAT, recorded=false).samples
        val closed = BrineKit.render(solo=2, recorded=false).samples
        fun tail(x: FloatArray) = x.drop(6615).sumOf { it.toDouble().pow(2) }
        assertTrue(tail(open) > tail(closed)*2)
        val gated = open.copyOf()
        BrineKitExpansion.choke(gated, 6615)
        assertContentEquals(open.copyOf(6615), gated.copyOf(6615))
        assertTrue(gated.drop(6880).all { it == 0f })
        val flat = FloatArray(1000) { 1f }
        BrineKitExpansion.choke(flat, 100)
        assertTrue((101..999).maxOf { abs(flat[it]-flat[it-1]) } < .01f)
    }
    @Test fun `expanded phrase is bounded and has all the new playing roles`() {
        val groove = BrineKitExpansion.render()
        assertContentEquals(groove.samples, BrineKitExpansion.render().samples)
        assertTrue(groove.samples.all { it.isFinite() && abs(it) <= 1f })
        assertTrue(BrineKitExpansion.groove().any { it.part.id == "floor-tom" })
        assertTrue(BrineKitExpansion.groove().any { it.velocity == .30f })
        assertTrue(BrineKitExpansion.snarePhrase().peak() > .01f)
    }
}
