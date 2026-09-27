package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SIREN, held (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md,
 * door 3): [Keys.sirenPad]'s own doubling trick, not [Keys.seamError].
 *
 * `Keys.seamError` compares the 256 frames before the loop starts against
 * the 256 frames before the file ends — a real check for RESIN, whose
 * attack and loop are different audio computed once. Applied to
 * [Keys.sirenPad]'s doubled loop it would compare copy one's tail against
 * copy two's tail, which are bit-identical by construction: a pass that
 * proves nothing about the wrap a held key actually plays (copy two's own
 * tail back to copy two's own head). That wrap is [Siren.renderLoop]'s own
 * promise, already held to float noise by `SirenTest`'s "the LOOP's wrap is
 * seamless" — this file tests what [Keys.sirenPad] adds on top: the right
 * zones, the right pitch, and a marker that plays that promise back.
 */
class SirenHeldTest {

    @Test
    fun `nine zones every minor third, the same for every voice`() {
        val want = listOf(60, 63, 66, 69, 72, 75, 78, 81, 84)
        assertEquals(want, Keys.sirenPadMidis())
        for (voice in SirenVoice.entries) {
            // The layout never reads the voice; asking each one is cheap
            // insurance against sirenPadMidis growing a voice branch that
            // silently disagrees with sirenPad's own low/high bound.
            assertEquals(want, Keys.sirenPadMidis())
            Keys.sirenPad(voice, emptyMap(), want.first())
            Keys.sirenPad(voice, emptyMap(), want.last())
        }
    }

    @Test
    fun `a midi outside the nine zones is refused`() {
        assertFailsWith<IllegalArgumentException> { Keys.sirenPad(SirenVoice.WAIL, emptyMap(), 59) }
        assertFailsWith<IllegalArgumentException> { Keys.sirenPad(SirenVoice.WAIL, emptyMap(), 85) }
    }

    @Test
    fun `the render is two bit-identical copies, the marker at the second`() {
        for (voice in SirenVoice.entries) {
            val note = Keys.sirenPad(voice, emptyMap(), Keys.sirenPadMidis()[4])
            val n = note.snip.samples.size
            assertTrue(n % 2 == 0, "$voice: an odd length can't be two equal copies")
            val half = n / 2
            assertEquals(half.toLong(), note.loopStartFrame, "$voice: the marker sits at the second copy")
            val copy1 = note.snip.samples.copyOfRange(0, half)
            val copy2 = note.snip.samples.copyOfRange(half, n)
            assertTrue(copy1.contentEquals(copy2), "$voice: the two copies must be bit-identical")
            // The marker copy is exactly Siren.renderLoop's own output for
            // the same recipe — sirenPad adds the marker, not new audio.
            val tune = (Keys.sirenPadMidis()[4] - Keys.sirenPadMidis().first()) / Siren.TUNE_SEMITONES.toFloat()
            val direct = Siren.renderLoop(voice, Siren.defaults(voice) + ("TUNE" to tune))
            assertTrue(copy2.contentEquals(direct), "$voice: copy two must be Siren.renderLoop's own render, unchanged")
        }
    }

    @Test
    fun `every zone's centre frequency lands on its MIDI pitch`() {
        // Not autocorrelation on the rendered audio: WAIL's own default DEPTH
        // is a full swung octave of vibrato (macrosFor's 12-semitone
        // default), so no single period near the centre pitch repeats often
        // enough for a general-purpose detector to lock onto it. Nor is it a
        // time-average of instantaneous frequency (e.g. a zero-crossing
        // count) — 2^x is convex, so the linear-Hz time-average of a
        // symmetric vibrato sits measurably *above* the centre (Jensen's
        // inequality), a gap this repo's own 20% GRIT+DEPTH presets make
        // large enough to fail a naive check meant to catch a real semitone
        // of mistuning. The musical centre — what a vibrato is heard as
        // being *around* — is the geometric mean of instantaneous
        // frequency, which planLoop's own construction fixes to `baseHz`
        // exactly (its KDoc: "moved so the pulse completes exactly cycles
        // cycles over the loop"), so that is what a held key must land on.
        for (voice in SirenVoice.entries) {
            for (midi in Keys.sirenPadMidis()) {
                val tune = (midi - Keys.sirenPadMidis().first()) / Siren.TUNE_SEMITONES.toFloat()
                val macros = Siren.defaults(voice) + ("TUNE" to tune)
                val plan = Siren.planLoop(voice, macros)
                val expected = Keys.midiHz(midi)
                val cents = 1200f * kotlin.math.ln(plan.baseHz.toFloat() / expected) / kotlin.math.ln(2f)
                assertTrue(kotlin.math.abs(cents) < 5f, "$voice midi $midi: expected ${expected}Hz, baseHz ${plan.baseHz} ($cents cents)")
            }
        }
    }

    @Test
    fun `sirenPad is deterministic`() {
        val midi = Keys.sirenPadMidis()[2]
        val a = Keys.sirenPad(SirenVoice.LASER, mapOf("GRIT" to 0.7f), midi)
        val b = Keys.sirenPad(SirenVoice.LASER, mapOf("GRIT" to 0.7f), midi)
        assertTrue(a.snip.samples.contentEquals(b.snip.samples))
        assertEquals(a.loopStartFrame, b.loopStartFrame)
    }

    @Test
    fun `RATE moves the render, HOLD and SWEEP do not`() {
        val midi = Keys.sirenPadMidis()[4]
        val plain = Keys.sirenPad(SirenVoice.WAIL, emptyMap(), midi)
        // HOLD/SWEEP moved, nothing else: Siren.renderLoop ignores both, so
        // the render must be unchanged — the same fact Siren's own render()
        // dispatch relies on to call renderLoop regardless of HOLD's value.
        val holdMoved = Keys.sirenPad(SirenVoice.WAIL, mapOf("HOLD" to 0.2f, "SWEEP" to 0.1f), midi)
        assertTrue(plain.snip.samples.contentEquals(holdMoved.snip.samples), "HOLD/SWEEP must not move a held SIREN's render")
        // RATE moved: the loop's own length changes (Siren.loopFrames depends on RATE), so the render must differ.
        val rateMoved = Keys.sirenPad(SirenVoice.WAIL, mapOf("RATE" to 0.9f), midi)
        assertTrue(!plain.snip.samples.contentEquals(rateMoved.snip.samples), "RATE must move a held SIREN's render")
    }
}
