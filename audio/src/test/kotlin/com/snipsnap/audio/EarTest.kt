package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EarTest {

    private val rate = 44_100

    /**
     * kick / hat / snare / hat / kick / snare, spaced clear of each
     * other's tails — and clear of frame zero: an energy-derivative
     * detector has no baseline to jump from at the very first sample.
     */
    private fun beat(): Pair<Snip, List<Pair<Int, DrumClass>>> {
        val plan = listOf(
            Triple(0.05f, DrumSynth.kick(), DrumClass.KICK),
            Triple(0.40f, DrumSynth.closedHat(), DrumClass.HAT_CLOSED),
            Triple(0.80f, DrumSynth.snare(), DrumClass.SNARE),
            Triple(1.20f, DrumSynth.closedHat(), DrumClass.HAT_CLOSED),
            Triple(1.60f, DrumSynth.kick(), DrumClass.KICK),
            Triple(2.20f, DrumSynth.snare(), DrumClass.SNARE),
        )
        val total = FloatArray((3.0f * rate).toInt())
        for ((at, hit, dc) in plan) {
            val start = (at * rate).toInt()
            val gain = if (dc == DrumClass.HAT_CLOSED) 0.5f else 0.9f
            for (i in hit.samples.indices) {
                val idx = start + i
                if (idx < total.size) total[idx] += hit.samples[i] * gain
            }
        }
        return Snip(total, 1, rate) to plan.map { (at, _, dc) -> (at * rate).toInt() to dc }
    }

    @Test
    fun `a synthetic beat transcribes exactly - classes, positions, dynamics`() {
        val (snip, expected) = beat()
        val hits = Ear.listen(snip)

        assertEquals(expected.size, hits.size, "every hit heard, none invented")
        expected.forEachIndexed { i, (frame, dc) ->
            assertEquals(dc, hits[i].drumClass, "hit $i heard as the right drum")
            assertTrue(
                Math.abs(hits[i].frame - frame) < rate / 50,
                "hit $i lands within 20ms: heard ${hits[i].frame}, placed $frame",
            )
            assertTrue(hits[i].confidence >= 0.5f, "hit $i is a confident hearing: ${hits[i].confidence}")
        }
        // The loudest hit pins velocity 1; quieter hits scale under it.
        assertTrue(hits.maxOf { it.velocity } == 1f)
        val kickVel = hits.first { it.drumClass == DrumClass.KICK }.velocity
        val hatVel = hits.first { it.drumClass == DrumClass.HAT_CLOSED }.velocity
        assertTrue(hatVel < kickVel, "dynamics survive: hat $hatVel under kick $kickVel")

        assertEquals(hits, Ear.listen(snip), "same audio, same hearing")
    }

    @Test
    fun `a beat that starts ON the hit is heard from the downbeat`() {
        // The chopped-break shape: the kick at frame zero, where the
        // detector has no baseline - the hot-open guard supplies it.
        val kick = DrumSynth.kick()
        val hat = DrumSynth.closedHat()
        val total = FloatArray((1.2f * rate).toInt())
        for (i in kick.samples.indices) total[i] += kick.samples[i] * 0.9f
        for (i in hat.samples.indices) {
            val idx = (0.5f * rate).toInt() + i
            if (idx < total.size) total[idx] += hat.samples[i] * 0.5f
        }
        val hits = Ear.listen(Snip(total, 1, rate))
        assertEquals(2, hits.size, "the downbeat is not lost")
        assertEquals(0, hits[0].frame)
        assertEquals(DrumClass.KICK, hits[0].drumClass)
        assertEquals(DrumClass.HAT_CLOSED, hits[1].drumClass)
    }

    @Test
    fun `tones and silence are not beats`() {
        val tone = Snip(
            FloatArray(3 * rate) { i -> (0.4 * Math.sin(2.0 * Math.PI * 220.0 * i / rate)).toFloat() },
            1, rate,
        )
        assertTrue(Ear.listen(tone).size <= 1, "a held tone is at most one 'hit', never a beat")
        assertEquals(emptyList(), Ear.listen(Snip(FloatArray(rate), 1, rate)), "silence hears nothing")
    }
}
