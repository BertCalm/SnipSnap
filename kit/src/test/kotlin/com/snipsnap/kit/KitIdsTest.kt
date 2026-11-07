package com.snipsnap.kit

import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitIdsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("kitids").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun kitDir(name: String = "Ids"): File {
        val dir = File(temp, name).apply { mkdirs() }
        KitStore.save(Kit(name, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav"))), dir)
        return dir
    }

    @Test
    fun `a kit without an id loads and saves exactly as before`() {
        val dir = kitDir()
        val file = File(dir, KitStore.FILE_NAME)
        val before = file.readBytes()
        assertFalse(file.readText().contains("\"id\""), "no id is written for a kit that has none")
        KitStore.save(KitStore.load(dir), dir)
        assertTrue(before.contentEquals(file.readBytes()), "load and save leave the bytes alone")
        assertNull(KitStore.load(dir).id)
    }

    @Test
    fun `ensure writes an id once and keeps it`() {
        val dir = kitDir()
        assertNull(KitIds.read(dir))
        val a = KitIds.ensure(dir, Random(7))
        assertEquals(12, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' }, "lowercase hex: $a")
        assertEquals(a, KitIds.ensure(dir, Random(99)), "a second ensure keeps the first id")
        assertEquals(a, KitStore.load(dir).id)
        assertEquals(a, KitIds.read(dir))
    }

    @Test
    fun `the id survives a rename's rewrite`() {
        val dir = kitDir()
        val id = KitIds.ensure(dir, Random(3))
        KitStore.save(KitStore.load(dir).copy(name = "Renamed"), dir)
        assertEquals(id, KitStore.load(dir).id)
        assertEquals("Renamed", KitStore.load(dir).name)
    }

    @Test
    fun `a save that does not know the id keeps the one on disk`() {
        // An open kit's editor holds the kit it loaded before the kit joined an expansion; its next save must not drop the id.
        val dir = kitDir()
        val stale = KitStore.load(dir)
        val id = KitIds.ensure(dir, Random(5))
        KitStore.save(stale.copy(name = "Edited"), dir)
        assertEquals(id, KitIds.read(dir))
        assertEquals("Edited", KitStore.load(dir).name)
    }

    @Test
    fun `forget takes the id away`() {
        val dir = kitDir()
        KitIds.ensure(dir, Random(6))
        KitIds.forget(dir)
        assertNull(KitIds.read(dir))
    }

    @Test
    fun `a missing or broken kit has no id to read`() {
        assertNull(KitIds.read(File(temp, "nowhere")))
        val broken = File(temp, "broken").apply { mkdirs() }
        File(broken, KitStore.FILE_NAME).writeText("not json")
        assertNull(KitIds.read(broken))
    }
}
