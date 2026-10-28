package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OverlapHeldPrototypeTest {
    private val rate = WavWriter.MPC_SAMPLE_RATE
    private val os = Dsp.OVERSAMPLE
    private fun controls(knot: Float = 1f, ring: Float = .45f) = mapOf(
        "KNOT" to knot, "RING" to ring, "PULSE" to 0f, "GLOW" to 0f, "HOLD" to 1f,
    )

    @Test
    fun `all passive topologies recur in full state and independently evolved neighboring periods`() {
        for (topology in OverlapTopology.entries) {
            val m = controls()
            val loop = OverlapHeldPrototype.renderLoopMeasured(topology, m, periodSeconds = .5)
            val d = loop.diagnostics
            assertTrue(d.converged && d.attempts in 3..d.maxAttempts)
            assertTrue(d.stateError < 2e-7 && d.audioError < 1e-8, "$topology state/audio recurrence failed")
            assertTrue(requireNotNull(d.seam) < Keys.MAX_SEAM_ERROR)
            assertTrue(requireNotNull(d.nextSeam) < Keys.MAX_SEAM_ERROR)
            assertTrue(d.maxLoopGain < 1.0 && d.maxBufferAbs < 1.0)
            assertTrue(d.rawPeak > 1e-6 && loop.snip.peak() <= 1f)
            assertEquals(rate, loop.snip.sampleRate)
            assertEquals(1, loop.snip.channels)
            assertEquals(d.periodFrames, loop.raw.size)
            assertEquals(3 * d.periodFrames, loop.seamContext.size)
            val before = loop.seamContext.copyOfRange(0, d.periodFrames)
            val kept = loop.seamContext.copyOfRange(d.periodFrames, 2 * d.periodFrames)
            val after = loop.seamContext.copyOfRange(2 * d.periodFrames, 3 * d.periodFrames)
            assertContentEquals(kept, loop.snip.samples, "$topology returned a separately conditioned loop")
            assertTrue(Keys.seamError(before + kept, d.periodFrames) < Keys.MAX_SEAM_ERROR)
            assertTrue(Keys.seamError(kept + after, d.periodFrames) < Keys.MAX_SEAM_ERROR)
            assertTrue(d.internalFrames > (d.attempts + 1L) * d.periodFrames * os)
            val finite = Overlap.probe(topology, m, midi = 48, velocity = .8f, seconds = .05, model = 2)
            assertEquals(finite.diagnostics.delaySamples, d.delaySamples, "$topology retuned the approved physical geometry")
            assertTrue(abs(d.sourceRootHz / d.referenceHz - 1.0) <= .5 / d.periodFrames + 1e-12,
                "$topology source period does not fit integer note cycles to nearest output frame")
            assertTrue(listOf(d.setupMillis, d.solveMillis, d.conditioningMillis, d.totalMillis).all { it.isFinite() && it >= 0.0 })
        }
    }

    @Test
    fun `long ring and intermediate knot converge without resetting or expanding the feedback`() {
        val loop = OverlapHeldPrototype.renderLoopMeasured(OverlapTopology.TREFOIL,
            controls(knot = .5f, ring = 1f), periodSeconds = .5)
        val d = loop.diagnostics
        assertTrue(d.converged && d.attempts <= d.maxAttempts && d.maxAttempts <= 192)
        assertTrue(d.stateError < 2e-7 && d.audioError < 1e-8)
        assertTrue(d.maxBufferAbs < 1.0 && d.maxLoopGain < 1.0)
        assertTrue(loop.raw.all { it.isFinite() })
    }

    @Test
    fun `register and loss extremes remain causal and converge with the real fractional geometry`() {
        data class Edge(val topology: OverlapTopology, val midi: Int, val knot: Float, val ring: Float)
        val edges = listOf(
            Edge(OverlapTopology.SELF, 24, 0f, 1f),
            Edge(OverlapTopology.TREFOIL, 84, 1f, 0f),
            Edge(OverlapTopology.MIRROR, 24, .5f, 0f),
            Edge(OverlapTopology.TORUS, 84, 1f, 1f),
        )
        for (edge in edges) {
            val loop = OverlapHeldPrototype.renderLoopMeasured(edge.topology,
                controls(edge.knot, edge.ring), midi = edge.midi, periodSeconds = .5)
            val d = loop.diagnostics
            assertTrue(d.converged && d.attempts <= d.maxAttempts && d.maxAttempts <= 192,
                "$edge did not converge within the declared budget")
            assertTrue(d.delaySamples.all { it * os >= 1.0 }, "$edge lost causal delay storage")
            assertTrue(d.stateError < 2e-7 && d.audioError < 1e-8)
            assertTrue(requireNotNull(d.seam) < Keys.MAX_SEAM_ERROR && requireNotNull(d.nextSeam) < Keys.MAX_SEAM_ERROR)
            assertTrue(loop.raw.all { it.isFinite() } && d.rawPeak > 1e-6)
            assertTrue(d.maxLoopGain < 1.0 && d.maxBufferAbs < 1.0)
            assertTrue(abs(d.sourceRootHz / d.referenceHz - 1.0) <= .5 / d.periodFrames + 1e-12)
        }
    }

    @Test
    fun `release and capture length preserve the causal trajectory before note off`() {
        val m = controls(knot = .5f, ring = .35f)
        val early = OverlapHeldPrototype.renderHeldNote(OverlapTopology.TREFOIL, m,
            sustainSeconds = .65, releaseSeconds = .2, periodSeconds = .5, tailSeconds = .6)
        val later = OverlapHeldPrototype.renderHeldNote(OverlapTopology.TREFOIL, m,
            sustainSeconds = 1.2, releaseSeconds = .2, periodSeconds = .5, tailSeconds = .6)
        val prefix = early.noteOffFrame * os
        assertContentEquals(early.internalRaw.copyOf(prefix), later.internalRaw.copyOf(prefix))
        // Shared centered decimation can see a small distance beyond note-off.
        val outsideLookahead = early.noteOffFrame - 256
        assertContentEquals(early.raw.copyOf(outsideLookahead), later.raw.copyOf(outsideLookahead))
        val extended = OverlapHeldPrototype.renderHeldNote(OverlapTopology.TREFOIL, m,
            sustainSeconds = .65, releaseSeconds = .2, periodSeconds = .5, tailSeconds = 1.2)
        assertContentEquals(early.internalRaw, extended.internalRaw.copyOf(early.internalRaw.size))
        assertContentEquals(early.raw, extended.raw.copyOf(early.raw.size), "real following guards should preserve even the shorter capture's last frames")
        val afterDrive = early.driveStopsFrame + rate / 20
        assertTrue(rms(early.raw, afterDrive, afterDrive + rate / 10) > 1e-6,
            "release erased the resonating network instead of withdrawing excitation")
        assertTrue(early.diagnostics.tailWasExplicit && !early.diagnostics.converged)
    }

    @Test
    fun `a key released during the attack withdraws rather than increases the actual source gate`() {
        val off = (rate * os * .1).toInt()
        val stops = off + (rate * os * .25).toInt()
        var previous = OverlapHeldPrototype.excitationEnvelope(off, off, stops)
        assertTrue(previous > 0.0)
        for (n in off + 1..stops + 100) {
            val drive = OverlapHeldPrototype.excitationEnvelope(n, off, stops)
            assertTrue(drive in 0.0..previous, "source grows after an early note-off at internal frame $n")
            previous = drive
        }
        assertEquals(0.0, previous)
    }

    @Test
    fun `maximum ring has an unfaded quiet passive ending and does not return afterward`() {
        val m = controls(knot = 0f, ring = 1f)
        val note = OverlapHeldPrototype.renderHeldNote(OverlapTopology.SELF, m,
            sustainSeconds = .8, releaseSeconds = .25, periodSeconds = .5)
        assertTrue(!note.diagnostics.tailWasExplicit)
        assertTrue(note.diagnostics.tailRmsRatio < 3.2e-4,
            "default tail still carries audible energy before any output fade: ${note.diagnostics.tailRmsRatio}")
        assertTrue(note.diagnostics.maxLoopGain < 1 && note.diagnostics.maxBufferAbs < 1)
        val actualTail = (note.raw.size - note.driveStopsFrame).toDouble() / rate
        val continued = OverlapHeldPrototype.renderHeldNote(OverlapTopology.SELF, m,
            sustainSeconds = .8, releaseSeconds = .25, periodSeconds = .5, tailSeconds = actualTail + 1.0)
        assertContentEquals(note.internalRaw, continued.internalRaw.copyOf(note.internalRaw.size))
        assertContentEquals(note.raw, continued.raw.copyOf(note.raw.size))
        val body = loudestRms(note.raw, rate / 5)
        val returning = loudestRms(continued.raw.copyOfRange(note.raw.size, continued.raw.size), rate / 5)
        assertTrue(returning <= body * 3.2e-4, "passive modes return after the declared natural ending")
    }

    @Test
    fun `prototype is deterministic and refuses controls whose held behavior is unproved`() {
        val a = OverlapHeldPrototype.renderLoopMeasured(OverlapTopology.MIRROR,
            controls(knot = .5f, ring = .2f), periodSeconds = .25)
        val b = OverlapHeldPrototype.renderLoopMeasured(OverlapTopology.MIRROR,
            controls(knot = .5f, ring = .2f), periodSeconds = .25)
        assertContentEquals(a.raw, b.raw)
        assertContentEquals(a.snip.samples, b.snip.samples)
        for (unsupported in listOf("PULSE" to .1f, "GLOW" to .1f, "HOLD" to .99f)) {
            assertFailsWith<IllegalArgumentException> {
                OverlapHeldPrototype.renderLoopMeasured(OverlapTopology.TREFOIL, controls() + unsupported)
            }
        }
        val silent = OverlapHeldPrototype.renderLoopMeasured(OverlapTopology.TORUS,
            controls(ring = .2f), velocity = 0f, periodSeconds = .25)
        assertTrue(silent.raw.all { it == 0f } && silent.snip.samples.all { it == 0f })
        assertEquals(0.0, silent.diagnostics.seam)
    }

    private fun rms(s: FloatArray, start: Int, end: Int): Double =
        sqrt((start until end).sumOf { s[it].toDouble() * s[it] } / (end - start))

    private fun loudestRms(s: FloatArray, window: Int): Double {
        var power = (0 until window).sumOf { s[it].toDouble() * s[it] }
        var maximum = power
        for (i in window until s.size) {
            power += s[i].toDouble() * s[i] - s[i - window].toDouble() * s[i - window]
            maximum = maxOf(maximum, power)
        }
        return sqrt(maximum / window)
    }
}
