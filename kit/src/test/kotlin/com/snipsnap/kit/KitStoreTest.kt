package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KitStoreTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("kitstore").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun sampleKit() = Kit(
        name = "SnipSnap Kit 01",
        pads = listOf(
            KitPad(
                slot = 1, sampleFile = "A01_Kick_01.wav", displayName = "Kick 01",
                drumClass = DrumClass.KICK, colorHex = "#e8542e",
                level = 0.9f, pan = 0.4f, tuneCoarse = -2, tuneFine = 30,
                muteGroup = 0, oneShot = true,
                source = mapOf("app" to "YouTube", "title" to "some video"),
            ),
            KitPad(
                slot = 3, sampleFile = "A03_HatClosed_01.wav",
                drumClass = DrumClass.HAT_CLOSED, muteGroup = 1,
            ),
            KitPad(slot = 16, sampleFile = "A16_Loop_01.wav", drumClass = DrumClass.LOOP, oneShot = false),
        ),
    )

    @Test
    fun `round-trips with full fidelity`() {
        val dir = File(temp, "kit")
        KitStore.save(sampleKit(), dir)
        assertEquals(sampleKit(), KitStore.load(dir))
    }

    @Test
    fun `the sidecar is stable across a save-load-save cycle`() {
        val dir = File(temp, "kit")
        KitStore.save(sampleKit(), dir)
        val first = File(dir, KitStore.FILE_NAME).readText()
        KitStore.save(KitStore.load(dir), dir)
        assertEquals(first, File(dir, KitStore.FILE_NAME).readText())
    }

    @Test
    fun `loading a folder without a sidecar fails clearly`() {
        val dir = File(temp, "empty").apply { mkdirs() }
        assertFailsWith<IOException> { KitStore.load(dir) }
    }

    @Test
    fun `refuses a future sidecar version`() {
        val dir = File(temp, "future").apply { mkdirs() }
        File(dir, KitStore.FILE_NAME).writeText("""{"version": 2, "name": "x", "pads": []}""")
        assertFailsWith<JsonException> { KitStore.load(dir) }
    }

    @Test
    fun `unknown fields are ignored, not fatal`() {
        val dir = File(temp, "forward").apply { mkdirs() }
        File(dir, KitStore.FILE_NAME).writeText(
            """{"version": 1, "name": "x", "futureThing": {"a": 1},
                "pads": [{"slot": 1, "sample": "a.wav", "swing": 54}]}""",
        )
        val kit = KitStore.load(dir)
        assertEquals("x", kit.name)
        assertEquals("a.wav", kit.pad(1)?.sampleFile)
    }

    @Test
    fun `an unknown drum class degrades to UNKNOWN`() {
        val dir = File(temp, "cls").apply { mkdirs() }
        File(dir, KitStore.FILE_NAME).writeText(
            """{"version": 1, "name": "x",
                "pads": [{"slot": 1, "sample": "a.wav", "class": "COWBELL"}]}""",
        )
        assertEquals(DrumClass.UNKNOWN, KitStore.load(dir).pad(1)?.drumClass)
    }

    @Test
    fun `lists kit folders only`() {
        KitStore.save(sampleKit(), File(temp, "b-kit"))
        KitStore.save(sampleKit().copy(name = "Another"), File(temp, "a-kit"))
        File(temp, "not-a-kit").mkdirs()
        File(temp, "loose.wav").writeText("x")

        assertEquals(listOf("a-kit", "b-kit"), KitStore.list(temp).map { it.name })
    }

    @Test
    fun `model rejects duplicate slots and bad fields`() {
        assertFailsWith<IllegalArgumentException> {
            Kit("x", listOf(KitPad(1, "a.wav"), KitPad(1, "b.wav")))
        }
        assertFailsWith<IllegalArgumentException> { KitPad(0, "a.wav") }
        assertFailsWith<IllegalArgumentException> { KitPad(1, "dir/a.wav") }
        assertFailsWith<IllegalArgumentException> { KitPad(1, "a.wav", colorHex = "red") }
        assertFailsWith<IllegalArgumentException> { KitPad(1, "a.wav", muteGroup = 33) }
        assertFailsWith<IllegalArgumentException> { Kit("", emptyList()) }
    }

    @Test
    fun `name rules match the SD card`() {
        assertTrue(Names.isMpcSafe("SnipSnap Kit 01"))
        assertTrue(!Names.isMpcSafe("Kit: The Remix"))
        assertTrue(!Names.isMpcSafe("naïve kit"))
        assertTrue(!Names.isMpcSafe("kit."))
        assertTrue(!Names.isMpcSafe(" kit"))
        assertTrue(!Names.isMpcSafe(""))

        assertEquals("Kick_01", Names.sanitizeStem("Kick_01"))
        assertEquals("Kick_1", Names.sanitizeStem("Kick?:1"))
        assertEquals("Sample", Names.sanitizeStem("???"))
        assertEquals("naive kit", Names.sanitizeStem("naive kit"))
    }
}
