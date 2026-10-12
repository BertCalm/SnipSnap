package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.synth.Corolla
import com.snipsnap.synth.CorollaVoice
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorollaRoutingTest {
    @Test
    fun `IN KEY retunes a finite Corolla note while preserving a held texture`() {
        val dir = Files.createTempDirectory("corolla-routing").toFile()
        try {
            val builder = KitBuilderModel.create("FLOWER", dir)
            val voice = CorollaVoice.TONGUE
            val finite = mapOf("FIELD" to 0f, "CONTACT" to 0f, "TUNE" to .5f)
            val held = finite + ("HOLD" to 1f)
            val note = builder.assign(1, Corolla.render(voice, finite), Corolla.drumClassFor(voice, finite))
            val texture = builder.assign(2, Corolla.render(voice, held), Corolla.drumClassFor(voice, held))
            assertEquals(DrumClass.TONAL, note.drumClass)
            assertEquals(DrumClass.LOOP, texture.drumClass)
            builder.setKey(KeySpec.parse("D")) // C is outside D major.
            assertEquals(listOf(1), builder.retuneTonalPads())
            assertEquals(1, abs(builder.pad(1)!!.tuneCoarse), "the finite C was not moved onto the selected scale")
            assertEquals(texture, builder.pad(2), "a held texture was retuned as a finite note")
            assertTrue(KeyPicker.readouts(builder.kit).single().startsWith("A01"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
