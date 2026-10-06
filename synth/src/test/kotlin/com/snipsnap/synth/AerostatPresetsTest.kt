package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AEROSTAT's factory roster: five presets, held to the same name, round-trip, blocklist and
 * spread contract as the other engines. They prove the roster is sound. The audition page is
 * where a listener says which of them is the instrument.
 */
class AerostatPresetsTest {

    private val choking = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM,
    )

    private val names = listOf("SOFT CATCH", "TWIN PIPES", "HEAVY ROTOR", "DRIFTING STEAM", "HIGH ENVELOPE")

    private val notes = mapOf(
        "SOFT CATCH" to "C4",
        "TWIN PIPES" to "C4",
        "HEAVY ROTOR" to "G3",
        "DRIFTING STEAM" to "E4",
        "HIGH ENVELOPE" to "G4",
    )

    private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private fun noteName(midi: Int): String = noteNames[midi % 12] + (midi / 12 - 1)

    @Test
    fun `preset names are uppercase, short, unique, and the frozen roster in order`() {
        val got = AerostatPresets.forVoice(AerostatVoice.FLOAT).map { it.name }
        assertEquals(names, got)
        assertEquals(got.toSet().size, got.size)
        for (name in got) {
            assertTrue(name.length <= 14, "$name is longer than 14 chars")
            assertEquals(name.uppercase(), name)
        }
    }

    @Test
    fun `every preset names every macro and lands on the note its comment names`() {
        val macros = Aerostat.macrosFor(AerostatVoice.FLOAT).map { it.name }.toSet()
        for (preset in AerostatPresets.all()) {
            assertEquals(macros, preset.macros.keys, "${preset.name} leaves a macro at its default")
            val exact = preset.macros.getValue("TUNE") * Aerostat.TUNE_SEMITONES
            assertTrue(abs(exact - exact.roundToInt()) < 0.05f, "${preset.name} is off a semitone")
            val midi = Aerostat.midiFor(preset.macros.getValue("TUNE"))
            assertEquals(notes.getValue(preset.name), noteName(midi), "${preset.name} sounds ${noteName(midi)}")
        }
    }

    @Test
    fun `every preset round-trips and is not filed as a drum`() {
        for (preset in AerostatPresets.all()) {
            val restored = AerostatPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored)
            assertContentEquals(preset.render().samples, restored.render().samples)
            val heard = Classifier.classify(FeatureExtractor.extract(preset.render())).drumClass
            assertTrue(heard !in choking, "${preset.name} classified as $heard")
        }
    }

    @Test
    fun `no preset name is an engine, a voice, a rack section, or a trademark`() {
        val taken = listOf(
            ThumpPatch.ENGINE, SkinPatch.ENGINE, TinesPatch.ENGINE, PluckPatch.ENGINE, TonewheelPatch.ENGINE, VelvetPatch.ENGINE,
            FathomPatch.ENGINE, ResinPatch.ENGINE, TidePatch.ENGINE, VoxPatch.ENGINE, SnapPatch.ENGINE, GlintPatch.ENGINE,
            SirenPatch.ENGINE, ForkPatch.ENGINE, TerraPatch.ENGINE, SilkPatch.ENGINE, BorePatch.ENGINE, ArcoPatch.ENGINE,
            MercuryPatch.ENGINE, MagnetPatch.ENGINE, AerostatPatch.ENGINE,
        ) + FxChain.SECTION_NAMES.map { it.uppercase() } + AerostatVoice.entries.map { it.name }
        for (preset in AerostatPresets.all()) {
            val words = preset.name.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            val clash = words.filter { it in taken }
            assertTrue(clash.isEmpty(), "${preset.name} names $clash")
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name), preset.name)
        }
    }

    @Test
    fun `presets spread out rather than cluster`() {
        val presets = AerostatPresets.forVoice(AerostatVoice.FLOAT)
        for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
            val d = PresetTestSupport.rmsDistance(a.macros, b.macros)
            assertTrue(d > 0.05f, "${a.name} and ${b.name} are nearly the same sound ($d)")
        }
    }

    @Test
    fun `the dispatcher knows AEROSTAT`() {
        assertEquals(AerostatPresets.forVoice(AerostatVoice.FLOAT), Presets.forVoice("AEROSTAT", "FLOAT"))
        assertEquals(AerostatPresets.forVoice(AerostatVoice.FLOAT).first(), Presets.byName("AEROSTAT", "FLOAT", "SOFT CATCH"))
        assertTrue(Presets.all().containsAll(AerostatPresets.all()))
    }
}
