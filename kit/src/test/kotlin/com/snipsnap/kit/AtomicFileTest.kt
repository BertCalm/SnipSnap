package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicFileTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("atomic").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `writes the whole content and leaves no temp behind`() {
        val f = File(temp, "kit.json")
        AtomicFile.writeText(f, "{\"whole\": true}")
        assertEquals("{\"whole\": true}", f.readText())
        assertTrue(temp.listFiles()!!.none { it.name.endsWith(".tmp") }, "no .tmp residue")
    }

    @Test
    fun `overwrites atomically - the reader never sees a mix`() {
        val f = File(temp, "kit.json")
        AtomicFile.writeText(f, "old content, quite long".repeat(100))
        AtomicFile.writeText(f, "new")
        assertEquals("new", f.readText(), "the new content fully replaced the old")
    }

    @Test
    fun `a stale temp from a prior crash does not corrupt the next write`() {
        val f = File(temp, "kit.json")
        File(temp, "kit.json.tmp").writeText("garbage from a killed process")
        AtomicFile.writeText(f, "clean")
        assertEquals("clean", f.readText())
        assertTrue(!File(temp, "kit.json.tmp").exists(), "the stale temp is gone")
    }

    @Test
    fun `streamed content replaces the target only after the complete write`() {
        val file = File(temp, "stream.bin")
        file.writeText("original")
        AtomicFile.write(file) { out ->
            out.write("first ".toByteArray())
            assertEquals("original", file.readText(), "readers must keep the old file while writing")
            out.write("second".toByteArray())
        }
        assertEquals("first second", file.readText())
        assertFalse(File(temp, "stream.bin.tmp").exists())
    }

    @Test
    fun `a failed streaming writer preserves the target and removes its partial temp`() {
        val file = File(temp, "stream.bin")
        file.writeText("original")
        val error = assertFailsWith<IOException> {
            AtomicFile.write(file) { out ->
                out.write("partial replacement".toByteArray())
                throw IOException("writer stopped before completion")
            }
        }
        assertEquals("writer stopped before completion", error.message)
        assertEquals("original", file.readText())
        assertFalse(File(temp, "stream.bin.tmp").exists(), "failed writes must not leave encoded fragments")
    }

    @Test
    fun `KitStore save survives as a whole file`() {
        val dir = File(temp, "kit")
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), Snip(FloatArray(100) { 0.1f }, 1, 44_100))
        val kit = Kit("Durable", listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav")))
        KitStore.save(kit, dir)
        assertEquals("Durable", KitStore.load(dir).name)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
