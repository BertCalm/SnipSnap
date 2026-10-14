package com.snipsnap.synth

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

class VentTest {
    @Test
    fun `saved note velocity seed model and macros regenerate exactly and survive edits`() {
        for (voice in VentVoice.entries) {
            val patch = VentPatch("Saved $voice", voice, Vent.defaultsFor(voice)+("LENGTH" to .1f),
                midi = 57, velocity = .65f, seed = -91827)
            val restored = VentPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertContentEquals(patch.render().samples, restored.render().samples)
            assertEquals(setOf("CHAOS", "LEASH", "VIEW", "DAMP", "PUSH", "LENGTH"), patch.macros.keys)
            val editedMacros = patch.macros+("VIEW" to .81f)
            assertEquals(patch.copy(name = "Edited", macros = editedMacros), Patches.edited(patch, "Edited", editedMacros))
            assertContentEquals(patch.copy(velocity = .25f).render().samples, Velocity.atVelocity(patch, .25f).samples)
            assertFalse(patch.render().samples.contentEquals(patch.copy(seed = 173).render().samples), "$voice seed has no effect")
        }
    }

    @Test
    fun `partial old recipes default optional fields and invalid model or controls are refused`() {
        val restored = VentPatch.fromJsonText("""{"engine":"VENT","version":1,"name":"Partial","voice":"WIRE","macros":{"LENGTH":0}}""")
        assertEquals(60, restored.midi)
        assertEquals(1f, restored.velocity)
        assertEquals(0, restored.seed)
        assertEquals(1, restored.model)
        assertContentEquals(Vent.render(VentVoice.WIRE, mapOf("LENGTH" to 0f)).samples, restored.render().samples)
        for (midi in listOf(35, 85)) assertFailsWith<IllegalArgumentException> { restored.copy(midi = midi) }
        for (v in listOf(-.1f, 1.1f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException> { restored.copy(velocity = v) }
            assertFailsWith<IllegalArgumentException> { restored.copy(macros = mapOf("PUSH" to v)) }
        }
        assertFailsWith<IllegalArgumentException> { restored.copy(model = 2) }
        assertFailsWith<IllegalArgumentException> { restored.copy(macros = mapOf("HOLD" to 1f)) }
    }

    @Test
    fun `corner register sweep has finite bounded audio and no emergency state resets`() {
        for (voice in VentVoice.entries) for (midi in listOf(36, 60, 84)) {
            for ((chaos, leash) in listOf(0f to 0f, 0f to 1f, .5f to .5f, 1f to 0f, 1f to 1f)) {
                val result = Vent.renderDetailed(voice, mapOf("CHAOS" to chaos, "LEASH" to leash, "PUSH" to 1f, "LENGTH" to .2f), midi, seed = 173)
                val label = "$voice MIDI$midi CHAOS$chaos LEASH$leash"
                assertEquals(0, result.diagnostics.integrationFailures, label)
                assertTrue(result.diagnostics.maxState in .1..256.0, label)
                assertTrue(result.diagnostics.modelTimeStep in 0.0.. .02, label)
                assertTrue(result.diagnostics.rawRms > .001, label)
                assertTrue(result.snip.peak() <= .991f, label)
                assertTrue(result.snip.samples.all { it.isFinite() }, label)
                assertEquals(1, result.snip.channels)
                assertEquals(44_100, result.snip.sampleRate)
                if (leash == 0f) assertEquals(0, result.diagnostics.leashResets, label)
                if (leash > 0f) assertTrue(result.diagnostics.leashResets > 0, label)
            }
        }
        for (voice in VentVoice.entries) {
            val silent = Vent.renderDetailed(voice, mapOf("LENGTH" to 0f), velocity = 0f)
            assertTrue(silent.snip.samples.all { it == 0f })
            assertEquals(0.0, silent.diagnostics.rawPeak)
            assertEquals(0, silent.diagnostics.integrationFailures)
        }
    }

    @Test
    fun `readout controls preserve the orbit while chaos leash push and velocity alter it`() {
        val voice = VentVoice.WIRE
        val base = Vent.defaultsFor(voice)+mapOf("CHAOS" to .75f, "LENGTH" to .5f)
        val initial = Vent.renderDetailed(voice, base, velocity = .8f, seed = 17)
        for (control in listOf("VIEW", "DAMP")) {
            val changed = Vent.renderDetailed(voice, base+(control to 1f), velocity = .8f, seed = 17)
            assertEquals(initial.trace, changed.trace, "$control unexpectedly changes the ODE")
            assertTrue(normalizedDistance(initial.raw, changed.raw) > .04, "$control has no audible shape effect")
        }
        for (control in listOf("CHAOS", "LEASH", "PUSH")) {
            val changed = Vent.renderDetailed(voice, base+(control to 0f), velocity = .8f, seed = 17)
            assertFalse(initial.trace == changed.trace, "$control has no trajectory effect")
            assertTrue(normalizedDistance(initial.raw, changed.raw) > .04, "$control only changes gain")
        }
        val soft = Vent.renderDetailed(voice, base, velocity = .25f, seed = 17)
        assertFalse(initial.trace == soft.trace, "velocity has no trajectory effect")
        assertTrue(normalizedDistance(initial.raw, soft.raw) > .04, "velocity only changes gain")
        val shorter = Vent.render(voice, base+("LENGTH" to 0f))
        assertTrue(shorter.frameCount < initial.snip.frameCount/3, "LENGTH has no duration effect")
    }

    @Test
    fun `round low chaos high leash retains measured requested roots in a modest register`() {
        val macros = Vent.defaultsFor(VentVoice.ROUND)+mapOf("CHAOS" to .15f, "LEASH" to 1f)
        for (midi in listOf(48, 60, 72)) for (velocity in listOf(.25f, 1f)) {
            val result = Vent.render(VentVoice.ROUND, macros, midi, velocity, seed = 17)
            val pitch = assertNotNull(Pitch.detect(result, .55f, .4f), "no root MIDI$midi velocity$velocity")
            val cents = 1200.0*ln(pitch.hz/Keys.midiHz(midi).toDouble())/ln(2.0)
            assertTrue(abs(cents) < 12.0, "MIDI$midi velocity$velocity departure $cents cents")
            assertTrue(pitch.confidence > .75f)
        }
    }

    @Test
    fun `cancelled renders stop before calculating the orbit`() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { Vent.render(VentVoice.TEAR) }
        } finally {
            Thread.interrupted()
        }
        assertTrue(Vent.render(VentVoice.ROUND, mapOf("LENGTH" to 0f)).samples.any { it != 0f })
    }

    /** Gain-independent sample-shape distance, so normalization cannot create a control effect. */
    private fun normalizedDistance(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        fun rms(x: FloatArray) = sqrt((0 until n).sumOf { x[it].toDouble()*x[it] }/n).coerceAtLeast(1e-12)
        val ra = rms(a); val rb = rms(b)
        return sqrt((0 until n).sumOf { val d = a[it]/ra-b[it]/rb; d*d }/n)
    }
}
