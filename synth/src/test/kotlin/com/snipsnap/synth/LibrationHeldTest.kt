package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Fft
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.test.*

class LibrationHeldTest {
    private fun patch(voice: LibrationVoice = LibrationVoice.GATHER) = LibrationPatch("Held proof", voice,
        Libration.defaults(voice) + mapOf("SWARM" to .3f, "RESONANCE" to .7f))

    @Test fun `continuous source modal and conditioning state recur at all three voices`() {
        for (voice in LibrationVoice.entries) {
            val p = LibrationKeys.Session().probe(patch(voice))
            println("LIBRATION $voice seam=${p.seamError} cycle=${p.cycleError} phase=${p.phaseError} state=${p.stateError} root=${p.rootCents} raw=${p.rawPeak} renderMs=${p.renderMilliseconds}")
            assertTrue(p.note.loopStartFrame > p.loop.size)
            assertTrue(p.seamError < Keys.MAX_SEAM_ERROR)
            assertTrue(p.cycleError < 1e-3)
            assertTrue(p.stateError < 1e-3)
            assertTrue(p.phaseError < 1e-6)
            assertTrue(abs(p.rootCents) < .1)
            assertTrue(p.note.snip.samples.all { it.isFinite() })
            assertTrue(p.note.snip.peak() <= .941f)
            assertTrue(p.loop.any { it != 0f })
            val pitch = assertNotNull(Pitch.detect(p.note.snip, .1f, .5f))
            assertTrue(abs(1200 * log2(pitch.hz / Keys.midiHz(60))) < 18, "$voice detected ${pitch.hz}")
        }
    }

    @Test fun `unfitted phases fail the real continuation guard`() {
        val error = assertFailsWith<IllegalStateException> {
            LibrationKeys.Session().probe(patch().copy(macros = patch().macros + ("RESONANCE" to .1f)), fitPhases = false)
        }
        assertTrue(error.message.orEmpty().contains("failed recurrence"), error.message)
    }

    @Test fun `dense mixed families close at register edges and maximum memory without losing their root`() {
        for (voice in LibrationVoice.entries) for (midi in listOf(36, 84)) {
            val region = when (voice) {
                LibrationVoice.GATHER -> .125f
                LibrationVoice.TWINS -> .875f
                LibrationVoice.DEPARTURE -> .375f
            }
            val source = patch(voice).copy(midi = midi, velocity = 1f, macros = patch(voice).macros + mapOf(
                "REGION" to region, "WANDER" to 1f, "NUDGE" to 1f, "SWARM" to 1f, "RESONANCE" to 1f))
            val p = LibrationKeys.Session().probe(source)
            println("LIBRATION dense $voice MIDI$midi seam=${p.seamError} cycle=${p.cycleError} state=${p.stateError} raw=${p.rawPeak} particles=${p.particleCount}")
            assertTrue(p.cycleError < 1e-3 && p.seamError < Keys.MAX_SEAM_ERROR)
            assertTrue(p.note.snip.peak() <= .941f)
            assertTrue(p.rawPeak < 1.0, "unconditioned held circuit exceeds its expected headroom: ${p.rawPeak}")
            assertTrue(abs(p.loop.average()) < 1e-4, "sustain has DC")
            assertTrue(p.particleCount <= 32)
            val body = p.note.snip.copy(samples = p.loop)
            val detected = assertNotNull(Pitch.detect(body, .1f, .5f))
            assertTrue(abs(1200 * log2(detected.hz / Keys.midiHz(midi))) < 30,
                "$voice MIDI$midi detected ${detected.hz} Hz")
        }
    }

    @Test fun `bright upper notes agree in spectral power with a sixteen times reference`() {
        for (voice in LibrationVoice.entries) {
            val source = patch(voice).copy(midi = 84, velocity = 1f, macros = patch(voice).macros +
                mapOf("WANDER" to 1f, "NUDGE" to 1f, "RESONANCE" to 1f))
            val session = LibrationKeys.Session()
            val a = session.probe(source, normalize = false)
            val b = session.probe(source, oversample = 16, normalize = false)
            assertEquals(a.note.loopStartFrame, b.note.loopStartFrame)
            assertEquals(a.loop.size, b.loop.size)
            for (position in listOf(.2, 2.1)) {
                fun spectrum(samples: FloatArray): DoubleArray {
                    val start = (position * Dsp.RATE).toInt()
                    val bins = Fft.magnitudeSpectrum(samples.copyOfRange(start, start + 16384), 16384)
                    val powers = DoubleArray(bins.size) { bins[it].toDouble() * bins[it] }
                    val energy = powers.sum()
                    assertTrue(energy > 1e-5, "a silent reference cannot validate rate agreement")
                    return DoubleArray(powers.size) { powers[it] / energy }
                }
                val native = spectrum(a.loop)
                val reference = spectrum(b.loop)
                val distance = native.indices.sumOf { abs(native[it] - reference[it]) } / 2
                val energyRatio = sqrt(a.loop.sumOf { it.toDouble() * it } / b.loop.sumOf { it.toDouble() * it })
                println("LIBRATION $voice 4x/16x t=$position normalizedPowerDistance=$distance rmsRatio=$energyRatio")
                // Whole-readout rate consistency, not a bound on every possible alias.
                assertTrue(distance < .05, "$voice 4x/16x normalized power distance $distance")
                assertTrue(energyRatio in .9..1.1, "$voice 4x/16x energy ratio $energyRatio")
            }
        }
    }

