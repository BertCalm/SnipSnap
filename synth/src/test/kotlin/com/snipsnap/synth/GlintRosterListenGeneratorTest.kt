package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The roster's final listen (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2.5): one file per preset. */
class GlintRosterListenGeneratorTest {

    @Test
    fun `a preset's file is its one-shot, then the pad at the lowest key, then at the top key`() {
        val preset = GlintPresets.forVoice(GlintVoice.SWEEP).first()
        val clip = GlintRosterListenGenerator.clipFor(preset)
        val rate = clip.sampleRate
        val shortest = preset.render().frameCount + 2 * (0.6f * rate).toInt() + 2 * 4 * rate
        assertTrue(clip.frameCount >= shortest, "the file is ${clip.frameCount} frames, shorter than its parts ($shortest)")
        assertTrue(clip.samples.all { it.isFinite() }, "a sample is not finite")
        assertTrue(clip.samples.maxOf { abs(it) } <= 0.99f, "the file is over the audition guard")
        assertTrue(abs(clip.samples.last()) < 1e-6f, "the top pad does not end at zero")
    }

    @Test
    fun `the file names are the roster audition's pattern, so one cleaner serves both folders`() {
        val pattern = Regex("(SWEEP|STEP|BRASS|VOWEL)-\\d\\d\\.wav")
        for (voice in GlintVoice.entries) {
            for (n in 1..8) {
                val name = GlintRosterListenGenerator.fileName(voice, n)
                assertTrue(pattern.matches(name), "$voice-$n: $name")
            }
        }
        assertEquals("STEP-03.wav", GlintRosterListenGenerator.fileName(GlintVoice.STEP, 3))
    }
}
