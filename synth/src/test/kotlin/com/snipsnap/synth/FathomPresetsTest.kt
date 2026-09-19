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
 *
 * GRIND was re-pinned again, from KICK to TOM, for the synth-depth phase-0
 * start-phase seeding (Task 2): its saw pair no longer starts phase-locked,
 * so the two oscillators' discontinuities land at different points in the
 * cycle instead of the same instant, roughly doubling the edge rate and
 * lifting spectral centroid across the board (see `FathomTest`'s "factory
 * defaults classify consistently" for the full explanation). That shift is
 * large enough that GRIND itself is no longer classifier-uniform: four of
 * its twelve presets now land off TOM. [presetOverrides] below pins each of
 * those explicitly, by measured centroid, rather than pretending the voice
 * still has one answer. GLASS mostly held PERC, but two presets crossed
 * into TOM by the same mechanism and are pinned the same way.
 */
class FathomPresetsTest {

    private val classifiedVoices = mapOf(
        FathomVoice.DEEP to DrumClass.KICK,
        FathomVoice.GRIND to DrumClass.TOM,
        FathomVoice.GLASS to DrumClass.PERC,
    )

    /**
     * Presets whose classification no longer matches [classifiedVoices]'
     * per-voice default, post start-phase seeding. Centroid noted for each
     * so a reviewer can see how far it moved, not just where it landed.
     */
    private val presetOverrides = mapOf(
        (FathomVoice.GRIND to "DIRTY GROWL") to DrumClass.KICK,  // centroid ~126 Hz, still under the stretch shelf
        (FathomVoice.GRIND to "LOUD GRIND") to DrumClass.KICK,   // centroid ~125 Hz, same shelf
        (FathomVoice.GRIND to "WIDE GRIND") to DrumClass.PERC,   // centroid ~214 Hz, low ratio ~0.21 - already the widest detune of the set
        (FathomVoice.GRIND to "GRAVEL BASS") to DrumClass.PERC,  // centroid ~204 Hz, low ratio ~0.34
        (FathomVoice.GLASS to "GLASSY LOW") to DrumClass.TOM,    // centroid ~155 Hz
        (FathomVoice.GLASS to "GLIDE BELL") to DrumClass.TOM,    // centroid ~145 Hz
    )

    @Test
    fun `every preset classifies as its own voice`() {
        val failures = mutableListOf<String>()
        for ((voice, expected) in classifiedVoices) {
            for (preset in FathomPresets.forVoice(voice)) {
                val want = presetOverrides[voice to preset.name] ?: expected
                val got = Classifier.classify(preset.render()).drumClass
                if (got != want) failures += "${voice.name}/${preset.name}: expected $want, got $got"
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
