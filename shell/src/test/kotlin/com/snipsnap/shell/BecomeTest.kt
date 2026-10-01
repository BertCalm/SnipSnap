package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpVoice
import com.snipsnap.synth.Tines
import com.snipsnap.synth.TinesVoice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BECOME (docs/superpowers/specs/2026-09-30-become-strung-say-design.md,
 * "BECOME, the design" and "Testing"): MORPH's amount as a ramp in time.
 * The first claim is the one every other rests on - BECOME 0 is today's
 * MORPH, byte for byte - and it is held against [LegacyMorph], a frozen
 * copy, not against the new code.
 */
class BecomeTest {

    private val rate = 44_100

    /** A decaying sine - the construction of CliTest's morph test (`CliTest.kt:3027-3030`). */
    private fun tone(hz: Double, sampleRate: Int, seconds: Float = 1f): Snip =
        Snip(
            FloatArray((seconds * sampleRate).toInt()) { i ->
                val t = i.toDouble() / sampleRate
                (0.6 * Math.sin(2.0 * Math.PI * hz * t) * Math.exp(-5.0 * t)).toFloat()
            },
            1, sampleRate,
        )

    /**
     * Three pairs, binding by the spec. They reach `toStereo`'s mono and
     * stereo branches (a mono kick into a mono bell; a stereo snare, WIDTH
     * above 0, into a kick) and `resampled`'s two branches (a 48 kHz tone
     * into a 44.1 kHz one, so `morph` and PGHI also run off 44.1 kHz). They
     * do NOT reach `alignToOnset` past its first exit: `Transients.detect`
     * finds no onset in any of these sounds, so each case returns at the
     * `?: return snip` and the trim is never run. The trim is held by
     * [BECOME 0 trims leading room exactly as MORPH does].
     */
    private val pairs: List<Triple<String, Snip, Snip>> by lazy {
        listOf(
            Triple("kick into bell", Thump.render(ThumpVoice.KICK), Tines.render(TinesVoice.BELL)),
            Triple("stereo snare into kick", Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 0.5f)), Thump.render(ThumpVoice.KICK)),
            Triple("48 kHz tone into 44.1 kHz tone", tone(300.0, 48_000), tone(1200.0, rate)),
        )
    }

    @Test
    fun `BECOME 0 is today's MORPH, bit for bit, against the frozen copy`() {
        assertEquals(2, pairs[1].second.channels, "the snare must be stereo or the stereo branch is never reached")
        var cases = 0
        for ((name, base, parent) in pairs) {
            for (amount in listOf(0f, 0.25f, 0.5f, 1f)) {
                val now = Mutate.render(base, listOf(Mutate.Source("parent", parent)), Mutate.Mode.MORPH, morphAmount = amount).snip
                val then = LegacyMorph.render(base, parent, amount)
                assertEquals(then.channels, now.channels, "$name at $amount: channels")
                assertEquals(then.sampleRate, now.sampleRate, "$name at $amount: rate")
                assertContentEquals(then.samples, now.samples, "$name at $amount: MORPH moved")
                cases++
            }
        }
        println("BECOME 0 against the frozen MORPH: $cases cases")
        assertEquals(12, cases)
    }

    /**
     * `alignToOnset`'s trim, the branch the three pairs above never reach: a
     * pad and a parent each led by 150 ms of silence (the construction of
     * the leading-room test) have their room cut, so MORPH renders them
     * within half the lead of the same sounds without the room (the onset
     * backoff leaves a few frames), not 150 ms longer. The precondition is
     * about the input: if it fails, the detector or its backoff moved and
     * the construction no longer reaches the trim - fix the input, not
     * `Mutate.kt`.
     */
    @Test
    fun `BECOME 0 trims leading room exactly as MORPH does`() {
        val lead = FloatArray(150 * rate / 1000)
        val base = Snip(lead + tone(300.0, rate).samples, 1, rate)
        val parent = Snip(lead + tone(1200.0, rate).samples, 1, rate)
        var cases = 0
        for (amount in listOf(0f, 0.25f, 0.5f, 1f)) {
            val untrimmed = LegacyMorph.render(tone(300.0, rate), tone(1200.0, rate), amount)
            val then = LegacyMorph.render(base, parent, amount)
            assertTrue(
                Math.abs(then.frameCount - untrimmed.frameCount) <= lead.size / 2,
                "alignToOnset did not trim the room on this construction at $amount " +
                    "(${then.frameCount} frames against ${untrimmed.frameCount} unleaded): fix the input",
            )
            val now = Mutate.render(base, listOf(Mutate.Source("parent", parent)), Mutate.Mode.MORPH, morphAmount = amount).snip
            assertEquals(then.channels, now.channels, "leaded at $amount: channels")
            assertEquals(then.sampleRate, now.sampleRate, "leaded at $amount: rate")
            assertContentEquals(then.samples, now.samples, "leaded at $amount: the trim moved")
            cases++
        }
        println("BECOME 0 trim against the frozen MORPH: $cases cases")
    }
}
