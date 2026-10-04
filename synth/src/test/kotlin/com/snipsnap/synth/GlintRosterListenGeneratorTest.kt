package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The roster's final listen (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2.5): one file per preset. */
class GlintRosterListenGeneratorTest {

    @Test
    fun `a preset's file is its one-shot, then the pad at the lowest, the middle and the top key, a gap before each`() {
        // SWEEP has a short onset; STEP climbs a ladder first, so its pads run to the end of the first whole breath.
        for (voice in listOf(GlintVoice.SWEEP, GlintVoice.STEP)) {
            val preset = GlintPresets.forVoice(voice).first()
            val clip = GlintRosterListenGenerator.clipFor(preset)
            val rate = clip.sampleRate
            val oneShot = AuditionLevel.level(preset.render()).samples
            val midis = Keys.glintPadMidis(voice)
            val gap = (0.6f * rate).toInt()
            assertContentEquals(oneShot, clip.samples.copyOfRange(0, oneShot.size), "$voice: the file does not begin with the one-shot")
            var at = oneShot.size
            for (midi in listOf(midis.first(), midis[midis.size / 2], midis.last())) {
                val pad = GlintRosterListenGenerator.padFor(preset, midi, rate)
                assertTrue(clip.samples.copyOfRange(at, at + gap).all { it == 0f }, "$voice: no gap before the pad at MIDI $midi")
                at += gap
                assertContentEquals(pad, clip.samples.copyOfRange(at, at + pad.size), "$voice: the pad at MIDI $midi is not where the file puts it")
                at += pad.size
            }
            assertEquals(at, clip.frameCount, "$voice: the file holds more than its parts")
            assertTrue(clip.samples.all { it.isFinite() }, "$voice: a sample is not finite")
            assertTrue(clip.samples.maxOf { abs(it) } <= 0.99f, "$voice: the file is over the audition guard")
            assertTrue(abs(clip.samples.last()) < 1e-6f, "$voice: the top pad does not end at zero")
        }
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
