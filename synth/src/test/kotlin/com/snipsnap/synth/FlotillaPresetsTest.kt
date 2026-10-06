package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The roster is fourteen named sounds, each with all seven knobs, none a drum's name. */
class FlotillaPresetsTest {

    @Test
    fun `fourteen presets, two or three a voice, every knob named`() {
        assertTrue(FlotillaPresets.all().size >= 12)
        assertEquals(14, FlotillaPresets.all().size)
        val names = mapOf(
            FlotillaVoice.RIPPLE to listOf("Small Wake", "Low Texture"),
            FlotillaVoice.KNOCK to listOf("Open Wood", "Strong Pulse", "Brighter Wood"),
            FlotillaVoice.HOLLOW to listOf("Deep Cavity", "Wide Bowl"),
            FlotillaVoice.CROSSWAVE to listOf("Crossing Paths", "Split Wake"),
            FlotillaVoice.DRIFT to listOf("Gentle Current", "Warm Canopy", "Held Sparse"),
            FlotillaVoice.GATHER to listOf("Gathered Vessels", "Held Dense"),
        )
        for (voice in FlotillaVoice.entries) {
            val got = FlotillaPresets.forVoice(voice)
            assertEquals(names.getValue(voice), got.map { it.name })
            assertEquals(got.map { it.name }.toSet().size, got.size)
            for (preset in got) {
                assertTrue(preset.name.length <= 18, preset.name)
                assertEquals(Flotilla.macrosFor(voice).map { it.name }.toSet(), preset.macros.keys, preset.name)
                for ((k, v) in preset.macros) assertTrue(v in 0f..1f, "$k=$v")
                assertEquals(Flotilla.DEFAULT_MIDI, preset.midi)
            }
        }
    }

    @Test
    fun `names stay clear of engines, voices, rack sections and makers, and the sounds spread`() {
        val taken = listOf(
            ThumpPatch.ENGINE, SkinPatch.ENGINE, TinesPatch.ENGINE, PluckPatch.ENGINE, TonewheelPatch.ENGINE, VelvetPatch.ENGINE,
            FathomPatch.ENGINE, ResinPatch.ENGINE, TidePatch.ENGINE, VoxPatch.ENGINE, SnapPatch.ENGINE, GlintPatch.ENGINE,
            SirenPatch.ENGINE, ForkPatch.ENGINE, TerraPatch.ENGINE, SilkPatch.ENGINE, BorePatch.ENGINE, ArcoPatch.ENGINE,
            MercuryPatch.ENGINE, GyrePatch.ENGINE, MagnetPatch.ENGINE, FlotillaPatch.ENGINE,
            "KICK", "SNARE", "CLAP", "HAT", "TOM",
        ) + FxChain.SECTION_NAMES.map { it.uppercase() } + FlotillaVoice.entries.map { it.name }
        for (preset in FlotillaPresets.all()) {
            val words = preset.name.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            val clash = words.filter { it in taken }
            assertTrue(clash.isEmpty(), "${preset.name} names $clash")
        }
        val offenders = FlotillaPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), offenders.map { it.name }.toString())
        for (voice in FlotillaVoice.entries) {
            val presets = FlotillaPresets.forVoice(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(a.macros, b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are ${"%.3f".format(d)} apart")
            }
        }
    }

    @Test
    fun `a preset round-trips, the dispatcher knows the roster, and a held one is not a drum`() {
        for (preset in FlotillaPresets.all()) {
            assertEquals(preset, FlotillaPatch.fromJsonText(preset.toJsonText()), preset.name)
        }
        for (voice in FlotillaVoice.entries) {
            assertEquals(FlotillaPresets.forVoice(voice), Presets.forVoice("FLOTILLA", voice.name))
        }
        assertTrue(Presets.all().containsAll(FlotillaPresets.all()))
        assertEquals(null, Presets.landingFor(FlotillaPatch.ENGINE, "DRIFT", FlotillaPresets.forVoice(FlotillaVoice.DRIFT).first().macros))
        val wake = FlotillaPresets.forVoice(FlotillaVoice.RIPPLE).first()
        assertContentEquals(wake.render().samples, FlotillaPatch.fromJsonText(wake.toJsonText()).render().samples)
        val held = FlotillaPresets.forVoice(FlotillaVoice.DRIFT).first { it.name == "Held Sparse" }
        val heard = Classifier.classify(held.render()).drumClass
        assertTrue(heard !in DRUMS, "Held Sparse classified as $heard")
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