    @Test fun `held rendering is deterministic across macro order and zero touch stays silent with valid markers`() {
        val source = patch().copy(velocity = .3f)
        val session = LibrationKeys.Session()
        val first = session.note(source)
        val second = session.note(source.copy(name = "Renamed", macros = source.macros.entries.reversed().associate { it.key to it.value }))
        assertEquals(first.loopStartFrame, second.loopStartFrame)
        assertContentEquals(first.snip.samples, second.snip.samples)
        val hard = session.note(source.copy(velocity = 1f))
        val softLoop = first.snip.samples.copyOfRange(first.loopStartFrame.toInt(), first.snip.frameCount)
        val hardLoop = hard.snip.samples.copyOfRange(hard.loopStartFrame.toInt(), hard.snip.frameCount)
        val softEnergy = softLoop.sumOf { it.toDouble() * it }
        val hardEnergy = hardLoop.sumOf { it.toDouble() * it }
        val gain = sqrt(hardEnergy / softEnergy)
        val articulation = sqrt(softLoop.indices.sumOf { i ->
            val difference = softLoop[i] * gain - hardLoop[i]
            difference * difference
        } / hardEnergy)
        println("LIBRATION held touch nativeRmsRatio=$gain matchedArticulation=$articulation")
        assertTrue(gain > 1.5, "held touch lost its native energy: $gain")
        assertTrue(articulation > .006, "held touch became a gain-only control: $articulation")
        val silent = session.note(source.copy(velocity = 0f))
        assertTrue(silent.loopStartFrame in 1 until silent.snip.frameCount.toLong())
        assertTrue(silent.snip.samples.all { it == 0f })
        var checks = 0
        val cancelled = assertFailsWith<CancellationException> { session.note(source, cancelled = { ++checks > 10 }) }
        assertTrue(cancelled.message.orEmpty().contains("cancelled"))
        assertContentEquals(first.snip.samples, session.note(source).snip.samples)
    }

    @Test fun `only explicitly versioned held recipes permit HOLD and finite JSON keeps its old shape`() {
        val finite = patch()
        assertFalse(finite.toJsonText().contains("heldModel"))
        assertFailsWith<IllegalArgumentException> { finite.copy(macros = finite.macros + ("HOLD" to 1f)) }
        val held = finite.copy(macros = finite.macros + ("HOLD" to 1f), heldModel = LibrationKeys.MODEL_VERSION)
        assertEquals(held, Patches.fromJsonText(held.toJsonText()))
        assertEquals(held, PadRecipe.fromJsonText(PadRecipe(patch = held).toJsonText()).patch)
        val json = held.toJsonValue()
        assertFails { LibrationPatch.fromJsonValue(JsonValue.Obj(json.entries - "heldModel")) }
        assertFailsWith<IllegalArgumentException> { held.copy(heldModel = 2) }
        assertFailsWith<IllegalArgumentException> { held.copy(model = 2) }
        assertFailsWith<IllegalArgumentException> { held.copy(macros = held.macros + ("HOLD" to .5f)) }
        val macros = json.entries.getValue("macros").obj()
        for (value in listOf(1.0 - 1e-10, 1.0 + 1e-10, 1e-50)) {
            val error = assertFails { LibrationPatch.fromJsonValue(JsonValue.Obj(json.entries +
                ("macros" to JsonValue.Obj(macros + ("HOLD" to JsonValue.Num(value)))))) }
            assertTrue(error.message.orEmpty().contains("exactly 0 or 1"), error.message)
        }
        assertEquals(finite, Patches.fromJsonText(finite.toJsonText()))
    }
}
