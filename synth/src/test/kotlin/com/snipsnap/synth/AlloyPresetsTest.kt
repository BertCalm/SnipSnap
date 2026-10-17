package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Factory recipes must preserve the engine's guarantees beyond the default macro positions. */
class AlloyPresetsTest {
    private val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)

    @Test
    fun `every factory recipe keeps its pitched body across three registers`() {
        var worstCents = 0.0
        for (preset in AlloyPresets.all()) for (midi in listOf(36, 60, 84)) {
            // A short capture prevents the classifier's duration-based LOOP rule from
            // masking a drum-like attack, including for long and held factory recipes.
            val probe = Alloy.renderProbe(preset.voice, preset.macros, midi = midi,
                seconds = 1.1f, normalize = true, config = preset.config)
            val label = "${preset.voice} ${preset.name} MIDI $midi"
            assertTrue(probe.samples.all { it.isFinite() } && probe.diagnostics.rawPeak < 2,
                "$label exceeded the raw synthesis bounds")
            assertTrue(rms(probe.samples) > 1e-4, "$label is silent")
            val hz = probe.diagnostics.rootHz.toFloat()
            val cents = FineTuning.cents(FineTuning.measuredHz(probe.body, Dsp.RATE, hz, .25f, .45f), hz.toDouble())
            worstCents = maxOf(worstCents, abs(cents))
            assertTrue(abs(cents) < 10.0, "$label moved its root by $cents cents")
            val actual = Classifier.classify(Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE))
            assertTrue(actual.drumClass !in drums,
                "$label read as ${actual.drumClass}: ${actual.features}")
        }
        println("ALLOY factory roster: 108 register checks, worst root error $worstCents cents")
    }

    @Test
    fun `every held factory recipe exports an audible settled cycle with its real predecessor`() {
        val held = AlloyPresets.all().filter { Alloy.isLoop(it.macros.getValue("HOLD")) }
        assertEquals(AlloyVoice.entries.toSet(), held.map { it.voice }.toSet())
        for (preset in held) {
            val probe = Alloy.renderProbe(preset.voice, preset.macros, config = preset.config)
            assertTrue(probe.previousCycle.size == probe.samples.size && probe.samples.size > 256,
                "${preset.name} lost its real previous cycle")
            assertTrue(probe.stateError < 1e-3 && probe.prerollCycles in 3..12,
                "${preset.name} did not settle during bounded preroll")
            val seam = Keys.seamError(probe.previousCycle + probe.samples, probe.previousCycle.size)
            assertEquals(seam, probe.seamError, 1e-12)
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "${preset.name} has a discontinuous loop: $seam")
            assertTrue(rms(probe.samples) > 1e-4 && rms(probe.conditionedAttack) > 1e-5,
                "${preset.name} settled by losing its material excitation")
        }
    }

    @Test
    fun `factory choices within a voice differ after matching loudness`() {
        var leastDifference = Double.POSITIVE_INFINITY
        for (voice in AlloyVoice.entries) {
            val rendered = AlloyPresets.forVoice(voice).map { preset ->
                preset.name to Alloy.renderProbe(voice, preset.macros, seconds = .8f, config = preset.config).samples
            }
            for (i in rendered.indices) for (j in i + 1 until rendered.size) {
                val a = rendered[i].second
                val b = rendered[j].second
                val ar = rms(a).coerceAtLeast(1e-12)
                val br = rms(b).coerceAtLeast(1e-12)
                val difference = sqrt(a.indices.sumOf { n ->
                    val d = a[n] / ar - b[n] / br
                    d * d
                } / a.size)
                leastDifference = minOf(leastDifference, difference)
                assertTrue(difference > .03,
                    "$voice ${rendered[i].first} / ${rendered[j].first} differ only in output gain: $difference")
            }
        }
        println("ALLOY factory roster: least matched waveform difference $leastDifference")
    }

    private fun rms(samples: FloatArray) =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
}
