package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.PluckVoice
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UprightStarterTest {
    @Test fun `both bass articulations save as editable finite tonal notes with exact replay`() {
        val dir = createTempDirectory("upright-starter").toFile()
        try {
            val starter = assertNotNull(StarterKits.byId("upright"))
            assertEquals(KeySpec.parse("Eminpent"), starter.key)
            assertFalse(starter.seeded)
            val kit = starter.render("Upright", dir)
            assertEquals(16, kit.pads.size)
            val expectedNotes = listOf(28, 31, 33, 35, 38, 40, 43, 45)
            for ((index, pad) in kit.pads.withIndex()) {
                val recipe = assertNotNull(Breed.recipeOf(pad.recipe))
                val patch = assertIs<PluckPatch>(recipe.patch)
                assertEquals(PluckVoice.UPRIGHT, patch.voice)
                assertEquals(.65f, patch.velocity)
                assertEquals(if (index < 8) .18f else 1f, patch.macros.getValue("DAMP"))
                assertEquals(expectedNotes[index % 8], pad.source.getValue("rootMidi").toInt())
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertEquals(0, pad.muteGroup)
                assertTrue(pad.oneShot)
                assertNull(recipe.fx)
            }
            for (pad in listOf(kit.pads.first(), kit.pads.last())) {
                val recipe = assertNotNull(Breed.recipeOf(pad.recipe))
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }.toByteArray()
                assertTrue(expected.contentEquals(File(dir, pad.sampleFile).readBytes()), pad.displayName)
            }
        } finally { dir.deleteRecursively() }
    }
}
