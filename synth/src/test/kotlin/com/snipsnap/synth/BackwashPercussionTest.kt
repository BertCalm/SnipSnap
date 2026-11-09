package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BackwashPercussionTest {
    @Test fun `replacement row contains four new instruments and no Double factory duplicates`() {
        val expected = listOf(BackwashVoice.CROSS_STICK, BackwashVoice.TAMBOURINE, BackwashVoice.COWBELL, BackwashVoice.RIDE)
        assertEquals(expected, BackwashPresets.kitOrder().takeLast(4).map { it.voice })
        assertEquals(expected, BackwashPresets.processingKitOrder().takeLast(4).map { it.voice })
        assertTrue(BackwashPresets.all().none { it.name.startsWith("Double ") })
        for (v in expected) assertFailsWith<IllegalArgumentException> { BackwashPatch("Invalid legacy", v, Backwash.defaults(v), 3) }
    }
    @Test fun `new instruments remain distinct after processing and round trip at all strike strengths`() {
        val patches = BackwashPresets.kitOrder().takeLast(4)
        for (v in listOf(.3f, .6f, 1f)) for (p in patches) {
            val s = p.render(v)
            assertContentEquals(s.samples, BackwashPatch.fromJsonText(p.toJsonText()).render(v).samples)
            assertTrue(s.samples.all { it.isFinite() && abs(it) <= 1f })
        }
        val signals = (patches + BackwashPresets.kitOrder().take(4)).map { it.render().samples }
        for (i in patches.indices) for (j in i + 1 until signals.size) {
            val a = signals[i]; val b = signals[j]; val n = minOf(a.size, b.size)
            val energyA = a.sumOf { it.toDouble() * it }; val energyB = b.sumOf { it.toDouble() * it }
            val dot = (0 until n).sumOf { a[it].toDouble() * b[it] }
            assertTrue(abs(dot / sqrt(energyA * energyB)) < .7, "${patches[i].name} duplicates slot $j")
        }
    }
}
