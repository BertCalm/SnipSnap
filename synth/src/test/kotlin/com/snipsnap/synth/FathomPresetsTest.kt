package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, FATHOM's turn: twelve presets per voice
 * (thirty-six total).
 *
 * Copilot's review of this PR caught a real mistake in an earlier version
 * of this file: FATHOM is *not* like TINES/PLUCK/VELVET/TONEWHEEL/VOX
 * here. Those five are statically TONAL in `SynthScreen`'s own mapping,
 * never judged from a render — but FATHOM isn't in `SynthScreen`'s
 * `Engine` enum at all yet (a pre-existing gap this PR doesn't close; see
 * the file's own top KDoc, which already named only five engines joining
 * THUMP), and `FathomTest`'s own `factory defaults classify consistently`
 * establishes that FATHOM's raw voices carry real classifier ambiguity:
 * DEEP and GRIND read as `DrumClass.KICK`, GLASS as `DrumClass.PERC` — and
 * GRIND/GLASS were each *re-pinned* once already as their DSP changed. A
 * preset is exactly the kind of macro move that could push a voice across
 * that boundary silently, so this needs the same identity check THUMP's
 * own preset suite has, not the "no ambiguity" claim this file used to
 * make.
 */
class FathomPresetsTest {

    private val classifiedVoices = mapOf(
        FathomVoice.DEEP to DrumClass.KICK,
        FathomVoice.GRIND to DrumClass.KICK,
        FathomVoice.GLASS to DrumClass.PERC,
    )

    @Test
    fun `every preset classifies as its own voice`() {
        val failures = mutableListOf<String>()
        for ((voice, expected) in classifiedVoices) {
            for (preset in FathomPresets.forVoice(voice)) {
                val got = Classifier.classify(preset.render()).drumClass
                if (got != expected) failures += "${voice.name}/${preset.name}: expected $expected, got $got"
            }
        }
        assertTrue(failures.isEmpty(), "presets that don't classify as their own voice:\n${failures.joinToString("\n")}")
    }

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
