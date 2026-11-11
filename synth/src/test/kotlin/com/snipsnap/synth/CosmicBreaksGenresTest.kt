package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.abs

class CosmicBreaksGenresTest {
    @Test fun `pitched source rejects top octave folding at the largest upward move`() {
        fun energy(hz: Double): Double {
            val input = com.snipsnap.audio.Snip(FloatArray(44100) { kotlin.math.sin(2 * Math.PI * hz * it / 44100).toFloat() }, 1, 44100)
            val out = CosmicBreaksGenres.pitchedRead(input, 4.0)
            return out.drop(1024).map { it.toDouble() * it }.average()
        }
        assertTrue(energy(20000.0) / energy(3000.0) < 1e-6, "at least 60 dB rejection of the folding source band")
    }

    @Test fun `genre banks retain twelve distinct strikes and finite authored gestures`() {
        val signatures = mutableSetOf<Int>()
        for (genre in CosmicBreaksGenres.Genre.entries) {
            val pads = CosmicBreaksGenres.build(genre)
            assertEquals(18, pads.size)
            signatures += pads[1].snip.samples.contentHashCode()
            for (pad in pads) {
                assertEquals(listOf(4, 4, 4), pad.velocityTakes.map { it.size })
                assertContentEquals(pad.snip.samples, pad.velocityTakes[2][0].samples)
                assertEquals(12, pad.velocityTakes.flatten().map { it.samples.contentHashCode() }.distinct().size, pad.displayName)
                for (hit in pad.velocityTakes.flatten()) {
                    assertTrue(hit.samples.all { it.isFinite() })
                    assertTrue(hit.peak() in .01f.. .881f)
                    assertTrue(abs(hit.samples.average()) < .005)
                    assertEquals(0f, hit.samples.last())
                }
            }
            assertEquals(listOf("roll", "riser"), pads.takeLast(2).map { it.source["articulation"] })
            if (genre == CosmicBreaksGenres.Genre.ORBIT) {
                val original = CosmicBreaksKits.build()
                for (i in 0..15) for (z in 0..2) for (t in 0..3)
                    assertContentEquals(original[i].velocityTakes[z][t].samples, pads[i].velocityTakes[z][t].samples)
            }
        }
        assertEquals(3, signatures.size, "genre voicing changes the snare audio")
    }
    @Test fun `arranged render is deterministic and buffers belong to the load`() {
        val a = CosmicBreaksGenres.build(CosmicBreaksGenres.Genre.JUNGLE)
        val b = CosmicBreaksGenres.build(CosmicBreaksGenres.Genre.JUNGLE)
        for (i in a.indices) for (z in 0..2) for (t in 0..3)
            assertContentEquals(a[i].velocityTakes[z][t].samples, b[i].velocityTakes[z][t].samples)
        a[0].snip.samples.fill(0f)
        assertTrue(b[0].snip.peak() > .01f)
    }
}
