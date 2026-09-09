package com.snipsnap.shell

import com.snipsnap.audio.Snip
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomPackagerTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("roompack").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    private fun impulse(): Snip {
        val out = FloatArray(4000)
        out[0] = 1f
        out[1] = 0.4f
        out[2000] = 0.5f
        return Snip(out, 1, rate)
    }

    @Test
    fun `a room packed, sniffed and read back carries its measurement whole`() {
        val shelf = File(temp, "shelf")
        val room = Rooms.keep(shelf, impulse(), "Funk", lagMs = 21.5f, confidence = 0.8f, from = "FUNK:A03", nowMillis = 1_000L)

        val packed = File(temp, "out/FUNK ROOM.${RoomPackager.EXTENSION}")
        RoomPackager.write(room, packed)
        assertTrue(packed.isFile)
        assertTrue(RoomPackager.sniff(packed), "a freshly packed room sniffs as one")

        val imported = RoomPackager.read(packed)
        assertEquals("FUNK ROOM", imported.name)
        assertEquals(21.5f, imported.lagMs, 1e-3f)
        assertEquals(0.8f, imported.confidence, 1e-3f)
        assertEquals("FUNK:A03", imported.from)
        assertEquals(1_000L, imported.measuredAt)
        assertEquals(impulse().frameCount, imported.impulse.frameCount)
        // The WAV round trip is lossy (PCM quantization) - close, not bit-exact.
        assertEquals(1f, imported.impulse.samples[0], 1e-3f)
        assertEquals(0.4f, imported.impulse.samples[1], 1e-3f)
        assertEquals(0.5f, imported.impulse.samples[2000], 1e-3f)
    }

    @Test
    fun `re-keeping an imported room reuses the fresh-name collision rule, same as a second trip`() {
        val shelf = File(temp, "shelf2")
        val room = Rooms.keep(shelf, impulse(), "Funk", 10f, 0.5f, "FUNK:A01", nowMillis = 5_000L)
        val packed = File(temp, "FUNK ROOM.${RoomPackager.EXTENSION}")
        RoomPackager.write(room, packed)

        // Arriving on the SAME shelf a second time (a re-share, a duplicate
        // send) - the importer never overwrites, it names the second one.
        val imported = RoomPackager.read(packed)
        val second = Rooms.keep(
            shelf, imported.impulse, imported.name, imported.lagMs, imported.confidence, imported.from, imported.measuredAt,
        )
        assertEquals("FUNK ROOM 2", second.name, "the same room arriving twice doesn't collide, it renumbers")
        // Same measuredAt (provenance preserved, not re-stamped) ties the
        // sort's primary key, so the name is the tiebreak - alphabetical,
        // not import order.
        assertEquals(listOf("FUNK ROOM", "FUNK ROOM 2"), Rooms.list(shelf).map { it.name })
        assertEquals(10f, second.lagMs, "the measurement survived the round trip")
        assertEquals(5_000L, second.measuredAt, "provenance: the original measurement time, not the import time")
    }

    @Test
    fun `write refuses to overwrite without being told to`() {
        val shelf = File(temp, "shelf3")
        val room = Rooms.keep(shelf, impulse(), "Funk", 10f, 0.5f, "FUNK:A01")
        val packed = File(temp, "FUNK ROOM.${RoomPackager.EXTENSION}")
        RoomPackager.write(room, packed)
        assertFailsWith<java.io.IOException> { RoomPackager.write(room, packed) }
        RoomPackager.write(room, packed, overwrite = true)
    }

    @Test
    fun `a kit's own xpn, or any foreign zip, is not a room`() {
        val shelf = File(temp, "shelf4")
        val kitDir = File(shelf, "FUNK").apply { mkdirs() }
        com.snipsnap.kit.KitStore.save(com.snipsnap.kit.Kit("FUNK", emptyList()), kitDir)
        val xpn = File(temp, "FUNK.xpn")
        // A plain ZIP with an unrelated entry - the shape a real .xpn or any
        // other archive presents to a fixed-name-only sniff.
        ZipOutputStream(xpn.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Expansion.xml"))
            zip.write("<xml/>".toByteArray())
            zip.closeEntry()
        }
        assertFalse(RoomPackager.sniff(xpn), "a kit-shaped ZIP is not a room")
        assertFailsWith<IllegalArgumentException> { RoomPackager.read(xpn) }

        val notAZip = File(temp, "not-a-zip.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        assertFalse(RoomPackager.sniff(notAZip), "not a ZIP at all - false, never a throw")
    }

    @Test
    fun `a room zip missing its sidecar, or one whose sidecar names nothing, is refused in words`() {
        val wavOnly = File(temp, "wav-only.${RoomPackager.EXTENSION}")
        ZipOutputStream(wavOnly.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("room.wav"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        assertFalse(RoomPackager.sniff(wavOnly))
        assertFailsWith<IllegalArgumentException> { RoomPackager.read(wavOnly) }

        val shelf = File(temp, "shelf5")
        val room = Rooms.keep(shelf, impulse(), "Funk", 10f, 0.5f, "FUNK:A01")
        val noName = File(temp, "no-name.${RoomPackager.EXTENSION}")
        ZipOutputStream(noName.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("room.wav"))
            room.file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("room.json"))
            zip.write("""{"lagMs":1}""".toByteArray())
            zip.closeEntry()
        }
        assertTrue(RoomPackager.sniff(noName), "both entries present - sniff only checks names")
        val e = assertFailsWith<IllegalArgumentException> { RoomPackager.read(noName) }
        assertTrue("name" in e.message!!, e.message)
    }
}
