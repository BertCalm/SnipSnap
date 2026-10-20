package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BorePlayerTest {
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    @Test
    fun `a caller envelope reproduces the raw one-shot through attack sustain and release`() {
        for (voice in BoreVoice.entries) for (hold in listOf(0f, .5f)) {
            // Below the key-thump threshold: Player returns the bore alone, before that output layer.
            val macros = Bore.defaults(voice) + mapOf("CHIFF" to .4f, "HOLD" to hold)
            val hz = Bore.tunedHz(voice, Bore.frequencyFor(voice, .5f))
            val seconds = .45f
            val attack = (Bore.attackSeconds(macros.getValue("CHIFF")) * rate).toInt().coerceAtLeast(1)
            val held = (seconds * rate).toInt()
            val release = (Bore.RELEASE_SECONDS * rate).toInt().coerceAtLeast(1)
            val player = Bore.Player(voice, hz, macros, rate, maxDetuneCents = 0f)
            val streamed = FloatArray(attack + held + release) { i ->
                val envelope = when {
                    i < attack -> i.toFloat() / attack
                    i >= attack + held -> (attack + held + release - i).toFloat() / release
                    else -> 1f
                }
                player.tick(envelope, i.toDouble() / rate)
            }
            assertContentEquals(Bore.blow(voice, hz, macros, rate, gateSeconds = seconds), streamed,
                "$voice HOLD $hold: coordinating the raw player changes its one-shot")
        }
    }

    @Test
    fun `noise belongs to each player and its explicit seed reproduces the performance`() {
        for (voice in BoreVoice.entries) {
            val macros = Bore.defaults(voice)
            val hz = Bore.tunedHz(voice, 440f)
            val a = Bore.Player(voice, hz, macros, rate, noiseSeed = 11)
            val same = Bore.Player(voice, hz, macros, rate, noiseSeed = 11)
            val other = Bore.Player(voice, hz, macros, rate, noiseSeed = 12)
            var differs = false
            repeat(rate / 4) { i ->
                val elapsed = i.toDouble() / rate
                val first = a.tick(1f, elapsed)
                assertTrue(first.toRawBits() == same.tick(1f, elapsed).toRawBits(), "$voice seed drifted at $i")
                if (first.toRawBits() != other.tick(1f, elapsed).toRawBits()) differs = true
            }
            assertTrue(differs, "$voice players with different seeds share their turbulence")
        }
    }

    @Test
    fun `zero pressure also removes the reed tongue and pop`() {
        for (voice in BoreVoice.entries) {
            val macros = Bore.defaults(voice) + ("CHIFF" to 1f)
            val player = Bore.Player(voice, 440f, macros, rate, noiseSeed = 23)
            repeat(rate / 4) { i ->
                assertTrue(player.tick(1f, i.toDouble() / rate, pressureScale = 0f) == 0f,
                    "$voice injects an onset while its pressure is zero")
            }
        }
    }

    @Test
    fun `caller pitch can cross both detune bounds without reallocating the sounding bore`() {
        for (voice in BoreVoice.entries) {
            val macros = Bore.defaults(voice) + ("HOLD" to .8f)
            val player = Bore.Player(voice, 440f, macros, rate, maxDetuneCents = 32f)
            var sounded = false
            repeat(rate) { i ->
                val pitch = if (i < rate / 2) 32f else -32f
                val out = player.tick(1f, i.toDouble() / rate, pitchCents = pitch)
                assertTrue(out.isFinite(), "$voice went non-finite across the detune bounds")
                if (abs(out) > 1e-4f) sounded = true
            }
            assertTrue(sounded, "$voice lost its speaking state while retuning")
        }
    }

    @Test
    fun `external excitation returns through the bore instead of appearing directly at its output`() {
        for (voice in BoreVoice.entries) {
            val player = Bore.Player(voice, 440f, Bore.defaults(voice), rate, noiseSeed = 23)
            assertTrue(player.tick(0f, 0.0, pressureScale = 0f, externalExcitation = .25f) == 0f)
            val tail = FloatArray(rate / 20) { i ->
                player.tick(0f, (i + 1).toDouble() / rate, pressureScale = 0f)
            }
            assertFalse(tail.all { abs(it) < 1e-6f }, "$voice does not receive the external bore excitation")
        }
    }
}
