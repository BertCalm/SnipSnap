package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Product contracts: the named roster, saved recipes, filing, the two-bank, two-tempo kit, velocity and spread. */
class ScourPresetsTest {

    @Test
    fun `the twelve impacts name every control and round-trip every voice`() {
        val names = setOf(
            "IRON SLAM", "FORGE RISE", "GRINDER RUN", "PEENED BELL",
            "GLASS SNAP", "FROST RISE", "SHATTER RUN", "PANE TOLL",
            "STEEL BOOM", "SHEET RISE", "THUNDER RUN", "DISTANT STORM",
        )
        val all = ScourPresets.all()
        assertEquals(names, all.map { it.name }.toSet())
        assertEquals(12, all.size)
        for (voice in ScourVoice.entries) {
            assertEquals(4, ScourPresets.forVoice(voice).size, "$voice has four impacts")
            assertEquals(ScourPresets.forVoice(voice), Presets.forVoice("SCOUR", voice.name))
        }
        assertTrue(Presets.all().containsAll(all))
        assertTrue(Presets.forVoice("SCOUR", "NOT_A_VOICE").isEmpty())
        for (preset in all) {
            assertEquals(Scour.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, preset.name)
            assertTrue(preset.name.length <= 18, preset.name)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name), preset.name)
            val semitone = preset.macros.getValue("TUNE") * Scour.TUNE_SEMITONES
            assertTrue(abs(semitone - semitone.roundToInt()) < 1e-5f, "${preset.name}: semitone tuning")
            assertTrue(semitone.roundToInt() % 12 in PENTATONIC_CLASSES, "${preset.name}: off C minor pentatonic")
            assertEquals(preset, Patches.fromJsonText(preset.toJsonText()), preset.name)
            assertEquals(preset, Presets.byName(preset.engine, preset.voiceName, preset.name))
            assertEquals(null, Presets.landingFor(preset.engine, preset.voiceName, preset.macros))
        }
    }

    @Test
    fun `factory impacts stay out of drum routing`() {
        for (preset in ScourPresets.all()) {
            val filed = Scour.drumClassFor(preset.voice, preset.macros)
            val heard = Classifier.classify(preset.render()).drumClass
            assertTrue(filed !in DRUMS, "${preset.voice}/${preset.name}: filed $filed")
            assertTrue(heard !in DRUMS, "${preset.voice}/${preset.name}: classified $heard")
            assertEquals(filed, heard, "${preset.voice}/${preset.name}: duration-aware routing")
        }
    }

    @Test
    fun `a saved impact regenerates through the existing effects chain`() {
        val patch = ScourPresets.forVoice(ScourVoice.PLATE).first()
        val fx = FxChain(crunch = mapOf("BITS" to 0.40f, "RATE" to 0.25f))
        val recipe = PadRecipe(patch, fx)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        assertContentEquals(fx.process(patch.render()).samples, restored.render().samples)
    }

    @Test
    fun `the dry kit cuts its builds for 120 and 90 BPM in both banks and ends each with its presets`() {
        val kit = SynthKits.scour()
        assertEquals(32, kit.size)
        val voices = mutableSetOf<ScourVoice>()
        // Slots 1-4 of each bank at 120 BPM, 5-8 at 90 BPM: one beat, two beats, one bar, two bars. Bank A
        // builds PLATE then PANE at C3; bank B builds SHEET at C2.
        val builds = listOf(120 to 1, 120 to 2, 120 to 4, 120 to 8, 90 to 1, 90 to 2, 90 to 4, 90 to 8)
        for ((i, maybePad) in kit.withIndex()) {
            val slot = "${'A' + i / 16}%02d".format(i % 16 + 1)
            val inBank = i % 16
            val pad = requireNotNull(maybePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = recipe.patch as ScourPatch
            voices += patch.voice
            assertEquals(null, recipe.fx, "$slot is dry")
            assertFalse(recipe.alias)
            assertTrue(pad.oneShot, "$slot plays once, whole")
            assertEquals(patch.name, pad.displayName, "$slot is named for its sound")
            assertEquals(Scour.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            assertContentEquals(pad.snip.samples, recipe.render().samples, "$slot regenerates exactly")
            val midi = Scour.midiFor(patch.voice, patch.macros.getValue("TUNE"))
            when {
                inBank < builds.size -> {
                    val (bpm, beats) = builds[inBank]
                    val voice = if (i >= 16) ScourVoice.SHEET else if (bpm == 120) ScourVoice.PLATE else ScourVoice.PANE
                    assertEquals(voice, patch.voice, slot)
                    assertEquals(Scour.rootMidi(voice), midi, "$slot is at its voice's root")
                    // The slam lands this far into the sample, to the frame: a pad hit that long before a downbeat lands on it.
                    val seconds = beats * 60.0 / bpm
                    val slam = Scour.mechanics(patch.voice, patch.macros).slamSeconds
                    assertTrue(abs(slam - seconds) * Dsp.RATE < 1.0, "$slot: the slam is at $slam s, not $seconds s")
                }
                i < 16 -> assertEquals(ScourPresets.all()[inBank - builds.size], patch, "$slot is the preset in roster order")
                inBank < 12 -> assertEquals(ScourPresets.forVoice(ScourVoice.SHEET)[inBank - builds.size], patch, "$slot is the preset in roster order")
                else -> {
                    // B13-B16: SHEET's shortest build on C2, E-flat 2, F2 and G2.
                    assertEquals(ScourVoice.SHEET, patch.voice, slot)
                    assertEquals(listOf(36, 39, 41, 43)[inBank - 12], midi, slot)
                    assertEquals(0f, patch.macros.getValue("BUILD"), slot)
                }
            }
        }
        assertEquals(ScourVoice.entries.toSet(), voices)
    }

    @Test
    fun `velocity renders the slam without editing the saved sound`() {
        val patch = ScourPresets.forVoice(ScourVoice.PLATE).first()
        val original = patch.macros.toMap()
        val soft = Velocity.atVelocity(patch, 0.25f)
        assertContentEquals(Scour.render(patch.voice, patch.macros, velocity = 0.25f).samples, soft.samples)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples), "a soft hit changes the synthesis")
        assertEquals(original, patch.macros, "the saved sound does not move")
    }

    @Test
    fun `the presets spread out rather than cluster`() {
        // Within a voice, no two presets sit closer than a fifth of the control range (RMS over every control).
        for (voice in ScourVoice.entries) {
            val list = ScourPresets.forVoice(voice)
            for (i in list.indices) for (j in i + 1 until list.size) {
                val d = PresetTestSupport.rmsDistance(list[i].macros, list[j].macros)
                assertTrue(d >= 0.2f, "$voice ${list[i].name} and ${list[j].name} are only $d apart")
            }
        }
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        /** C minor pentatonic's pitch classes from C: C, E-flat, F, G, B-flat. */
        val PENTATONIC_CLASSES = setOf(0, 3, 5, 7, 10)
    }
}
