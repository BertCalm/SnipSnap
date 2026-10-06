package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CisternPresetsTest {
    @Test
    fun `the proposed twelve sounds cover each voice with complete distinct macros`() {
        val names = mapOf(
            CisternVoice.FIRST to listOf("First Drop", "Soft Skin"),
            CisternVoice.DRIP to listOf("Hanging Rain", "Quiet Reservoir"),
            CisternVoice.CASCADE to listOf("Wide Cascade", "Heavy Landing"),
            CisternVoice.POOL to listOf("Wet Basin", "Slow Drain"),
            CisternVoice.RIPPLE to listOf("Thin Ripple", "Dense Surface"),
            CisternVoice.RECOVERY to listOf("Clear Return", "Replenished Circle"),
        )
        assertEquals(12, CisternPresets.all().size)
        assertEquals(12, CisternPresets.all().map { it.name }.toSet().size)
        for (voice in CisternVoice.entries) {
            val presets = CisternPresets.forVoice(voice)
            assertEquals(names.getValue(voice), presets.map { it.name })
            for (preset in presets) {
                assertEquals(Cistern.macrosFor(voice).map { it.name }.toSet(), preset.macros.keys)
                assertTrue(preset.macros.values.all { it in 0f..1f })
                assertEquals(Cistern.DEFAULT_MIDI, preset.midi)
                assertEquals(1f, preset.velocity)
                assertEquals(Cistern.MODEL_VERSION, preset.model)
                assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
                assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name), preset.name)
                assertEquals(null, Presets.landingFor(CisternPatch.ENGINE, voice.name, preset.macros))
            }
            assertTrue(PresetTestSupport.rmsDistance(presets[0].macros, presets[1].macros) > 0.30f)
            assertEquals(presets, Presets.forVoice(CisternPatch.ENGINE, voice.name))
        }
        assertTrue(Presets.all().containsAll(CisternPresets.all()))
        assertTrue(Presets.forVoice(CisternPatch.ENGINE, "KICK").isEmpty())
    }

    @Test
    fun `standard dry presets retain pitched routing and the held circle is reproducible`() {
        for (voice in CisternVoice.entries) {
            val preset = CisternPresets.forVoice(voice).first()
            assertTrue(Cistern.drumClassFor(voice, preset.macros) !in DRUMS)
            val heard = Classifier.classify(preset.render()).drumClass
            assertTrue(heard !in DRUMS, "${preset.name} classified as $heard")
        }
        val held = CisternPresets.forVoice(CisternVoice.RECOVERY).last()
        assertTrue(Cistern.isLoop(held.macros.getValue("HOLD")))
        assertContentEquals(held.render().samples, CisternPatch.fromJsonText(held.toJsonText()).render().samples)
    }

    @Test
    fun `FIRST and DRIP preset pairs retain different gesture envelopes after level matching`() {
        // A 40 ms energy distribution ignores waveform phase and overall
        // gain. Near-identical gestures cannot pass by changing their level.
        fun envelope(patch: CisternPatch): DoubleArray {
            val audio = Cistern.renderInternal(patch.voice, patch.macros, patch.midi, patch.velocity, seconds = 4f).snip.samples
            val window = Dsp.RATE / 25
            val energy = DoubleArray(100) { block ->
                val start = block * window
                (start until minOf(start + window, audio.size)).sumOf { frame ->
                    val sample = audio[frame].toDouble()
                    sample * sample
                }
            }
            val total = energy.sum()
            assertTrue(total > 0.0, "${patch.name} is silent")
            return DoubleArray(energy.size) { energy[it] / total }
        }
        for (voice in listOf(CisternVoice.FIRST, CisternVoice.DRIP)) {
            val pair = CisternPresets.forVoice(voice)
            val a = envelope(pair.first())
            val b = envelope(pair.last())
            val distance = a.indices.sumOf { abs(a[it] - b[it]) } * 0.5
            assertTrue(distance > 0.10, "$voice presets have nearly identical leveled envelopes: $distance")
        }
    }

    @Test
    fun `the sixteen pad kit combines six profiles and twelve dry presets at explicit in-key notes`() {
        val pads = SynthKits.cistern()
        assertEquals(16, pads.size)
        val recipes = pads.map { pad ->
            val present = assertNotNull(pad)
            assertTrue(present.drumClass !in DRUMS)
            val recipe = PadRecipe.fromJsonValue(assertNotNull(present.recipe))
            assertEquals(null, recipe.fx)
            assertTrue(recipe.patch is CisternPatch)
            recipe
        }
        val patches = recipes.map { it.patch as CisternPatch }
        assertEquals(listOf(60, 63, 65, 67, 70, 72, 75, 77), patches.take(8).map { it.midi })
        assertEquals(
            listOf(CisternVoice.FIRST, CisternVoice.DRIP, CisternVoice.CASCADE, CisternVoice.POOL,
                CisternVoice.RIPPLE, CisternVoice.RECOVERY, CisternVoice.FIRST, CisternVoice.DRIP),
            patches.take(8).map { it.voice },
        )
        assertTrue(patches.all { it.midi % 12 in setOf(0, 3, 5, 7, 10) }, "a pad leaves C minor pentatonic")
        assertEquals(48, patches.minOf { it.midi })
        assertEquals(77, patches.maxOf { it.midi })
        assertTrue(patches.all { it.velocity == 1f && it.model == Cistern.MODEL_VERSION })
        assertEquals(CisternVoice.entries.toSet(), patches.map { it.voice }.toSet())
        assertEquals(CisternPresets.all().map { it.name }.toSet(), patches.map { it.name }.toSet())
        assertEquals(16, patches.map { Triple(it.voice, it.midi, it.macros) }.toSet().size)
        for (patch in patches) {
            val preset = CisternPresets.all().first { it.name == patch.name }
            assertEquals(preset.macros, patch.macros, "${patch.name} did not retain its preset gesture")
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        }
        assertTrue(Cistern.isLoop(patches.last().macros.getValue("HOLD")))
        assertContentEquals(assertNotNull(pads.first()).snip.samples, recipes.first().render().samples)
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
