package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LatticeMotionTest {
    @Test
    fun `native reference reproduces the accepted independent B notes exactly`() {
        val independentCurve = LatticeGestureStudy.controls(LatticeGestureStudy.frames, 2.0)
        for (midi in listOf(48, 55, 51)) {
            val accepted = Lattice.probe(LatticeGestureStudy.settings(midi), independentCurve)
            val native = Lattice.traceProbe(midi = midi)
            assertContentEquals(accepted.snip.samples, native.snip.samples, "MIDI $midi changed accepted B")
            assertContentEquals(accepted.dry.samples, native.dry.samples, "MIDI $midi passing branch changed")
            assertContentEquals(accepted.halo.samples, native.halo.samples, "MIDI $midi collector changed")
            assertEquals(accepted.diagnostics.sourceGain, native.diagnostics.sourceGain)
            assertContentEquals(native.snip.samples, LatticePatch("Reference", LatticeVoice.TRACE,
                Lattice.defaults(LatticeVoice.TRACE), midi = midi).render().samples)
        }
    }

    @Test
    fun `speed moves the harmonic change and source release after an unchanged attack`() {
        val expected = listOf(
            Triple(0f, .205, .885),
            Triple(.5f, .12, .46),
            Triple(1f, .0775, .2475),
        )
        val clips = expected.map { (speed, bodyTime, sourceEnd) ->
            val control = LatticeMotion.controls(bodyTime, speed)
            assertEquals(4.0, control.gateCount!!, 1e-10)
            assertEquals(1.0, control.color, 1e-10)
            val settings = Lattice.settingsFor(LatticeVoice.TRACE, mapOf("SPEED" to speed))
            assertEquals(sourceEnd, settings.excitationSeconds, 1e-12)
            assertEquals(sourceEnd + 1.54, settings.durationSeconds, 1e-12)
            val probe = Lattice.traceProbe(mapOf("SPEED" to speed))
            assertEquals((settings.durationSeconds * Dsp.RATE).roundToInt(), probe.snip.samples.size)
            val tailStart = settings.excitationSeconds + .04
            val tailEnd = tailStart + .25
            val ring = LatticeGestureStudy.rms(probe.snip, tailStart, tailEnd)
            val passing = LatticeGestureStudy.rms(probe.dry, tailStart, tailEnd)
            assertTrue(ring > 1e-4, "SPEED $speed lost its post-source ring: $ring")
            assertTrue(passing < ring * 1e-3, "SPEED $speed left the direct body open: $passing / $ring")
            probe.snip.samples
        }
        val initialFrames = (.025 * Dsp.RATE).toInt()
        for (clip in clips.drop(1)) assertContentEquals(clips.first().copyOf(initialFrames), clip.copyOf(initialFrames),
            "SPEED must preserve the initial attack rather than transpose a completed WAV")
        assertTrue(clips.map { it.size }.toSet().size == 3, "SPEED must change the finite event timing")
    }

    @Test
    fun `TRACE hold adds bounded source effort and never promises a loop`() {
        assertEquals(listOf("TUNE", "SPEED", "HOLD"), Lattice.macrosFor(LatticeVoice.TRACE).map { it.name })
        assertEquals(mapOf("TUNE" to .5f, "SPEED" to .5f, "HOLD" to 0f), Lattice.defaults(LatticeVoice.TRACE))
        for (speed in listOf(0f, .5f, 1f)) {
            val short = Lattice.settingsFor(LatticeVoice.TRACE, mapOf("SPEED" to speed))
            val held = Lattice.settingsFor(LatticeVoice.TRACE, mapOf("SPEED" to speed, "HOLD" to 1f))
            assertEquals(3.24, held.excitationSeconds - short.excitationSeconds, 1e-12)
            assertEquals(3.24, held.durationSeconds - short.durationSeconds, 1e-12)
            assertFalse(Lattice.isLoop(LatticeVoice.TRACE, 1f))
            assertEquals(DrumClass.TONAL, Lattice.drumClassFor(LatticeVoice.TRACE,
                mapOf("SPEED" to speed, "HOLD" to 1f)))
        }
        val short = Lattice.traceProbe()
        val held = Lattice.traceProbe(mapOf("HOLD" to 1f))
        assertTrue(LatticeGestureStudy.rms(held.snip, 1.0, 1.5) >
            LatticeGestureStudy.rms(short.snip, 1.0, 1.5) * 10.0, "HOLD must retain collector effort")
        assertTrue(held.snip.samples.takeLast((.01 * Dsp.RATE).toInt()).all { abs(it) < 1e-4 },
            "finite HOLD must finish rather than expose an open sustain")
        assertFailsWith<IllegalArgumentException> { Lattice.renderLoop(LatticeVoice.TRACE) }
        for (voice in listOf(LatticeVoice.WARM, LatticeVoice.PLUCK, LatticeVoice.HOLLOW)) {
            assertTrue(Lattice.isLoop(voice, 1f), "$voice retains its original periodic HOLD")
        }
    }

    @Test
    fun `compiled motion is shared across notes bounded and reuses its settled transfer`() {
        val reference = LatticeMotion.plan(.5f)
        assertSame(reference, LatticeMotion.plan(.5f))
        val far = reference.block(Int.MAX_VALUE)
        assertSame(far, reference.block(reference.compiledBlocks - 1))
        assertTrue(far.geometry.groups.all { abs(it.transmission) < 1e-12 })
        assertEquals(1.0, far.reject)
        assertTrue(reference.compiledBlocks < 1300, "only the moving first .44 seconds need coefficients")
        for (step in 0..16) LatticeMotion.plan(step / 16f)
        assertTrue(LatticeMotion.cachedPlanCount() <= 8, "SPEED edits must not retain an unbounded coefficient bank")
    }

    @Test
    fun `cold precomputation and warm rendering both honor cancellation`() {
        var checks = 0
        assertFailsWith<CancellationException> {
            Lattice.render(LatticeVoice.TRACE, mapOf("SPEED" to .1234567f), cancelled = { ++checks > 64 })
        }
        assertTrue(checks in 65..66, "a cold speed must stop within its next compile block: $checks")
        LatticeMotion.plan(.5f)
        assertFailsWith<CancellationException> { Lattice.render(LatticeVoice.TRACE, cancelled = { true }) }
        val recovered = Lattice.render(LatticeVoice.TRACE)
        assertTrue(recovered.samples.any { abs(it) > 1e-4f }, "cancellation cannot poison a later note")
    }

    @Test
    fun `speed extremes keep the played pitch finite headroom and silent velocity`() {
        for ((midi, speed) in listOf(36 to 0f, 48 to 1f, 84 to 0f)) {
            val probe = Lattice.traceProbe(mapOf("SPEED" to speed), midi = midi, velocity = 1f)
            val samples = probe.snip.samples
            assertTrue(samples.all { it.isFinite() && abs(it) < .99f }, "MIDI $midi SPEED $speed")
            assertTrue(abs(samples.average()) < 1e-4, "MIDI $midi SPEED $speed DC")
            val pitch = assertNotNull(Pitch.detect(probe.snip, fromSec = .45f, windowSec = .25f))
            val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
            assertTrue(abs(cents) < 30.0, "SPEED changed MIDI $midi pitch by $cents cents")
        }
        val silence = Lattice.render(LatticeVoice.TRACE, mapOf("SPEED" to 0f, "HOLD" to 1f), velocity = 0f)
        assertTrue(silence.samples.all { it == 0f })
    }
}
