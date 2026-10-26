package com.snipsnap.synth

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Saved model-2 recipes must keep their sound when redundant work is removed.
 * These fingerprints were captured from the unmodified renderer at
 * 91384fa189a68c4993c8e359af6b748412bf95c3, then checked against the optimized
 * renderer in the same JVM. They include the raw and final samples, trajectory
 * and diagnostics, so output leveling cannot conceal a changed source orbit.
 */
class VentOptimizationTest {
    @Test
    fun `all twelve factory recipes retain their complete pre-optimization render`() {
        val expected = mapOf(
            "pulse-bass" to "234fb3d44d0846af0abcf0b4635a4aad6e47ef05448b3fd5c58a73a4ae706b4b",
            "pulse-note" to "6ff03c1bc578f4f870da5b9a791f40db7b4f5405a97e65f71dd44a01e4404567",
            "pulse-hit" to "0e5920d75754b7cfc058c45c5552e62dd789a0e3c7a70e1ffeae5fa9de0353d2",
            "pulse-texture" to "fedfe3b3305ec0a48fa071a70d0ee378b6fcacd6b9dfce6e0e7f495f079553d4",
            "coil-bass" to "755ec633c4e2469adabb3feaabd52bbfd53f843acfab429145416f1763f522c5",
            "coil-note" to "024c56716fd8e84327f64dd0bc190345ccd844d50b45790d60e78ae2915528d2",
            "coil-hit" to "af5994babcc61a292c4612a49363214a2dbb6bf7dac862a9378116cd8bbc895f",
            "coil-texture" to "cb380b8e67a97eae555a3d2ba4410a22e868435bbf338fdff94159dc1a74e4aa",
            "plume-bass" to "392cc496d9ccd6c92032d7f901e8d9842e0f40ffb17fdba5798d535db1943020",
            "plume-note" to "763fa101d2aace528aa27c7a0ad33e721390ddba4189e1365b1ebc9199819526",
            "plume-hit" to "aa97a99c282025fdb74b9e21297102fd7fa8b42ab726e9b6ab751b95f199bf0a",
            "plume-texture" to "f79cb2ae46d3c7569297bf8a1d45e95e725d6e68698e12e09dbbdb7d0ef9a95e",
        )
        assertEquals(expected.keys, VentKitPresets.entries.map { it.id }.toSet())
        for (entry in VentKitPresets.entries) {
            val patch = entry.patch
            val render = Vent.renderDetailed(patch.voice, patch.macros, patch.midi,
                patch.velocity, patch.seed, patch.model)
            assertEquals(expected.getValue(entry.id), fingerprint(render), "${entry.id} changed its saved source or output")
        }
    }

    @Test
    fun `long high free orbits retain their complete pre-optimization render`() {
        val expected = mapOf(
            VentVoice.PULSE to "fe173ff1dfedfd3e117608bc3a33297f11baeaf6f66f780001a703632decfd74",
            VentVoice.COIL to "0948a2ec41297ba00533a21d9ff21f3e936f900a2172ce4d8235b7f06e30c84a",
            VentVoice.PLUME to "a76be738a1b1d8b77cbfc2f729a5f8879be20d6739089a8f0f91213fd07802d9",
        )
        for ((voice, hash) in expected) {
            val macros = Vent.defaultsFor(voice) + mapOf(
                "CHAOS" to 1f, "LEASH" to 0f, "VIEW" to 1f,
                "DAMP" to 0f, "PUSH" to 1f, "LENGTH" to 1f,
            )
            val render = Vent.renderDetailed(voice, macros, midi = 84, velocity = 1f, seed = 173, model = 2)
            assertEquals(hash, fingerprint(render), "$voice long free orbit changed")
        }
    }

    /** Fixed big-endian field order; lengths distinguish arrays and strings unambiguously. */
    private fun fingerprint(render: VentRender): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream ->
            stream.writeInt(render.snip.sampleRate)
            stream.writeInt(render.snip.channels)
            for (samples in listOf(render.raw, render.snip.samples)) {
                stream.writeInt(samples.size)
                for (sample in samples) stream.writeInt(sample.toRawBits())
            }
            stream.writeInt(render.trace.size)
            for (point in render.trace) {
                for (value in listOf(point.seconds, point.x, point.y, point.z)) stream.writeLong(value.toRawBits())
            }
            val d = render.diagnostics
            for (value in listOf(d.rawPeak, d.rawRms, d.rawDc, d.maxState, d.stateRms,
                d.measuredOrbitPeriod, d.modelTimeStep, d.externalForceIntegral, d.projectionSpan)) {
                stream.writeLong(value.toRawBits())
            }
            stream.writeInt(d.integrationFailures)
            stream.writeInt(d.leashResets)
            stream.writeUTF(d.signalPath)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
