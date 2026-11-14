package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ETCH's factory bank (round K2): the settings the owner heard and accepted in round 3, each voice's note and line
 * plus SAW's busy line and SQUARE's sparse line. These are the house's preset contracts on that bank.
 */
class EtchPresetsTest {
    private val bank = EtchPresets.all()
    private val renders = bank.associateWith { it.render().samples }

    @Test fun `the bank is every voice's note and line plus the two heard chain lines`() {
        for (voice in EtchVoice.entries) {
            val names = EtchPresets.forVoice(voice).map { it.name }
            assertEquals(listOf("Note", "Line"), names.take(2), "$voice")
            assertEquals(Etch.defaults(voice), EtchPresets.forVoice(voice).first().macros, "$voice: its note is the voice's defaults")
        }
        assertEquals(listOf("Busy line"), EtchPresets.forVoice(EtchVoice.SAW).drop(2).map { it.name })
        assertEquals(listOf("Sparse line"), EtchPresets.forVoice(EtchVoice.SQUARE).drop(2).map { it.name })
        assertEquals(EtchVoice.entries.size * 2 + 2, bank.size)
        assertEquals(bank, Presets.all().filterIsInstance<EtchPatch>())
    }

    @Test fun `every preset renders finite, bounded and centred, a note as a tonal one-shot and a line as a loop`() {
        for ((patch, out) in renders) {
            assertTrue(out.all { it.isFinite() } && out.isNotEmpty(), "${patch.voice} ${patch.name}: not finite")
            assertTrue(out.maxOf { abs(it) } in .05f..1f, "${patch.voice} ${patch.name}: peak ${out.maxOf { abs(it) }}")
            assertTrue(abs(out.average()) < .01, "${patch.voice} ${patch.name}: DC ${out.average()}")
            val loop = Etch.isLoop(patch.macros.getValue("HOLD"))
            assertEquals(if (loop) DrumClass.LOOP else DrumClass.TONAL, Etch.drumClassFor(patch.voice, patch.macros))
            val seconds = out.size.toFloat() / Dsp.RATE
            if (loop) assertTrue(seconds > 1.5f, "${patch.name}: a line is a loop") else assertTrue(seconds < 1.5f, "${patch.name}: $seconds s")
        }
    }

    @Test fun `no preset is heard as a drum`() {
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        for (patch in bank) {
            val heard = Classifier.classify(FeatureExtractor.extract(patch.render())).drumClass
            assertFalse(heard in drums, "${patch.voice} ${patch.name} reads as $heard")
        }
    }

    @Test fun `every preset round-trips through JSON with its root, velocity and model`() {
        for (patch in bank) {
            val back = EtchPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, back, "${patch.voice} ${patch.name}")
            assertTrue(back.render().samples.contentEquals(renders.getValue(patch)), "${patch.voice} ${patch.name}: not the same bytes")
        }
    }

    @Test fun `names are unique within a voice and borrow no maker's name`() {
        for (voice in EtchVoice.entries) {
            val names = EtchPresets.forVoice(voice).map { it.name }
            assertEquals(names.size, names.toSet().size, "$voice: duplicate names $names")
        }
        val offenders = bank.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    @Test fun `every two presets of a voice are different recipes and different sounds`() {
        for (voice in EtchVoice.entries) {
            val presets = EtchPresets.forVoice(voice)
            for (i in presets.indices) for (j in i + 1 until presets.size) {
                val a = presets[i]; val b = presets[j]
                assertFalse(a.macros == b.macros, "${a.name} and ${b.name} are one recipe")
                assertFalse(renders.getValue(a).contentEquals(renders.getValue(b)), "${a.name} and ${b.name} render the same bytes")
            }
        }
    }
}
