package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, TONEWHEEL's turn: twelve presets per
 * voice (thirty-six total). No classifier "identity" check — every
 * TONEWHEEL voice is statically TONAL, not judged from its render — so
 * this covers the rest of the playability contract: a real, clean sound;
 * a faithful JSON round-trip; and listbox-legal names. (No spread check:
 * good presets cluster in the narrow regions of macro space that
 * actually sound good, so "evenly spread" is not a property worth
 * enforcing.)
 */
class TonewheelPresetsTest {

    @Test
    fun `every preset renders clean non-silent audio`() {
        for (voice in TonewheelVoice.entries) {
            for (preset in TonewheelPresets.forVoice(voice)) {
                val snip = preset.render()
                assertTrue(snip.frameCount > 0, "${preset.name} rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() }, "${preset.name} produced non-finite samples")
                assertTrue(snip.samples.all { it in -1f..1f }, "${preset.name} clipped")
                // 0.5 assumed render() still peak-normalized to 0.95. Task 4
                // swapped that for Dsp.levelTo, a loudness target - every
                // preset here measures within noise of the same 0.1834
                // loudness now (confirmed directly), landing peaks around
                // 0.22-0.49 depending on registration. This floor only needs
                // to catch a genuinely silent render.
                assertTrue(snip.peak() > 0.1f, "${preset.name} is too quiet: ${snip.peak()}")
            }
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in TonewheelPresets.all()) {
            val restored = TonewheelPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in TonewheelVoice.entries) {
            val names = TonewheelPresets.forVoice(voice).map { it.name }
            assertEquals(12, names.size, "$voice should ship 12 presets, has ${names.size}")
            assertEquals(names.toSet().size, names.size, "$voice has duplicate preset names: $names")
            for (name in names) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
        }
    }

    @Test
    fun `no preset name references a real drum machine`() {
        val offenders = TonewheelPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }
}
