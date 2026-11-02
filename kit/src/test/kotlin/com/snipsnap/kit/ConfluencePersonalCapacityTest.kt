package com.snipsnap.kit

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfluencePersonalCapacityTest {
    private val root = Files.createTempDirectory("personal-capacity").toFile()
    @AfterTest fun cleanUp() { root.deleteRecursively() }
    private fun id(index: Int) = index.toString(16).padStart(32, '0')

    /** Admission reads lengths only: sparse files exercise large byte boundaries without buffers. */
    private fun existing(count: Int, full: Boolean = false): Kit {
        val pads = (1..count).map { i ->
            val dir = File(root, "${ConfluencePersonalFiles.DIRECTORY}/${id(i)}").apply { mkdirs() }
            for ((name, length) in listOf(
                "voice.json" to if (full) ConfluencePersonalFiles.MAX_MANIFEST_BYTES else 1L,
                "raw.wav" to if (full) ConfluencePersonalFiles.MAX_RAW_BYTES else 44L,
                "source.wav" to if (full) ConfluencePersonalFiles.MAX_SOURCE_BYTES else 44L,
            )) RandomAccessFile(File(dir, name), "rw").use { it.setLength(length) }
            KitPad(i, "A${i.toString().padStart(2, '0')}_Voice.wav", source = mapOf(ConfluencePersonalFiles.SOURCE_ID_KEY to id(i)))
        }
        return Kit("Capacity", pads)
    }

    @Test fun `the sixteenth distinct voice fits while seventeenth refuses before writes`() {
        val kit = existing(15)
        ConfluencePersonalFiles.requireCapacity(kit, root, id(16), 100)
        assertFalse(File(root, "${ConfluencePersonalFiles.DIRECTORY}/${id(16)}").exists())
        val sixteen = existing(16)
        val error = assertFailsWith<IllegalArgumentException> { ConfluencePersonalFiles.requireCapacity(sixteen, root, id(17), 100) }
        assertTrue(error.message!!.contains("16 distinct voices"), error.message)
        // A second pad using an immutable existing voice spends no new voice slot or source bytes.
        ConfluencePersonalFiles.requireCapacity(sixteen, root, id(1), ConfluencePersonalFiles.MAX_VOICE_BYTES)
    }

    @Test fun `the source byte boundary includes a two MiB kit metadata reserve`() {
        val kit = existing(4, full = true)
        val lengths = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.length() }
        val available = ConfluencePersonalFiles.MAX_KIT_BYTES - ConfluencePersonalFiles.KIT_METADATA_RESERVE_BYTES - lengths.values.sum()
        assertTrue(available in 1..ConfluencePersonalFiles.MAX_VOICE_BYTES)
        ConfluencePersonalFiles.requireCapacity(kit, root, id(5), available)
        val error = assertFailsWith<IllegalArgumentException> { ConfluencePersonalFiles.requireCapacity(kit, root, id(5), available + 1) }
        assertTrue(error.message!!.contains("including kit metadata reserve"), error.message)
        assertEquals(lengths, root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.length() })
        assertFalse(File(root, "${ConfluencePersonalFiles.DIRECTORY}/${id(5)}").exists())
    }

    @Test fun `missing oversized or linked existing sources and unbounded incoming sizes refuse`() {
        val kit = existing(1)
        val raw = File(root, "${ConfluencePersonalFiles.DIRECTORY}/${id(1)}/raw.wav")
        assertTrue(raw.delete())
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalFiles.requireCapacity(kit, root, id(2), 100) }
            .also { assertTrue(it.message!!.contains("existing personal voice file")) }
        RandomAccessFile(raw, "rw").use { it.setLength(ConfluencePersonalFiles.MAX_RAW_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalFiles.requireCapacity(kit, root, id(2), 100) }
            .also { assertTrue(it.message!!.contains("existing personal voice file")) }
        assertTrue(raw.delete())
        val outside = File(root, "outside.wav").apply { writeBytes(ByteArray(44)) }
        Files.createSymbolicLink(raw.toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalFiles.requireCapacity(kit, root, id(2), 100) }
            .also { assertTrue(it.message!!.contains("symbolic link")) }
        for (size in listOf(0L, -1L, ConfluencePersonalFiles.MAX_VOICE_BYTES + 1)) {
            assertFailsWith<IllegalArgumentException> { ConfluencePersonalFiles.requireCapacity(Kit("Empty", emptyList()), root, id(2), size) }
                .also { assertTrue(it.message!!.contains("bounded source size")) }
        }
    }
}
