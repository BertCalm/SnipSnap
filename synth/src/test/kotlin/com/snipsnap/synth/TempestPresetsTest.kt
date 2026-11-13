package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import kotlin.test.*

class TempestPresetsTest {
    @Test fun `all twenty two presets render clean full level finite audio`() {
        for (p in TempestPresets.all()) {
            val s = p.render()
            assertEquals(Tempest.OUTPUT_FRAMES, s.frameCount)
            assertTrue(s.samples.all { it.isFinite() && it in -1f..1f })
            assertTrue(Loudness.of(s) >= .9f * Dsp.MELODIC_LOUDNESS_TARGET || s.peak() >= .95f, p.name)
        }
    }
    @Test fun `factory patches round trip with identical samples`() {
        for (p in TempestPresets.all()) {
            val restored = Patches.fromJsonText(p.toJsonText())
            assertEquals(p, restored)
            assertContentEquals(p.render().samples, restored.render().samples)
        }
    }
    @Test fun `short uppercase unique names for every accepted voice`() {
        for (v in TempestVoice.entries) {
            val names = TempestPresets.forVoice(v).map { it.name }
            assertEquals(when (v) { TempestVoice.KICK, TempestVoice.METAL -> 6; TempestVoice.SNARE, TempestVoice.CLAP -> 2; else -> 3 }, names.size)
            assertEquals(names.size, names.toSet().size)
            assertTrue(names.all { it == it.uppercase() && it.length <= 14 })
        }
    }
    @Test fun `names avoid makers and machine trademarks`() {
        for (p in TempestPresets.all()) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name), p.name)
            assertFalse(Regex("erica|perkons", RegexOption.IGNORE_CASE).containsMatchIn(p.name), p.name)
        }
    }
    @Test fun `presets save all controls with approved model and reserved hold`() {
        assertEquals(22, TempestPresets.all().size)
        for (p in TempestPresets.all()) {
            assertEquals(Tempest.defaultModelFor(p.voice), p.model)
            assertEquals(Tempest.defaults(p.voice).keys, p.macros.keys)
            assertEquals(0f, p.macros.getValue("HOLD"))
            assertTrue(p.macros.values.all { it in 0f..1f })
        }
    }
    @Test fun `colors spread through the accepted macro settings`() {
        for (v in TempestVoice.entries) {
            val ps = TempestPresets.forVoice(v)
            for ((i, a) in ps.withIndex()) for (b in ps.drop(i + 1))
                assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .05f, "${a.name} ${b.name}")
        }
    }
}
