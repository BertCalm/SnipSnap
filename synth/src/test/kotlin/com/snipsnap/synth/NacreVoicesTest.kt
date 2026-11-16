package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * HARMONICS (saved model 5), SHELL (saved model 6) and BUBBLE (saved model 7), NACRE's new sources.
 * The first test pins PEARL as rendered on the untouched base (0d57e9e80), so adding the sources
 * cannot have moved a single PEARL sample.
 */
class NacreVoicesTest {
    private val M = NacreVoicesMeasure
    private val range = listOf(36, 48, 60, 72)

    private fun hash(samples: FloatArray): String {
        val b = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putFloat(s)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    private fun render(source: NacreSource, midi: Int = 48, velocity: Float = 1f,
                       macros: Map<String, Float> = emptyMap(), seed: Long = 7L) =
        NacreVoices.render(source, macros, midi, velocity, seed).samples

    private fun pearl(midi: Int = 48, velocity: Float = 1f, macros: Map<String, Float> = emptyMap()) =
        NacreModels.render(NacreVoice.PEARL, macros, midi, velocity, 7L, 3).samples

    @Test
    fun `every PEARL model and preset renders byte for byte as on the base`() {
        val base = mapOf(
            "model1-m48-v1.0" to "fd0443d1a09e5cb166d9474853b637802d8404e1f0f5074884d12cd2987ae4df",
            "model1-m60-v0.65" to "4bec7aab45266f09f7a567ac5ec8f452b3a51bd1a4f8ce67b04cd43c34215e29",
            "model1-m36-v0.3" to "c5f25164c4fa23fc75a428fa6594c27098711f16bc2c2f4414fd2b2f78d0c67e",
            "model2-m48-v1.0" to "a266149e24ca1efe25f366a5844e2acca82561f20f98bed2dc267f61eee2615e",
            "model2-m60-v0.65" to "f3b7a1e6e7816771d21dfa0f78a746770c3af78037924a44345f54ed08895e80",
            "model2-m36-v0.3" to "4bcfab0fcc3ffc0f847ab0770e4ff7395b4678c1c6c2d1ab62fa5b411f551b2b",
            "model3-m48-v1.0" to "4e5ea42d80554b323c2c684bb949aa3f79c509814c65f4f79a4de5a9f7cb8cbd",
            "model3-m60-v0.65" to "521417c122cdec655642fd18250245b2349c55eb4f357e30a536c3f56596ddc0",
            "model3-m36-v0.3" to "05fd0c42f6989a8e52816316e2a662dd24b5a3ff024a4871a2272e3eb6e6f0c9",
            "model4-m48-v1.0" to "4e5ea42d80554b323c2c684bb949aa3f79c509814c65f4f79a4de5a9f7cb8cbd",
            "model4-m60-v0.65" to "521417c122cdec655642fd18250245b2349c55eb4f357e30a536c3f56596ddc0",
            "model4-m36-v0.3" to "05fd0c42f6989a8e52816316e2a662dd24b5a3ff024a4871a2272e3eb6e6f0c9",
        )
        for (model in 1..4) for ((midi, vel) in listOf(48 to 1f, 60 to .65f, 36 to .3f)) {
            val s = NacreModels.render(NacreVoice.PEARL, emptyMap(), midi, vel, Nacre.DEFAULT_SEED, model)
            assertEquals(base.getValue("model$model-m$midi-v$vel"), hash(s.samples), "PEARL model $model MIDI $midi moved")
        }
        val presets = mapOf(
            "Pearl Close" to "de822099817c5da3de3dc65c52b6f03e48fc5e9a3e7ca18233dad18804bf66b3",
            "Coral Reply" to "740e31c8790960fccdd5cf694eac2616424b1327d92e96682f9707a1e28f5be1",
            "Kelp Passage" to "11de6325b6a263f46cad5a15a54df3fba02d84a0553432d30e9c67e8e5daad3e",
            "Pearl Moderate Contact" to "65403955e4250f620f05a2b33ea56093a58e685743da3680001bd8ae38ccb018",
            "Pearl Strong Contact" to "48304c74c17949b3ea375803d2cec3bd11220f7edc93ed9ed167554ca01ef73c",
        )
        assertEquals(presets.keys, NacrePresets.forVoice(NacreVoice.PEARL).map { it.name }.toSet())
        for (p in NacrePresets.forVoice(NacreVoice.PEARL)) assertEquals(presets.getValue(p.name), hash(p.render().samples), "${p.name} moved")
        val moderate = NacrePresets.forVoice(NacreVoice.PEARL).first { it.model == 4 }
        assertEquals("969b33a8af8cbf87835e86e3001f8e082c12a022e5abf8a4bc10fb37cc10132d",
            hash(NacreModels.renderStruck(moderate.copy(velocity = .3f), NacreStrikeShape(.8, .5, .12)).samples))
    }

    /**
     * The owner kept HARMONICS and SHELL as heard in round nacre-voices-1 (H1 to H3, L1 to L3, all KEEP,
     * 15 Nov 2026). These are the 22 renders behind those six clips, at seed 7 and the default controls;
     * the clips are exactly these renders mixed at their onsets and written as 16-bit.
     */
    @Test
    fun `the renders the owner kept in nacre-voices-1 are unchanged`() {
        val kept = mapOf(
        "HARMONICS 36 0.85" to "c339fec282e7ad1fd982dbdb70950d75e1bc804ae9e764cc59ea79dea7fcfb11",
        "HARMONICS 43 0.6" to "0cd7f3a27ece0d0a23118ecb21b102801d6925460e5ad17789161ae6491cb254",
        "HARMONICS 48 0.65" to "a38bc1dc10c92c0dbb7a78d645363e79634cce22e146dcdb433f604030757a6d",
        "HARMONICS 48 0.75" to "8d9c8d6cadf2653d6e8925e99cfcad6c058d879fb2556b413482acc8f3007c2e",
        "HARMONICS 48 0.8" to "234666589bf68be718f666744282ad3d5f23901106bec822eecb966da2b3f782",
        "HARMONICS 48 1.0" to "4e1a00f71c0161fbdf7c3b249e7c0faf82ca204a6246d4fd1c9e1329ec3a18fc",
        "HARMONICS 55 0.55" to "1a96301d452c84c37621007fc007358086410189945d79c865621f74960660d8",
        "HARMONICS 60 0.8" to "b3747cc7ce2d96ef0704e894de3884025739e6494892818358bb1994c53a0337",
        "HARMONICS 67 0.6" to "6004cbca84db48d5740c1887ea275eb6661a1efa2767ef55c7c59e8e38b9f61a",
        "HARMONICS 72 0.9" to "72a8f21cacce06e0b9b4b38e8a0569d922fe9b634873d86dfb85e1448f8d92fa",
        "SHELL 36 0.85" to "d9eb9676437033fa90f061d540ae959ac485f7db04dfdaae5cbfadee06e1e1c1",
        "SHELL 43 0.6" to "8f47ff31fb468bd786c223dee077b6d91ef8060a45a4b472c285aaa078c15c99",
        "SHELL 48 0.3" to "9a12de30ae1d1b6fc9bb59057bd84edbafddd9e25b190b55732657f85b1cbb60",
        "SHELL 48 0.65" to "3cdd2b17c892e34f55944c614c084f4ae573ad87c70750fd8c95ea1efc0ddef4",
        "SHELL 48 0.75" to "4571ed1cef70158cc63cbc05a6b78ec1d1cd4c00e0ae1ffe37851be3c39e5d8c",
        "SHELL 48 0.8" to "1af741755a65ff81b8f0fcf94f23a6e87f1827c243e64176b82b5c199e010cdd",
        "SHELL 48 1.0" to "d2d7992fc59a6fdbee9d1a8dff72ba9111d4fb38d2f265644936fcf6e1ad8d46",
        "SHELL 55 0.55" to "f4aa1b7401b591654d098a70403194d143300d335a2ee5c1e28ddcd1cc34b5bd",
        "SHELL 60 0.8" to "1c10e9a2ac74d6e1d393c4e9b1a9cc7f81f71b29e2aa2812eb203de334ec2b00",
        "SHELL 67 0.6" to "ffadf55fb27abf6fde24964a260755c8c28ee8b6e2bae572e96da7cda234f352",
        "SHELL 72 0.9" to "5230a7b0e904fee47c9ddce68407d7a826976d5d3445810acabda59bb169c6a2",
"HARMONICS 48 0.3" to "41299939795c27301fb7fe92a5a5b34c1d10f2268274a328bd38e71189b7a810",
        )
        for ((key, expected) in kept) {
            val (name, midi, velocity) = key.split(" ")
            val source = NacreSource.valueOf(name)
            assertEquals(expected, hash(render(source, midi.toInt(), velocity.toFloat())), "$key moved")
        }
    }

    /**
     * Round nacre-voices-2 (15 Nov 2026), all KEEP, no notes. Q1 and Q2 are these eight presets at C3,
     * 3.5 s apart. U1 to U3 are BUBBLE at its default controls and seed 7 (soft, medium and hard C3; a
     * phrase from C2 to C5; and beside PEARL at C3 and C4), rendered here through a saved model-7
     * recipe. Each clip is exactly these renders mixed at their onsets and written as 16-bit.
     */
    @Test
    fun `the renders the owner kept in nacre-voices-2 are unchanged`() {
        val presets = mapOf(
            "Glass Harmonic" to "4e1a00f71c0161fbdf7c3b249e7c0faf82ca204a6246d4fd1c9e1329ec3a18fc",
            "Soft Harmonic" to "0a15a2d813f50be2ca536afa25807039fd42ffeeff783424ca6a5eb013809541",
            "Harmonic Kelp" to "8c7e4ba225e35c3d4f185a9b943bba4f48ff60e0f7b90764d37c4df3dfa569cd",
            "Harmonic Ring" to "44b930b16e6dd526955643313c5ba5051ba0ed1a41e10a562f025d22d60fdee3",
            "Shell Tap" to "d2d7992fc59a6fdbee9d1a8dff72ba9111d4fb38d2f265644936fcf6e1ad8d46",
            "Deep Shell" to "ab6aa409b448298a774697d48917d006767b33dddd915bfe2abb5c48ad7a33c9",
            "Shell Reef" to "47cc8d0acb2b7faf61667e3c3da71786fcef3a283da9c5aa13a78c3c6123cdc3",
            "Shell Bloom" to "5c83bd91181a85dcbac8eb8f4b4bed107a702cd1d9f8e11c383940387566b478",
        )
        val kept = NacrePresets.forVoice(NacreVoice.HARMONICS) + NacrePresets.forVoice(NacreVoice.SHELL)
        assertEquals(presets.keys.toList(), kept.map { it.name })
        for (p in kept) assertEquals(presets.getValue(p.name), hash(p.render().samples), "${p.name} moved")
        val bubble = mapOf(
            "48 0.3" to "e1071693b602eb67598d0b66b7de061df98d22806a9de79f5a9e943d5db29673",
            "48 0.65" to "fc54ff728e05b9222fb6932b6a273ce5c330ba8a73c357acd21961f37cee9657",
            "48 1.0" to "85752e096e6e5f026755f360f5de4b5fcd364623349706a560fba7aacbd80d6b",
            "36 0.85" to "7ffa9a9c074a723173ddcb1b9aae037d72f40e399ff74b3293e64bf6ab44f2ff",
            "43 0.6" to "4e5b3dd019cb90dcff1b73e3d99513869b6ca8b9b686a174220293d1758c5c36",
            "48 0.75" to "05675cd48f422f2c4b5ee3bd34404ee54fb9e51266076b9f7c71d510afd16180",
            "55 0.55" to "0a7f2beeb8b4e894ddad63c86ed33f0c72f9d970deb1eb4d185bf468aa1de899",
            "60 0.8" to "257ae1a10d2b52418297effe964ae52d9ea5b8edf536e29521e5ffc387b85a5a",
            "67 0.6" to "4ffb2417158f893256628f960a6746961ed669f9def924d102edfead77223083",
            "72 0.9" to "946cefb3e162ac9490277f28c58469cab348a83ae72835015f650e702a047616",
            "48 0.8" to "7393d4a0dc57cf84e31d11489799f89825e57687f887216e1e6cb3bab2c1c7de",
        )
        for ((key, expected) in bubble) {
            val (midi, velocity) = key.split(" ")
            val saved = NacrePatch("kept", NacreVoice.BUBBLE, emptyMap(), midi.toInt(), velocity.toFloat(), 7L)
            assertEquals(NacreVoices.BUBBLE_MODEL, saved.model)
            assertEquals(expected, hash(saved.render().samples), "BUBBLE $key moved")
            assertEquals(expected, hash(render(NacreSource.BUBBLE, midi.toInt(), velocity.toFloat())), "BUBBLE $key differs by entry point")
        }
        assertEquals("7df580affb2eb281014c7484c2744c591091982d873482cdf7924341a3934c3f", hash(pearl(48, .8f)), "PEARL C3 moved")
        assertEquals("3d7bd6ea34ccae95ad976e3140002aacc4b90a604f3861625a0ba5d9311649a4", hash(pearl(60, .8f)), "PEARL C4 moved")
    }

    /**
     * Round nacre-voices-3 (15 Nov 2026), KEEP, no notes. Q3 is BUBBLE's four presets at C3, 3.5 s apart: each
     * preset's render, summed at its onset with no trim (peak .94) and written as 16-bit. A git-excluded scratch test
     * rebuilt Q3 from these renders and matched the desk WAV byte for byte. Bubble Drop is also U1's hard C3.
     */
    @Test
    fun `the renders the owner kept in nacre-voices-3 are unchanged`() {
        val presets = mapOf(
            "Bubble Drop" to "85752e096e6e5f026755f360f5de4b5fcd364623349706a560fba7aacbd80d6b",
            "Soft Bubble" to "0d7e426aea204d251954b40d51426e167c2d2ac7a53a3b08db568ec7b325b5b2",
            "Bubble Stream" to "2413fb15089d6701a0d71eedacc27d54c3e8705d95f9266ed6a8f71048019a9f",
            "Bubble Burst" to "00fb93ed828e8b62c454df4af8d18f02a7c8ac01dbcffb27539308270d8a9414",
        )
        val kept = NacrePresets.forVoice(NacreVoice.BUBBLE)
        assertEquals(presets.keys.toList(), kept.map { it.name })
        for (p in kept) {
            assertEquals(48, p.midi, "${p.name} was heard at C3")
            assertEquals(presets.getValue(p.name), hash(p.render().samples), "${p.name} moved")
        }
    }

    @Test
    fun `each source is its own saved model and replays exactly from JSON`() {
        assertEquals(NacreSource.HARMONICS, NacreVoices.sourceFor(5))
        assertEquals(NacreSource.SHELL, NacreVoices.sourceFor(6))
        assertEquals(NacreSource.BUBBLE, NacreVoices.sourceFor(7))
        assertFailsWith<IllegalArgumentException> { NacreVoices.sourceFor(4) }
        assertFailsWith<IllegalArgumentException> { NacreVoices.sourceFor(8) }
        for (source in NacreSource.entries) {
            val voice = NacreVoice.valueOf(source.name)
            val patch = NacrePatch("study", voice, NacreVoices.defaults(), midi = 55, velocity = .7f, seed = -42L)
            assertEquals(source.model, patch.model)
            assertFailsWith<IllegalArgumentException> { patch.copy(voice = NacreVoice.PEARL) }
            assertFailsWith<IllegalArgumentException> { patch.copy(model = 3) }
            val replayed = NacrePatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, replayed)
            assertEquals(hash(NacreVoices.render(source, NacreVoices.defaults(), 55, .7f, -42L).samples),
                hash(replayed.render().samples))
            assertEquals(NacreVoices.macrosFor().map { it.name }, NacreModels.macrosFor(voice, source.model).map { it.name })
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("CONTACT" to .3f)) }
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("HOLD" to .5f)) }
        }
    }

    @Test
    fun `renders are deterministic and the seed is a real input`() {
        for (source in NacreSource.entries) {
            val a = render(source, 52, .8f)
            val b = render(source, 52, .8f)
            assertEquals(hash(a), hash(b), "$source is not deterministic")
            assertNotEquals(hash(a), hash(render(source, 52, .8f, seed = 8L)), "$source ignores its seed")
            assertTrue(a.all { it.isFinite() })
        }
    }

    @Test
    fun `peaks stay at or under the ceiling across the range at the loudest settings`() {
        for (source in NacreSource.entries) for (midi in listOf(28) + range) {
            for (macros in listOf(emptyMap(), mapOf("TOUCH" to 1f, "BLOOM" to 1f, "VENUE" to 0f), mapOf("TOUCH" to 1f, "VENUE" to 1f))) {
                val s = render(source, midi, 1f, macros)
                val peak = s.maxOf { abs(it) }
                assertTrue(peak <= NacreVoices.PEAK_CEILING, "$source MIDI $midi $macros peaks at $peak")
                assertTrue(peak > .02f, "$source MIDI $midi $macros is nearly silent: $peak")
            }
        }
    }

    @Test
    fun `the note is in tune across the range, SHELL measured on its ring-out`() {
        for (source in NacreSource.entries) for (midi in range) {
            val f = Keys.midiHz(midi).toDouble()
            // BUBBLE decays within about a second, so in its full mix the delayed water returns (copies of
            // the same tone) can pull the spectral peak: at C3 the mix reads -16 cents while the bass harp,
            // the sympathetic strings and the BLOOM-0 mix all read within 1.1. Its direct source is measured.
            val cents = if (source == NacreSource.BUBBLE) {
                val bass = NacreVoices.inspect(source, emptyMap(), midi, 1f, 7L).stems.getValue("bass")
                M.cents(M.pitchHz(bass, .05, .6, f), f)
            } else {
                val window = if (source == NacreSource.SHELL) 1.5 to 3.5 else .3 to 1.5
                M.cents(M.pitchHz(render(source, midi), window.first, window.second, f), f)
            }
            assertTrue(abs(cents) <= TUNING_CENTS, "$source MIDI $midi is $cents cents off")
        }
    }

    @Test
    fun `softer touches are duller, by velocity and by TOUCH`() {
        for (source in NacreSource.entries) {
            // Brightness is the share of power above four times the note in the first 0.3 s, so a
            // quieter strike cannot pass by level alone.
            val c3 = Keys.midiHz(48).toDouble()
            val byVelocity = listOf(.3f, .65f, 1f).map { M.upperShareDb(render(source, velocity = it), c3, 0.0, .3) }
            assertTrue(byVelocity[0] < byVelocity[1] - 1.0 && byVelocity[1] < byVelocity[2] - 1.0, "$source velocity brightness $byVelocity dB")
            val byTouch = listOf(0f, .3f, 1f).map { M.upperShareDb(render(source, macros = mapOf("TOUCH" to it)), c3, 0.0, .3) }
            assertTrue(byTouch[0] < byTouch[1] - 1.0 && byTouch[1] < byTouch[2] - 1.0, "$source TOUCH brightness $byTouch dB")
        }
    }

    /**
     * A new voice must be further from PEARL than any PEARL setting is from another, by timbre
     * on the ring-out ([NacreVoicesMeasure.toneDistance], 0.5 to 3 s). The PEARL settings span its
     * presets, TOUCH, DUET, BLOOM and a soft strike, all at C3. In the strike itself (the first half
     * second) PEARL's DUET and TOUCH settings differ as much as these sources do; the round's A/B
     * clips put that question to the owner.
     */
    @Test
    fun `each source is further from PEARL and from the other sources than PEARL's own settings are apart`() {
        val settings = NacrePresets.forVoice(NacreVoice.PEARL).map { it.render().samples } + listOf(
            pearl(macros = mapOf("TOUCH" to 0f)), pearl(macros = mapOf("TOUCH" to 1f)),
            pearl(macros = mapOf("DUET" to 1f)), pearl(macros = mapOf("BLOOM" to 1f)), pearl(velocity = .3f))
        var widest = 0.0
        for (i in settings.indices) for (j in i + 1 until settings.size) widest = maxOf(widest, M.toneDistance(settings[i], settings[j]))
        val sources = NacreSource.entries.associateWith { render(it) }
        for ((source, s) in sources) {
            val nearest = settings.minOf { M.toneDistance(s, it) }
            assertTrue(nearest > DISTINCT_FACTOR * widest, "$source nearest PEARL setting $nearest vs PEARL's widest $widest")
        }
        val list = sources.entries.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val between = M.toneDistance(list[i].value, list[j].value)
            assertTrue(between > DISTINCT_FACTOR * widest, "${list[i].key} vs ${list[j].key} $between vs PEARL's widest $widest")
        }
    }

    @Test
    fun `a cold render stays inside the budget`() {
        for (source in NacreSource.entries) {
            val start = System.nanoTime()
            render(source, 41, .9f, seed = 11L)
            val seconds = (System.nanoTime() - start) / 1e9
            assertTrue(seconds < RENDER_BUDGET_SECONDS, "$source took $seconds s")
        }
    }

    private companion object {
        const val TUNING_CENTS = 8.0
        const val DISTINCT_FACTOR = 1.25
        const val RENDER_BUDGET_SECONDS = 20.0
    }
}
