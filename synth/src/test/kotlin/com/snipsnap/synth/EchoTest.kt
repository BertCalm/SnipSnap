package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ECHO's HEAD macro (docs/superpowers/specs/2026-10-22-delay-head-design.md).
 *
 * The first test pins ECHO as it shipped before HEAD existed: SHA-256 hashes of its output, recorded on the base and
 * never re-recorded. Every setting is a literal, so a later change to a recipe, to Siren, or to the macro list (whose
 * scramble gains a fifth draw) cannot move what the pin compares.
 */
class EchoTest {

    private val kick = Thump.render(ThumpVoice.KICK)
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val choir = Vox.render(VoxVoice.CHOIR)

    private val sources = linkedMapOf("kick" to kick, "snare" to snare, "choir" to choir)

    /** Shipped settings, as literals: the bank's `slapback`, SIREN's `LANDING_ECHO`, ECHO's defaults, and three draws made once with Random(0..2). */
    private val settings: Map<String, Map<String, Float>> = linkedMapOf(
        "slapback" to mapOf("TIME" to 0.25f, "REPEAT" to 0.3f, "TONE" to 0.4f, "MIX" to 0.5f),
        "landing" to mapOf("TIME" to 0.45f, "REPEAT" to 0.55f, "TONE" to 0.5f, "MIX" to 0.45f),
        "defaults" to emptyMap(),
        "random0" to mapOf("TIME" to 0.54963315f, "REPEAT" to 0.32810485f, "TONE" to 0.84885544f, "MIX" to 0.6613528f),
        "random1" to mapOf("TIME" to 0.13972723f, "REPEAT" to 0.6433261f, "TONE" to 0.87724733f, "MIX" to 0.62778014f),
        "random2" to mapOf("TIME" to 0.84498405f, "REPEAT" to 0.28444004f, "TONE" to 0.91098607f, "MIX" to 0.0035728216f),
    )

    /** Recorded on the base before HEAD existed (plan Task 1). Never re-record: a mismatch means HEAD 0 is no longer ECHO. */
    private val PINS: Map<String, String> = mapOf(
        "kick/slapback" to "979160c2327daedc29f8cc69a2a991bfd3e63ea3b9f0d88c0bc0f0675118b44e",
        "kick/landing" to "beeec4a2b1ccd365d06eade56dd90135cc4e6ab04c19a1980a64f04a6be9fce5",
        "kick/defaults" to "290689c66d2351602b7e018b0ac2e399d1baf0adf6459fbece0c4b789ba172e0",
        "kick/random0" to "58b05a9f184bb13bd4bb4c78170767bba9fd928838070dc214354ba4ad558a6d",
        "kick/random1" to "664acb2867d39193b8c781d5c1b91e18e56f2acd6bc81b755c735c22dc14c49b",
        "kick/random2" to "7db263c9eaef8e2ea3c64abaf7241e51571e04e63ed7d53d27c010ca478672d8",
        "snare/slapback" to "df17bd11a82b532a725acebc0cadea825d7555570735ae831f980aecd50d23a2",
        "snare/landing" to "7b617a491ff491f8a2ea1ecccdf0fdefcfded23ae8c7e6f54f5e67e7b2cc7a42",
        "snare/defaults" to "23b98ce5e351f75040869a72066d64b263f96d440716bfea5aa4785b1e08755d",
        "snare/random0" to "5d2e7a8e1c700967c6c956ba1b4e620a4af5c7cd191ece01f97ff2143705a078",
        "snare/random1" to "620a79343cf98d4be84425c196ed562dddc1ab232186fb2b870b0dc8d4656ca0",
        "snare/random2" to "368e2b4f24c091248486cd640f710a186a2558a550d8ca8467c260dffccba975",
        "choir/slapback" to "4179f4089f14b3a3588fc7b0777f58bd102ad9fcc3804db16c5771cd8dc6dcb5",
        "choir/landing" to "1369ed8d04a9220dc10e987e1938b0f36fcc50e0254fa4fcd6966d3f4a6cca56",
        "choir/defaults" to "4b4f4b734faa6ac239688dc6405d9a39e1ce1b25a1f5f81dba0946e1e9ce2692",
        "choir/random0" to "26027182fed16bb37bb596c7c92c6e415c3e0f5adae0ca34c661a3ae22e8b7cb",
        "choir/random1" to "ca5ca6a1392622124de6cde3909d1e0359036017da89897a9a77c58356ea37ec",
        "choir/random2" to "1c9a6551e31d9a3a6e9e3cc40d830c87d50757c5c33edc12b3bd1774e9e4bdf4",
    )

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun `HEAD 0 is ECHO as it shipped, byte for byte - absent, zero, under the off point and at it`() {
        assertEquals(18, PINS.size, "18 pins: three sources by six settings")
        for ((src, snip) in sources) for ((set, m) in settings) {
            val want = PINS.getValue("$src/$set")
            assertEquals(want, hash(Echo.process(snip, m).samples), "$src/$set: HEAD absent is no longer the shipped ECHO")
            // 0.001f is Echo.HEAD_OFF; Task 2 swaps the constant in once it exists.
            for (off in listOf(0f, 0.0005f, 0.001f)) {
                assertEquals(want, hash(Echo.process(snip, m + ("HEAD" to off)).samples), "$src/$set at HEAD $off")
            }
        }
    }
}
