package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The owner's R4 listening verdict is backed by exact buffers, then wider numerical probes. */
class TremorCharacterTest {
    private data class Expected(val voice: TremorVoice, val velocity: Float, val sha256: String)

    @Test
    fun `model two preserves every owner approved voice and touch buffer`() {
        // Raw float32 SHA-256, before audition level matching. Captured from the heard R4
        // prototype b6f95b8e; these assert sound identity rather than a loose similarity score.
        val expected = listOf(
            Expected(TremorVoice.HIDE, 0.20000000298023224f, "c1ec3067291fc4cf2d5a80a260da315e45a2b2b0619094617612bb7e44eb18f4"),
            Expected(TremorVoice.HIDE, 0.550000011920929f, "7d9a326aee7c4676d7094c12f7a48e1708089a6725819fe08dca08a7c2025366"),
            Expected(TremorVoice.HIDE, 1f, "4a14c1aa822f454e7dc7a321a6703a6774182ca150eceb28f67900aeb5d49f73"),
            Expected(TremorVoice.UNISON, 0.20000000298023224f, "4172e31d692b9d8f74302c42302de31a22a7e396d9cca524815cdaa27613b3c9"),
            Expected(TremorVoice.UNISON, 0.550000011920929f, "09e7a615680d8bbc6fbca6dd44aca11bed9e14a30cfeac782da844fb7cc8d086"),
            Expected(TremorVoice.UNISON, 1f, "8439c5f16beb7629a8ec68211c4cb2ccab02109f0d8ba2c77ab0796363664901"),
            Expected(TremorVoice.ROLL, 0.20000000298023224f, "98b0eb7ca1b3af6c74867a76938fc446b9f63d193aa3a288f39d9ff540afcd9d"),
            Expected(TremorVoice.ROLL, 0.550000011920929f, "4b1fe70dd061d8c1b4c59f975547cd4c2bd178e34e6d26bfbced026395150b71"),
            Expected(TremorVoice.ROLL, 1f, "f4e4d2dca6b49bd9e7c1748754b8d5a75454f456974c6a684b0ccc2c9ac35d81"),
            Expected(TremorVoice.WIRE, 0.20000000298023224f, "31349d19163164a5d13855b458e19722585be73b9a677f3136fd7903f019d834"),
            Expected(TremorVoice.WIRE, 0.550000011920929f, "af12975ff5cb2f94667d5541d28f0afe6546dfc7caea2f91242d0552dd8f04ca"),
            Expected(TremorVoice.WIRE, 1f, "ea97f59119bb88cd33d7e61587fd723355a0d316fd24fb9331d55b1e43b5487c"),
            Expected(TremorVoice.CHARGE, 0.20000000298023224f, "a3ce989d5320a4b87f2e717bd85ae21a576e5500e4a33a109717ed775c71a474"),
            Expected(TremorVoice.CHARGE, 0.550000011920929f, "8efc1074e546a94d42044a709b0f6036b3386b914f5113e0f83b65077adfb318"),
            Expected(TremorVoice.CHARGE, 1f, "69fd68b21804e460456e6712eb676b45563bbcd0de8ff318e78fd2f81ae22e38"),
            Expected(TremorVoice.FRACTURE, 0.20000000298023224f, "200cbdfd252a1c369f15fc50fa4a675b013cc2a15dc5e656f4aeae9ed980968b"),
            Expected(TremorVoice.FRACTURE, 0.550000011920929f, "eee94f66c6b27bf9c313fcde414959cbb9553ac838393e9413711cd7bbaee4b7"),
            Expected(TremorVoice.FRACTURE, 1f, "fdefcd4f5432e8195cd8c9cc27379f899df90b761e2a7a499b3de214b258da35"),
        )
        for (e in expected) {
            val snip = Tremor.render(e.voice, mapOf("TUNE" to 0.5f), e.velocity, model = Tremor.MODEL)
            assertEquals(e.sha256, hash(snip.samples), "${e.voice} touch ${e.velocity} changed the approved buffer")
        }
    }

