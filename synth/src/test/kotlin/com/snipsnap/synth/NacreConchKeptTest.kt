package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round nacre-conch-1 (16 Nov 2026): the owner kept all eleven CONCH clips, O1 to O11, with no notes. These are the
 * renders behind them, at seed 7, pinned by SHA-256 of their float samples. Each clip is exactly these renders (a
 * finite note, a held note's key and loop, or a loop repeated) placed at their onsets, eased out over 60 ms at a held
 * segment's end, mixed and written as 16-bit; a git-excluded scratch test rebuilt all eleven from them and matched the
 * desk WAVs byte for byte.
 *
 * CONCH's four factory presets are four of these renders, so every preset is a sound the owner heard.
 */
class NacreConchKeptTest {
    private fun hash(samples: FloatArray): String {
        val b = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putFloat(s)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** The desk's 4 s breath: HOLD `(4 - 2.5) / (8 - 2.5) * .98`, the finite clips' length. */
    private val breath4 = NacreConch.holdFor(4f)
    private val held = mapOf("HOLD" to 1f)

    @Test
    fun `the 4 s breath is the HOLD the desk used`() {
        assertEquals((4f - NacreConch.BREATH_SECONDS_LOW) / (NacreConch.BREATH_SECONDS_HIGH - NacreConch.BREATH_SECONDS_LOW) * .98f, breath4)
        assertEquals(.26727274f, breath4)
        assertEquals(4f, NacreConch.breathSeconds(breath4), 1e-5f)
    }

    @Test
    fun `the finite notes behind O1, O2, O6, O9 and O11 are unchanged`() {
        val kept = listOf(
            // O1: C3 at the default controls, a 4 s breath (also O2's middle breath, TOUCH .30).
            Triple(48, .8f, mapOf("HOLD" to breath4)) to "051884032d996d4ceeed7898cbbeedeaab89d0cec55efa37b8afae3b5cece070",
            // O2: the light and firm breaths.
            Triple(48, .8f, mapOf("TOUCH" to .15f, "HOLD" to breath4)) to "2da27f381b1f5fe031632e23c6314da508d2750b97ca120106a525529ceb0e2e",
            Triple(48, .8f, mapOf("TOUCH" to .70f, "HOLD" to breath4)) to "c36de16c119fb99a24e97b25601152e6db987aa346fadfd3b76359be007c9caa",
            // O6: C2, C4 and C5.
            Triple(36, .8f, mapOf("HOLD" to breath4)) to "7f226494e6adc8aebec5bd3a276921b662281b92573b4cbac7eea713b9effc24",
            Triple(60, .8f, mapOf("HOLD" to breath4)) to "91f74c14648b410d3e7b0b6bf1c04a61983bb9e4fa9edf71d2cfab1169b7edc3",
            Triple(72, .8f, mapOf("HOLD" to breath4)) to "a985f02d17138e708ef1ff9f65997e25dea84b5c19d523654d9b9674cacd535b",
            // O9: soft, medium and hard.
            Triple(48, .35f, mapOf("HOLD" to breath4)) to "2c6364d865a0ef0629fa6a6dfc1afa3f4a1c3ceb470fed3be7e0023b7d98f5d1",
            Triple(48, .65f, mapOf("HOLD" to breath4)) to "a17cef3953fea8be322991a4146873c6bf0201c14f8305279fe7a84464c81f86",
            Triple(48, 1f, mapOf("HOLD" to breath4)) to "2f0466ceb8077b35c1943ee5681b78d5dcb9c4d1c998181115e8bcf221ebb8eb",
            // O11: the phrase, HOLD 0 (2.5 s breaths).
            Triple(48, .75f, emptyMap<String, Float>()) to "8f80ff197ada7fdf56964790f6d0c27ac84a6222d48b7bfd133c720cf5c768f9",
            Triple(52, .7f, emptyMap<String, Float>()) to "dcb2f196bf8a21b1a6cb09b1824e79863a050d13657315cb173cf13ca69acb57",
            Triple(55, .8f, emptyMap<String, Float>()) to "e6281a19245ad5a1bf24998e562538dccf93df0a44477597946a6ddee91195d2",
            Triple(60, .9f, emptyMap<String, Float>()) to "1b0e000e44c8c2ba849a1ced49c25fba6c4c9c4c98f107a817d54fa172db001a",
            Triple(55, .65f, emptyMap<String, Float>()) to "a0f9d2d10b23d75fd814877bc045ccfd0481812ce4b608e24180c51e42b56711",
        )
        for ((recipe, expected) in kept) {
            val (midi, velocity, macros) = recipe
            assertEquals(expected, hash(NacreConch.render(macros, midi, velocity, 7L).samples), "CONCH $midi $velocity $macros moved")
        }
        assertEquals("051884032d996d4ceeed7898cbbeedeaab89d0cec55efa37b8afae3b5cece070",
            hash(NacreConch.render(mapOf("TOUCH" to .30f, "HOLD" to breath4), 48, .8f, 7L).samples), "O2's middle breath is O1")
    }

    @Test
    fun `the held keys and loops behind O3 to O5, O7, O8 and O10 are unchanged`() {
        // O4: the held C3 from its first breath (the key, the loop marked at frame 306073), then its loop; O5 is that
        // loop eight times; O3's DUET .15 is the same key and loop, and O10 plays the loop beside its references.
        val c3 = NacreConch.played(held, 48, .8f, 7L)
        assertEquals(306073, c3.loopStart)
        assertEquals(163842, c3.loopFrames)
        assertEquals("b04c1394c418b28a9ec3eba4f5b8d8bd0c11a98d604834f7438d1a836a6bdb6d",
            hash(c3.native.copyOf(c3.loopStart + c3.loopFrames)), "the held C3's key moved")
        val (key, start) = NacreConch.renderKey(emptyMap(), 48, .8f, 7L)
        assertEquals(c3.loopStart, start)
        assertEquals(hash(c3.native.copyOf(c3.loopStart + c3.loopFrames)), hash(key))
        assertEquals("d20306a49ab4656cf986246225b0bb9a4b9095c711495a42a8cc36e90e5f349e", hash(c3.probe.snip.samples))

        // O3: each DUET from its first breath (key) and its loop; the lean at .80 is its own loop.
        val duets = mapOf(
            .15f to Triple(306073, "b04c1394c418b28a9ec3eba4f5b8d8bd0c11a98d604834f7438d1a836a6bdb6d",
                "d20306a49ab4656cf986246225b0bb9a4b9095c711495a42a8cc36e90e5f349e"),
            .50f to Triple(317799, "3220606a850cfd0d57d84ea6567746168f0837316a3cc8d219783e18568ce993",
                "bcd3829c7499dbbe04bfe6754a53228e25c1427ae11bff5764ededaa44b3b9a6"),
            .80f to Triple(316987, "08bd61f19efbb69e88e3be584debd9d848b9846520d6701e52c22e377f728a9a",
                "6a2f1345bcb60c7eaf24265f1e225eaf210f2852a973052edfd891a1ea8aab77"),
        )
        for ((duet, expected) in duets) {
            val (loopStart, keyHash, loopHash) = expected
            val r = NacreConch.played(mapOf("DUET" to duet) + held, 48, .8f, 7L)
            assertEquals(loopStart, r.loopStart, "DUET $duet loop start")
            assertEquals(keyHash, hash(r.native.copyOf(r.loopStart + r.loopFrames)), "held C3 DUET $duet key moved")
            assertEquals(loopHash, hash(r.probe.snip.samples), "held C3 DUET $duet loop moved")
            assertEquals(loopHash, hash(NacreConch.render(mapOf("DUET" to duet) + held, 48, .8f, 7L).samples))
        }

        // O8: close coral, far seaweed, a little and a lot of bloom.
        val loops = mapOf(
            mapOf("VENUE" to 0f) to "feaafcb9ad950edb6a924b34eeef815dd399bab1c9168328601d02c3e3743934",
            mapOf("VENUE" to 1f) to "611fe3afcb62e73593e2639a144853faba79fd0e6a5407daeec09aae5830b350",
            mapOf("BLOOM" to .15f) to "072210f0ca34b55d16d0ae37129e3cc9e0f1395eb679bb998434cd008303a05e",
            mapOf("BLOOM" to .70f) to "9ad5659b9bebcf730e1fe7c2f61e48f481ea688d94f0a8fce03864422b2b343c",
        )
        for ((macros, expected) in loops) {
            assertEquals(expected, hash(NacreConch.render(macros + held, 48, .8f, 7L).samples), "held C3 $macros moved")
        }

        // O7: held C2 and held C4 (O10's C4 is the same loop).
        val c2 = NacreConch.played(held, 36, .8f, 7L)
        assertEquals(163842, c2.loopFrames)
        assertEquals("99895c630c857728ffde666031b245f2b1b736e6f632798c3924815335e5ef03", hash(c2.probe.snip.samples), "held C2 moved")
        val c4 = NacreConch.played(held, 60, .8f, 7L)
        assertEquals(163673, c4.loopFrames)
        assertEquals("d4cf28c5d5198c0268da055104e509551741d00388e132763547580a576a4455", hash(c4.probe.snip.samples), "held C4 moved")
    }

    @Test
    fun `O10's references are the accepted BORE, EMBOUCHURE, OBBLIGATO and BUBBLE sounds`() {
        // BORE FLUTE's is the held render kept in round fix-bore-1 (20 Nov 2026), after the held-note player humanized
        // it; O10's clip was heard before that, with the earlier, steadier hold. The others are the ones O10 played.
        assertEquals("f37f960d0f2ae4d7cc693c763c75e7b8b6f679de54eb0a146b5f7b66dd21975d",
            hash(Bore.render(BoreVoice.FLUTE, mapOf("TUNE" to 0f, "HOLD" to 1f)).samples), "BORE FLUTE's held loop moved")
        assertEquals("3046967ce6cd0ea595540f0ae785748cba54e6c516f898ca69b3462b433b6d41",
            hash(Embouchure.render(EmbouchureVoice.WHISTLE, mapOf("HOLD" to 1f), 60, .8f).samples), "EMBOUCHURE WHISTLE moved")
        assertEquals("69fdb7d723353a95c0cb8a8a772a084cf8387497c6be5731bf81d87320b8cebd",
            hash(Obbligato.render(emptyMap(), 60, .8f).samples), "OBBLIGATO C4 moved")
        assertEquals("639181b073efc741ca0316b1c0ff25e089124d324da7bceecae615a76c84d087",
            hash(Obbligato.render(emptyMap(), 48, .8f).samples), "OBBLIGATO C3 moved")
        // BUBBLE at C4, velocity .8: also U2's kept note (nacre-voices-2).
        assertEquals("257ae1a10d2b52418297effe964ae52d9ea5b8edf536e29521e5ffc387b85a5a",
            hash(Nacre.render(NacreVoice.BUBBLE, emptyMap(), 60, .8f, 7L).samples), "BUBBLE C4 moved")
    }

    @Test
    fun `CONCH's four presets are kept renders and replay exactly`() {
        // Each preset and the clip it came from: O1's default, O2's light breath, O3's slow lean, O8's most bloom.
        val kept = linkedMapOf(
            "Conch Breath" to "051884032d996d4ceeed7898cbbeedeaab89d0cec55efa37b8afae3b5cece070",
            "Soft Conch" to "2da27f381b1f5fe031632e23c6314da508d2750b97ca120106a525529ceb0e2e",
            "Conch Lean" to "6a2f1345bcb60c7eaf24265f1e225eaf210f2852a973052edfd891a1ea8aab77",
            "Conch Bloom" to "9ad5659b9bebcf730e1fe7c2f61e48f481ea688d94f0a8fce03864422b2b343c",
        )
        val presets = NacrePresets.forVoice(NacreVoice.CONCH)
        assertEquals(kept.keys.toList(), presets.map { it.name })
        for (p in presets) {
            assertEquals(kept.getValue(p.name), hash(p.render().samples), "${p.name} is not the kept render")
            assertEquals(NacreConch.MODEL, p.model)
            assertEquals(48, p.midi)
            assertEquals(.8f, p.velocity)
            assertEquals(7L, p.seed)
            assertTrue(p.name.length <= 14, "${p.name} is longer than 14 letters")
            val replayed = NacrePatch.fromJsonText(p.toJsonText())
            assertEquals(p, replayed)
            assertEquals(kept.getValue(p.name), hash(replayed.render().samples), "${p.name} does not replay from JSON")
            assertEquals(p, Presets.byName("NACRE", "CONCH", p.name))
            assertEquals(p, PadRecipe.fromJsonText(PadRecipe(p).toJsonText()).patch)
        }
        // Two finite 4 s breaths and two held loops: the held ones save HOLD 1 and are LOOP pads.
        val (loops, finite) = presets.partition { Nacre.isLoop(it.voice, it.macros) }
        assertEquals(listOf("Conch Lean", "Conch Bloom"), loops.map { it.name })
        assertTrue(loops.all { it.macros["HOLD"] == 1f && Nacre.drumClassFor(it.voice, it.macros) == com.snipsnap.audio.DrumClass.LOOP })
        assertTrue(finite.all { it.macros["HOLD"] == breath4 })
        // No preset label is shared with another NACRE voice: the picker finds each preset by its name.
        val names = NacrePresets.all().map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate NACRE preset names")
        assertFalse(NacrePresets.all().filter { it.voice != NacreVoice.CONCH }.any { it.name.contains("Conch") })
    }
}
