package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.fail

/**
 * Fingerprints of CIRCUIT's published sound (docs/CONTROL_RATE_MECHANICS_SPEC_2026_11.md, 4.6).
 *
 * Every hash is SHA-256 of the rendered float PCM, little-endian IEEE bits, so a change of one
 * bit in one sample fails. They were captured from the code before any control-rate change, so
 * they are the evidence that model 1, which every saved pad without a model field renders, and
 * the approved one-minute ceremony are still exactly what was published.
 */
class CircuitFrozenModelTest {
    private fun sha(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bytes.putFloat(s)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Collects every mismatch before failing, so one run reports all changed fingerprints. */
    private fun check(cases: List<Pair<String, () -> FloatArray>>, expected: Map<String, String>) {
        val wrong = ArrayList<String>()
        for ((name, render) in cases) {
            val actual = sha(render())
            if (expected[name] != actual) wrong += "\"$name\" to \"$actual\","
        }
        if (wrong.isNotEmpty()) fail("CIRCUIT model 1 output changed:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `model 1 voice defaults render the published bytes`() {
        check(CircuitVoice.entries.map { voice -> "default ${voice.name}" to { Circuit.render(voice).samples } }, mapOf(
            "default ROOT" to "618c935b03ac22e5a9f48a1b0c4a8d01858237873c872b7c5a8d4c2c1f37c3b1",
            "default PROCESSION" to "8302160e028095dc99e5516405c4a6e415c97f33a216dd838ae811af73ca948c",
            "default ANSWER" to "e1ca7650c26e3ead03f0c3d4c4e4ac75a4f835d7497c3bf39eff1fea4a0f1913",
            "default VOICED" to "9dd3a80d19952d5b8a9f0dd4e897022db0a813fb347738d4e3a11831886d326b",
            "default EXPANSE" to "e87e7a1a93dfda59320bfb495563d390b49c7023fb57a5b0ed7388740a9348fa",
            "default CONFLUENCE" to "68d65ebefff589d7bd0d2b75cded0a4479dff94f2920b9fd33929e6a2c7baf0b",
        ))
    }

    @Test
    fun `model 1 factory presets render the published bytes`() {
        val presets = CircuitVoice.entries.map { CircuitPresets.forVoice(it).first() } +
            listOf("EMBER PULSE", "HELD CIRCLE").map { name -> CircuitPresets.all().single { it.name == name } }
        check(presets.map { patch -> "preset ${patch.name}" to { patch.render().samples } }, mapOf(
            // THREE BREATHS stores ROOT's own defaults, so it shares the default ROOT fingerprint.
            "preset THREE BREATHS" to "618c935b03ac22e5a9f48a1b0c4a8d01858237873c872b7c5a8d4c2c1f37c3b1",
            "preset SLOW PARADE" to "863c6c406b1dc020f10ca6f4ff1afc0342eea9fe6a148bf1141f1283c804bf18",
            "preset PAIRED WOOD" to "f3c7f0f81ebde56e40bf1f704b2a7d0c0c6175d5d86bd9263a839fa857958f43",
            "preset CHEST GESTURE" to "d494d6e5cab1aeec527c68948ea5125ce2332010c2babc1182bb87bab05dc4e5",
            "preset WIDE CIRCLE" to "fd3ea401bb31643aabd1c4497fa7d50c3a7c88e7505fff49028de7187e613628",
            "preset FAST GATHER" to "c8498f218e28d791d7d16d8df85fac7c6a0533c891bc21db87cd73dc9ca66d7c",
            "preset EMBER PULSE" to "b68a21e0e7ea06bdf8b5c25fbdd2c50df5766f4615840f37c61a619a0a53c007",
            "preset HELD CIRCLE" to "814fabbd00db8fc88481a38ffbc4a711fe62b359d71c045e35299dd492c8741c",
        ))
    }

    @Test
    fun `approved one-minute ceremony renders the published bytes`() {
        check(listOf("ceremony" to { Circuit.inspectCeremony().snip.samples }), mapOf(
            "ceremony" to "8e5b653867f44fffc5d846750baa37308c24e0d32e321bb0a097bd4aff448e0f",
        ))
    }
}