    @Test
    fun `soft middle and hard have absolute upper mode contrast in every voice`() {
        for (voice in TremorVoice.entries) {
            val shares = listOf(0.2f, 0.55f, 1f).map { velocity ->
                upperShare(Tremor.render(voice, velocity = velocity).samples, Tremor.frequencyFor(voice, 0.5f) * 1.5)
            }
            // The previous relative-ratio check passed while >99% stayed in the same tone.
            // These absolute fractions keep a substantially broader hard contact exposed.
            assertTrue(shares[1] > shares[0], "$voice soft/middle contrast $shares")
            assertTrue(shares[2] > shares[1], "$voice middle/hard contrast $shares")
            assertTrue(shares[2] > 0.05 && shares[2] - shares[0] > 0.04, "$voice upper modes remain buried: $shares")
        }
    }

    @Test
    fun `every voice and touch retains the open spectral anchor through the register`() {
        for (voice in TremorVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) for (velocity in listOf(0.2f, 0.55f, 1f)) {
            val r = Tremor.play(voice, mapOf("TUNE" to tune), velocity)
            assertAnchor(r.snip.samples, Tremor.frequencyFor(voice, tune), "$voice TUNE$tune touch$velocity")
            assertEquals(0, r.railHits)
            assertTrue(r.rawPeak.isFinite() && r.rawPeak < 4f)
        }
    }

    @Test
    fun `hard coordinated strikers do not cancel the requested note at ensemble corners`() {
        for (tune in listOf(0f, 0.125f, 0.5f, 1f)) for (ensemble in listOf(0.8f, 0.9f, 1f)) for (strike in listOf(0.8f, 0.9f, 1f)) {
            val macros = Tremor.defaults(TremorVoice.UNISON).mapValues { (name, value) -> when (name) {
                "TUNE" -> tune
                "STRIKE" -> strike
                "ENSEMBLE" -> ensemble
                "HOLD" -> 0f
                else -> 1f
            } }
            val r = Tremor.play(TremorVoice.UNISON, macros)
            assertAnchor(r.snip.samples, Tremor.frequencyFor(TremorVoice.UNISON, tune), "UNISON coordination TUNE$tune ENSEMBLE$ensemble STRIKE$strike")
            assertEquals(0, r.railHits)
            assertTrue(r.strikes.all { it.sample in 0..(0.012 * Dsp.RATE * Dsp.OVERSAMPLE).toInt() },
                "anchor coordination turned the initial group into a roll: ${r.strikes}")
        }
    }

    @Test
    fun `anchor coordination keeps late contacts inside the initial unison window`() {
        val voice = TremorVoice.CHARGE
        val macros = Tremor.defaults(voice) + mapOf(
            "TUNE" to 4 / 24f, "STRIKE" to .879606f, "ENSEMBLE" to .44305366f,
        )
        // Without the initial-window ceiling the prepared striker at 11.780 ms moved to
        // 12.851 ms. Coordination must respect the instrument's existing 12 ms group.
        for (hold in listOf(0f, 1f)) {
            val r = Tremor.play(voice, macros + ("HOLD" to hold))
            assertTrue(r.strikes.all { it.sample in 0..(0.012 * Dsp.RATE * Dsp.OVERSAMPLE).toInt() },
                "anchor coordination exceeded the initial unison window at HOLD$hold: ${r.strikes}")
            assertAnchor(r.snip.samples, Tremor.frequencyFor(voice, macros.getValue("TUNE")), "late CHARGE contacts HOLD$hold")
        }
    }

