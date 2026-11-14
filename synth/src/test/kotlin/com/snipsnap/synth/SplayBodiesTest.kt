package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPLAY model 3's WIRE, RUB, AIR, BAR and SKIN bodies. TONGUE's accepted sound is pinned by [SplayAcceptedBankTest]
 * (all five kept populations, raw and matched PCM) and replayed unchanged there; these tests prove the
 * new bodies only read TONGUE's mechanics and never change them.
 */
class SplayBodiesTest {
    private val accepted = SplayAcceptedBank.entries.map { it.patch }

    private fun sha(x: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(x.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in x) bytes.putFloat(v)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun rms(x: FloatArray, from: Int, until: Int): Double {
        var s = 0.0
        for (i in from until until) s += x[i].toDouble() * x[i]
        return sqrt(s / (until - from))
    }

    /** Magnitude-weighted mean frequency of a Hann-windowed block. */
    private fun centroid(x: FloatArray, from: Int, size: Int = 32768): Double {
        val re = FloatArray(size) { (x[from + it] * (.5 - .5 * cos(2 * PI * it / (size - 1)))).toFloat() }
        val im = FloatArray(size)
        Fft.forward(re, im)
        var num = 0.0; var den = 0.0
        for (k in 1 until size / 2) {
            val m = hypot(re[k].toDouble(), im[k].toDouble())
            num += m * k * Dsp.RATE.toDouble() / size; den += m
        }
        return num / den
    }

    /** The quietest tenth of the active gesture's 50 ms windows against their median: how much sound stays between catches. */
    private fun sustainFloor(x: FloatArray): Double {
        val window = Dsp.RATE / 20
        val levels = (20 until 110).map { rms(x, it * window, (it + 1) * window) }.sorted()
        return levels[9] / levels[45]
    }

    @Test fun `the bodies strike exactly TONGUE's model-2 catches`() {
        val fractional = accepted.first().macros + ("POPULATION" to 5.5f / 99f)
        for (macros in accepted.map { it.macros } + listOf(fractional)) {
            val tongue = Splay.renderDetailed(SplayVoice.TONGUE, macros, 60, .8f, model = 2)
            val prepared = Splay.prepareModel2Excitation(macros)
            assertEquals(tongue.events, prepared.events)
            assertEquals(Splay.populationWeights(macros.getValue("POPULATION")).toList(), prepared.weights.toList())
            for (body in SplayBody.entries) {
                // Note, velocity and seed reach the body only, never the catches.
                assertEquals(tongue.events, SplayBodies.renderDetailed(body, macros, 72, .3f, seed = 7).events, "$body")
            }
        }
    }

    @Test fun `TONGUE renders the same bytes before and after the bodies, at both of its models`() {
        val cases = accepted.map { it.macros to 2 } + listOf(Splay.defaults(SplayVoice.TONGUE, 1) to 1)
        val before = cases.map { (m, model) -> sha(Splay.renderDetailed(SplayVoice.TONGUE, m, model = model).raw) }
        for ((m, _) in cases) for (body in SplayBody.entries) SplayBodies.render(body, m)
        val after = cases.map { (m, model) -> sha(Splay.renderDetailed(SplayVoice.TONGUE, m, model = model).raw) }
        assertEquals(before, after)
        // TONGUE stays on models 1 and 2; model 3 is the bodies' alone.
        assertFalse(Splay.supportsModel(SplayBodies.MODEL))
        assertEquals(2, Splay.MODEL_VERSION)
        assertEquals(listOf("TONGUE"), SplayVoice.entries.map { it.name })
    }

    @Test fun `WIRE and RUB ring at the requested pitch across SPLAY's range`() {
        for (body in listOf(SplayBody.WIRE, SplayBody.RUB)) for (midi in listOf(36, 48, 60, 72, 84)) {
            val r = SplayBodies.renderDetailed(body, midi = midi)
            val hz = Keys.midiHz(midi).toDouble()
            val (measured, confidence) = BowMeter.pitch(r.raw, Dsp.RATE / 2, Dsp.RATE / 2, hz.toFloat())
            val cents = 1200 * ln(measured / hz) / ln(2.0)
            assertTrue(abs(cents) <= 12.0, "$body MIDI $midi: %.1f cents".format(cents))
            assertTrue(confidence > .4, "$body MIDI $midi: pitch confidence %.2f".format(confidence))
        }
    }

    @Test fun `a softer touch is a duller hit after the levels are matched`() {
        for (body in SplayBody.entries) {
            val soft = SplayBodies.render(body, velocity = .3f).samples
            val hard = SplayBodies.render(body, velocity = 1f).samples
            val ratio = centroid(soft, 2 * Dsp.RATE) / centroid(hard, 2 * Dsp.RATE)
            assertTrue(ratio < .85, "$body soft/hard brightness %.2f".format(ratio))
            val loudness = Loudness.of(soft, 1, Dsp.RATE) / Loudness.of(hard, 1, Dsp.RATE)
            assertEquals(.3.pow(.65), loudness.toDouble(), .02, "$body loudness still follows velocity")
        }
        // The contact law shortens strictly as touch or CONTACT rises, at every pitch, and never passes .38 of a period.
        for (midi in listOf(36, 60, 84)) {
            val hz = Keys.midiHz(midi).toDouble()
            var last = Double.MAX_VALUE
            for (v in listOf(0.0, .3, .6, 1.0)) {
                val w = SplayBodies.contactSeconds(.7, v, hz)
                assertTrue(w < last && w <= .38 / hz && w < .004)
                last = w
            }
            assertTrue(SplayBodies.contactSeconds(.2, .6, hz) > SplayBodies.contactSeconds(.8, .6, hz))
        }
    }

    @Test fun `WIRE and RUB are new bodies, not TONGUE settings`() {
        for (population in listOf(0f, 15f / 99f)) {
            val m = accepted.first().macros + ("POPULATION" to population)
            val tongue = Splay.render(SplayVoice.TONGUE, m, model = 2).samples
            val wire = SplayBodies.render(SplayBody.WIRE, m).samples
            val rub = SplayBodies.render(SplayBody.RUB, m).samples
            val t = centroid(tongue, 2 * Dsp.RATE)
            assertTrue(centroid(wire, 2 * Dsp.RATE) > 1.6 * t, "WIRE is a brighter, harmonic string")
            assertTrue(centroid(rub, 2 * Dsp.RATE) > 1.6 * t, "RUB is a brighter, harmonic resonator")
            // RUB keeps singing between catches; WIRE decays between them like a plucked string.
            assertTrue(sustainFloor(rub) > sustainFloor(wire) + .08,
                "population $population: RUB floor %.2f, WIRE %.2f".format(sustainFloor(rub), sustainFloor(wire)))
        }
    }

    @Test fun `RUB's and AIR's held loops close and are the real motion until the window begins`() {
        for (body in listOf(SplayBody.RUB, SplayBody.AIR)) for (midi in listOf(36, 60, 84)) {
            val held = SplayBodies.renderDetailed(body, mapOf("HOLD" to 1f), midi, .6f)
            val start = assertNotNull(held.loopStart)
            assertEquals(((SplayBodies.LOOP_FROM_SECONDS + SplayBodies.HELD_CYCLES * SplayBodies.CYCLE_SECONDS) * Dsp.RATE).toInt(), held.raw.size)
            assertEquals((SplayBodies.CYCLE_SECONDS * Dsp.RATE).toInt(), held.raw.size - start, "the loop is exactly one cycle")
            val seam = Keys.seamError(held.raw, start)
            assertTrue(seam < 1e-6, "$body MIDI $midi seam %.2e".format(seam))
            assertTrue(SplayBodies.isLoop(body, mapOf("HOLD" to 1f)))
            // Before the loop window the held note is the finite note, sample for sample.
            val finite = SplayBodies.renderDetailed(body, emptyMap(), midi, .6f)
            assertNull(finite.loopStart)
            val early = (2.5 * Dsp.RATE).toInt()
            assertContentEquals(finite.raw.copyOf(early), held.raw.copyOf(early))
        }
    }

    @Test fun `renders are deterministic, bounded and refuse what they cannot play`() {
        for (body in SplayBody.entries) {
            val a = SplayBodies.renderDetailed(body)
            val b = SplayBodies.renderDetailed(body)
            assertContentEquals(a.snip.samples, b.snip.samples)
            assertEquals(8 * Dsp.RATE, a.snip.frameCount)
            assertTrue(a.snip.peak() <= .94f + 1e-6f)
            val started = System.nanoTime()
            SplayBodies.render(body, midi = 50)
            assertTrue((System.nanoTime() - started) / 1e6 < 3000, "$body warm render under 3 s")
            assertFailsWith<IllegalArgumentException> { SplayBodies.render(body, midi = 85) }
            assertFailsWith<IllegalArgumentException> { SplayBodies.render(body, velocity = 1.5f) }
        }
        // RUB's rosin and AIR's breath follow the acoustic seed; WIRE, BAR and SKIN have no noise.
        for (body in listOf(SplayBody.RUB, SplayBody.AIR)) {
            assertFalse(SplayBodies.render(body, seed = 1).samples.contentEquals(SplayBodies.render(body, seed = 2).samples), "$body")
            assertTrue(SplayBodies.supportsHold(body))
        }
        for (body in listOf(SplayBody.WIRE, SplayBody.BAR, SplayBody.SKIN)) {
            assertContentEquals(SplayBodies.render(body, seed = 1).samples, SplayBodies.render(body, seed = 2).samples, "$body")
            assertFailsWith<IllegalArgumentException> { SplayBodies.render(body, mapOf("HOLD" to 1f)) }
            assertFalse(SplayBodies.supportsHold(body))
        }
    }

    // ---- The owner's KEEP of WIRE (round splay-voices-1, 14 Nov: S2, S4, S6) ----

    private val pc16 = SplayAcceptedBank.entries.single { it.mechanisms == 16 }.patch
    private val phraseNotes = listOf(48, 55, 60, 64, 67, 72)

    /** A desk clip's file: trimmed to a .94 peak if it passed it (the S6 phrase did, by under .05 dB), as PCM16. */
    private fun wav(x: FloatArray): String {
        val peak = x.maxOf { abs(it) }
        val y = if (peak > .94f) FloatArray(x.size) { x[it] * .94f / peak } else x
        return MessageDigest.getInstance("SHA-256").digest(
            ByteArrayOutputStream().also { WavWriter.write(it, Snip(y, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16) }.toByteArray(),
        ).joinToString("") { "%02x".format(it) }
    }

    /** The desk's clip shapes: a note cut at [seconds] with a 50 ms fade, notes joined with .35 s gaps, a phrase .9 s apart. */
    private fun excerpt(x: FloatArray, seconds: Double): FloatArray {
        val n = min(x.size, (seconds * Dsp.RATE).toInt()); val fade = Dsp.RATE / 20
        return FloatArray(n) { i -> x[i] * (if (i >= n - fade) (n - i).toFloat() / fade else 1f) }
    }
    private fun join(parts: List<FloatArray>): FloatArray {
        val gap = (.35 * Dsp.RATE).toInt(); val out = FloatArray(parts.sumOf { it.size } + gap * (parts.size - 1)); var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size + gap }
        return out
    }
    private fun phrase(takes: List<FloatArray>): FloatArray {
        val step = (.9 * Dsp.RATE).toInt(); val out = FloatArray(step * (takes.size - 1) + takes.maxOf { it.size })
        takes.forEachIndexed { k, t -> for (i in t.indices) out[k * step + i] += t[i] }
        return out
    }

    @Test fun `WIRE renders exactly the takes the owner kept`() {
        val s2 = SplayBodies.render(SplayBody.WIRE, pc16.macros, pc16.midi, pc16.velocity, pc16.seed).samples
        val s4 = listOf(.3f, .6f, 1f).map { SplayBodies.render(SplayBody.WIRE, pc16.macros, 60, it, pc16.seed).samples }
        val s6 = phraseNotes.map { SplayBodies.render(SplayBody.WIRE, pc16.macros, it, .7f, pc16.seed).samples }
        // Every take behind the kept clips, as rendered float samples.
        assertEquals("c3b0e92ac4a329da3f90dc2e91fc43226f217093aec17f211d65a1710fb64291", sha(s2))
        assertEquals(listOf("dcdc94adfcd65a4a7f104828212fdf02710aaac163078d699761c56d6bc1ea3c", "87212241eab7473f27b2617394603317868300e0b9afb25ce001dc893b4e4fde", "e88699f3027341eaf7967a33d721a4d0c48f99e6ee0f22cbade7e646c9b0dad9"), s4.map { sha(it) })
        assertEquals(listOf("abc6e2b7ac23ce694a236c156be97443b3f7e1197a4b3ea12fb91c94245d9d96", "031a7f395fb1b11ea0a9804d839290b99a47a330b6e45e1278246e147692a09b", "4d78f095f834917765621982e108398c70e1645b1fa5086cb454b57a1c19f7aa", "9aa849053ece690cd2d92cc4e7065853b4c339b76b7f9e6065fce4d78eb63050", "fd464597a092adc596613548e78516f77634b1d60fd2abab76f5cea524aa17de", "7697eeb07ff7ae593ac0cd23dbfb949561c597853bf991f9576cdef48999912f"), s6.map { sha(it) })
        // The three clips the owner heard on the desk, rebuilt byte for byte (PCM16 WAV files).
        assertEquals("32ef3c5ccd867028696fd6ba7cd78b9bd9066405b6018a6071a99a05b4a3798a", wav(s2))
        assertEquals("0a789d83f67a8caad1e4c5011f51c04219e71f2eecaf9849ee866435457891d7", wav(join(s4.map { excerpt(it, 4.0) })))
        assertEquals("949e2cff576a30b6ab24964109c92f177d9722d3ea4a7b3870655ddf848b94c5", wav(phrase(s6)))
    }

    // ---- RUB's catches (round splay-voices-1 TWEAK: "We need a bit of a catch like gesture in the sound.") ----

    @Test fun `RUB's catches are heard as grip-and-slip gestures, not just soft accents`() {
        val r = SplayBodies.renderDetailed(SplayBody.RUB, pc16.macros, pc16.midi, pc16.velocity, pc16.seed)
        val x = r.snip.samples
        val frame = Dsp.RATE / 200
        val levels = DoubleArray(x.size / frame) { 20 * log10(1e-7 + rms(x, it * frame, (it + 1) * frame)) }
        // One gesture per group of catches: catches within 60 ms of the last one are the same flam.
        val groups = ArrayList<Double>()
        for (t in r.events.map { it.seconds }.sorted()) if (t < 5.6 && (groups.isEmpty() || t - groups.last() > .06)) groups += t
        val swings = groups.drop(1).map { t ->
            val grip = ((t - .03) * 200).toInt(); val slip = ((t + .005) * 200).toInt(); val after = ((t + .05) * 200).toInt()
            (slip until after).maxOf { levels[it] } - (grip until slip).minOf { levels[it] }
        }.sorted()
        val median = swings[swings.size / 2]
        // Measured about 9.5 dB; RUB's first version swung about 2.5 dB and the kept WIRE about 3.2 dB.
        assertTrue(median > 6.0, "RUB's dip-to-peak swing around a catch is %.1f dB".format(median))
        // The slip's scrape stays a touch, not static: well under 3% of the energy above 4 kHz.
        val size = 1 shl 17
        val re = FloatArray(size) { x[Dsp.RATE + it] }; val im = FloatArray(size)
        Fft.forward(re, im)
        var high = 0.0; var all = 0.0
        for (k in 1 until size / 2) {
            val e = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            all += e; if (k * Dsp.RATE.toDouble() / size > 4000) high += e
        }
        assertTrue(high / all < .03, "RUB energy above 4 kHz %.2f%%".format(100 * high / all))
    }

    // ---- RUB as kept in round splay-voices-2 (S9-S12, owner KEEP 14 Nov). ----

    @Test fun `RUB renders exactly the takes the owner kept`() {
        val m = pc16.macros
        assertEquals("5b97c746a8dc4de33418aefd7b61f472f063eb3fc32d2a9a48368f728d2e9ce4", sha(SplayBodies.render(SplayBody.RUB, m, pc16.midi, pc16.velocity, pc16.seed).samples))
        assertEquals(listOf("21a10a373dbda8e1479aace0ef8aff69f0175e9a9502568348e9d959b768a913", "768823cbd4864f331644f87729b5763acf2a3ee089e297419360a090f46d8f74", "58fb8a1fbf035bb7663472863f2dd81484cc0b12aa2e6d6d6dd20613ea43db7d"),
            listOf(.3f, .6f, 1f).map { sha(SplayBodies.render(SplayBody.RUB, m, 60, it, pc16.seed).samples) })
        assertEquals(listOf("e70b8869d808818fcf2e79ed67332fdfa25d97ad81ded035e2f56a7b6fe126e6", "2e1d1229a82421dbf5ba1d90ad0a05e4e96fe6f8d61c29b2f802fabe4361dea6", "d4de9052b240361b448fa5dd5f99060b2d4d3d45fa308168ee8b9a8daa4d669b", "fa946ea737f4167b0d31e5997d3c1d645e88f7628da2192133ff4bd54052a468", "0d262789fac36d557c20e6b9a68b64def4002ed0a5f69c3583303001921be34f", "ed908f5365467fe735aa324faad1c8d81976f83662184e04ab090b2af15734b1"),
            phraseNotes.map { sha(SplayBodies.render(SplayBody.RUB, m, it, .7f, pc16.seed).samples) })
        val held = SplayBodies.renderDetailed(SplayBody.RUB, m + ("HOLD" to 1f), pc16.midi, pc16.velocity, pc16.seed)
        assertEquals("563c67f495e749918f27ba689edcff77e8a57185b21a8638b251a7f9abdaf0f1", sha(held.snip.samples))
        assertEquals(308700, held.loopStart)
        // The four clips the owner heard on the desk, rebuilt byte for byte (PCM16 WAV files).
        val s9 = SplayBodies.render(SplayBody.RUB, m, pc16.midi, pc16.velocity, pc16.seed).samples
        val s10 = listOf(.3f, .6f, 1f).map { SplayBodies.render(SplayBody.RUB, m, 60, it, pc16.seed).samples }
        val s11 = phraseNotes.map { SplayBodies.render(SplayBody.RUB, m, it, .7f, pc16.seed).samples }
        assertEquals("0175fc33551adeeedf5ce919f3732f2eb4de38320e69757be58af8a75af9dbf9", wav(s9))
        assertEquals("36bd0047c5c2630eec5636be38096ab18b7f43d18da54f916e87568fb511a681", wav(join(s10.map { excerpt(it, 4.0) })))
        assertEquals("307697b0e02587a666236acbe5b24d957e2c9f54143f789f96682fa37b820eed", wav(phrase(s11)))
        // S12: the held render, then its loop twice more, with the 50 ms end fade.
        val loop = held.snip.samples.copyOfRange(held.loopStart!!, held.snip.samples.size)
        val played = held.snip.samples + loop + loop
        assertEquals("de152edadf72f496b4eafe5e0f793bc04da55c32dd533a4fed1909224a989856", wav(excerpt(played, played.size / 44100.0)))
    }

    // ---- AIR, BAR and SKIN as kept in round splay-voices-3 (S13-S22, owner KEEP 14 Nov). ----

    @Test fun `AIR, BAR and SKIN render exactly the takes the owner kept`() {
        val m = pc16.macros
        // Per body: the same-settings take, the soft/medium/hard takes at C4, the six phrase notes,
        // then the desk files' hashes (same settings, soft-medium-hard, phrase).
        val pins = mapOf(
            SplayBody.AIR to listOf(
                listOf("182524e9907689331ec002b6f139455b459840ee1396c327aae9ec73935b4b04"),
                listOf("852c3daf3ac6f96776e4a5324c2d01940eaf03ac55d3afeb84c50f227ae29b80", "cc0021c015721bed42320414175ad56102e12e2bfaae5f38ab4f177a9258ec98", "9dec05bf1cb0584b860524c27c4c7ed9718f42c9c7c60b5ecfa2e548ab2e89ed"),
                listOf("7819fdab1d9d6a78f861e40d349d27eef62980bffb0a56fd7ceb2cc43f6cb77b", "2ddd40b14475c5b27d44cde895d9a7a3fea0db55f6c12c63032471d7583fcb0e", "6fcbdbe8b5e4f2d3832f409322169d0ef01081b1d91e844bd8291d1f3c65908b", "b60bfb78ae18e0663aafe0dbbaeb62e5bd028b52bab7e636a22ee640ead88af9", "a5d3ac8fe882449b67c9fc1b9d91bf6668f53341369b3ce1dc1e8e7f5a71bbe0", "c51e59838b9969c6f42e41c82d1863cec18f33574073abc22fa379a88ddfb2af"),
                listOf("b774386e18a5673329fcf29c9a0becfb078cfa4efbb50f7c40722e9787822f6e", "be8b4afe8a5a2d172788d5a396ef7b730c3cfdcf20cd7fa23577fb3209efe3ca", "ecc4b98c537552f97c16c67891e57f787030cc97defcd686b0cf469e3eb04e2f"),
            ),
            SplayBody.BAR to listOf(
                listOf("50066b54c18f8612d0861e0b3921abe8080b462ac47854c6a3188787f68492cd"),
                listOf("ea91191046351584051a89c2ae820797feda27aca0bb140de24ace1438cd29c4", "abc5d4bce0d22c9e7e7502d7f2556facae35898963b4e03eae6e6d566929244e", "29549974866ae6a059756d43295294159510c4377d3fd2e6b3baed4cc400331b"),
                listOf("08991dc9031ff2ff185263f42ca4f84e32d9e64650da21b150a43725ec43468e", "5c7e64d89975b915f380db60f71698432fe3e5df7523150943df3414ad34b1d1", "d8cd14706aeb6e6f2f5ffa0674e886103775eebb2a81d0b7bffa15c821af0c9a", "8c465948b002a45cef3e9fd4f5ba00cf7dd1ef0db2ae8a5655fcfc2d1298e53b", "c9553e44a1c1b3bdd51efcb4a04fba8a990bf4af8481e5c798da3d3fc800c6c0", "033f662b79e5ed8acd2e312f9932e11ef3ebad643ebf81b41eb73ad3da761710"),
                listOf("712f5d5bdc2fa04ce29a3f87041264a78ea01f1f575e700daca7f2b13a3d4caa", "8bd0d5fcdede72d8bad5ee55dd9d9029539fb527b61f28ee2b88b488aab9af52", "2b17fb320c62b5c42fc1896d31cf5bdd8e02abb25a2310dc36c9aa5e4b83aa09"),
            ),
            SplayBody.SKIN to listOf(
                listOf("0da53b5cea53d105a46b02f8dbc78e067eb3098e105c4fb5ceaf923bceddf0b1"),
                listOf("a4d53821d23a09104c798b87eafe33d0d8ede2ba4bc074217c27c417ad3c4fc4", "e996c9c81cb70eccf007f79b6401a341e44c3e280671c98f3d21afa91f94c51a", "d9d8b3fc7d8b3b3a9fb3df29410235040df6ead18ab86d19b92ee77c24085563"),
                listOf("affc921255a1f23ae587953073a2f6203f0ddf1d2bcc293a420acad574e9c466", "6353af0225390de8b62a221f5b6767d4a339e1a5d9169ed31a2de1d706680454", "7b7d88dd6a325490b51a3c60bc64330746a3d513b553d072a791b1223d2dad21", "8306d2fcb9f0682e01ce0d4eee7f7e39c3383435b123c457f633ff492378470d", "52fe4edc730e69a7dc707db0f682c22ddeb822ea994281022c4ae6a6b6af6c76", "49bc96c9cb471f5c56a59d54efc9942f4d2eda2f6ff182cda4ca47e197fcaa64"),
                listOf("d6f5c0607c2b1a5e9b1f68eaec8806f941bc01b36b068a59266d7ec67750dc45", "d5c538d806e337ddc38f22ff1f0656644596afbe20c384212774f8fda827ecc5", "72ac5e560a1ae95169d8e15b24faf010c0da39d56b7d28e9f884d5153a3303b4"),
            ),
        )
        for ((body, p) in pins) {
            val same = SplayBodies.render(body, m, pc16.midi, pc16.velocity, pc16.seed).samples
            val touch = listOf(.3f, .6f, 1f).map { SplayBodies.render(body, m, 60, it, pc16.seed).samples }
            val notes = phraseNotes.map { SplayBodies.render(body, m, it, .7f, pc16.seed).samples }
            assertEquals(p[0], listOf(sha(same)), "$body same settings")
            assertEquals(p[1], touch.map { sha(it) }, "$body soft, medium, hard")
            assertEquals(p[2], notes.map { sha(it) }, "$body phrase notes")
            assertEquals(p[3], listOf(wav(same), wav(join(touch.map { excerpt(it, 4.0) })), wav(phrase(notes))), "$body desk files")
        }
        // S16: AIR held, the loop played round three times after the real motion.
        val h = SplayBodies.renderDetailed(SplayBody.AIR, m + ("HOLD" to 1f), pc16.midi, pc16.velocity, pc16.seed)
        assertEquals("3cca3fc89dbcd3f1e40e70650c9183064f6ce434bd043bbe00dfbac82ac4f53c", sha(h.snip.samples))
        assertEquals(308700, h.loopStart)
        val s = h.snip.samples; val start = h.loopStart!!; val loop = s.size - start
        assertEquals("1925e1d2558944bec12cd9d5aaccf0e1ea8a43e8413e85f7628aedc731d2a34f",
            wav(FloatArray(start + 3 * loop) { if (it < start) s[it] else s[start + (it - start) % loop] }))
    }

    // ---- AIR, BAR and SKIN: what makes them new bodies (round splay-voices-3) ----

    private val newBodies = listOf(SplayBody.AIR, SplayBody.BAR, SplayBody.SKIN)

    private fun power(x: FloatArray, from: Int, size: Int): DoubleArray {
        val re = FloatArray(size) { (x[from + it] * (.5 - .5 * cos(2 * PI * it / (size - 1)))).toFloat() }
        val im = FloatArray(size)
        Fft.forward(re, im)
        return DoubleArray(size / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    /** The strongest spectral peak within 6% of [near], from [from] on, interpolated on the log spectrum. */
    private fun peakHz(x: FloatArray, from: Int, near: Double): Double {
        val size = 1 shl 16
        val p = power(x, from, size)
        val bin = Dsp.RATE.toDouble() / size
        val lo = (near * .94 / bin).toInt(); val hi = (near * 1.06 / bin).toInt() + 1
        var best = lo
        for (k in lo..hi) if (p[k] > p[best]) best = k
        val a = ln(p[best - 1]); val b = ln(p[best]); val c = ln(p[best + 1])
        return (best + .5 * (a - c) / (a - 2 * b + c)) * bin
    }

    private class Shape(val high: Double, val harmonic: Double, val evenOvertones: Double)

    /**
     * From one second in: the share of energy above 4 kHz; the share of 50 Hz-10 kHz energy within 2% of
     * a harmonic of [hz]; and the share of the near-harmonic energy above the fundamental that sits on even
     * harmonics (TONGUE's overtones are mostly its second harmonic).
     */
    private fun shape(x: FloatArray, hz: Double): Shape {
        val size = 1 shl 17
        val p = power(x, Dsp.RATE, size)
        val bin = Dsp.RATE.toDouble() / size
        var all = 0.0; var high = 0.0; var band = 0.0; var harmonic = 0.0; var overtones = 0.0; var even = 0.0
        for (k in 1 until p.size) {
            val f = k * bin
            all += p[k]
            if (f > 4000) high += p[k]
            if (f < 50 || f > 10000) continue
            band += p[k]
            val n = Math.round(f / hz)
            if (n >= 1 && abs(f / (n * hz) - 1) < .02) {
                harmonic += p[k]
                if (n >= 2) { overtones += p[k]; if (n % 2 == 0L) even += p[k] }
            }
        }
        return Shape(high / all, harmonic / band, even / overtones)
    }

    @Test fun `AIR, BAR and SKIN sit at the requested pitch across SPLAY's range`() {
        for (body in newBodies) for (midi in listOf(36, 48, 60, 72, 84)) {
            val hz = Keys.midiHz(midi).toDouble()
            val raw = SplayBodies.renderDetailed(body, midi = midi).raw
            // Once the catches have stopped, the lowest resonance (the tube, the bar's first mode, the head's
            // (0,1) mode) rings out at the note: the bodies are tuned, whatever their overtones.
            val tail = 1200 * ln(peakHz(raw, 6 * Dsp.RATE, hz) / hz) / ln(2.0)
            assertTrue(abs(tail) <= 12.0, "$body MIDI $midi rings out at %.1f cents".format(tail))
            // AIR is harmonic, so its pitch also reads during the gesture.
            if (body == SplayBody.AIR && midi >= 48) {
                val (measured, confidence) = BowMeter.pitch(raw, Dsp.RATE / 2, Dsp.RATE / 2, hz.toFloat())
                val cents = 1200 * ln(measured / hz) / ln(2.0)
                assertTrue(abs(cents) <= 12.0 && confidence > .4, "AIR MIDI $midi: %.1f cents, confidence %.2f".format(cents, confidence))
            }
        }
    }

    @Test fun `AIR, BAR and SKIN are new bodies, not TONGUE settings, and differ from each other`() {
        val tongue = Splay.render(SplayVoice.TONGUE, pc16.macros, pc16.midi, pc16.velocity, pc16.seed, model = 2).samples
        val hz = Keys.midiHz(pc16.midi).toDouble()
        val render = newBodies.associateWith { SplayBodies.render(it, pc16.macros, pc16.midi, pc16.velocity, pc16.seed).samples }
        val shapes = render.mapValues { shape(it.value, hz) }
        val bright = render.mapValues { centroid(it.value, 2 * Dsp.RATE) }
        val t = shape(tongue, hz)
        val tBright = centroid(tongue, 2 * Dsp.RATE)
        // TONGUE's overtones are mostly its second harmonic. AIR's tube has odd harmonics only, and the bar's and
        // the head's overtones are inharmonic, so none of the three has TONGUE's even overtone.
        for (body in newBodies) {
            assertTrue(t.evenOvertones - shapes.getValue(body).evenOvertones > .6,
                "$body even overtones %.2f, TONGUE %.2f".format(shapes.getValue(body).evenOvertones, t.evenOvertones))
            assertTrue(shapes.getValue(body).high < .03, "$body keeps a clean top: %.2f%% above 4 kHz".format(100 * shapes.getValue(body).high))
        }
        // BAR and SKIN are inharmonic where TONGUE is nearly harmonic; BAR is brighter than TONGUE, SKIN darker.
        for (body in listOf(SplayBody.BAR, SplayBody.SKIN)) assertTrue(t.harmonic - shapes.getValue(body).harmonic > .15,
            "$body harmonic share %.2f, TONGUE %.2f".format(shapes.getValue(body).harmonic, t.harmonic))
        assertTrue(bright.getValue(SplayBody.BAR) > 1.5 * tBright, "BAR %.0f Hz against TONGUE %.0f Hz".format(bright.getValue(SplayBody.BAR), tBright))
        assertTrue(bright.getValue(SplayBody.SKIN) < .9 * tBright, "SKIN %.0f Hz against TONGUE %.0f Hz".format(bright.getValue(SplayBody.SKIN), tBright))
        assertTrue(bright.getValue(SplayBody.BAR) > 2 * bright.getValue(SplayBody.SKIN), "BAR and SKIN differ in brightness")
        // AIR is breath on a harmonic tube: much brighter than TONGUE, more harmonic than BAR, and, unlike RUB
        // (the other held body), without even overtones. BAR and SKIN are far less harmonic than WIRE's string.
        assertTrue(bright.getValue(SplayBody.AIR) > 2.5 * tBright)
        assertTrue(shapes.getValue(SplayBody.AIR).harmonic > shapes.getValue(SplayBody.BAR).harmonic + .1)
        val rub = shape(SplayBodies.render(SplayBody.RUB, pc16.macros, pc16.midi, pc16.velocity, pc16.seed).samples, hz)
        assertTrue(rub.evenOvertones - shapes.getValue(SplayBody.AIR).evenOvertones > .3)
        val wire = shape(SplayBodies.render(SplayBody.WIRE, pc16.macros, pc16.midi, pc16.velocity, pc16.seed).samples, hz)
        for (body in listOf(SplayBody.BAR, SplayBody.SKIN)) assertTrue(wire.harmonic - shapes.getValue(body).harmonic > .25)
    }

    @Test fun `AIR's catches are heard as puffs`() {
        val r = SplayBodies.renderDetailed(SplayBody.AIR, pc16.macros, pc16.midi, pc16.velocity, pc16.seed)
        val x = r.snip.samples
        val frame = Dsp.RATE / 200
        val levels = DoubleArray(x.size / frame) { 20 * log10(1e-7 + rms(x, it * frame, (it + 1) * frame)) }
        val groups = ArrayList<Double>()
        for (t in r.events.map { it.seconds }.sorted()) if (t < 5.6 && (groups.isEmpty() || t - groups.last() > .06)) groups += t
        val swings = groups.drop(1).map { t ->
            val grip = ((t - .03) * 200).toInt(); val slip = ((t + .005) * 200).toInt(); val after = ((t + .05) * 200).toInt()
            (slip until after).maxOf { levels[it] } - (grip until slip).minOf { levels[it] }
        }.sorted()
        val median = swings[swings.size / 2]
        // Measured about 6.7 dB, as RUB's grip-and-slip (the kept WIRE swings about 3.1 dB).
        assertTrue(median > 5.0, "AIR's swing around a catch is %.1f dB".format(median))
    }
}
