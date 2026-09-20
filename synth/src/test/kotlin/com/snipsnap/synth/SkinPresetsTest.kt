package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SkinPresets] held to the same bar every other engine's preset list is.
 *
 * Shaped after `ThumpPresetsTest` deliberately — the eighth engine's
 * presets should be checked the way the first seven's are, not a new way.
 */
class SkinPresetsTest {

    /**
     * The class each voice's presets have to keep. RIDE's target is
     * HAT_OPEN and that is not a typo: `SkinTest` calibrated it by
     * rendering and classifying rather than predicting, and RIDE is built
     * from `hat()`'s exact recipe — continuous noise through a resonant
     * bank — just denser and longer.
     */
    private val classifiedVoices = mapOf(
        SkinVoice.KICK to DrumClass.KICK,
        SkinVoice.SNARE to DrumClass.SNARE,
        SkinVoice.HAT_CLOSED to DrumClass.HAT_CLOSED,
        SkinVoice.HAT_OPEN to DrumClass.HAT_OPEN,
        SkinVoice.TOM to DrumClass.TOM,
        SkinVoice.RIDE to DrumClass.HAT_OPEN,
    )

    // SHAKER and STICK have no dedicated DrumClass — the same carve-out
    // ThumpPresetsTest makes for COWBELL and RIM. Measured rather than
    // assumed: both classify as SNARE at their own factory defaults, so
    // there is no honest identity to assert and they are covered by the
    // sanity check below only.

    // ---------- identity: the classifier is the judge ----------

    @Test
    fun `every preset classifies as its own voice`() {
        val failures = mutableListOf<String>()
        for ((voice, expected) in classifiedVoices) {
            for (preset in SkinPresets.forVoice(voice)) {
                val got = Classifier.classify(preset.render()).drumClass
                if (got != expected) failures += "${voice.name}/${preset.name}: expected $expected, got $got"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "presets that don't classify as their own voice:\n${failures.joinToString("\n")}\n" +
                "SkinPresets' own KDoc records the measured safe band per voice — a preset that " +
                "drifted out of one is the table to fix, not this expectation.",
        )
    }

    // ---------- sanity: every preset is a real, clean sound ----------

    @Test
    fun `every preset renders clean non-silent audio`() {
        for (voice in SkinVoice.entries) {
            for (preset in SkinPresets.forVoice(voice)) {
                val snip = preset.render()
                assertTrue(snip.frameCount > 0, "${preset.name} rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() }, "${preset.name} produced non-finite samples")
                assertTrue(snip.samples.all { it in -1f..1f }, "${preset.name} clipped")
                assertTrue(snip.peak() > 0.5f, "${preset.name} is too quiet: ${snip.peak()}")
            }
        }
    }

    // ---------- round-trip: a preset is a Patch, so it must serialize like one ----------

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in SkinPresets.all()) {
            val restored = SkinPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    // ---------- the shape of the list ----------

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in SkinVoice.entries) {
            val names = SkinPresets.forVoice(voice).map { it.name }
            assertEquals(16, names.size, "$voice should ship 16 presets, has ${names.size}")
            assertEquals(names.toSet().size, names.size, "$voice has duplicate preset names: $names")
            for (name in names) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
        }
    }

    // ---------- the naming rule, machine-checked ----------

    @Test
    fun `no preset name references a real drum machine`() {
        val offenders = SkinPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // ---------- spread: sixteen presets must actually differ ----------

    // Set from what the tables actually achieve, with a margin under it —
    // never at the measured value, which would be a threshold chosen to
    // just barely pass. Measured closest pair per voice, in order below:
    // 0.100, 0.080, 0.075, 0.103, 0.090, 0.086, 0.068, 0.068.
    //
    // SHAKER and STICK sit lowest because they carry three macros, not
    // four: the same sign pattern has one fewer axis to separate on, so
    // an honest floor for them is lower than for the rest rather than the
    // tables being worse.
    private val spreadThreshold = mapOf(
        SkinVoice.KICK to 0.09f,
        SkinVoice.SNARE to 0.07f,
        SkinVoice.HAT_CLOSED to 0.07f,
        SkinVoice.HAT_OPEN to 0.09f,
        SkinVoice.TOM to 0.08f,
        SkinVoice.RIDE to 0.08f,
        SkinVoice.SHAKER to 0.06f,
        SkinVoice.STICK to 0.06f,
    )

    @Test
    fun `presets within a voice do not cluster`() {
        for (voice in SkinVoice.entries) {
            val presets = SkinPresets.forVoice(voice)
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
                "$voice's closest pair ${closest?.first} / ${closest?.second} is $minDist apart, " +
                    "under $threshold — sixteen presets that all sound alike is a list that looks full " +
                    "and plays empty.",
            )
        }
    }

    // ---------- the point of the whole file ----------

    /**
     * SCRAMBLE's reason for existing, and the thing SKIN could not do
     * before this file: roll *near a preset* rather than near the one
     * factory default. Checked as a property rather than by restating the
     * implementation — over many rolls, a seeded SCRAMBLE has to reach
     * more than one neighbourhood.
     */
    @Test
    fun `scramble explores the preset pool, not just the default`() {
        for (voice in SkinVoice.entries) {
            val presets = SkinPresets.forVoice(voice)
            val nearest = (0 until 60).map { seed ->
                val rolled = Skin.scramble(voice, kotlin.random.Random(seed), temperature = 0.05f)
                presets.minBy { PresetTestSupport.rmsDistance(it.macros, rolled) }.name
            }.toSet()
            assertTrue(
                nearest.size > 1,
                "$voice: 60 low-temperature rolls all landed nearest the same preset (${nearest.first()}), " +
                    "so scramble is still orbiting one point. That is the pre-U2 behaviour this file exists " +
                    "to end.",
            )
        }
    }
}