    @Test
    fun `crowded high held bead strikes retain the requested note in the attack and loop`() {
        val voice = TremorVoice.ROLL
        val macros = Tremor.defaults(voice).mapValues { 1f }
        for (velocity in listOf(0.9f, 1f)) {
            val r = Tremor.play(voice, macros, velocity)
            val wanted = Tremor.frequencyFor(voice, 1f)
            // The uncoordinated R4 attack and recurring contacts promoted a membrane mode
            // roughly 507 cents above C4. Check the actual exported attack and held segment.
            assertAnchor(r.snip.samples, wanted, "ROLL held C4 attack touch$velocity")
            assertAnchor(r.snip.samples.copyOfRange(r.loopStart, r.snip.frameCount), wanted,
                "ROLL held C4 loop touch$velocity")
            assertEquals(0, r.railHits)
        }
    }

    @Test
    fun `held versions are deterministic bounded and retain the constructed join`() {
        for (voice in TremorVoice.entries) for (velocity in listOf(0.2f, 1f)) {
            val macros = mapOf("HOLD" to 1f)
            val a = Tremor.play(voice, macros, velocity)
            val b = Tremor.play(voice, macros, velocity)
            assertContentEquals(a.snip.samples, b.snip.samples)
            assertEquals(0, a.railHits)
            assertTrue(Keys.seamError(a.snip.samples, a.loopStart) < Keys.MAX_SEAM_ERROR,
                "$voice has a broken constructed join")
        }
    }

    @Test
    fun `nonfinite model two controls fall back to the actual defaults`() {
        for (voice in TremorVoice.entries) {
            val expected = Tremor.render(voice).samples
            val bad = Tremor.defaults(voice).mapValues { Float.NaN } + ("UNKNOWN" to Float.POSITIVE_INFINITY)
            assertContentEquals(expected, Tremor.render(voice, bad).samples)
            assertContentEquals(expected, Tremor.render(voice, velocity = Float.NaN).samples)
        }
    }

    private fun assertAnchor(samples: FloatArray, wanted: Float, label: String) {
        val actual = peakHz(samples)
        val cents = 1200.0 * ln(actual / wanted) / ln(2.0)
        assertTrue(abs(cents) < 25.0, "$label global anchor moved $cents cents ($actual Hz, wanted $wanted)")
    }

    private fun spectrum(samples: FloatArray, window: Int, n: Int): Pair<FloatArray, FloatArray> {
        val re = FloatArray(n)
        val im = FloatArray(n)
        val count = minOf(samples.size, window)
        for (i in 0 until count) re[i] = (samples[i] * (0.5 - 0.5 * cos(2.0 * PI * i / (count - 1)))).toFloat()
        Fft.forward(re, im)
        return re to im
    }

    private fun upperShare(samples: FloatArray, fromHz: Double): Double {
        val n = 16384
        val (re, im) = spectrum(samples, (0.18 * Dsp.RATE).toInt(), n)
        var upper = 0.0
        var all = 0.0
        for (k in 1 until n / 2) {
            val power = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            if (k * Dsp.RATE.toDouble() / n >= 20.0) all += power
            if (k * Dsp.RATE.toDouble() / n > fromHz) upper += power
        }
        return upper / all.coerceAtLeast(1e-30)
    }

    private fun peakHz(samples: FloatArray): Double {
        val n = 65536
        val (re, im) = spectrum(samples, 16384, n)
        fun magnitude(k: Int) = sqrt(re[k].toDouble() * re[k] + im[k].toDouble() * im[k])
        val from = (20.0 * n / Dsp.RATE).toInt().coerceAtLeast(1)
        val to = n / 2 - 2
        val bin = (from..to).maxBy { magnitude(it) }
        val a = ln(magnitude(bin - 1).coerceAtLeast(1e-30))
        val b = ln(magnitude(bin).coerceAtLeast(1e-30))
        val c = ln(magnitude(bin + 1).coerceAtLeast(1e-30))
        val offset = (0.5 * (a - c) / (a - 2.0 * b + c)).coerceIn(-0.5, 0.5)
        return (bin + offset) * Dsp.RATE / n
    }

    private fun hash(samples: FloatArray): String {
        val b = ByteBuffer.allocate(samples.size * 4)
        samples.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
