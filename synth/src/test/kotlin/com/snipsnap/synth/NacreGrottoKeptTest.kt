package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round nacre-grotto-1 (15 Nov 2026): the owner kept all ten GROTTO clips, D1 to D10, with no notes. These are the
 * renders behind them, at seed 7, pinned by SHA-256 of their float samples. Each clip is exactly these renders (a
 * finite note, a held note's key and loop, or a loop repeated) placed at their onsets, eased out over 60 ms at a held
 * segment's end, mixed and written as 16-bit; a git-excluded scratch test rebuilt all ten from them and matched the desk
 * WAVs byte for byte.
 *
 * GROTTO's four factory presets are four of these renders, so every preset is a sound the owner heard.
 */
class NacreGrottoKeptTest {
    private fun hash(samples: FloatArray): String {
        val b = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putFloat(s)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** The desk's 4 s bow: HOLD `(4 - 2.5) / (8 - 2.5) * .98`, the finite clips' length. */
    private val bow4 = NacreGrotto.holdFor(4f)
    private val held = mapOf("HOLD" to 1f)

    @Test
    fun `the 4 s bow is the HOLD the desk used`() {
        assertEquals((4f - NacreGrotto.BOW_SECONDS_LOW) / (NacreGrotto.BOW_SECONDS_HIGH - NacreGrotto.BOW_SECONDS_LOW) * .98f, bow4)
        assertEquals(.26727274f, bow4)
        assertEquals(4f, NacreGrotto.bowSeconds(bow4), 1e-5f)
    }

    @Test
    fun `the finite notes behind D1, D2, D3, D8 and D10 are unchanged`() {
        val kept = listOf(
            // D1: C2 at the default controls, a 4 s bow (also D2's middle stroke, TOUCH .30).
            Triple(36, .8f, mapOf("HOLD" to bow4)) to "94d7818ead98d27494259a9e0932f2f101bb44c993e4b10b53d6780476ae915a",
            // D2: the light and firm bows.
            Triple(36, .8f, mapOf("TOUCH" to .15f, "HOLD" to bow4)) to "1b30cfa0159a3e1518e2327d025624f25fc3e8d5d533417b7d85d6582c6331fa",
            Triple(36, .8f, mapOf("TOUCH" to .70f, "HOLD" to bow4)) to "0b376b7ff6cf96a8353d85e1ea912d13d2bbc71bbd390435b8de882901b2db9c",
            // D3: C3 and C4.
            Triple(48, .8f, mapOf("HOLD" to bow4)) to "8b2847f4f463b33023b47dc5d27ffc2c8a5c23f47a1d4d9ba641ad6f4c4342b4",
            Triple(60, .8f, mapOf("HOLD" to bow4)) to "68bb64a463395363bf6facbdffda6fb8eec5c76e9067631c74d8a9785d50c831",
            // D8: soft, medium and hard.
            Triple(36, .35f, mapOf("HOLD" to bow4)) to "b27706da614ac7a825f7a84ff13cca57f6d6c2722ea9fc849d46c560c0c49e7d",
            Triple(36, .65f, mapOf("HOLD" to bow4)) to "a8d6995da5d513e38180dfc6f2b66e4b5988235051b7cc2db02a31a88d89c698",
            Triple(36, 1f, mapOf("HOLD" to bow4)) to "6765fe06b12909719a575d65407cb0e96093fc9491c8ac98605b549b57b6b576",
            // D10: the bass line, HOLD 0 (2.5 s bows).
            Triple(36, .8f, emptyMap<String, Float>()) to "c3556be162227db026ac57a5c2b0883adcfe19e4067eab567f7a89e11a36457f",
            Triple(41, .7f, emptyMap<String, Float>()) to "97607abb345e59c3d149a7268cb609d0506bdbaeca9a932970a0ba61e9e8371a",
            Triple(43, .85f, emptyMap<String, Float>()) to "5cd6a1cc72de75a1c1f58a667f11efa1d24fbdb8f7a66379eae92b894424fe64",
        )
        for ((recipe, expected) in kept) {
            val (midi, velocity, macros) = recipe
            assertEquals(expected, hash(NacreGrotto.render(macros, midi, velocity, 7L).samples), "GROTTO $midi $velocity $macros moved")
        }
        assertEquals("94d7818ead98d27494259a9e0932f2f101bb44c993e4b10b53d6780476ae915a",
            hash(NacreGrotto.render(mapOf("TOUCH" to .30f, "HOLD" to bow4), 36, .8f, 7L).samples), "D2's middle stroke is D1")
    }

    @Test
    fun `the held key and loops behind D4 to D7 and D9 are unchanged`() {
        // D4: the held C2 from its first stroke (the key, the loop marked at frame 328017), then its loop; D5 is that
        // loop eight times; D6's DUET .15 is the same loop, and D9 plays it beside ARCO and OSTERIA.
        val c2 = NacreGrotto.played(held, 36, .8f, 7L)
        assertEquals(328017, c2.loopStart)
        assertEquals(163842, c2.loopFrames)
        assertEquals("a85dbd1ffbfe93bd5f7676f7cb101f6024d3f66f4a2f4aca2c682f6982603c07",
            hash(c2.native.copyOf(c2.loopStart + c2.loopFrames)), "the held C2's key moved")
        val (key, start) = NacreGrotto.renderKey(emptyMap(), 36, .8f, 7L)
        assertEquals(c2.loopStart, start)
        assertEquals(hash(c2.native.copyOf(c2.loopStart + c2.loopFrames)), hash(key))
        val loops = mapOf(
            emptyMap<String, Float>() to "68e68557de7dd0e3f933d031529a46d0349c5d18359dbe41f5ee1cd2cd9755d1",
            // D6: no glints and the most glints.
            mapOf("DUET" to 0f) to "38dd63db9a000d154eff3943e06a2436718e2325b3664bb0415017324b716c5d",
            mapOf("DUET" to .15f) to "68e68557de7dd0e3f933d031529a46d0349c5d18359dbe41f5ee1cd2cd9755d1",
            mapOf("DUET" to .55f) to "0e576538a78e2e10e7317d06b941b2d6f37f222941ee8386b793ebb560efbe31",
            // D7: close coral, far seaweed, a little and a lot of bloom.
            mapOf("VENUE" to 0f) to "94c9492640b76453831417fedd5a09f43744c9f2c841bb536ca77a83ec699bbf",
            mapOf("VENUE" to 1f) to "67f38672f133af95467aa94f62ddbb9722af8f1b2cb5ef850062be65c52ad524",
            mapOf("BLOOM" to .15f) to "546dce4f7e4160e6c4623fcd811b2f55f54808cb936ae53e5d7198803eb0f337",
            mapOf("BLOOM" to .70f) to "d26f798cb4cfb9813716414b71d716ab41b754cc83a3692ff37cf99abbf8254e",
        )
        assertEquals(loops.getValue(emptyMap()), hash(c2.probe.snip.samples))
        for ((macros, expected) in loops) {
            assertEquals(expected, hash(NacreGrotto.render(macros + held, 36, .8f, 7L).samples), "held C2 $macros moved")
        }
        // D9's second pair: GROTTO held at D3.
        assertEquals("fc13228edb50e5ad35137bc1c2c5a6a9645827c1e485a63e2743ff402602c7be",
            hash(NacreGrotto.render(held, 50, .8f, 7L).samples), "held D3 moved")
    }

    @Test
    fun `D9's references are the accepted ARCO and OSTERIA sounds`() {
        assertEquals("783e8e54df5466f334e1a3d3302f7e810ff66b225732a6d809affe43b4e248c9",
            hash(Arco.render(ArcoVoice.CELLO, mapOf("TUNE" to 0f, "HOLD" to 1f)).samples), "ARCO CELLO's held loop moved")
        assertEquals("5e67be4d76b7c4345f016be835e31f94cd0eba8386bec4a2b17b40d8ff0114d5",
            hash(Arco.render(ArcoVoice.SARANGI, mapOf("TUNE" to 0f, "HOLD" to 1f)).samples), "ARCO SARANGI's held loop moved")
        assertEquals("e506fdd5298b049414da1a875798adf9ffeb36d0511808d620a7052b38355645",
            hash(Osteria.render(OsteriaVoice.BOWED, mapOf("HOLD" to 1f), 36, .8f).samples), "OSTERIA BOWED moved")
    }

    @Test
    fun `GROTTO's four presets are kept renders and replay exactly`() {
        // Each preset and the clip it came from: D1's default, D2's light bow, D6's most glints, D7's far seaweed.
        val kept = linkedMapOf(
            "Grotto Bow" to "94d7818ead98d27494259a9e0932f2f101bb44c993e4b10b53d6780476ae915a",
            "Soft Grotto" to "1b30cfa0159a3e1518e2327d025624f25fc3e8d5d533417b7d85d6582c6331fa",
            "Grotto Glints" to "0e576538a78e2e10e7317d06b941b2d6f37f222941ee8386b793ebb560efbe31",
            "Grotto Kelp" to "67f38672f133af95467aa94f62ddbb9722af8f1b2cb5ef850062be65c52ad524",
        )
        val presets = NacrePresets.forVoice(NacreVoice.GROTTO)
        assertEquals(kept.keys.toList(), presets.map { it.name })
        for (p in presets) {
            assertEquals(kept.getValue(p.name), hash(p.render().samples), "${p.name} is not the kept render")
            assertEquals(NacreGrotto.MODEL, p.model)
            assertEquals(36, p.midi)
            assertEquals(.8f, p.velocity)
            assertEquals(7L, p.seed)
            assertTrue(p.name.length <= 14, "${p.name} is longer than 14 letters")
            val replayed = NacrePatch.fromJsonText(p.toJsonText())
            assertEquals(p, replayed)
            assertEquals(kept.getValue(p.name), hash(replayed.render().samples), "${p.name} does not replay from JSON")
            assertEquals(p, Presets.byName("NACRE", "GROTTO", p.name))
            assertEquals(p, PadRecipe.fromJsonText(PadRecipe(p).toJsonText()).patch)
        }
        // Two finite 4 s bows and two held loops: the held ones save HOLD 1 and are LOOP pads.
        val (loops, finite) = presets.partition { Nacre.isLoop(it.voice, it.macros) }
        assertEquals(listOf("Grotto Glints", "Grotto Kelp"), loops.map { it.name })
        assertTrue(loops.all { it.macros["HOLD"] == 1f && Nacre.drumClassFor(it.voice, it.macros) == com.snipsnap.audio.DrumClass.LOOP })
        assertTrue(finite.all { it.macros["HOLD"] == bow4 })
        // Only the held voices (GROTTO and CONCH) save a HOLD; every finite voice's preset keeps it at 0.
        assertFalse(NacrePresets.all().filter { it.voice != NacreVoice.GROTTO && it.voice != NacreVoice.CONCH }
            .any { (it.macros["HOLD"] ?: 0f) != 0f })
    }
}
