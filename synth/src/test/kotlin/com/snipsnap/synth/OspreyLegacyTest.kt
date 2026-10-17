package com.snipsnap.synth

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Captured from the original compiled Osprey renderer BEFORE its model-2 rewrite, at
 * 85969dd0313d0c64c7ef5ec360ab34bfedb7ca10 (the published R1 source).
 * These are SHA-256 hashes of raw Float32 sample bits in little-endian interleaved order,
 * not quantized WAV hashes. They also pin implicit model-1 defaults and scene seed derivation.
 */
class OspreyLegacyTest {
    private data class Golden(val name: String, val macros: Map<String, Float>, val midi: Int,
        val velocity: Float, val seed: Int, val seconds: Float, val samples: Int, val sha256: String)

    private fun macros(shore: Float, sea: Float, motion: Float, depth: Float, space: Float, hold: Float) =
        mapOf("SHORE" to shore, "SEA" to sea, "MOTION" to motion,
            "DEPTH" to depth, "SPACE" to space, "HOLD" to hold)

    private val goldens = listOf(
        Golden("defaults", emptyMap(), 60, 1f, 0, 8f, 705_600,
            "cd59e3da1219f9808d7d0219148867dfd1ba1530e3b55486965412c8a8fd7d77"),
        Golden("atlantic-calm", macros(0f, .1f, .45f, .55f, 0f, .45f), 60, 1f, 731, 8f, 705_600,
            "3478a3d04174c6354382c5026abe36cb7c79ff0cad2a9ceeae73f6b4edbb7d36"),
        Golden("nordic-soft", macros(.25f, .5f, .45f, .55f, 0f, .45f), 48, .35f, -7_104, 3.5f, 308_700,
            "6c31d6a3f987c2079aa8b6bc82c367c156096d9f9f8c240271d6bf7a6a9d476f"),
        Golden("mediterranean-storm", macros(.5f, .9f, .45f, .55f, 0f, .45f), 72, 1f, 731, 8f, 705_600,
            "910c7818720754bb98388ccd63aef288508643b2cb2c57b54f4fbc103ab20aae"),
        Golden("pacific-held-space", macros(.75f, .1f, 0f, 0f, 1f, 1f), 84, .6f, Int.MAX_VALUE, 12f, 1_058_400,
            "14b85d32d4136a4cf290e6f4e2f4c03a9cb2e534990a285c5ee3089d67d0c5b4"),
        Golden("southern-deep-storm", macros(1f, .9f, 1f, 1f, .55f, 0f), 36, 1f, Int.MIN_VALUE, 8f, 705_600,
            "f4f806148a2041bd6bd461f62abb5976bed67a56de721054e15dd5a64aeca19d"),
        Golden("fractional-crossing", macros(.37f, .73f, .61f, .8f, .55f, .2f), 55, .63f, -7_104, 3.5f, 308_700,
            "64790b389c4163b99f79ea0ca69a50dccb22f1ebed4a0122dda36175a9ab1365"),
        Golden("short-gesture", macros(.5f, .5f, .45f, .55f, 0f, .45f), 60, 1f, 0, .25f, 22_050,
            "4dc4b03c46b79630c5665765704068fc465edef592bb40e487e07f95a15b966d"),
    )

    private fun sha256(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (sample in samples) {
            val bits = sample.toRawBits()
            for (shift in 0 until 32 step 8) digest.update((bits ushr shift).toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun `model one reproduces original sample bits across coast gesture and seed edge cases`() {
        for (g in goldens) {
            val snip = Osprey.render(OspreyVoice.SWELL, g.macros, g.midi, g.velocity, g.seed,
                g.seconds, modelVersion = 1)
            assertEquals(2, snip.channels)
            assertEquals(44_100, snip.sampleRate)
            assertEquals(g.samples, snip.samples.size, g.name)
            assertEquals(g.sha256, sha256(snip.samples), "${g.name}: published model-1 audio changed")
            val patch = OspreyPatch(g.name, OspreyVoice.SWELL, g.macros, g.midi, g.velocity,
                g.seed, g.seconds, modelVersion = 1)
            assertContentEquals(snip.samples, Patches.fromJsonText(patch.toJsonText()).render().samples,
                "${g.name}: restored legacy patch bypassed its model renderer")
        }
    }

    @Test
    fun `unversioned old recipes retain model one and its original empty map defaults`() {
        val old = OspreyPatch.fromJsonText(
            """{"engine":"OSPREY","version":1,"name":"Old Sea","voice":"SWELL","macros":{}}""")
        assertEquals(1, old.modelVersion)
        assertEquals(goldens.first().sha256, sha256(old.render().samples))
    }
}
