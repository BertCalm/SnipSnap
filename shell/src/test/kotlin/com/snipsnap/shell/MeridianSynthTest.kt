package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual bench persistence and pad placement, distinct from a continuous keyboard export. */
class MeridianSynthTest {
    @Test
    fun `factory defaults and performance edits keep the accepted recipe and legacy transport`() {
        val accepted = MeridianPresets.all().first()
        assertEquals(8, MeridianSynth.factory().size)
        assertEquals(accepted, MeridianSynth.defaultPatch())
        assertEquals(4810, MeridianSynth.defaultPatch().seedContext)
        assertEquals(accepted.macros, MeridianSynth.defaults())
        val old = accepted.copy(model = 1, midi = 57, velocity = .7f, seedContext = -37,
            macros = accepted.macros + mapOf("TUNE" to .75f, "HOLD" to 1f))
        val edited = MeridianSynth.withVelocity(MeridianSynth.withMidi(old, 69), .35f)
        assertEquals(old.copy(midi = 69, velocity = .35f), edited)
        assertEquals(75, MeridianSynth.rootMidi(edited), "TUNE's six semitones are part of the authored note")
        assertEquals(75f, Spread.detect(Snip(FloatArray(256), 1, Meridian.RATE), edited),
            "SPREAD must use the saved source note even if a modal transient defeats audio detection")
        assertFalse(MeridianSynth.isHeld(edited), "a saved model-one HOLD=1 remains a finite gesture")
        assertEquals("FINITE", MeridianSynth.holdLabel(edited))
        assertNull(MeridianSynth.padLanding(edited).recipe.terminalFadeMs)
        val scrambled = MeridianSynth.scramble(edited, Random(17))
        assertEquals(scrambled, MeridianSynth.scramble(edited, Random(17)))
        assertEquals(edited.midi, scrambled.midi)
        assertEquals(edited.velocity, scrambled.velocity)
        assertEquals(edited.model, scrambled.model)
        assertEquals(edited.seedContext, scrambled.seedContext)
        assertTrue(scrambled.macros.values.all { it.isFinite() && it in 0f..1f })
        assertFalse(MeridianSynth.isHeld(scrambled))
        assertFailsWith<IllegalArgumentException> { MeridianSynth.withMidi(edited, Meridian.MIDI_MIN - 1) }
        assertFailsWith<IllegalArgumentException> { MeridianSynth.withVelocity(edited, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { MeridianSynth.withVelocity(edited, 1.01f) }
    }

    @Test
    fun `reopened held work keeps its complete source rack metadata and finite ending through edits`() {
        val patch = MeridianPresets.all().last().copy(name = "MY FILAMENT", midi = 69,
            velocity = .35f, seedContext = -37,
            macros = MeridianPresets.all().last().macros + ("TUNE" to .75f))
        val original = PadRecipe(patch, FxChain(reverse = true), treatment = "KEPT REVERSE",
            amount = .42f, alias = true, terminalFadeMs = 10f)
        val landing = MeridianSynth.padLanding(patch, original)
        assertEquals(original.copy(terminalFadeMs = 20f), landing.recipe)
        assertEquals(DrumClass.TONAL, landing.drumClass)
        assertTrue(landing.oneShot)
        assertEquals(75, landing.knownRootMidi)
        assertEquals("FINITE_HELD_CAPTURE", landing.source["playback"])
        assertEquals("FIXED LOOP", MeridianSynth.holdLabel(patch))
        assertEquals(40f, MeridianSynth.padLanding(patch, original.copy(terminalFadeMs = 40f)).recipe.terminalFadeMs,
            "a recalled longer ending must not be shortened")

        val settings = mutableMapOf("engine" to "MERIDIAN", "voice" to "SWEEP")
        SynthDrafts.write(settings, listOf(patch))
        SynthWork.replace(settings, listOf(SynthWork.Working(landing.recipe, SynthWork.Source("MY KIT", 16))))
        val draft = assertIs<MeridianPatch>(SynthDrafts.read(settings).single())
        val reopened = SynthWork.read(settings).single()
        assertEquals(patch, draft)
        assertEquals(landing.recipe, reopened.recipe)
        assertEquals(SynthWork.Source("MY KIT", 16), reopened.source)
        val edited = assertIs<MeridianPatch>(Patches.edited(draft, "EDITED FILAMENT",
            draft.macros + ("MEMORY" to .65f)))
        val editedLanding = MeridianSynth.padLanding(edited, reopened.recipe)
        assertEquals(patch.midi, edited.midi)
        assertEquals(patch.velocity, edited.velocity)
        assertEquals(patch.model, edited.model)
        assertEquals(patch.seedContext, edited.seedContext)
        assertEquals(landing.recipe.copy(patch = edited), editedLanding.recipe)
        val back = PadRecipe.fromJsonText(editedLanding.recipe.toJsonText())
        val sound = editedLanding.render()
        assertContentEquals(wav(sound), wav(back.render()), "full reopened recipe must recreate the finite delivered PCM")
        assertTrue(sound.samples.last() == 0f, "the saved capture ending reaches silence")
        val heldSource = MeridianSynth.render(edited)
        assertTrue(heldSource.samples.indices.any { heldSource.samples[it] != sound.samples[it] },
            "the finite capture is separate from the unchanged source transport and rack")
    }

    @Test
    fun `sending a held source lands a finite tonal pad with the authored root and replayable ending`() {
        val directory = createTempDirectory("meridian-send").toFile()
        try {
            val patch = MeridianPresets.all().last().copy(midi = 69, velocity = .65f,
                macros = MeridianPresets.all().last().macros + ("TUNE" to .75f), seedContext = -21)
            val landing = MeridianSynth.padLanding(patch)
            val model = KitBuilderModel.create("MY MERIDIAN", directory)
            val audio = landing.render()
            val pad = model.assign(16, audio, landing.drumClass, patch.name,
                source = landing.source, knownRootMidi = landing.knownRootMidi)
            model.update(16) { it.copy(recipe = landing.recipe.toJsonValue(), oneShot = landing.oneShot) }
            model.save()
            val saved = KitStore.load(directory).pad(16)!!
            assertEquals(DrumClass.TONAL, saved.drumClass)
            assertTrue(saved.oneShot)
            assertEquals("75", saved.source["rootMidi"])
            assertEquals("authored", saved.source["rootOrigin"])
            assertEquals("-21", saved.source["seedContext"])
            val recipe = PadRecipe.fromJsonValue(saved.recipe!!)
            assertEquals(patch, recipe.patch)
            assertEquals(20f, recipe.terminalFadeMs)
            assertContentEquals(wav(audio), File(directory, pad.sampleFile).readBytes())
            val replay = RecipeReplay.apply(KitBuilderModel.open(directory), 16, saved.recipe!!, saved.displayName)
            assertEquals(saved.recipe, replay.pad.recipe)
            assertContentEquals(wav(audio), File(directory, replay.pad.sampleFile).readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun wav(snip: Snip): ByteArray = ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()
}
