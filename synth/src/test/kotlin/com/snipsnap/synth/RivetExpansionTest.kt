package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlin.test.*

class RivetExpansionTest {
    @Test fun `all original corners at two strikes retain the approved model one PCM`() {
        val frozen = mapOf(
            (RivetVoice.KICK to .25f) to "3c0a60a006153b4f14859d77a8cf100d1ce733aa743cb342d22b78be10d5228c",
            (RivetVoice.KICK to 1f) to "a5092f4be1d6eeb773f5c65f6f89a050f99e29a5b377c735b7593cdab6948019",
            (RivetVoice.SNARE to .25f) to "cc7887bd8a5b7ad1c6dd8d9a0e9655dc5a7b0a258ad9e4d0201a2d34a67bcc51",
            (RivetVoice.SNARE to 1f) to "92c71a29e7c1a9aa09fb8fdcfded1685c1c0da9da40be9173cad928f830c31f9",
        )
        val keys = listOf("TUNE", "STRIKE", "METAL", "DRIVE", "SIZE", "WALLS")
        for ((key, expected) in frozen) {
            val (voice, velocity) = key
            val digest = MessageDigest.getInstance("SHA-256")
            for (bits in 0 until 64) {
                val m = keys.mapIndexed { i, k -> k to if ((bits and (1 shl i)) == 0) 0f else 1f }.toMap()
                val x = Rivet.render(voice, m, modelVersion = 1, velocity = velocity).samples
                assertContentEquals(x, Rivet.render(voice, m, modelVersion = 2, velocity = velocity).samples)
                val buffer = ByteBuffer.allocate(4 + 4 * x.size).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(x.size); x.forEach { buffer.putFloat(it) }; digest.update(buffer.array())
            }
            assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it) }, "$voice velocity=$velocity")
        }
    }

    @Test fun `new voices cannot be smuggled into model one recipes`() {
        for (voice in listOf(RivetVoice.TOM, RivetVoice.METAL, RivetVoice.HAT_CLOSED, RivetVoice.HAT_OPEN)) {
            val error = assertFailsWith<IllegalArgumentException> { Rivet.render(voice, modelVersion = 1) }
            assertTrue(error.message.orEmpty().contains("unsupported RIVET model version"))
        }
    }

    @Test fun `hat closure retains a related attack but removes the extended metal response`() {
        val closed = Rivet.inspect(RivetVoice.HAT_CLOSED).close.samples
        val open = Rivet.inspect(RivetVoice.HAT_OPEN).close.samples
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        for (i in 0 until 441) { xy += closed[i] * open[i]; xx += closed[i] * closed[i]; yy += open[i] * open[i] }
        assertTrue(xy / sqrt(xx * yy) > .8, "hat attacks lost their shared metal identity")
        fun tail(x: FloatArray) = (4410 until x.size).sumOf { x[it].toDouble() * x[it] }
        assertTrue(tail(open) > tail(closed) * 5, "opening the hats did not reveal a longer response")
    }

    @Test fun `sixteen native three-zone pads retain recipes and reserve choking for the hats`() {
        val patches = RivetKits.patches(.55f)
        val pads = RivetKits.build(.55f)
        assertEquals(16, pads.size)
        assertEquals(RivetVoice.entries.toSet(), patches.map { it.voice }.toSet())
        assertEquals(16, patches.map { it.name }.toSet().size)
        for ((i, pad) in pads.withIndex()) {
            assertEquals(.55f, patches[i].macros["SIZE"])
            assertEquals(2, pad.softVariants.size)
            val hats = patches[i].voice in setOf(RivetVoice.HAT_CLOSED, RivetVoice.HAT_OPEN)
            assertEquals(if (hats) 1 else 0, pad.muteGroup)
            val recipe = PadRecipe.fromJsonValue(pad.recipe!!)
            assertContentEquals(pad.snip.samples, recipe.render().samples)
            assertTrue(pad.softVariants.all { it.samples.all { v -> v.isFinite() } && it.peak() > .1f })
        }
    }
}
