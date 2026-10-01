package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shipped ENSEMBLE's output, pinned to the bit.
 *
 * `FxTest` checks the section's constants, its weights, its zero-depth reference and its doubled-equals-widened
 * rule, but nothing pins what the moving taps actually write, and a pad's recipe is regenerated from the section's
 * arithmetic every time it is opened: an edit that moves one sample of the output moves every saved pad that
 * carries the section. This pins the arithmetic on synthetic signals only (an engine render could change for an
 * unrelated reason and redden it), across five signals and five macro sets, each digest named so a failure says
 * which signal and which macros moved.
 *
 * The literals were produced before `ENSEMBLE` was given a second voicing, so the test proves that the new macro at
 * its default leaves the old output byte for byte - that is the promise a saved recipe without the key relies on.
 * They are floating-point results (`sin`, `exp`, `pow`); they held across the JVM's interpreter, C1 and C2 and with
 * the libm intrinsics off on the JVM that wrote them. If a platform ever disagrees, the failure names the digest and
 * the fix is to regenerate it on that platform after the diff against the old bytes is understood, never to loosen
 * the test.
 */
class EnsembleBytesTest {

    private fun tone(hz: Float, seconds: Float, rate: Int = 44_100): Snip {
        val n = (seconds * rate).toInt()
        return Snip(
            FloatArray(n) { i ->
                val ph = (i.toDouble() * hz / rate) % 1.0
                (2.0 * ph - 1.0).toFloat() * 0.5f
            },
            1, rate,
        )
    }

    private fun doubled(s: Snip): Snip = Snip(FloatArray(s.frameCount * 2) { s.samples[it / 2] }, 2, s.sampleRate)

    private fun hardLeft(s: Snip): Snip = Snip(FloatArray(s.frameCount * 2) { if (it % 2 == 0) s.samples[it / 2] else 0f }, 2, s.sampleRate)

    private fun noise(): Snip {
        val n = Dsp.Noise(12345)
        return Snip(FloatArray(44_100) { n.next() * 0.5f }, 1, 44_100)
    }

    private fun digest(s: Snip): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(byteArrayOf(s.channels.toByte(), (s.sampleRate shr 8).toByte(), (s.sampleRate and 255).toByte()))
        for (v in s.samples) {
            val b = java.lang.Float.floatToRawIntBits(v)
            md.update(byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte()))
        }
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    private val f1 = tone(130.81f, 1.5f)

    /** Signal name to signal: a mono saw, the same doubled to a pair, the same hard left, a 48 kHz saw, and seeded noise. */
    private val fixtures: List<Pair<String, Snip>> = listOf(
        "F1 mono saw 131 Hz" to f1,
        "F2 doubled pair" to doubled(f1),
        "F3 hard left" to hardLeft(f1),
        "F4 mono saw 262 Hz at 48 kHz" to tone(261.63f, 0.8f, 48_000),
        "F5 seeded noise" to noise(),
    )

    private val macroSets: List<Pair<String, Map<String, Float>>> = listOf(
        "M1 defaults" to emptyMap(),
        "M2 depth 1 rate 0 width 0" to mapOf("DEPTH" to 1f, "RATE" to 0f, "WIDTH" to 0f),
        "M3 depth .25 rate 1 width .5" to mapOf("DEPTH" to 0.25f, "RATE" to 1f, "WIDTH" to 0.5f),
        "M4 depth 0 width 0" to mapOf("DEPTH" to 0f, "WIDTH" to 0f),
        "M5 depth .6 width 1" to mapOf("DEPTH" to 0.6f, "WIDTH" to 1f),
    )

    /** Fixture then macro set, in print order. */
    private val expected: Map<String, String> = mapOf(
        "F1 mono saw 131 Hz / M1 defaults" to "e581006790b40f91",
        "F1 mono saw 131 Hz / M2 depth 1 rate 0 width 0" to "38fa9ebecaaaa55a",
        "F1 mono saw 131 Hz / M3 depth .25 rate 1 width .5" to "3a4430f45c61cc28",
        "F1 mono saw 131 Hz / M4 depth 0 width 0" to "5d158cf2f83dd15e",
        "F1 mono saw 131 Hz / M5 depth .6 width 1" to "990e2ae8b3c52503",
        "F2 doubled pair / M1 defaults" to "e581006790b40f91",
        "F2 doubled pair / M2 depth 1 rate 0 width 0" to "3809c6a17e8af59f",
        "F2 doubled pair / M3 depth .25 rate 1 width .5" to "3a4430f45c61cc28",
        "F2 doubled pair / M4 depth 0 width 0" to "f26e0cc7b5d8dbda",
        "F2 doubled pair / M5 depth .6 width 1" to "990e2ae8b3c52503",
        "F3 hard left / M1 defaults" to "1078eff53b980c04",
        "F3 hard left / M2 depth 1 rate 0 width 0" to "a89c18f9eb3d07e8",
        "F3 hard left / M3 depth .25 rate 1 width .5" to "093d6d36be7dde1d",
        "F3 hard left / M4 depth 0 width 0" to "3bb3c16e6f3d48fc",
        "F3 hard left / M5 depth .6 width 1" to "a8adc7e5768c4821",
        "F4 mono saw 262 Hz at 48 kHz / M1 defaults" to "212826a64a347e54",
        "F4 mono saw 262 Hz at 48 kHz / M2 depth 1 rate 0 width 0" to "e8481b6fa376f340",
        "F4 mono saw 262 Hz at 48 kHz / M3 depth .25 rate 1 width .5" to "1f814df8c55c3544",
        "F4 mono saw 262 Hz at 48 kHz / M4 depth 0 width 0" to "732241336274ad79",
        "F4 mono saw 262 Hz at 48 kHz / M5 depth .6 width 1" to "bc3e411867aec22e",
        "F5 seeded noise / M1 defaults" to "176fea92318c1cba",
        "F5 seeded noise / M2 depth 1 rate 0 width 0" to "2852fb2fbb0811e4",
        "F5 seeded noise / M3 depth .25 rate 1 width .5" to "e8e2ce4c6ba664e4",
        "F5 seeded noise / M4 depth 0 width 0" to "9d227a31568ce6ad",
        "F5 seeded noise / M5 depth .6 width 1" to "0faf1028b1ce7b83",
    )

    @Test
    fun `the shipped section's bytes are pinned on five signals and five macro sets`() {
        val actual = LinkedHashMap<String, String>()
        for ((signalName, signal) in fixtures) for ((macroName, macros) in macroSets) {
            actual["$signalName / $macroName"] = digest(Ensemble.process(signal, macros))
        }
        for ((k, v) in actual) println("ENSEMBLE-BYTES $k $v")
        assertEquals(expected, actual, "the shipped ENSEMBLE's output moved: a saved pad that carries the section would now regenerate differently")
    }

    @Test
    fun `the wide-weight family's rows are pinned too`() {
        assertEquals("24eff8a7cf95c43e", digest(Ensemble.process(f1, emptyMap(), 0f)), "z = 0")
        assertEquals("be51093ef3ae43fa", digest(Ensemble.process(f1, emptyMap(), 0.3f)), "z = 0.3")
    }

    @Test
    fun `a recipe read from JSON without any newer key is the same bytes as its macros`() {
        val chain = FxChain.fromJsonText("""{"fx":1,"reverse":false,"ensemble":{"DEPTH":0.6,"WIDTH":1.0}}""")
        assertEquals("990e2ae8b3c52503", digest(chain.process(f1)), "the JSON route and the macro route disagree")
    }
}
