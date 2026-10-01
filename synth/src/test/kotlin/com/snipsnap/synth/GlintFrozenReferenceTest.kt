package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * DEPTH 0 is today's sound, bit for bit
 * (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §4.1).
 * [LegacyGlint] is the render code as it stood before DEPTH existed; every
 * case here must come out of the production code with the same raw bits,
 * `-0f` and `+0f` told apart.
 */
class GlintFrozenReferenceTest {

    private val corners: List<Pair<String, Map<String, Float>>> = listOf(
        "defaults" to emptyMap<String, Float>(),
        "bright corner" to mapOf("PEAK" to 1f, "TUNE" to 1f, "BLOOM" to 0.5f, "BODY" to 0f, "FOLLOW" to 1f),
        "still and long" to mapOf("BLOOM" to 0.5f, "DECAY" to 1f),
        "short, rising" to mapOf("DECAY" to 0f, "BLOOM" to 0f),
        "falling wide, low" to mapOf("BLOOM" to 1f, "PEAK" to 0f, "TUNE" to 0f),
    )

    /** The corners, plus all-zeros and all-ones over the macros the voice has, DEPTH left out (so it is 0). */
    private fun cornersFor(voice: GlintVoice): List<Pair<String, Map<String, Float>>> {
        val names = Glint.macrosFor(voice).map { it.name }.filter { it != "DEPTH" }
        return corners + listOf("all zeros" to names.associateWith { 0f }, "all ones" to names.associateWith { 1f })
    }

    /**
     * What the held loop is held to besides its TUNE x BLOOM grid: the two corner maps (every macro at 0
     * and at 1, so TUNE sits at each end of its range and BLOOM at each extreme of its sign), a rising
     * BLOOM (the negative-BLOOM branch of the breath, and on BRASS the `depth = abs(path.bloom)` that
     * sets how far its level breathes) and a zero BODY (on the path voices the second burst's level is
     * then 0f, so the held second term is a signed zero; VOWEL's second burst has a fixed level, and
     * its BODY scales the formants instead).
     */
    private fun heldMapsFor(voice: GlintVoice): List<Pair<String, Map<String, Float>>> {
        val corner = cornersFor(voice).toMap()
        return listOf(
            "all zeros" to corner.getValue("all zeros"),
            "all ones" to corner.getValue("all ones"),
            "a rising BLOOM" to mapOf("BLOOM" to 0.15f),
            "a zero BODY" to mapOf("BODY" to 0f),
        )
    }

    private fun assertBitIdentical(expected: FloatArray, actual: FloatArray, what: String) {
        assertEquals(expected.size, actual.size, "$what: length")
        val first = expected.indices.firstOrNull { expected[it].toRawBits() != actual[it].toRawBits() }
        if (first != null) fail("$what: DEPTH 0 moved sample $first: frozen ${expected[first]}, now ${actual[first]}")
    }

    @Test
    fun `the one-shot loop is the frozen copy at DEPTH 0, at both render rates`() {
        var cases = 0
        for (voice in GlintVoice.entries) {
            for ((name, macros) in cornersFor(voice)) {
                for (rate in listOf(Dsp.RATE, Dsp.RATE * Dsp.OVERSAMPLE)) {
                    assertBitIdentical(
                        LegacyGlint.synthesize(voice, macros, rate),
                        Glint.synthesize(voice, macros, rate),
                        "$voice $name at $rate",
                    )
                    cases++
                }
            }
        }
        assertEquals(4 * 7 * 2, cases)
    }

    @Test
    fun `render is the frozen copy at DEPTH 0, and so is a patch saved without DEPTH`() {
        for (voice in GlintVoice.entries) {
            for ((name, macros) in cornersFor(voice)) {
                val legacy = LegacyGlint.render(voice, macros)
                assertBitIdentical(legacy, Glint.render(voice, macros).samples, "$voice $name render")
                // A patch only holds macros its voice has (VOWEL has no FOLLOW).
                val saved = GlintPatch("Old", voice, macros.filterKeys { it in Glint.defaults(voice) })
                assertBitIdentical(legacy, saved.render().samples, "$voice $name, a patch saved without DEPTH")
            }
        }
    }

    @Test
    fun `the held loop is the frozen copy at DEPTH 0`() {
        var cases = 0
        for (voice in GlintVoice.entries) {
            for (tune in listOf(0f, 1f)) {
                for (bloom in listOf(0.5f, 0.85f)) {
                    val macros = mapOf("TUNE" to tune, "BLOOM" to bloom)
                    val legacy = LegacyGlint.held(voice, macros)
                    val now = GlintHeld.render(voice, macros)
                    assertEquals(legacy.loopStart, now.loopStart, "$voice TUNE $tune BLOOM $bloom: the loop marker moved")
                    assertBitIdentical(legacy.audio, now.audio, "$voice TUNE $tune BLOOM $bloom held")
                    cases++
                }
            }
        }
        assertEquals(16, cases)
    }

