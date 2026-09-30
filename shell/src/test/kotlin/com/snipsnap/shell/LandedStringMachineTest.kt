package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Presets
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stereo half of SEND TO PAD, which nothing else ran with a two-channel
 * pad: a string machine lands with its ENSEMBLE and is 2 channels the moment
 * `KitBuilderModel` writes it. This walks that pad through the doors it
 * meets - ADD, REPLACE, save and reopen, the recipe that regenerates it, the
 * preflight and the program export - so a channel-count assumption in any of
 * them fails here and not on the card.
 */
class LandedStringMachineTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("landedstrings").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun recipeOf(engine: String, voice: String, name: String): PadRecipe {
        val patch = requireNotNull(Presets.byName(engine, voice, name)) { "$engine/$voice/$name" }
        val chain = requireNotNull(Presets.landingFor(engine, voice, patch.macros)) { "$name lands dry" }
        return PadRecipe(patch, chain)
    }

    @Test
    fun `a landed string machine is a stereo pad through add, replace, reopen, preflight and export`() {
        val dir = File(temp, "Strings")
        val model = KitBuilderModel.create("Strings", dir)

        // ADD, the way SynthScreen.sendToSlot does it: assign the render, then attach the recipe.
        val first = recipeOf("VELVET", "BRASS", "STRING MACHINE")
        val rendered = first.render()
        assertEquals(2, rendered.channels)
        model.assign(1, rendered, DrumClass.TONAL, "Brass Velvet")
        val added = model.update(1) { it.copy(recipe = first.toJsonValue()) }
        val onDisk = WavReader.read(File(dir, added.sampleFile))
        assertEquals(2, onDisk.channels, "the pad's file kept its pair")
        assertEquals(rendered.frameCount, onDisk.frameCount, "and its length")

        // REPLACE with a different string machine on the same slot: the new file is a pair too.
        val second = recipeOf("RESIN", "BRASS", "DARK STRINGS")
        val replacement = second.render()
        model.replaceAudio(1, second.toJsonValue()) { _ -> replacement }
        val replaced = WavReader.read(File(dir, model.pad(1)!!.sampleFile))
        assertEquals(2, replaced.channels)
        assertEquals(replacement.frameCount, replaced.frameCount)

        // Save and reopen: the recipe on the pad regenerates the audio in the file (24-bit quantisation aside).
        model.save()
        val reopened = KitBuilderModel.open(dir)
        val pad = reopened.pad(1)!!
        val regenerated = PadRecipe.fromJsonValue(assertNotNull(pad.recipe as? JsonValue.Obj, "the pad kept its recipe")).render()
        assertEquals(2, regenerated.channels)
        val stored = WavReader.read(File(dir, pad.sampleFile))
        assertEquals(regenerated.samples.size, stored.samples.size)
        val worst = regenerated.samples.indices.maxOf { abs(regenerated.samples[it] - stored.samples[it]) }
        assertTrue(worst < 1e-5f, "the recipe no longer regenerates the file: worst sample difference $worst")

        // The card: preflight has nothing to fail on, and the program's slice end is the pair's frame count.
        val findings = Preflight.check(reopened.kit, dir)
        assertFalse(findings.blocked(), "preflight blocks a stereo string machine: $findings")
        val result = KitExporter.exportProgramFolder(reopened.kit, dir, File(temp, "sd"))
        val exported = File(result.directory, pad.sampleFile)
        val info = WavInfo.read(exported)
        assertEquals(stored.frameCount.toLong(), info.frameCount.toLong())
        assertTrue("<SliceEnd>${info.frameCount}</SliceEnd>" in result.program.readText(), "the program's slice end is not the exported file's frame count")
    }
}
