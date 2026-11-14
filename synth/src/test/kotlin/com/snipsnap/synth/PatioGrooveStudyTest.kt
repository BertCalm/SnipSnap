package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.test.*

class PatioGrooveStudyTest {
    @Test fun `arrangements stay on grid and transition without inserted gaps`() {
        val sparse = PatioGrooveStudy.events("sparse")
        assertEquals(setOf("kick", "llamador", "closed", "scrape-long", "scrape-short"), sparse.map { it.pad }.toSet())
        assertTrue(PatioGrooveStudy.events("fill").any { it.pad == "open" })
        val all = PatioGrooveStudy.runThrough()
        PatioGrooveStudy.VARIATIONS.forEachIndexed { i, v ->
            assertEquals(PatioGrooveStudy.events(v), all.filter { it.beat >= i * 16 && it.beat < (i + 1) * 16 }.map { it.copy(beat = it.beat - i * 16) })
        }
        assertTrue(all.all { it.gain in 0f..1f && it.beat < 48 })
    }
    @Test fun `closed hat chokes open tail without mutating the samples`() {
        val source = Snip(FloatArray(Dsp.RATE) { .2f }, 1, Dsp.RATE)
        val silent = source.copy(samples = FloatArray(Dsp.RATE))
        val events = listOf(PatioGrooveStudy.Hit("open", 0.0, 1f), PatioGrooveStudy.Hit("closed", .5, 1f))
        val hits = mapOf("open" to source, "closed" to silent)
        val levels = mapOf("open" to 1f, "closed" to 1f)
        val out = PatioGrooveStudy.render(events, hits, levels, 4)
        assertTrue(out.samples.take(1000).all { it > 0f })
        assertTrue(out.samples.drop((.3 * Dsp.RATE).toInt()).all { it == 0f })
        assertTrue(source.samples.all { it == .2f })
        assertContentEquals(out.samples, PatioGrooveStudy.render(events, hits, levels, 4).samples)
    }
}
