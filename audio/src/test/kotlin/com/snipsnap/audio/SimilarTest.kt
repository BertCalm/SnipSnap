package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimilarTest {

    @Test
    fun `a snare's nearest neighbours in a mixed library are the other snares`() {
        val target = FeatureExtractor.extract(DrumSynth.snare(seed = 1))
        val candidates = listOf(
            "snare-2" to FeatureExtractor.extract(DrumSynth.snare(seed = 2)),
            "kick" to FeatureExtractor.extract(DrumSynth.kick()),
            "snare-3" to FeatureExtractor.extract(DrumSynth.snare(seed = 3, noiseMix = 0.6f)),
            "hat-closed" to FeatureExtractor.extract(DrumSynth.closedHat()),
            "kick-long" to FeatureExtractor.extract(DrumSynth.kick(seconds = 0.5f)),
            "hat-open" to FeatureExtractor.extract(DrumSynth.openHat()),
            "tom" to FeatureExtractor.extract(DrumSynth.tom()),
        )
        val ranked = Similar.rank(target, candidates)
        assertEquals(
            setOf("snare-2", "snare-3"), ranked.take(2).map { it.first }.toSet(),
            "the snares come first: $ranked",
        )
        assertTrue(
            ranked.last().first !in setOf("snare-2", "snare-3"),
            "the least similar thing is not a snare: $ranked",
        )
        assertTrue(
            ranked[1].second < ranked[2].second / 3,
            "the snares sit close, everything else sits far: $ranked",
        )
        assertEquals(ranked, Similar.rank(target, candidates), "deterministic order")
    }

    @Test
    fun `the same sound is at distance zero and the vector is bounded`() {
        val f = FeatureExtractor.extract(DrumSynth.clap())
        assertEquals(0f, Similar.distance(f, f))
        val v = Similar.vector(f)
        assertEquals(Similar.DIMENSIONS, v.size)
        assertTrue(v.all { it in 0f..1f }, "every dimension normalized: ${v.toList()}")
    }
}
