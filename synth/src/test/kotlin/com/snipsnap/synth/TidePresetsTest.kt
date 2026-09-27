package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, TIDE's turn: ten presets per voice
 * (forty total), and the classifier's reading of the struck ones, which
 * is what `SynthScreen`'s BONGO and DRIP mapping mirrors (the FATHOM
 * rule): a real measurement, not a guess.
 *
 * Measured with the edge and the oomph in (GLOW, held notes, SWEEP,
 * CROSS, TILT, WOBBLE, THUMP, BODY): BONGO and DRIP land 10 PERC each;
 * the zap and the thump read more struck than before (8 and 8). GONG and FLARE
 * are notes DECAY can hold, and the classifier reads them by length alone
 * (GONG: 4 LOOP, 4 SNARE, 2 PERC; FLARE: 8 PERC, 2 LOOP), so they are
 * TONAL by design, RESIN's rule, and held to ringing harmonic.
 */
class TidePresetsTest {

    @Test
    fun `every preset renders clean audio at full level`() {
        for (preset in TidePresets.all()) {
            val snip = preset.render()
            assertTrue(snip.frameCount > 0, "${preset.name} rendered nothing")
            assertTrue(snip.samples.all { it.isFinite() }, "${preset.name} produced non-finite samples")
            assertTrue(snip.samples.all { it in -1f..1f }, "${preset.name} clipped")
            // Levelled by loudness, not peak: a held note reaches the target
            // with a low peak, a short hit reaches the ceiling first. Either
            // way it is as loud as the engine can make it.
            val loud = Loudness.of(snip)
            assertTrue(
                loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f,
                "${preset.name} is too quiet: loudness $loud, peak ${snip.peak()}",
            )
        }
    }

    @Test
    fun `the classifier hears BONGO and DRIP as percussion`() {
        for (voice in listOf(TideVoice.BONGO, TideVoice.DRIP)) {
            assertEquals(DrumClass.PERC, Classifier.classify(Tide.render(voice)).drumClass, "$voice defaults")
            val perc = TidePresets.forVoice(voice).count { Classifier.classify(it.render()).drumClass == DrumClass.PERC }
            assertTrue(perc >= 8, "$voice: only $perc of 10 presets classify PERC")
        }
    }

    @Test
    fun `GONG and FLARE presets ring as notes, not noise`() {
        // Measured from 100 ms: the edge's SWEEP and CROSS make the strike a
        // zap, noisy by design (TEMPLE GONG reads 0.23 whole), and it has
        // landed by then. What rings after it is the note.
        for (voice in listOf(TideVoice.GONG, TideVoice.FLARE)) {
            for (preset in TidePresets.forVoice(voice)) {
                val s = preset.render()
                val from = (0.1f * s.sampleRate).toInt()
                val ring = com.snipsnap.audio.Snip(s.samples.copyOfRange(from, s.samples.size), 1, s.sampleRate)
                val f = FeatureExtractor.extract(ring)
                assertTrue(f.flatness < 0.2f, "${preset.name} should ring harmonic, got flatness ${f.flatness}")
            }
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in TidePresets.all()) {
            val restored = TidePatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in TideVoice.entries) {
            val names = TidePresets.forVoice(voice).map { it.name }
            assertEquals(10, names.size, "$voice should ship 10 presets, has ${names.size}")
            assertEquals(names.toSet().size, names.size, "$voice has duplicate preset names: $names")
            for (name in names) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
        }
    }

    @Test
    fun `no preset name references a real machine or maker`() {
        val offenders = TidePresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine or maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `presets spread out rather than cluster`() {
        for (voice in TideVoice.entries) {
            val presets = TidePresets.forVoice(voice)
            val base = Tide.defaults(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(base + a.macros, base + b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are nearly the same sound (${"%.3f".format(d)})")
            }
        }
    }
}
