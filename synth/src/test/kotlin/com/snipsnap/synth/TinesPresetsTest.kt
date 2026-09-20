package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, TINES' turn: twelve presets per voice
 * (sixty total). No classifier "identity" check here the way THUMP's has
 * one — TINES has no drum-class ambiguity for a preset to fall on the
 * wrong side of (every TINES voice reads as PERC or TONAL by fixed
 * mapping in `SynthScreen.kt`, not by analyzing the render) — so this
 * suite covers what's left of THUMP's playability contract: a real,
 * clean sound; a faithful JSON round-trip; and names the sunken listbox
 * and naming rule both require. (No spread check: good presets cluster
 * in the narrow regions of macro space that actually sound good, so
 * "evenly spread" is not a property worth enforcing.)
 */
class TinesPresetsTest {

    @Test
    fun `every preset renders clean non-silent audio`() {
        for (voice in TinesVoice.entries) {
            for (preset in TinesPresets.forVoice(voice)) {
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
        for (preset in TinesPresets.all()) {
            val restored = TinesPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in TinesVoice.entries) {
            val names = TinesPresets.forVoice(voice).map { it.name }
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
        val offenders = TinesPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }
}
