package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** HOLD must close genuinely generated cycles without leveling away the projection model. */
class LatticeHeldTest {
    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    private fun peak(samples: FloatArray): Double = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
    private fun cents(actual: Double, expected: Double) = 1200.0 * ln(actual / expected) / ln(2.0)

    @Test
    fun `held sources close independent generated cycles and retain pitch throughout their register`() {
        for (profile in Lattice.SourceProfile.entries) for (midi in listOf(36, 60, 84)) {
            val settings = Lattice.Settings(midi = midi, velocity = .75f,
                turn = .85, gateCount = 4.0, color = .6, reject = .35,
                sourceProfile = profile)
            val held = Lattice.heldProbe(settings)
            val current = held.snip.samples
            assertEquals(held.loopFrames, current.size)
            assertEquals(held.loopFrames, held.previous.size)
            assertEquals(1, held.snip.channels)
            assertEquals(44_100, held.snip.sampleRate)
            assertTrue(current.all { it.isFinite() } && held.previous.all { it.isFinite() })
            assertTrue(rms(current) > 1e-5, "$profile MIDI$midi has no sustained body")
            assertTrue(peak(current) < .99, "$profile MIDI$midi exceeds raw headroom")
            assertTrue(abs(current.average()) < 1e-4, "$profile MIDI$midi carries DC")
            val seam = Keys.seamError(held.previous + current, held.loopFrames)
            assertTrue(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR,
                "$profile MIDI$midi does not close its generated seam: $seam")
            assertEquals(seam, held.seamError, 1e-12)
            val cycleDifference = current.indices.sumOf {
                val d = current[it] - held.previous[it].toDouble()
                d * d
            } / held.previous.sumOf { it.toDouble() * it }
            assertTrue(cycleDifference < 1e-5,
                "$profile MIDI$midi changes across the complete retained cycles: $cycleDifference")
            assertTrue(abs(cents(held.fittedHz, held.requestedHz)) < 1.0,
                "$profile MIDI$midi loop fitting detunes the root")
            assertEquals(Keys.midiHz(midi).toDouble(), held.requestedHz, 1e-5)
            // The detector is independent of the fitted-period metadata. Its tolerance is wider
            // than the period-fit bar because its autocorrelation/spectral estimate is approximate.
            val pitch = assertNotNull(Pitch.detect(held.snip, fromSec = .2f, windowSec = .4f))
            assertTrue(abs(cents(pitch.hz.toDouble(), held.requestedHz)) < 10.0,
                "$profile MIDI$midi emitted the wrong audible root: ${pitch.hz}")
        }
    }

    @Test
    fun `held transmission keeps common gain and analytical extinction and restoration`() {
        val settings = Lattice.Settings(midi = 48, turn = 0.0, gateCount = 2.0,
            color = 0.0, reject = 0.0, haloEnabled = false,
            sourceProfile = Lattice.SourceProfile.PLUCK)
        val aligned = Lattice.heldProbe(settings).snip.samples
        val alignedRms = rms(aligned)
        assertTrue(alignedRms > .01)
        for ((count, expected) in listOf(2 to 0.0, 3 to .5,
            4 to .649519052838329, 16 to .9209058561872373)) {
            val output = Lattice.heldProbe(settings.copy(turn = 1.0, gateCount = count.toDouble())).snip.samples
            assertEquals(aligned.size, output.size)
            assertEquals(expected, rms(output) / alignedRms, 2e-5,
                "$count held gates received makeup gain or the wrong transmission")
            val error = sqrt(output.indices.sumOf {
                val d = output[it] - expected * aligned[it]
                d * d
            } / output.size)
            assertTrue(error < 2e-6, "$count held gates changed the source phase or spectrum: $error")
            if (count == 2) assertTrue(peak(output) < 1e-12, "crossed HOLD must remain silent")
        }
    }

