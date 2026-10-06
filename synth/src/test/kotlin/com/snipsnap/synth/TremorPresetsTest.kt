package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TREMOR's factory roster. Thirteen sounds, held to the same naming and spread rules as the other
 * rosters. They prove the roster is sound. They do not prove it is good: nothing in it was listened to.
 */
class TremorPresetsTest {

    private class Reading(val preset: TremorPatch) {
        val snip = preset.render()
        val heard: DrumClass = Classifier.classify(FeatureExtractor.extract(snip)).drumClass
        val filed: DrumClass = Tremor.drumClassFor(preset.voice, preset.macros)
        val label: String get() = "${preset.voice} ${preset.name}"
    }

    private companion object {
        val readings: List<Reading> by lazy { TremorPresets.all().map { Reading(it) } }

        val names = mapOf(
            TremorVoice.HIDE to listOf("BROAD DRUM", "SOFT STRIKE", "HELD DRUM"),
            TremorVoice.UNISON to listOf("FOUR HANDS", "DEEP ASSEMBLY"),
            TremorVoice.ROLL to listOf("LIGHT BED", "SETTLING BED"),
            TremorVoice.WIRE to listOf("DRY CAGE", "LATE STRAND"),
            TremorVoice.CHARGE to listOf("CHARGED TAIL", "HELD BLOOM"),
            TremorVoice.FRACTURE to listOf("SOFT FAULT", "BROKEN RETURN"),
        )
    }

    @Test
    fun `every preset renders clean audio and keeps its filing contract`() {
        for (r in readings) {
            assertTrue(r.snip.frameCount > 0, "${r.label} rendered nothing")
            assertTrue(r.snip.samples.all { it.isFinite() && it in -1f..1f }, "${r.label} clipped or was not finite")
            val loud = Loudness.of(r.snip)
            assertTrue(loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || r.snip.peak() >= 0.95f, "${r.label} is quiet")
            val dc = r.snip.samples.average()
            assertTrue(abs(dc) < 0.05, "${r.label} has DC $dc")
            // Audible bead contacts make SETTLING BED classify PERC; removing the feedback
            // squeal makes CHARGED TAIL classify TOM. Preserve those two factory pad roles
            // without tuning either sound to the generic file-import centroid thresholds.
            when (r.preset.name) {
                "SETTLING BED" -> assertEquals(DrumClass.TOM, r.filed, r.label)
                "CHARGED TAIL" -> assertEquals(DrumClass.PERC, r.filed, r.label)
                else -> assertEquals(r.filed, r.heard, "${r.label}: filed ${r.filed}, heard ${r.heard}")
            }
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in TremorPresets.all()) {
            val restored = TremorPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertContentEquals(preset.render().samples, restored.render().samples, "${preset.name} rendered differently after a round-trip")
        }
    }

    @Test
    fun `preset names are uppercase, short, unique per voice, and the frozen roster in order`() {
        val all = TremorPresets.all().map { it.name }
        assertEquals(all.toSet().size, all.size, "duplicate names: $all")
        assertTrue(all.size >= 12, "the roster has ${all.size}, the spec asks for at least 12")
        for (voice in TremorVoice.entries) {
            val got = TremorPresets.forVoice(voice).map { it.name }
            assertEquals(got.toSet().size, got.size, "$voice has duplicate preset names")
            for (name in got) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
            assertEquals(names.getValue(voice), got, "$voice's roster")
        }
    }

    @Test
    fun `no preset name is an engine's name, a voice's or a rack section's`() {
        val taken = listOf(
            ThumpPatch.ENGINE, SkinPatch.ENGINE, TinesPatch.ENGINE, PluckPatch.ENGINE, TonewheelPatch.ENGINE, VelvetPatch.ENGINE,
            FathomPatch.ENGINE, ResinPatch.ENGINE, TidePatch.ENGINE, VoxPatch.ENGINE, SnapPatch.ENGINE, GlintPatch.ENGINE,
            SirenPatch.ENGINE, ForkPatch.ENGINE, TerraPatch.ENGINE, SilkPatch.ENGINE, BorePatch.ENGINE, ArcoPatch.ENGINE,
            MercuryPatch.ENGINE, GyrePatch.ENGINE, MagnetPatch.ENGINE, TremorPatch.ENGINE,
        ) + FxChain.SECTION_NAMES.map { it.uppercase() } + TremorVoice.entries.map { it.name }
        for (preset in TremorPresets.all()) {
            val words = preset.name.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            val clash = words.filter { it in taken }
            assertTrue(clash.isEmpty(), "${preset.name} names $clash, which is an engine, a voice or a rack section")
        }
    }

    @Test
    fun `no preset name references a real instrument or its maker`() {
        val offenders = TremorPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `presets spread out rather than cluster`() {
        for (voice in TremorVoice.entries) {
            val presets = TremorPresets.forVoice(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(a.macros, b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are nearly the same sound (${"%.3f".format(java.util.Locale.ROOT, d)})")
            }
        }
    }

    @Test
    fun `every preset names every macro`() {
        for (preset in TremorPresets.all()) {
            assertEquals(Tremor.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, "${preset.name} leaves a macro unnamed")
        }
    }

    @Test
    fun `tune lands on a semitone`() {
        for (preset in TremorPresets.all()) {
            val exact = preset.macros.getValue("TUNE") * Tremor.TUNE_SEMITONES
            assertTrue(abs(exact - exact.roundToInt()) < 0.05f, "${preset.name}: TUNE is not on a semitone")
        }
    }

    @Test
    fun `the dispatcher knows TREMOR`() {
        for (voice in TremorVoice.entries) assertEquals(TremorPresets.forVoice(voice), Presets.forVoice("TREMOR", voice.name))
        assertEquals(TremorPresets.forVoice(TremorVoice.HIDE).first(), Presets.byName("TREMOR", "HIDE", "BROAD DRUM"))
        assertTrue(Presets.all().containsAll(TremorPresets.all()))
    }
}
