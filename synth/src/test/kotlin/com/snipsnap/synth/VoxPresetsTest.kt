package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, VOX's turn: twelve presets per voice
 * (thirty-six total). No classifier "identity" check — every VOX voice is
 * statically TONAL, not judged from its render — so this covers the rest
 * of the playability contract: a real, clean sound; a faithful JSON
 * round-trip; listbox-legal names; and a roster that doesn't cluster.
 */
class VoxPresetsTest {

    @Test
    fun `every preset renders clean non-silent audio`() {
        for (voice in VoxVoice.entries) {
            for (preset in VoxPresets.forVoice(voice)) {
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
        for (preset in VoxPresets.all()) {
            val restored = VoxPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in VoxVoice.entries) {
            val names = VoxPresets.forVoice(voice).map { it.name }
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
        val offenders = VoxPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // Thresholds are set from what the table actually achieves, not chosen
    // to just barely pass — same discipline ThumpPresets' own thresholds
    // follow.
    private val spreadThreshold = mapOf(
        VoxVoice.CHOIR to 0.06f,
        VoxVoice.ROBOT to 0.05f,
        VoxVoice.GHOST to 0.09f,
    )

    @Test
    fun `presets within a voice do not cluster`() {
        for (voice in VoxVoice.entries) {
            val presets = VoxPresets.forVoice(voice)
            val threshold = spreadThreshold.getValue(voice)
            var minDist = Float.MAX_VALUE
            var closest: Pair<String, String>? = null
            for (i in presets.indices) {
                for (j in i + 1 until presets.size) {
                    val d = PresetTestSupport.rmsDistance(presets[i].macros, presets[j].macros)
                    if (d < minDist) {
                        minDist = d
                        closest = presets[i].name to presets[j].name
                    }
                }
            }
            assertTrue(
                minDist >= threshold,
                "$voice: closest pair $closest is only $minDist apart (need >= $threshold)",
            )
        }
    }
}
