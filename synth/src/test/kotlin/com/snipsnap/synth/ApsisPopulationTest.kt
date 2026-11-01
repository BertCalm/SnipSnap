package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

class ApsisPopulationTest {
    private val voices = listOf(ApsisVoice.GRAIN, ApsisVoice.ANCHOR)
    private val short = Apsis.ProbeOptions(seconds = 1.2f, recordStems = false, recordTraces = false)
    private fun probe(voice: ApsisVoice, macros: Map<String, Float> = Apsis.defaults(voice),
        midi: Int = 60, velocity: Float = .65f, seed: Int = 0,
        options: Apsis.ProbeOptions = short) = Apsis.probe(voice, macros, midi, velocity,
            ApsisConfig(seedContext = seed), options)
    private fun distance(a: FloatArray, b: FloatArray) = sqrt(a.indices.sumOf {
        val d = a[it].toDouble() - b[it]; d * d
    } / a.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30))

    @Test fun `new identities keep their first model and full recipe while rejecting nonexistent older models`() {
        for (voice in voices) {
            val presets = ApsisPresets.forVoice(voice)
            assertEquals(3, presets.size)
            assertEquals(1, presets.count { Apsis.isLoop(it.macros.getValue("HOLD")) })
            for (preset in presets) {
                assertEquals(.65f, preset.velocity)
                val patch = preset.copy(config = ApsisConfig(3, Int.MIN_VALUE), midi = 43)
                assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            }
            val text = """{"engine":"APSIS","version":1,"name":"First","voice":"$voice","macros":{}}"""
            assertEquals(3, ApsisPatch.fromJsonText(text).config.modelVersion)
            for (model in 1..2) {
                val error = assertFailsWith<JsonException> {
                    ApsisPatch.fromJsonText(text.dropLast(1) + ",\"config\":{\"modelVersion\":$model}}")
                }
                assertTrue(error.message.orEmpty().contains("model"), error.message)
                assertFailsWith<IllegalArgumentException> { presets.first().copy(config = ApsisConfig(model)) }
            }
        }
        assertEquals(12, ApsisPresets.all().size)
        assertEquals(6, ApsisPresets.forVoice(ApsisVoice.PASSAGE).size)
    }

    @Test fun `voices and native velocity change the excited material rather than only its gain`() {
        val grain = probe(ApsisVoice.GRAIN).samples
        val anchor = probe(ApsisVoice.ANCHOR).samples
        assertTrue(distance(grain, anchor) > .1)
        for (voice in voices) {
            val quiet = probe(voice, velocity = .2f, options = short.copy(normalize = true)).samples
            val hard = probe(voice, velocity = .9f, options = short.copy(normalize = true)).samples
            val q = quiet.maxOf { abs(it) }; val h = hard.maxOf { abs(it) }
            assertTrue(distance(FloatArray(quiet.size) { quiet[it] / q },
                FloatArray(hard.size) { hard[it] / h }) > .02, "$voice lost native touch")
            assertTrue(probe(voice, velocity = 0f).samples.all { it == 0f })
        }
        assertFalse(grain.contentEquals(probe(ApsisVoice.GRAIN, seed = Int.MIN_VALUE).samples))
        // ANCHOR's deterministic harmonic pressure source has no random component.
        assertContentEquals(anchor, probe(ApsisVoice.ANCHOR, seed = Int.MIN_VALUE).samples)
    }

    @Test fun `movement controls retain their approved low to middle response`() {
        for (voice in voices) {
            for (control in listOf("STRETCH", "DISTANCE", "BODIES", "ALIGN", "COUPLE")) {
                val low = probe(voice, Apsis.defaults(voice) + (control to 0f), options = short.copy(seconds = 6f)).samples
                val middle = probe(voice, Apsis.defaults(voice) + (control to .5f), options = short.copy(seconds = 6f)).samples
                val changed = distance(low, middle)
                println("APSIS $voice $control low-middle distance=$changed")
                // The approved GRAIN law deliberately keeps exchange subtle: .00520
                // across the complete take. Guard that response without revoicing it.
                val minimum = if (voice == ApsisVoice.GRAIN && control == "COUPLE") .004 else .01
                assertTrue(changed > minimum, "$voice $control did not engage: $changed")
            }
        }
    }

    @Test fun `the root survives across the playable range`() {
        for (voice in voices) {
            for (midi in listOf(36, 60, 84)) {
                val p = probe(voice, midi = midi, options = short.copy(normalize = true))
                assertTrue(p.samples.all { it.isFinite() && abs(it) <= 1.001f })
                assertTrue(abs(p.samples.average()) < .005)
                val wanted = Keys.midiHz(midi)
                val hz = FineTuning.measuredHz(Snip(p.samples, 1, Dsp.RATE), wanted, .2f, .8f)
                val cents = FineTuning.cents(hz, wanted.toDouble())
                println("APSIS $voice MIDI $midi root cents=$cents rawPeak=${p.diagnostics.rawPeak}")
                assertTrue(abs(cents) < 50, "$voice MIDI $midi root moved $cents cents")
            }
        }
    }

    @Test fun `upper material reaches an unexcited recipient only through the center`() {
        for (voice in voices) {
            val m = Apsis.defaults(voice) + ("COUPLE" to 1f)
            val o = short.copy(seconds = 3f, directExcitationDisabled = 1, soloBody = 1)
            val coupled = probe(voice, m, options = o).samples
            val isolated = probe(voice, m, options = o.copy(ablation = Apsis.Ablation.NO_COUPLING)).samples
            assertTrue(coupled.any { abs(it) > 1e-9 }, "$voice recipient did not respond")
            assertTrue(isolated.all { it == 0f }, "$voice recipient retained a direct drive")
        }
    }

    @Test fun `held voices close genuine adjacent cycles with complete material state`() {
        for (voice in voices) {
            val p = probe(voice, Apsis.defaults(voice) + ("HOLD" to 1f),
                options = short.copy(seconds = null))
            assertEquals(p.loopFrames, p.samples.size)
            assertEquals(p.loopFrames, p.previousCycle.size)
            assertTrue(p.previousCycle.any { it != 0f })
            assertTrue(p.stateError < 1e-8)
            assertTrue(p.diagnostics.converged)
            assertTrue(Keys.seamError(p.previousCycle + p.samples, p.loopFrames) < Keys.MAX_SEAM_ERROR)
            assertTrue(distance(p.previousCycle, p.samples) < 1e-5)
            println("APSIS $voice HOLD seam=${p.seamError} state=${p.stateError} cycles=${p.diagnostics.prerollCycles}")
        }
    }

    @Test fun `geometry cache shares immutable motion and never shares mutable sound state`() {
        ApsisPopulation.clearPreparationCache()
        val first = probe(ApsisVoice.GRAIN)
        assertFalse(first.diagnostics.preparationCacheHit)
        val crossVoice = probe(ApsisVoice.ANCHOR, midi = 48, velocity = .4f, seed = 91,
            macros = Apsis.defaults(ApsisVoice.ANCHOR) + mapOf("BODIES" to 1f, "COUPLE" to .9f))
        assertTrue(crossVoice.diagnostics.preparationCacheHit)
        assertContentEquals(first.samples, probe(ApsisVoice.GRAIN).samples)
        assertContentEquals(first.samples, probe(ApsisVoice.GRAIN,
            options = short.copy(usePreparationCache = false)).samples)
        assertFalse(probe(ApsisVoice.GRAIN, Apsis.defaults(ApsisVoice.GRAIN) + ("ALIGN" to .1f))
            .diagnostics.preparationCacheHit)
        assertTrue(ApsisPopulation.preparationCacheSnapshot().bytes <= 16L * 1024 * 1024)
        ApsisPopulation.clearPreparationCache()
        val error = assertFailsWith<CancellationException> {
            Apsis.probe(ApsisVoice.GRAIN, emptyMap(), options = short, cancelled = { true })
        }
        assertTrue(error.message.orEmpty().contains("cancelled"))
        assertEquals(0, ApsisPopulation.preparationCacheSnapshot().entries)
    }

    @Test fun `long held diagnostics restart the same force clock at each complete return`() {
        for (voice in voices) {
            val m = Apsis.defaults(voice) + ("HOLD" to 1f)
            var settled = FloatArray(0)
            val loop = ApsisPopulation.probe(voice, m, 60, .65f, ApsisConfig(),
                short.copy(seconds = null), { false }, { settled = it.copyOf() })
            var continuous = FloatArray(0)
            ApsisPopulation.probe(voice, m, 60, .65f, ApsisConfig(),
                short.copy(seconds = 12f), { false }, { continuous = it })
            val frames = loop.loopFrames * Dsp.OVERSAMPLE
            assertTrue(continuous.size >= 2 * frames)
            val second = continuous.copyOfRange(frames, 2 * frames)
            assertTrue(distance(settled, second) < 1e-5, "$voice diagnostic lost periodic forcing")
        }
    }

    @Test fun `recorded stems sum to the internal radiation observer without a dry source`() {
        for (voice in voices) {
            val p = probe(voice, options = short.copy(recordStems = true))
            assertTrue(p.source.all { it == 0f })
            val sum = FloatArray(p.rawMix.size) { i ->
                (p.center[i].toDouble() + p.bodies.sumOf { it[i].toDouble() }).toFloat()
            }
            assertTrue(distance(p.rawMix, sum) < 1e-5)
        }
    }
}
