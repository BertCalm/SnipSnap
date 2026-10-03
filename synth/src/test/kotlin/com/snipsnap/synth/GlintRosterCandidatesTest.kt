package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The roster candidates (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2): a stratified spread, not a choice. */
class GlintRosterCandidatesTest {

    private fun top(name: String): Float = if (name == "DEPTH") GlintRosterCandidates.DEPTH_MAX else 1f

    /** Which of the sixteen evenly spaced levels, 0 to 15, candidate [c] has for [name]. */
    private fun levelOf(name: String, c: GlintRosterCandidates.Candidate): Int =
        Math.round(c.macros.getValue(name) / top(name) * 15f)

    @Test
    fun `sixteen candidates per voice, numbered 1 to 16, each setting every macro its voice has and no other`() {
        for (voice in GlintVoice.entries) {
            val candidates = GlintRosterCandidates.forVoice(voice)
            assertEquals((1..16).toList(), candidates.map { it.number }, "$voice: numbering")
            val names = Glint.macrosFor(voice).map { it.name }.toSet()
            for (c in candidates) {
                assertEquals(names, c.macros.keys, "$voice-${c.number}: the macros it sets")
                for ((name, value) in c.macros) {
                    assertTrue(value in 0f..1f, "$voice-${c.number}: $name is $value, outside 0..1")
                }
            }
        }
    }

    @Test
    fun `each varied macro takes each of its sixteen evenly spaced levels exactly once`() {
        for (voice in GlintVoice.entries) {
            val candidates = GlintRosterCandidates.forVoice(voice)
            for (name in GlintRosterCandidates.varied(voice)) {
                assertEquals((0..15).toList(), candidates.map { levelOf(name, it) }.sorted(), "$voice $name: the levels taken")
                for (c in candidates) {
                    val value = c.macros.getValue(name)
                    val want = levelOf(name, c) / 15f * top(name)
                    assertTrue(abs(value - want) < 1e-6f, "$voice-${c.number} $name is $value, not level ${levelOf(name, c)}/15 ($want)")
                }
            }
        }
    }

    @Test
    fun `DEPTH reaches 0 and 0_9 and never 1, which is the same bare sine on every voice`() {
        for (voice in GlintVoice.entries) {
            val depths = GlintRosterCandidates.forVoice(voice).map { it.macros.getValue("DEPTH") }
            assertTrue(depths.max() <= 0.9f + 1e-6f, "$voice: DEPTH reaches ${depths.max()}")
            assertTrue(depths.min() == 0f, "$voice: DEPTH never reaches 0, its lowest is ${depths.min()}")
        }
    }

    @Test
    fun `TUNE, DECAY, FOLLOW and every macro that is not varied stay at their defaults`() {
        for (voice in GlintVoice.entries) {
            val defaults = Glint.defaults(voice)
            val varied = GlintRosterCandidates.varied(voice)
            assertTrue(
                "TUNE" !in varied && "DECAY" !in varied && "FOLLOW" !in varied,
                "$voice: TUNE, DECAY and FOLLOW are not to be varied; none can be heard at the clips' one zone",
            )
            for (c in GlintRosterCandidates.forVoice(voice)) {
                for ((name, value) in c.macros) {
                    if (name !in varied) assertEquals(defaults.getValue(name), value, "$voice-${c.number}: $name left its default")
                }
            }
        }
    }

    @Test
    fun `the varied macros are the spec's, and each is one its voice has`() {
        for (voice in GlintVoice.entries) {
            val want = listOf("PEAK", "BODY", "BLOOM", "DEPTH")
            assertEquals(want, GlintRosterCandidates.varied(voice), "$voice: the varied macros")
            val has = Glint.macrosFor(voice).map { it.name }
            assertTrue(want.all { it in has }, "$voice: a varied macro that the voice does not have")
        }
    }

    @Test
    fun `the macros are shuffled against one another, not stepped in lockstep`() {
        for (voice in GlintVoice.entries) {
            val candidates = GlintRosterCandidates.forVoice(voice)
            val names = GlintRosterCandidates.varied(voice)
            val levels = names.associateWith { name -> candidates.map { levelOf(name, it) } }
            for ((name, seq) in levels) {
                assertTrue(seq != seq.sorted() && seq != seq.sortedDescending(), "$voice $name: its levels are in order, not shuffled")
            }
            for (a in names.indices) {
                for (b in a + 1 until names.size) {
                    assertTrue(levels.getValue(names[a]) != levels.getValue(names[b]), "$voice: ${names[a]} and ${names[b]} take the same level at every candidate")
                }
            }
        }
    }

    @Test
    fun `a voice draws the same twice, and voices that share their macros draw differently`() {
        val sweep = GlintRosterCandidates.forVoice(GlintVoice.SWEEP).map { it.macros }
        assertEquals(sweep, GlintRosterCandidates.forVoice(GlintVoice.SWEEP).map { it.macros }, "SWEEP: a second draw differs from the first")
        for (other in listOf(GlintVoice.STEP, GlintVoice.BRASS)) {
            assertTrue(sweep != GlintRosterCandidates.forVoice(other).map { it.macros }, "$other draws exactly as SWEEP does")
        }
    }
}
