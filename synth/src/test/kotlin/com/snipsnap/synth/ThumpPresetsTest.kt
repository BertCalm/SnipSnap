package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`: THUMP shipped eight voices and zero
 * presets, so a beginner met sliders instead of a sound. These tests are
 * the same playability contract `ThumpTest` runs on the raw voices, plus
 * the property specific to a *library*: names that can't smuggle a
 * trademark past the roadmap's naming rule. (No spread check: good
 * presets cluster in the narrow regions of macro space that actually
 * sound good, so "evenly spread" is not a property worth enforcing.)
 */
class ThumpPresetsTest {

    private val classifiedVoices = mapOf(
        ThumpVoice.KICK to DrumClass.KICK,
        ThumpVoice.SNARE to DrumClass.SNARE,
        ThumpVoice.HAT_CLOSED to DrumClass.HAT_CLOSED,
        ThumpVoice.HAT_OPEN to DrumClass.HAT_OPEN,
        ThumpVoice.CLAP to DrumClass.CLAP,
        ThumpVoice.TOM to DrumClass.TOM,
    )

    // COWBELL and RIM have no dedicated DrumClass (same carve-out ThumpTest
    // makes for the raw voices) so they're covered by the sanity check only.

    // ---------- identity: the classifier is the judge, same as the raw voices ----------

    @Test
    fun `every preset classifies as its own voice`() {
        val failures = mutableListOf<String>()
        for ((voice, expected) in classifiedVoices) {
            for (preset in ThumpPresets.forVoice(voice)) {
                val got = Classifier.classify(preset.render()).drumClass
                if (got != expected) failures += "${voice.name}/${preset.name}: expected $expected, got $got"
            }
        }
        assertTrue(failures.isEmpty(), "presets that don't classify as their own voice:\n${failures.joinToString("\n")}")
    }

    // ---------- sanity: every preset is a real, clean sound ----------

    @Test
    fun `every preset renders clean non-silent audio`() {
        for (voice in ThumpVoice.entries) {
            for (preset in ThumpPresets.forVoice(voice)) {
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
        for (preset in ThumpPresets.all()) {
            val restored = ThumpPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    // ---------- names: what the sunken listbox and the naming rule both require ----------

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in ThumpVoice.entries) {
            val names = ThumpPresets.forVoice(voice).map { it.name }
            assertEquals(20, names.size, "$voice should ship 20 presets, has ${names.size}")
            assertEquals(names.toSet().size, names.size, "$voice has duplicate preset names: $names")
            for (name in names) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
        }
    }

    // ---------- the naming rule, machine-checked ----------
    //
    // The blocklist regex itself lives in PresetTestSupport now, shared by
    // every <Engine>PresetsTest — see that file's KDoc for why `\b` would
    // be a hole here, not a tightening.

    @Test
    fun `no preset name references a real drum machine`() {
        val offenders = ThumpPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    @Test
    fun `the blocklist actually catches near-misses, not just exact names`() {
        // A guard proven by what it rejects, not just what it once caught:
        // suffix concatenation (no separator at all) and the classic
        // "acid" family name the roadmap's own examples call out.
        for (nearMiss in listOf("808ISH", "909CORE", "TB303", "ACID 303", "808-ADJACENT")) {
            assertTrue(PresetTestSupport.trademarkBlocklist.containsMatchIn(nearMiss), "blocklist let '$nearMiss' through")
        }
        for (clean in listOf("CONCRETE", "DUSTY BOOM", "PEAK TIME", "DEEP DUB")) {
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(clean), "blocklist wrongly flagged '$clean'")
        }
    }

    // The Presets dispatcher itself (forVoice/byName/all across every
    // registered engine, and the unregistered-engine case) has its own
    // PresetsTest now that six more engines join THUMP behind it.
}
