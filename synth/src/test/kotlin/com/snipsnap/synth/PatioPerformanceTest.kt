package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import kotlin.math.abs
import kotlin.test.*

class PatioPerformanceTest {
    @Test fun `every finite pad has real repeat and velocity variation with reproducible recipes`() {
        for (id in PatioStudy.recipes().map { it.id }) {
            val recipe = PatioPerformance.Recipe(id, PatioPerformance.Genre.CUMBIA)
            assertEquals(recipe, PatioPerformance.fromJson(recipe.json()))
            val grid = PatioPerformance.grid(recipe)
            assertEquals(3, grid.size)
            for (zone in grid) {
                assertEquals(4, zone.size)
                for (s in zone) { assertTrue(s.samples.all { it.isFinite() }); assertTrue(s.peak() in .001f..1f); assertTrue(abs(s.samples.average()) < .003) }
                for (i in 1..3) assertFalse(zone[0].samples.contentEquals(zone[i].samples), "$id duplicate take $i")
            }
            assertTrue(Loudness.of(grid[0][0]) < Loudness.of(grid[1][0]) * .85, id)
            assertTrue(Loudness.of(grid[1][0]) < Loudness.of(grid[2][0]) * .85, id)
            assertContentEquals(grid[1][2].samples, PatioPerformance.render(PatioPerformance.fromJson(recipe.json()), 1, 2).samples, id)
            assertContentEquals(PatioHollow.render(PatioStudy.recipes().single { it.id == id }).samples, grid[2][0].samples, "$id accepted hard anchor")
        }
    }
    @Test fun `phrases have authored length and bounded endings at every HOLD extreme`() {
        for (id in listOf("hand-roll", "rise")) {
            val short = PatioPerformance.render(PatioPerformance.Recipe(id, PatioPerformance.Genre.CUMBIA, 0f), 2, 0)
            val long = PatioPerformance.render(PatioPerformance.Recipe(id, PatioPerformance.Genre.CUMBIA, 1f), 2, 0)
            assertTrue(long.frameCount > short.frameCount * 1.5)
            assertTrue(long.samples.all { it.isFinite() } && long.peak() <= .95f)
            assertTrue(abs(long.samples.last()) < .001)
        }
    }
    @Test fun `revised riser preserves velocity takes and legacy recipe replay`() {
        for (genre in PatioPerformance.Genre.entries) {
            val recipe = PatioPerformance.Recipe("rise", genre)
            val grid = PatioPerformance.grid(recipe)
            for (zone in grid) {
                for (s in zone) {
                    assertTrue(s.samples.all { it.isFinite() })
                    assertTrue(s.peak() in .001f.. .95f)
                    assertTrue(abs(s.samples.average()) < .003)
                    assertTrue(s.samples.takeLast(100).all { abs(it) < .001 })
                }
                for (i in 1..3) assertFalse(zone[0].samples.contentEquals(zone[i].samples))
            }
            assertTrue(Loudness.of(grid[0][0]) < Loudness.of(grid[1][0]) * .85)
            assertTrue(Loudness.of(grid[1][0]) < Loudness.of(grid[2][0]) * .85)
            assertContentEquals(grid[1][2].samples,
                PatioPerformance.render(PatioPerformance.fromJson(recipe.json()), 1, 2).samples)
            val old = recipe.copy(version = 1)
            assertEquals(old, PatioPerformance.fromJson(old.json()))
            assertFalse(PatioPerformance.render(old, 2, 0).samples.contentEquals(grid[2][0].samples))
        }
    }
    @Test fun `cuica retains dynamics takes and finite short and long gestures`() {
        assertTrue("cuica" in PatioPerformance.IDS)
        assertFalse("rise" in PatioPerformance.IDS)
        for (genre in PatioPerformance.Genre.entries) {
            val recipe = PatioPerformance.Recipe("cuica", genre)
            val grid = PatioPerformance.grid(recipe)
            for (zone in grid) {
                for (voice in zone) {
                    assertTrue(voice.samples.all { it.isFinite() })
                    assertTrue(voice.peak() in .001f.. .95f)
                    assertTrue(abs(voice.samples.average()) < .003)
                    assertTrue(voice.samples.takeLast(100).all { abs(it) < .001 })
                }
                for (take in 1..3) assertFalse(zone[0].samples.contentEquals(zone[take].samples))
            }
            assertTrue(Loudness.of(grid[0][0]) < Loudness.of(grid[1][0]) * .85)
            assertTrue(Loudness.of(grid[1][0]) < Loudness.of(grid[2][0]) * .85)
            assertContentEquals(grid[1][2].samples, PatioPerformance.render(
                PatioPerformance.fromJson(recipe.json()), 1, 2).samples)
            val short = PatioPerformance.render(recipe.copy(hold = 0f), 2, 0)
            val long = PatioPerformance.render(recipe.copy(hold = 1f), 2, 0)
            assertTrue(long.frameCount > short.frameCount * 2)
            assertTrue(long.frameCount < long.sampleRate)
        }
        val start = System.nanoTime()
        val voice = PatioCuica.render(1f, .5f, 0)
        println("Cuica dry render ms=" + (System.nanoTime() - start) / 1e6)
        assertContentEquals(voice.samples, PatioCuica.render(1f, .5f, 0).samples)
    }
}
