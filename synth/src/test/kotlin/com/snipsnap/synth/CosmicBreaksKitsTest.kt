package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import java.security.MessageDigest
import kotlin.test.*

class CosmicBreaksKitsTest {
    @Test fun `versioned assets match checksums and expected PCM`() {
        val pads = CosmicBreaksKits.build()
        assertEquals(16, pads.size)
        for ((i, entry) in CosmicBreaksKits.entries.withIndex()) {
            val digest = MessageDigest.getInstance("SHA-256").digest(CosmicBreaksKits.resource(entry.file))
                .joinToString("") { "%02x".format(it) }
            assertEquals(entry.sha256, digest, entry.name)
            val p = pads[i]
            assertEquals(entry.name, p.displayName)
            assertEquals(44100, p.snip.sampleRate)
            assertEquals(1, p.snip.channels)
            assertEquals(176400, p.snip.frameCount)
            assertTrue(p.snip.samples.all { it.isFinite() })
            assertTrue(p.snip.peak() in .01f.. .881f)
            assertTrue(kotlin.math.abs(p.snip.samples.average()) < .005)
            assertEquals(0f, p.snip.samples.last())
            assertNull(p.recipe, "printed treatment must not be applied twice")
            assertEquals(listOf(4, 4, 4), p.velocityTakes.map { it.size })
            assertContentEquals(p.snip.samples, p.velocityTakes[2][0].samples)
            assertEquals(12, entry.variants.map { it.sha256 }.distinct().size)
            for (v in entry.variants) {
                val actual = MessageDigest.getInstance("SHA-256").digest(CosmicBreaksKits.resource(v.file)).joinToString("") { "%02x".format(it) }
                assertEquals(v.sha256, actual)
            }
            assertTrue(p.oneShot)
        }
        assertEquals(DrumClass.HAT_CLOSED, pads[2].drumClass)
        assertEquals(DrumClass.HAT_OPEN, pads[3].drumClass)
        assertEquals(DrumClass.PERC, pads[12].drumClass)
        assertEquals(DrumClass.PERC, pads[13].drumClass)
    }
    @Test fun `factory buffers are isolated and repeatable`() {
        val first = CosmicBreaksKits.build()
        val second = CosmicBreaksKits.build()
        first.indices.forEach { assertContentEquals(first[it].snip.samples, second[it].snip.samples) }
        first.first().snip.samples.fill(0f)
        assertTrue(second.first().snip.peak() > .01f)
        assertContentEquals(second.first().snip.samples, CosmicBreaksKits.build().first().snip.samples)
    }
}
