package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassifierTest {

    private fun classOf(snip: Snip) = Classifier.classify(snip).drumClass

    // --- Per-class recognition ---

    @Test
    fun `recognises a kick`() {
        assertEquals(DrumClass.KICK, classOf(DrumSynth.kick()))
    }

    @Test
    fun `recognises a snare`() {
        assertEquals(DrumClass.SNARE, classOf(DrumSynth.snare()))
    }

    @Test
    fun `recognises a closed hat`() {
        assertEquals(DrumClass.HAT_CLOSED, classOf(DrumSynth.closedHat()))
    }

    @Test
    fun `recognises an open hat`() {
        assertEquals(DrumClass.HAT_OPEN, classOf(DrumSynth.openHat()))
    }

    @Test
    fun `recognises a clap`() {
        // Claps and snares are spectrally close; what separates them is that a
        // clap is physically several impacts a few milliseconds apart.
        assertEquals(DrumClass.CLAP, classOf(DrumSynth.clap()))
    }

    @Test
    fun `recognises a tom`() {
        assertEquals(DrumClass.TOM, classOf(DrumSynth.tom()))
    }

    @Test
    fun `recognises sustained tonal material`() {
        assertEquals(DrumClass.TONAL, classOf(DrumSynth.tonal()))
    }

    @Test
    fun `recognises a loop by its length`() {
        assertEquals(DrumClass.LOOP, classOf(DrumSynth.loop()))
    }

    // --- Boundaries the rules turn on ---

    @Test
    fun `hats are told apart by decay, not by spectrum`() {
        // Same synthesis, different decay. If these came out the same class the
        // open-hat rule would be doing nothing.
        val closed = Classifier.classify(DrumSynth.closedHat())
        val open = Classifier.classify(DrumSynth.openHat())

        assertEquals(DrumClass.HAT_CLOSED, closed.drumClass)
        assertEquals(DrumClass.HAT_OPEN, open.drumClass)
        assertTrue(open.features.decayMs > closed.features.decayMs * 3)
    }

    @Test
    fun `kick and tom are told apart by pitch`() {
        val kick = Classifier.classify(DrumSynth.kick())
        val tom = Classifier.classify(DrumSynth.tom())

        assertEquals(DrumClass.KICK, kick.drumClass)
        assertEquals(DrumClass.TOM, tom.drumClass)
        assertTrue(tom.features.centroidHz > kick.features.centroidHz)
    }

    @Test
    fun `a sustained bass note is not mistaken for a kick`() {
        // Both are low-frequency; only the decay separates them.
        assertEquals(DrumClass.TONAL, classOf(DrumSynth.tonal(seconds = 1.2f, freq = 55.0)))
    }

    @Test
    fun `length beats spectrum for loops`() {
        // A two-bar break starts with a kick, and its head spectrum says so. It
        // is still a loop, and putting it on the kick pad would be wrong.
        val loop = DrumSynth.loop(seconds = 2.4f)
        assertEquals(DrumClass.LOOP, classOf(loop))
    }

    @Test
    fun `burst counting separates a clap from a snare`() {
        assertTrue(Classifier.attackBurstCount(DrumSynth.clap()) >= 3)
        assertEquals(1, Classifier.attackBurstCount(DrumSynth.snare()))
    }

    // --- Degenerate input ---

    @Test
    fun `silence is unknown, not a kick`() {
        val result = Classifier.classify(Snip(FloatArray(10_000), 1, DrumSynth.RATE))
        assertEquals(DrumClass.UNKNOWN, result.drumClass)
        assertEquals(0f, result.confidence)
    }

    @Test
    fun `an empty snip does not throw`() {
        assertEquals(DrumClass.UNKNOWN, classOf(Snip(FloatArray(0), 1, DrumSynth.RATE)))
    }

    @Test
    fun `a very short snip does not throw`() {
        assertTrue(classOf(Snip(FloatArray(50) { 0.5f }, 1, DrumSynth.RATE)) != DrumClass.UNKNOWN)
    }

    @Test
    fun `handles stereo`() {
        val mono = DrumSynth.kick()
        val stereo = Snip(
            FloatArray(mono.frameCount * 2) { mono.samples[it / 2] },
            channels = 2,
            sampleRate = DrumSynth.RATE,
        )
        assertEquals(DrumClass.KICK, classOf(stereo))
    }

    @Test
    fun `is not thrown off by level`() {
        // A quiet kick is still a kick. If normalisation changed the verdict the
        // classifier would be keying on loudness rather than timbre.
        val quiet = DrumSynth.kick().let { snip ->
            Snip(FloatArray(snip.samples.size) { snip.samples[it] * 0.05f }, 1, DrumSynth.RATE)
        }
        assertEquals(DrumClass.KICK, classOf(quiet))
    }

    @Test
    fun `reports a confidence and the features behind it`() {
        val result = Classifier.classify(DrumSynth.kick())

        assertTrue(result.confidence in 0f..1f)
        assertTrue(result.confidence > 0.5f, "a textbook kick should not be a coin flip")
        assertTrue(result.features.lowRatio > 0.9f, "features should be inspectable")
    }

    @Test
    fun `is deterministic`() {
        val snip = DrumSynth.snare()
        assertEquals(Classifier.classify(snip).drumClass, Classifier.classify(snip).drumClass)
    }

    @Test
    fun `classifies a whole synthetic kit correctly`() {
        val kit = mapOf(
            DrumClass.KICK to DrumSynth.kick(),
            DrumClass.SNARE to DrumSynth.snare(),
            DrumClass.HAT_CLOSED to DrumSynth.closedHat(),
            DrumClass.HAT_OPEN to DrumSynth.openHat(),
            DrumClass.CLAP to DrumSynth.clap(),
            DrumClass.TOM to DrumSynth.tom(),
        )

        val wrong = kit.filter { (expected, snip) -> classOf(snip) != expected }
        assertTrue(wrong.isEmpty(), "misclassified: ${wrong.keys.map { "$it -> ${classOf(kit[it]!!)}" }}")
    }
}