    @Test
    fun `the held loop is the frozen copy at DEPTH 0 under a rising BLOOM, a zero BODY and both corners`() {
        var cases = 0
        for (voice in GlintVoice.entries) {
            for ((name, macros) in heldMapsFor(voice)) {
                val legacy = LegacyGlint.held(voice, macros)
                val now = GlintHeld.render(voice, macros)
                assertEquals(legacy.loopStart, now.loopStart, "$voice $name: the loop marker moved")
                assertBitIdentical(legacy.audio, now.audio, "$voice $name held")
                cases++
            }
        }
        assertEquals(4 * 4, cases)
    }

    @Test
    fun `an explicit DEPTH 0 and an omitted DEPTH are the same bits`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (voice in GlintVoice.entries) {
            assertBitIdentical(
                Glint.synthesize(voice, emptyMap(), rate),
                Glint.synthesize(voice, mapOf("DEPTH" to 0f), rate),
                "$voice one-shot",
            )
            val omitted = GlintHeld.render(voice, emptyMap())
            val zero = GlintHeld.render(voice, mapOf("DEPTH" to 0f))
            assertEquals(omitted.loopStart, zero.loopStart, "$voice held loop marker")
            assertBitIdentical(omitted.audio, zero.audio, "$voice held")
        }
    }

    // The test above compares production with production: a change that moved an explicit 0 and an
    // omitted key together would leave them equal. The four below hold the frozen copy to an explicit
    // 0 (the copy is fed the same map without a DEPTH key) and to a patch file that has no DEPTH.

    @Test
    fun `the one-shot loop is the frozen copy at an explicit DEPTH 0, at both render rates`() {
        var cases = 0
        for (voice in GlintVoice.entries) {
            for ((name, macros) in cornersFor(voice)) {
                for (rate in listOf(Dsp.RATE, Dsp.RATE * Dsp.OVERSAMPLE)) {
                    assertBitIdentical(
                        LegacyGlint.synthesize(voice, macros, rate),
                        Glint.synthesize(voice, macros + ("DEPTH" to 0f), rate),
                        "$voice $name with an explicit DEPTH 0 at $rate",
                    )
                    cases++
                }
            }
        }
        assertEquals(4 * 7 * 2, cases)
    }

    @Test
    fun `render is the frozen copy at an explicit DEPTH 0`() {
        var cases = 0
        for (voice in GlintVoice.entries) {
            for ((name, macros) in cornersFor(voice)) {
                assertBitIdentical(
                    LegacyGlint.render(voice, macros),
                    Glint.render(voice, macros + ("DEPTH" to 0f)).samples,
                    "$voice $name render with an explicit DEPTH 0",
                )
                cases++
            }
        }
        assertEquals(4 * 7, cases)
    }

    @Test
    fun `the held loop is the frozen copy at an explicit DEPTH 0 under a rising BLOOM, a zero BODY and both corners`() {
        var cases = 0
        for (voice in GlintVoice.entries) {
            for ((name, macros) in heldMapsFor(voice)) {
                val legacy = LegacyGlint.held(voice, macros)
                val now = GlintHeld.render(voice, macros + ("DEPTH" to 0f))
                assertEquals(legacy.loopStart, now.loopStart, "$voice $name with an explicit DEPTH 0: the loop marker moved")
                assertBitIdentical(legacy.audio, now.audio, "$voice $name held with an explicit DEPTH 0")
                cases++
            }
        }
        assertEquals(4 * 4, cases)
    }

    /**
     * A patch file saved before DEPTH existed reaches the engine as JSON text: [Patches.fromJsonText]
     * parses it, decodes it and migrates its BLOOM (a no-op for a patch saved under a voice's own name,
     * as these are), and only then does [GlintPatch]'s constructor run and validate the macros. This
     * test adds that path in front of the constructor path covered by
     * `render is the frozen copy at DEPTH 0, and so is a patch saved without DEPTH`.
     */
    @Test
    fun `a patch file saved without DEPTH, loaded through the JSON path, renders as the frozen copy`() {
        var cases = 0
        for (voice in GlintVoice.entries) {
            for ((name, corner) in cornersFor(voice)) {
                // A patch only holds macros its voice has (VOWEL has no FOLLOW).
                val macros = corner.filterKeys { it in Glint.defaults(voice) }
                val json = GlintPatch("Old", voice, macros).toJsonText()
                assertFalse("DEPTH" in json, "$voice $name: the saved text must not name DEPTH")
                val loaded = Patches.fromJsonText(json) as GlintPatch
                assertBitIdentical(
                    LegacyGlint.render(voice, macros),
                    loaded.render().samples,
                    "$voice $name, a patch file loaded without DEPTH",
                )
                cases++
            }
        }
        assertEquals(4 * 7, cases)
    }
}
