package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, SIREN's turn: ten presets per voice
 * (forty total), the last of each list a LOOP, and the classifier's
 * reading of them measured rather than guessed — a one-shot siren is a
 * pitched note for as long as it is under the classifier's LOOP length,
 * and a LOOP is over it by construction.
 */
class SirenPresetsTest {

    @Test
    fun `every preset renders clean audio at full level`() {
        for (preset in SirenPresets.all()) {
            val snip = preset.render()
            assertTrue(snip.frameCount > 0, "${preset.name} rendered nothing")
            assertTrue(snip.samples.all { it.isFinite() }, "${preset.name} produced non-finite samples")
            assertTrue(snip.samples.all { it in -1f..1f }, "${preset.name} clipped")
            val loud = Loudness.of(snip)
            assertTrue(
                loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f,
                "${preset.name} is too quiet: loudness $loud, peak ${snip.peak()}",
            )
            assertTrue(snip.durationSeconds <= Siren.MAX_SECONDS, "${preset.name} runs ${snip.durationSeconds} s")
        }
    }

    @Test
    fun `one LOOP per voice, last in its list, and the classifier hears it as the LOOP it is`() {
        for (voice in SirenVoice.entries) {
            val presets = SirenPresets.forVoice(voice)
            val loops = presets.filter { Siren.isLoop(it.macros.getValue("HOLD")) }
            assertEquals(1, loops.size, "$voice ships one LOOP preset, has ${loops.map { it.name }}")
            assertEquals(presets.last(), loops.single(), "$voice's LOOP is the last preset")
            assertEquals(DrumClass.LOOP, Siren.drumClassFor(voice, loops.single().macros))
            assertEquals(DrumClass.LOOP, Classifier.classify(loops.single().render()).drumClass, "${loops.single().name} by the classifier")
        }
    }

    @Test
    fun `the classifier reads the one-shots by length, and never as a drum with a choke`() {
        // Measured (the FATHOM rule), and recorded rather than wished for:
        // a pitch that moves defeats the pitch detector, so the classifier
        // does not hear a siren as TONAL. What it does is read the long
        // holds as LOOP by length alone (its own 1.5 s rule) and the short
        // ones as a struck thing. SynthScreen files a one-shot siren as
        // TONAL *by design* - the same fallback RESIN, GLINT and VELVET
        // take - so a siren never lands in a hat's choke group; this test
        // holds the measurement so a change in either reading is seen.
        val all = ArrayList<String>()
        for (voice in SirenVoice.entries) {
            val shots = SirenPresets.forVoice(voice).filter { !Siren.isLoop(it.macros.getValue("HOLD")) }
            for (preset in shots) {
                val snip = preset.render()
                val heard = Classifier.classify(snip).drumClass
                all += "${preset.name}=$heard(${"%.2f".format(snip.durationSeconds)}s)"
                if (snip.durationSeconds > 1.5f) assertEquals(DrumClass.LOOP, heard, "${preset.name} is over the classifier's LOOP length")
                assertTrue(heard !in setOf(DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.KICK), "${preset.name} read as $heard")
                assertEquals(DrumClass.TONAL, Siren.drumClassFor(voice, preset.macros), "${preset.name} is filed TONAL by design")
            }
        }
        println("SIREN one-shots by the classifier: $all")
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in SirenPresets.all()) {
            val restored = SirenPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in SirenVoice.entries) {
            val names = SirenPresets.forVoice(voice).map { it.name }
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
        val offenders = SirenPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine or maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `presets spread out rather than cluster`() {
        for (voice in SirenVoice.entries) {
            val presets = SirenPresets.forVoice(voice)
            val base = Siren.defaults(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(base + a.macros, base + b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are nearly the same sound (${"%.3f".format(d)})")
            }
        }
    }

    @Test
    fun `the dispatcher knows SIREN`() {
        assertEquals(SirenPresets.forVoice(SirenVoice.WAIL), Presets.forVoice("SIREN", "WAIL"))
        assertEquals(SirenPresets.forVoice(SirenVoice.WAIL).first(), Presets.byName("SIREN", "WAIL", "AIR RAID"))
        assertTrue(Presets.all().containsAll(SirenPresets.all()))
    }
}