    @Test
    fun `the held rejected branch needs real rejection and remains deterministic`() {
        val aligned = Lattice.Settings(midi = 48, turn = 0.0, gateCount = 4.0,
            color = 0.0, reject = 1.0, haloEnabled = true,
            sourceProfile = Lattice.SourceProfile.HOLLOW)
        val empty = Lattice.heldProbe(aligned)
        assertTrue(peak(empty.snip.samples) < 1e-12, "an aligned chain cannot excite a hidden oscillator")
        val crossed = aligned.copy(turn = 1.0, gateCount = 2.0)
        val first = Lattice.heldProbe(crossed)
        val repeat = Lattice.heldProbe(crossed)
        assertTrue(rms(first.snip.samples) > 1e-5, "actual rejected source energy did not reach the outline")
        assertContentEquals(first.snip.samples, repeat.snip.samples)
        assertContentEquals(first.previous, repeat.previous)
        assertEquals(first.fittedHz, repeat.fittedHz)
        assertTrue(first.seamError < Keys.MAX_SEAM_ERROR)
    }

    @Test
    fun `held velocity remains source effort and zero velocity does not acquire a noise floor`() {
        val settings = Lattice.Settings(midi = 48, turn = .8, gateCount = 4.0,
            color = .55, reject = .35, sourceProfile = Lattice.SourceProfile.PLUCK)
        val soft = Lattice.heldProbe(settings.copy(velocity = .25f))
        val hard = Lattice.heldProbe(settings.copy(velocity = .85f))
        assertTrue(rms(soft.snip.samples) < rms(hard.snip.samples) * .7,
            "HOLD normalization erased the native excitation velocity")
        val silent = Lattice.heldProbe(settings.copy(velocity = 0f))
        assertTrue(silent.snip.samples.all { it == 0f })
        assertTrue(silent.previous.all { it == 0f })
        assertEquals(0.0, silent.seamError)
    }

    @Test
    fun `finite HOLD grows the note before its explicit loop threshold`() {
        for (voice in LatticeVoice.entries) {
            val short = Lattice.render(voice, mapOf("HOLD" to 0f))
            val medium = Lattice.render(voice, mapOf("HOLD" to .5f))
            val long = Lattice.render(voice, mapOf("HOLD" to .98f))
            assertTrue(short.samples.size < medium.samples.size && medium.samples.size < long.samples.size,
                "$voice finite HOLD did not extend its source effort")
            assertTrue(long.samples.size <= 6 * Dsp.RATE, "$voice exceeded the finite note budget")
            assertFalse(Lattice.isLoop(.98f))
            assertTrue(Lattice.isLoop(Lattice.HOLD_LOOP))
            assertEquals(DrumClass.TONAL, Lattice.drumClassFor(voice, mapOf("HOLD" to .98f)))
            assertEquals(DrumClass.LOOP, Lattice.drumClassFor(voice, mapOf("HOLD" to Lattice.HOLD_LOOP)))
            // A zero fade gain can retain the preceding sample's sign. Both signed zeros
            // are silence; boxed Float equality would reject the negative representation.
            assertTrue(long.samples.last() == 0f, "$voice finite release must close: ${long.samples.last()}")
        }
    }

    @Test
    fun `loop rendering uses saved tune note velocity and the same native entry point`() {
        val voice = LatticeVoice.PLUCK
        val macros = Lattice.defaults(voice) + mapOf("TUNE" to .625f,
            "COLOR" to .65f, "TWIST" to .78f, "REJECT" to .35f)
        val loop = Lattice.renderLoop(voice, macros, midi = 55, velocity = .54f)
        val patch = LatticePatch("Held native", voice, macros + ("HOLD" to 1f), midi = 55, velocity = .54f)
        assertContentEquals(loop, patch.render().samples)
        val pitch = assertNotNull(Pitch.detect(patch.render(), fromSec = .2f, windowSec = .4f))
        val expectedMidi = Lattice.midiFor(voice, .625f, midi = 55)
        assertTrue(abs(cents(pitch.hz.toDouble(), Keys.midiHz(expectedMidi).toDouble())) < 10.0,
            "HOLD dropped the saved note or TUNE transposition")
    }

    @Test
    fun `obsolete finite and held renders honor cancellation and leave fresh renders usable`() {
        val voice = LatticeVoice.WARM
        for (hold in listOf(0f, 1f)) {
            assertFailsWith<CancellationException> {
                Lattice.render(voice, mapOf("HOLD" to hold), cancelled = { true })
            }
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<CancellationException> {
                    Lattice.render(voice, mapOf("HOLD" to hold))
                }
            } finally {
                Thread.interrupted()
            }
        }
        assertFailsWith<CancellationException> {
            Lattice.heldProbe(Lattice.Settings(midi = 48), cancelled = { true })
        }
        assertTrue(Lattice.render(voice).samples.any { it != 0f })
    }
}
