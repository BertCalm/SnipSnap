package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnswerTest {

    private val s16 = Mpc3Clip.PULSES_PER_16TH

    private fun kit() = Kit(
        "Chamber",
        listOf(KitPad(1, "A01_Kick_01.wav")),
        key = KeySpec.parse("Am"),
    )

    /**
     * Two bars: strong kicks and snares on the quarters, and weak hats on
     * every other off-8th (steps 1, 5, 9, 13) played consistently 25
     * pulses late — a pocket the answer must both avoid (the hits) and
     * adopt (the lean), with off-16ths left free for a shaker to take.
     */
    private fun groove(): Mpc3Clip {
        val notes = mutableListOf<Mpc3Note>()
        for (bar in 0 until 2) {
            val b = bar * Mpc3Clip.PULSES_PER_BAR
            notes += Mpc3Note(36, b, 0.9f)
            notes += Mpc3Note(36, b + 8 * s16, 0.88f)
            notes += Mpc3Note(38, b + 4 * s16, 0.85f)
            notes += Mpc3Note(38, b + 12 * s16, 0.9f)
            for (e in 0 until 4) {
                notes += Mpc3Note(42, b + (e * 4 + 1) * s16 + 25, 0.4f)
            }
        }
        return Mpc3Clip("Chamber Groove", 2, notes)
    }

    @Test
    fun `same seed same answer, another seed another answer`() {
        val a = Answer.derive(kit(), groove(), seed = 7)
        val b = Answer.derive(kit(), groove(), seed = 7)
        assertEquals(a.clip, b.clip, "the answer rerolls only when asked")
        assertTrue(a.one.sample.samples.contentEquals(b.one.sample.samples))
        val c = Answer.derive(kit(), groove(), seed = 8)
        assertTrue(a.clip.notes != c.clip.notes, "a new seed is a new B-side")
    }

    @Test
    fun `the answer plays the gaps - never the kit's strong hits - in the kit's key`() {
        val g = groove()
        val d = Answer.derive(kit(), g, seed = 3)
        assertTrue(d.clip.notes.isNotEmpty())

        // Rebuild the groove's step strengths the way the derivation reads them.
        val steps = g.bars * 16
        val strength = FloatArray(steps)
        for (n in g.notes) {
            val pos = (((n.timePulses + s16 / 2) / s16) % steps).toInt()
            if (n.velocity > strength[pos]) strength[pos] = n.velocity
        }
        val strongCut = Answer.STRONG_FRACTION * g.notes.maxOf { it.velocity }

        val aMinor = setOf(9, 11, 0, 2, 4, 5, 7) // A B C D E F G as pitch classes
        for (n in d.clip.notes) {
            val step = (((n.timePulses + s16 / 2) / s16) % steps).toInt()
            assertTrue(strength[step] < strongCut, "answer note at step $step sits on a strong hit")
            assertTrue(n.note % 12 in aMinor, "note ${n.note} is outside A minor")
            assertTrue(n.note >= 36, "the answer lives in the bass register, got ${n.note}")
        }
        // Weighted toward home: the root's pitch class leads the line.
        val rootCount = d.clip.notes.count { it.note % 12 == 9 }
        assertTrue(rootCount >= d.clip.notes.size / 3, "root-heavy: $rootCount of ${d.clip.notes.size}")
        assertTrue(d.one.rootMidi % 12 == 9, "the bass note itself is rooted on A, got ${d.one.rootMidi}")
    }

    @Test
    fun `the answer leans the way the drummer does`() {
        val d = Answer.derive(kit(), groove(), seed = 3)
        // The donor's odd 16ths land 25 pulses late; the answer's odd-step
        // notes must carry that exact lean, and its even-step notes stay
        // straight (the donor's quarters are on the grid).
        var oddSeen = false
        for (n in d.clip.notes) {
            val grid = (n.timePulses + s16 / 2) / s16 * s16
            val step = (grid / s16).toInt()
            val offset = n.timePulses - grid
            if (step % 2 == 1) {
                assertEquals(25L, offset, "odd step $step leans 25 pulses late like the hats")
                oddSeen = true
            } else {
                assertEquals(0L, offset, "even step $step stays straight like the kicks")
            }
        }
        assertTrue(oddSeen, "the answer used at least one off-16th (this groove leaves them open)")
    }

    @Test
    fun `the band stays out of everyone's way and in the key`() {
        val g = groove()
        val band = Answer.band(kit(), g, seed = 3)
        val stabs = band.stabs
        val shaker = band.shaker
        assertTrue(stabs != null && stabs.clip.notes.isNotEmpty(), "this groove leaves room for stabs")
        assertTrue(shaker != null && shaker.clip.notes.isNotEmpty(), "and for a tick")

        // Asking for the band never rewrites the answer itself.
        assertEquals(Answer.derive(kit(), g, seed = 3).clip, band.bass.clip)

        val steps = g.bars * 16
        val strength = FloatArray(steps)
        for (n in g.notes) {
            val pos = (((n.timePulses + s16 / 2) / s16) % steps).toInt()
            if (n.velocity > strength[pos]) strength[pos] = n.velocity
        }
        val strongCut = Answer.STRONG_FRACTION * g.notes.maxOf { it.velocity }
        val bassSteps = band.bass.clip.notes
            .map { (((it.timePulses + s16 / 2) / s16) % steps).toInt() }.toSet()

        val aMinor = setOf(9, 11, 0, 2, 4, 5, 7)
        for (n in stabs!!.clip.notes) {
            val step = (((n.timePulses + s16 / 2) / s16) % steps).toInt()
            assertTrue(strength[step] < strongCut, "a stab at step $step sits on a strong hit")
            assertTrue(step !in bassSteps, "a stab at step $step sits on the bass")
            assertTrue(n.note % 12 in aMinor, "stab tone ${n.note} is outside A minor")
        }
        // Stabs are triads: every hit time carries exactly root, third, fifth.
        for ((time, chord) in stabs.clip.notes.groupBy { it.timePulses }) {
            assertEquals(
                listOf(0, 3, 7), chord.map { it.note - (48 + 9) }.sorted(),
                "the stab at $time is the scale's own triad",
            )
        }
        for (n in shaker!!.clip.notes) {
            val step = (((n.timePulses + s16 / 2) / s16) % steps).toInt()
            assertTrue(step % 2 == 1, "the tick lives on off-16ths, got step $step")
            assertTrue(strength[step] == 0f, "the tick only takes completely free steps")
        }

        val same = Answer.band(kit(), g, seed = 3)
        assertEquals(band.stabs!!.clip, same.stabs!!.clip, "same seed, same band")
        assertEquals(band.shaker!!.clip, same.shaker!!.clip)
        val other = Answer.band(kit(), g, seed = 4)
        assertTrue(band.stabs!!.clip.notes != other.stabs!!.clip.notes, "a new seed is a new comp")
    }

    @Test
    fun `no key, no answer - and a wall of hits leaves nothing to say`() {
        assertFailsWith<IllegalArgumentException> {
            Answer.derive(kit().copy(key = null), groove())
        }
        // Every 16th at full velocity: no gaps anywhere.
        val wall = Mpc3Clip(
            "Wall", 1,
            (0 until 16).map { Mpc3Note(36, it * s16, 1f) },
        )
        assertFailsWith<IllegalArgumentException> { Answer.derive(kit(), wall) }
    }
}
