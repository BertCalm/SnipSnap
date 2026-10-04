package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BallastTest {
    @Test
    fun `every control changes every voice`() {
        for (voice in BallastVoice.entries) {
            val defaults = Ballast.defaults(voice)
            val base = Ballast.render(voice, defaults).samples
            for (spec in Ballast.macrosFor(voice)) {
                val moved = defaults + (spec.name to if (spec.default > .5f) .1f else .9f)
                val other = Ballast.render(voice, moved).samples
                val n = minOf(base.size, other.size)
                var difference = 0.0
                for (i in 0 until n) {
                    val d = base[i] - other[i].toDouble()
                    difference += d * d
                }
                assertTrue(base.size != other.size || difference / n > 1e-8, "$voice ${spec.name} did nothing")
            }
        }
    }

    @Test
    fun `the structure is driven by the bass and returns to the wires`() {
        val macros = Ballast.defaults(BallastVoice.SWARM)
        val live = Ballast.simulate(BallastVoice.SWARM, 36, macros)
        val noActuator = Ballast.simulate(BallastVoice.SWARM, 36, macros, probe = Ballast.Probe(actuator = false))
        val noReturn = Ballast.simulate(BallastVoice.SWARM, 36, macros, probe = Ballast.Probe(frameReturn = false))

        assertTrue(live.frameEnergy > 0.0)
        assertTrue(live.stringEnergy > 0.0)
        assertTrue(live.contacts > 0, "the glass never contacted")
        assertTrue(live.glassEnergy > 0.0)
        assertEquals(0.0, noActuator.frameEnergy, "the frame moved without its actuator")
        assertEquals(0, noActuator.contacts, "tiles contacted without frame drive")
        assertEquals(0.0, noActuator.glassEnergy, "glass rang without a contact")
        assertNotEquals(live.stringEnergy, noReturn.stringEnergy, "frame return did not change the wires")
    }

    @Test
    fun `renders are deterministic finite bounded mono at the house rate`() {
        for (voice in BallastVoice.entries) for (midi in listOf(24, 36, 60)) {
            val a = Ballast.render(voice, midi = midi)
            val b = Ballast.render(voice, midi = midi)
            assertContentEquals(a.samples, b.samples, "$voice MIDI $midi")
            assertEquals(1, a.channels)
            assertEquals(Dsp.RATE, a.sampleRate)
            assertTrue(a.samples.all(Float::isFinite))
            assertTrue(a.samples.all { it in -1f..1f })
            assertTrue(Loudness.of(a) >= Dsp.MELODIC_LOUDNESS_TARGET * .85f)
            assertTrue(abs(a.samples.average()) < .02)
        }
    }

    @Test
    fun `moderate voices never enter a drum choke class`() {
        val prohibited = setOf(
            DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
        )
        for (voice in BallastVoice.entries) for (midi in listOf(36, 48, 60)) {
            val heard = Classifier.classify(Ballast.render(voice, midi = midi)).drumClass
            assertTrue(heard !in prohibited, "$voice MIDI $midi classified as $heard")
        }
    }

    @Test
    fun `HOLD exports a seamless deterministic period`() {
        for (voice in BallastVoice.entries) {
            val macros = Ballast.defaults(voice) + ("HOLD" to 1f)
            val a = Ballast.render(voice, macros)
            val b = Ballast.render(voice, macros)
            assertContentEquals(a.samples, b.samples, voice.name)
            assertEquals((Ballast.LOOP_SECONDS * Dsp.RATE).toInt(), a.frameCount)
            assertTrue(Ballast.loopSeamError(a.samples) < Keys.MAX_SEAM_ERROR)
            assertEquals(DrumClass.LOOP, Ballast.drumClassFor(voice, macros))
        }
    }

    @Test
    fun `patch JSON keeps pitch velocity voice and sound`() {
        val patch = BallastPatch(
            "Wake Test", BallastVoice.GLINT,
            Ballast.defaults(BallastVoice.GLINT) + ("FRAME" to .71f),
            midi = 41, velocity = .63f,
        )
        val restored = BallastPatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored)
        assertContentEquals(patch.render().samples, restored.render().samples)
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
    }

    @Test
    fun `velocity changes source brightness and mechanical energy`() {
        val patch = BallastPatch("Velocity", BallastVoice.BLOOM, Ballast.defaults(BallastVoice.BLOOM))
        val soft = Velocity.atVelocity(patch, .2f)
        val hard = Velocity.atVelocity(patch, 1f)
        var difference = 0.0
        for (i in 0 until minOf(soft.samples.size, hard.samples.size)) {
            difference = max(difference, abs(soft.samples[i] - hard.samples[i].toDouble()))
        }
        assertTrue(difference > .01)
    }
}
