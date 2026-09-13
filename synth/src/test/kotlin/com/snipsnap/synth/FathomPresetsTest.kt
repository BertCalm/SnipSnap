package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, FATHOM's turn: twelve presets per voice
 * (thirty-six total). No classifier "identity" check — every FATHOM voice
 * is statically TONAL, not judged from its render — so this covers the
 * rest of the playability contract: a real, clean sound; a faithful JSON
 * round-trip; listbox-legal names; and a roster that doesn't cluster.
 * Names are unique and thresholds are set per voice rather than globally,
 * same as every other engine here — DEEP/GRIND/GLASS each carry a
 * different sixth macro (`SWEEP`/`SPREAD`/`RATIO`), which the spread
 * check picks up for free since it runs over whatever keys the voice's
 * own macro map actually has.
 */
class FathomPresetsTest {

    @Test
    fun `every preset renders clean non-silent audio`() {
        for (voice in FathomVoice.entries) {
            for (preset in FathomPresets.forVoice(voice)) {
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
        for (preset in FathomPresets.all()) {
            val restored = FathomPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in FathomVoice.entries) {
            val names = FathomPresets.forVoice(voice).map { it.name }
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
        val offenders = FathomPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // Thresholds are set from what the table actually achieves, not chosen
    // to just barely pass — same discipline ThumpPresets' own thresholds
    // follow.
    private val spreadThreshold = mapOf(
        FathomVoice.DEEP to 0.06f,
        FathomVoice.GRIND to 0.06f,
        FathomVoice.GLASS to 0.07f,
    )

    @Test
    fun `presets within a voice do not cluster`() {
        for (voice in FathomVoice.entries) {
            val presets = FathomPresets.forVoice(voice)
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
