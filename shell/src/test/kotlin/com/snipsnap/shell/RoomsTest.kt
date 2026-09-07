package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitStore
import java.io.File
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("rooms").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    /** A room: a direct path, then a slap 90 ms later at half height, then quiet. */
    private fun impulse(echoAt: Int = (0.09f * rate).toInt()): Snip {
        val out = FloatArray(echoAt + 2000)
        out[0] = 1f
        out[1] = 0.4f
        out[echoAt] = 0.5f
        out[echoAt + 1] = 0.2f
        return Snip(out, 1, rate)
    }

    private fun hit(seed: Long): Snip {
        val rng = Random(seed)
        val frames = (0.2f * rate).toInt()
        return Snip(FloatArray(frames) { f -> (0.6 * rng.nextGaussian() * 0.3 * Math.exp(-f / (0.05 * rate))).toFloat() }, 1, rate)
    }

    @Test
    fun `a room kept is a WAV and a sidecar beside the kits, listed back as it was measured, and never a kit`() {
        val shelf = File(temp, "shelf")
        val kitDir = File(shelf, "FUNK").apply { mkdirs() }
        KitStore.save(com.snipsnap.kit.Kit("FUNK", emptyList()), kitDir)

        val room = Rooms.keep(shelf, impulse(), "Funk", lagMs = 21.5f, confidence = 0.8f, from = "FUNK:A03", nowMillis = 1_000L)
        assertEquals("FUNK ROOM", room.name)
        assertEquals("room:FUNK ROOM", room.label)
        assertTrue(room.file.isFile && room.file.name == "FUNK ROOM.wav")
        assertTrue(File(Rooms.dir(shelf), "FUNK ROOM.json").isFile, "the sidecar")
        assertEquals(impulse().frameCount, WavReader.read(room.file).frameCount)

        val listed = Rooms.list(shelf).single()
        assertEquals(room.name, listed.name)
        assertEquals(21.5f, listed.lagMs, 1e-3f)
        assertEquals(0.8f, listed.confidence, 1e-3f)
        assertEquals("FUNK:A03", listed.from)
        assertEquals(1_000L, listed.measuredAt)
        assertEquals(impulse().frameCount.toFloat() / rate, listed.seconds, 1e-4f)
        assertEquals(listed, Rooms.find(shelf, "FUNK ROOM"))
        assertNull(Rooms.find(shelf, "NOWHERE"))

        assertEquals(listOf("FUNK"), KitStore.list(shelf).map { it.name }, "the rooms folder is not a kit")
    }

    @Test
    fun `a second room from the same kit is FUNK ROOM 2, the latest listed first, and a forgotten one is gone`() {
        val shelf = File(temp, "shelf2")
        val first = Rooms.keep(shelf, impulse(), "FUNK", 10f, 0.5f, "FUNK:A01", nowMillis = 1_000L)
        val second = Rooms.keep(shelf, impulse(), "FUNK ROOM", 12f, 0.6f, "FUNK:A02", nowMillis = 2_000L)
        assertEquals("FUNK ROOM", first.name)
        assertEquals("FUNK ROOM 2", second.name, "ROOM is not doubled, the number is added")
        assertEquals(listOf("FUNK ROOM 2", "FUNK ROOM"), Rooms.list(shelf).map { it.name }, "latest first")

        // Forgotten is binned, not gone: the shelf no longer lists it, the bin does, with the days it has left.
        val binned = Rooms.forget(shelf, first, nowMillis = 10_000L)
        assertEquals(listOf("FUNK ROOM 2"), Rooms.list(shelf).map { it.name })
        assertTrue(!File(Rooms.dir(shelf), "FUNK ROOM.wav").exists() && !File(Rooms.dir(shelf), "FUNK ROOM.json").exists())
        assertEquals(listOf("FUNK ROOM"), Rooms.binned(shelf).map { it.room.name })
        assertEquals(10_000L, Rooms.binned(shelf).single().binnedAt)
        assertEquals(30, binned.daysLeft(10_000L))
        assertEquals(29, binned.daysLeft(10_000L + 24L * 60 * 60 * 1000), "a whole day on, a whole day fewer")
        assertEquals(1, binned.daysLeft(10_000L + 30L * 24 * 60 * 60 * 1000 - 1), "a moment before the boundary still reads a day, as the sweep still keeps it")
        assertEquals(0, binned.daysLeft(10_000L + 30L * 24 * 60 * 60 * 1000), "at the boundary it reads 0, as the sweep goes")
        assertEquals(0, binned.daysLeft(10_000L + 40L * 24 * 60 * 60 * 1000))
        assertEquals(10f, Rooms.binned(shelf).single().room.lagMs, "the measurement rides into the bin")
        // The sidecar moved with the WAV: frames and rate are still written down, and a field this
        // version never heard of survives the trip both ways.
        val binSide = File(Rooms.binDir(shelf), "FUNK ROOM.json")
        assertTrue(binSide.isFile, "the sidecar moved into the bin")
        val binText = binSide.readText()
        assertTrue("\"frames\"" in binText && "\"binnedAt\"" in binText, binText)
        binSide.writeText(binText.replaceFirst("{", "{\"stranger\": \"kept\", "))

        // 29 days on, the sweep leaves it; 31 days on, it is gone for good.
        assertEquals(0, Rooms.sweepBin(shelf, nowMillis = 10_000L + 29L * 24 * 60 * 60 * 1000))
        assertEquals(1, Rooms.binned(shelf).size)
        // Out of the bin first, to prove the way back: it lands under a fresh name beside FUNK ROOM 2.
        val back = Rooms.unforget(shelf, Rooms.binned(shelf).single())
        assertEquals("FUNK ROOM", back.name)
        assertEquals(listOf("FUNK ROOM 2", "FUNK ROOM"), Rooms.list(shelf).map { it.name })
        assertTrue(Rooms.binned(shelf).isEmpty())
        val shelfSide = File(Rooms.dir(shelf), "FUNK ROOM.json").readText()
        assertTrue("\"frames\"" in shelfSide && "\"stranger\"" in shelfSide && "binnedAt" !in shelfSide, shelfSide)
        assertEquals(10f, back.lagMs)
        // And forgotten again, then left past the bin's days, the sweep takes it.
        Rooms.forget(shelf, back, nowMillis = 20_000L)
        assertEquals(0, Rooms.sweepBin(shelf, nowMillis = 20_000L + 30L * 24 * 60 * 60 * 1000 - 1), "a moment before the boundary it stays")
        assertEquals(1, Rooms.sweepBin(shelf, nowMillis = 20_000L + 30L * 24 * 60 * 60 * 1000), "at the boundary it goes, as daysLeft reads 0")
        assertTrue(Rooms.binned(shelf).isEmpty())
        assertEquals(listOf("FUNK ROOM 2"), Rooms.list(shelf).map { it.name }, "the bin folder is never a room")

        // A WAV with no sidecar still lists, its length read off the file.
        File(Rooms.dir(shelf), "FUNK ROOM 2.json").delete()
        val bare = Rooms.list(shelf).single()
        assertEquals("FUNK ROOM 2", bare.name)
        assertEquals(impulse().frameCount.toFloat() / rate, bare.seconds, 1e-4f)
        assertEquals(0f, bare.lagMs)

        // A file that is not a WAV is skipped, not fatal - even with a sidecar vouching for it.
        File(Rooms.dir(shelf), "junk.wav").writeText("not a wav")
        File(Rooms.dir(shelf), "junk.json").writeText("""{"version":1,"name":"junk","frames":4410,"sampleRate":44100,"measuredAt":9}""")
        assertEquals(listOf("FUNK ROOM 2"), Rooms.list(shelf).map { it.name })
        assertTrue(Rooms.list(File(temp, "nowhere")).isEmpty(), "no folder, no rooms")
    }

    @Test
    fun `a room that says nothing is not kept`() {
        val shelf = File(temp, "shelf3")
        val e = assertFailsWith<IllegalArgumentException> { Rooms.keep(shelf, Snip(FloatArray(4410), 1, rate), "FUNK", 0f, 0f, "FUNK:A01") }
        assertTrue(e.message!!.contains("nothing"), e.message)
        assertTrue(Rooms.list(shelf).isEmpty())
        assertTrue(!Rooms.dir(shelf).exists(), "refused before the folder is even made")
    }

    @Test
    fun `a kept room is a MUTATE parent - ROOM plays another kit's pad inside it, the recipe names the room`() {
        val shelf = File(temp, "shelf4")
        val room = Rooms.keep(shelf, impulse(), "FUNK", 20f, 0.9f, "FUNK:A01", nowMillis = 5L)

        val m = KitBuilderModel.create("SOUL", File(shelf, "SOUL"))
        m.assign(1, hit(3), DrumClass.KICK)
        m.save()
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val before = WavReader.read(padFile)

        val partner = Rooms.partner(room)
        assertEquals("FUNK ROOM", MutateSheet.name(partner))
        MutateSheet.apply(m, 1, partner, Mutate.Mode.ROOM, fraction = 1f)
        m.save()

        val after = WavReader.read(padFile)
        assertTrue(after.frameCount >= before.frameCount + (0.09f * rate).toInt(), "the pad plus the room's slap: ${after.frameCount}")
        val applied = MutateSheet.read(m.pad(1)!!.recipe)!!
        assertEquals("ROOM", applied.mode)
        assertEquals(listOf("room:FUNK ROOM"), applied.parents)
        val mutate = m.pad(1)!!.recipe!!.entries["mutate"] as JsonValue.Obj
        assertEquals("FUNK ROOM", (mutate.entries["room"] as JsonValue.Str).value, "the room block names it")
        assertNull(OutsideSheet.read(m.pad(1)!!.recipe), "a kept room is a mutate, not a trip")

        MutateSheet.undo(m, 1)
        m.save()
        assertTrue(WavReader.read(padFile).samples.contentEquals(before.samples), "undo is the original")
    }

    @Test
    fun `the copy shouts`() {
        val line = Copy.roomKept("FUNK ROOM")
        assertEquals(line.uppercase(), line)
        assertTrue(line.startsWith("FUNK ROOM ") && "MUTATE" in line, line)
        assertEquals(Copy.ROOM_NONE_TO_KEEP.uppercase(), Copy.ROOM_NONE_TO_KEEP)
        val gone = Copy.roomForgotten("FUNK ROOM")
        assertEquals(gone.uppercase(), gone)
        assertTrue("BIN" in gone && "30 DAYS" in gone, gone)
        val back = Copy.roomRestored("FUNK ROOM")
        assertEquals(back.uppercase(), back)
        assertTrue(back.startsWith("FUNK ROOM ") && "SHELF" in back, back)
    }
}
