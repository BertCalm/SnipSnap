package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KitAssemblerTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("assembler").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun snip(seconds: Float = 0.2f): Snip =
        Cleanup.process(
            Snip(FloatArray((44_100 * seconds).toInt()) { i -> (0.5 * Math.sin(i / 15.0)).toFloat() }, 1, 44_100),
        )

    @Test
    fun `oneShot false threads through to the KitPad, defaulting true`() {
        val dir = File(temp, "Gate")
        val kit = KitAssembler.assembleArranged(
            "Gate Kit",
            listOf(
                ArrangedPad(snip(), DrumClass.TONAL, oneShot = false),
                ArrangedPad(snip(), DrumClass.KICK),
            ),
            dir,
        )
        assertEquals(false, kit.pad(1)?.oneShot, "explicit oneShot = false should carry through")
        assertEquals(true, kit.pad(2)?.oneShot, "default stays true for every existing caller")
    }
}
