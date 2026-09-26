package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, TIDE's turn: ten presets per voice
 * (forty total), and the classifier's reading of them, which is what
 * `SynthScreen`'s TIDE mapping mirrors (the FATHOM rule): a real
 * measurement, not a guess about what a struck, gated note ought to be.
 *
 * Measured when this was written: every voice's defaults classify PERC.
 * Of the presets, BONGO lands 8 PERC + 2 TOM (LOW CONGA, LOG DRUM), DRIP
 * 8 PERC + 2 SNARE (ICE BLIP, CRYSTAL), GONG 9 PERC + 1 LOOP (SHIP BELL,
 * the longest ring), FLARE 10 PERC. The gate's struck envelope is what
 * the classifier hears, FLARE's lead included.
 */
class TidePresetsTest {

    @Test
    fun `every preset renders clean non-silent audio`() {
        for (preset in TidePresets.all()) {
            val snip = preset.render()
            assertTrue(snip.frameCount > 0, "${preset.name} rendered nothing")
            assertTrue(snip.samples.all { it.isFinite() }, "${preset.name} produced non-finite samples")
            assertTrue(snip.samples.all { it in -1f..1f }, "${preset.name} clipped")
            assertTrue(snip.peak() > 0.5f, "${preset.name} is too quiet: ${snip.peak()}")
        }
    }

    @Test
    fun `the classifier hears TIDE as percussion`() {
        for (voice in TideVoice.entries) {
            assertEquals(DrumClass.PERC, Classifier.classify(Tide.render(voice)).drumClass, "$voice defaults")
            val perc = TidePresets.forVoice(voice).count { Classifier.classify(it.render()).drumClass == DrumClass.PERC }
            assertTrue(perc >= 8, "$voice: only $perc of 10 presets classify PERC")
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
