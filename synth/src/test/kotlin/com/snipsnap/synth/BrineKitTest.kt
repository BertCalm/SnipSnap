package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.*

class BrineKitTest {
    @Test fun `reference source replays without mutating shared settings`() {
        val a = BrineKit.render()
        val b = BrineKit.render()
        assertContentEquals(a.samples, b.samples)
        assertEquals(1, a.channels)
        assertEquals(44100, a.sampleRate)
        assertTrue(a.samples.all { it.isFinite() && abs(it) <= .99001f })
        assertTrue(abs(a.samples.average()) < .005)
        assertEquals(29, BrineKit.hits.size)
    }
    @Test fun `wire and room candidates change texture without changing the event clock`() {
        val a = BrineKit.render().samples
        for (c in listOf(BrineKit.Character.WIRES, BrineKit.Character.ROOM)) {
            val b = BrineKit.render(c).samples
            assertEquals(a.size, b.size)
            assertTrue(a.indices.sumOf { (a[it]-b[it]).toDouble().pow(2) } > .001)
        }
    }
    @Test fun `single drums remain available before recording and invalid solos are refused`() {
        for (i in 0..3) {
            val a = BrineKit.render(solo=i, recorded=false)
            assertTrue(a.peak() > .001f)
            assertTrue(a.samples.all { it.isFinite() })
        }
        assertFailsWith<IllegalArgumentException> { BrineKit.render(solo=4) }
    }
}
