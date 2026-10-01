package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Spectral
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpVoice
import com.snipsnap.synth.Tines
import com.snipsnap.synth.TinesVoice
import kotlin.math.ceil
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

    @Test
    fun `the ramp is 0 up to the onset, never falls, is linear between, and MIX from BECOME on`() {
        val amount = 0.8f
        var checked = 0
        for (sr in listOf(44_100, 48_000, 96_000)) {
            // 1 ms is the CLI's floor and shorter than one analysis window: it reads as a step, and must not divide by zero.
            for (ms in listOf(1, 50, 400, 2000)) {
                val ramp = ms * sr / 1000.0 // samples
                var prev = 0f
                var f = 0
                while (true) {
                    val centre = f.toLong() * Spectral.HOP - Spectral.FRAME / 2
                    val a = Mutate.becomeAmount(f, amount, ms, sr)
                    if (centre <= 0) assertEquals(0f, a, "$sr Hz, $ms ms, frame $f: at or before the onset is the pad alone")
                    assertTrue(a >= prev, "$sr Hz, $ms ms: the ramp fell at frame $f ($prev -> $a)")
                    if (centre >= ramp) {
                        assertTrue(a == amount, "$sr Hz, $ms ms, frame $f: from BECOME on it must be MIX itself, got $a")
                        break
                    }
                    prev = a
                    f++
                }
                assertTrue(Mutate.becomeAmount(f + 1000, amount, ms, sr) == amount, "$sr Hz, $ms ms: long after BECOME it is MIX")
                for (p in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                    // The first frame centred at or after p of the ramp.
                    val at = ceil((p * ramp + Spectral.FRAME / 2) / Spectral.HOP).toInt()
                    val centre = at.toLong() * Spectral.HOP - Spectral.FRAME / 2
                    val expected = (amount * (centre / ramp).coerceIn(0.0, 1.0)).toFloat()
                    val got = Mutate.becomeAmount(at, amount, ms, sr)
                    assertEquals(expected, got, 1e-6f, "$sr Hz, $ms ms at ${(p * 100).toInt()}%: not linear in amount")
                    if (p < 1.0) {
                        // ...and within one hop of p itself, so "linear" means the spec's points, not only the formula.
                        assertTrue(got >= amount * p - 1e-6 && got <= amount * (p + Spectral.HOP / ramp) + 1e-6, "$sr Hz, $ms ms: $got is not near ${amount * p}")
                    }
                    checked++
                }
            }
        }
        assertEquals(60, checked)
        // BECOME 0 is the amount itself at every frame, the ones before the onset included: today's loop.
        for (f in listOf(0, 1, 2, 3, 100)) assertTrue(Mutate.becomeAmount(f, 0.37f, 0, 44_100) == 0.37f, "frame $f at BECOME 0")
    }
}
