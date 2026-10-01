package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpVoice
import com.snipsnap.synth.Tines
import com.snipsnap.synth.TinesVoice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
     * Three pairs that reach every branch of the frozen path: a mono kick
     * into a mono bell; a stereo snare (WIDTH above 0, so `toStereo` passes
     * it through untouched) into a kick; and a pad at 48 kHz into a parent at
     * 44.1 kHz, so `resampled` runs and `morph` and PGHI both run at a rate
     * that is not 44.1 kHz.
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
}
