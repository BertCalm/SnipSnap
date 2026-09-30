package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, RESIN's turn: twelve presets per voice
 * (thirty-six total), and BRASS's two string machines after them (thirty-eight;
 * their landings are [StringMachineTest]'s). No classifier identity check — every RESIN voice is
 * statically TONAL in `SynthScreen`'s mapping, like VELVET's — so this is
 * the rest of the playability contract: a real, clean sound; a faithful
 * JSON round-trip; listbox-legal names under the naming rule. Authored
 * from `Resin.kt`'s DSP (the STACK curve, CONTOUR's octave sweep,
 * CREAM's 1/(1+r) thinning), not by ear.
 */
class ResinPresetsTest {

    @Test
    fun `every preset renders clean non-silent audio`() {
        for (voice in ResinVoice.entries) {
            for (preset in ResinPresets.forVoice(voice)) {
                val snip = preset.render()
                assertTrue(snip.frameCount > 0, "${preset.name} rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() }, "${preset.name} produced non-finite samples")
                assertTrue(snip.samples.all { it in -1f..1f }, "${preset.name} clipped")
                assertTrue(snip.peak() > 0.5f, "${preset.name} is too quiet: ${snip.peak()}")
            }
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in ResinPresets.all()) {
            val restored = ResinPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in ResinVoice.entries) {
            val names = ResinPresets.forVoice(voice).map { it.name }
            // Twelve, and BRASS's two string machines (StringMachine) after its twelve.
            val expected = if (voice == ResinVoice.BRASS) 14 else 12
            assertEquals(expected, names.size, "$voice should ship $expected presets, has ${names.size}")
            assertEquals(names.toSet().size, names.size, "$voice has duplicate preset names: $names")
            for (name in names) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
        }
    }

    @Test
    fun `no preset name references a real drum machine`() {
        val offenders = ResinPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }
}
