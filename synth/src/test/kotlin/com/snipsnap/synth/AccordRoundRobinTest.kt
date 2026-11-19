package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Passage-offset round-robin takes ([Accord.renderTake]): take 0 is the kept render itself, later
 * takes are the same machine heard from an earlier moment, at take 0's performance gain. These are
 * engine checks only; whether a take sounds like the same note played again is the owner's listen.
 */
class AccordRoundRobinTest {
    private fun preset(voice: AccordVoice) = AccordPresets.forVoice(voice).first()

    @Test fun `take 0 of every voice is the plain render, byte for byte`() {
        for (voice in AccordVoice.entries) {
            val patch = preset(voice)
            val plain = patch.render().samples
            assertContentEquals(plain, Accord.renderTake(patch, 0).samples, "$voice take 0")
            assertContentEquals(Accord.render(patch).samples, Accord.renderTake(patch, 0).samples, "$voice take 0 via Accord")
        }
    }

    @Test fun `later takes differ from take 0 and each other, stay under the ceiling and keep take 0's gain`() {
        for (voice in AccordVoice.entries) {
            val patch = preset(voice)
            val probes = (0 until Accord.TAKES).map { Accord.probeTake(patch, it) }
            for (a in probes.indices) {
                assertTrue(probes[a].samples.peak() <= .85f, "$voice take $a peak ${probes[a].samples.peak()}")
                for (b in a + 1 until probes.size) {
                    val x = probes[a].samples.samples
                    val y = probes[b].samples.samples
                    val n = minOf(x.size, y.size)
                    val differing = (0 until n).count { abs(x[it] - y[it]) > 1e-4f }
                    assertTrue(differing > n / 2, "$voice takes $a and $b differ in only $differing of $n samples")
                }
            }
            val gain0 = probes[0].diagnostics.calibrationGain
            for (take in 1 until Accord.TAKES) {
                val gain = probes[take].diagnostics.calibrationGain
                // Take 0's gain, lowered only where it would carry this passage over the ceiling.
                assertTrue(gain <= gain0 && gain > .85 * gain0, "$voice take $take gain $gain against $gain0")
                assertEquals(Accord.takeStartCycles(patch.sourceStartCycles, take), patch.sourceStartCycles -
                    take * Accord.TAKE_STEP_CYCLES, 0.0)
            }
        }
    }

    @Test fun `a take replays identically, from a cold cache, soft and hard`() {
        for (voice in listOf(AccordVoice.WOOD, AccordVoice.REED)) {
            // Normal touch is replayed by the tests above; soft and hard take the other two paths through the shared gain.
            val touches = listOf(.3f, 1f)
            val first = touches.map { v -> (0 until Accord.TAKES).map { Accord.renderTake(preset(voice).copy(velocity = v), it).samples } }
            Accord.clearCaches()
            val again = touches.map { v -> (0 until Accord.TAKES).map { Accord.renderTake(preset(voice).copy(velocity = v), it).samples } }
            for (t in touches.indices) for (k in 0 until Accord.TAKES) {
                assertContentEquals(first[t][k], again[t][k], "$voice touch ${touches[t]} take $k")
                assertTrue(first[t][k].maxOf { abs(it) } <= .85f, "$voice touch ${touches[t]} take $k peak")
            }
        }
    }

    @Test fun `a take is not the plain render of its passage, which has its own gain`() {
        // WOOD's take 0 plays at its frozen factory gain; the plain passage would compute its own.
        val patch = preset(AccordVoice.WOOD)
        val take = Accord.probeTake(patch, 1)
        val passage = Accord.probe(patch.copy(sourceStartCycles = Accord.takeStartCycles(8.0, 1)))
        assertContentEquals(take.raw.samples, passage.raw.samples)
        assertEquals(Accord.probe(patch).diagnostics.calibrationGain, take.diagnostics.calibrationGain)
        assertTrue(take.diagnostics.calibrationGain != passage.diagnostics.calibrationGain)
        // Rendering the plain passage did not disturb the cached take.
        assertContentEquals(take.samples.samples, Accord.renderTake(patch, 1).samples)
    }

    @Test fun `take starts go back by the step, forward near the start of the history, and only four exist`() {
        assertEquals(listOf(8.0, 7.125, 6.25, 5.375), (0 until Accord.TAKES).map { Accord.takeStartCycles(8.0, it) })
        assertEquals(listOf(1.0, .125, 2.75, 3.625), (0 until Accord.TAKES).map { Accord.takeStartCycles(1.0, it) })
        assertEquals(listOf(0.0, .875, 1.75, 2.625), (0 until Accord.TAKES).map { Accord.takeStartCycles(0.0, it) })
        assertFailsWith<IllegalArgumentException> { Accord.takeStartCycles(8.0, Accord.TAKES) }
        assertFailsWith<IllegalArgumentException> { Accord.takeStartCycles(8.0, -1) }
        // A short edited performance near the start of the history: forward takes, all under the ceiling.
        val edited = AccordPatch("edited", AccordVoice.TINE, mapOf("CONTACT" to .61f), midi = 63, velocity = 1f,
            acousticProfile = AccordAcousticProfile.BRIGHT_EDGE, sourceStartCycles = .75, sourceSeconds = 1.04)
        val takes = (0 until Accord.TAKES).map { Accord.renderTake(edited, it) }
        assertContentEquals(edited.render().samples, takes[0].samples)
        for ((k, t) in takes.withIndex()) assertTrue(t.peak() <= .85f, "edited take $k peak ${t.peak()}")
        assertTrue(Accord.renderTake(edited.copy(velocity = 0f), 2).samples.all { it == 0f })
    }

    @Test fun `Restrike deals ACCORD's own takes at the saved touch scaled, with no force spread and no seed`() {
        val patch = preset(AccordVoice.WOOD)
        assertTrue(Restrike.canRestrike(patch))
        // Take 0 is the plain render; later Restrike takes cycle through takes 1..3, never back to 0.
        assertEquals(listOf(0, 1, 2, 3, 1, 2, 3, 1), (0..7).map { Restrike.accordTake(it) })
        // Robin's untouched hit (1) is the saved performance: its takes are the kept takes, byte for byte.
        assertContentEquals(patch.render().samples, Restrike.take(patch, 1f, seed = 7, take = 0)!!.samples)
        for (take in 1..4) assertContentEquals(Accord.renderTake(patch, Restrike.accordTake(take)).samples,
            Restrike.take(patch, 1f, seed = 7, take = take)!!.samples, "take $take")
        // A softer Robin zone scales the saved touch; the seed never matters.
        val softer = patch.copy(velocity = patch.velocity * .4f)
        for (take in 0..4) {
            val want = Accord.renderTake(softer, Restrike.accordTake(take)).samples
            assertContentEquals(want, Restrike.take(patch, .4f, seed = 7, take = take)!!.samples, "touch .4 take $take")
            assertContentEquals(want, Restrike.take(patch, .4f, seed = 99, take = take)!!.samples, "touch .4 take $take, another seed")
        }
    }
}
