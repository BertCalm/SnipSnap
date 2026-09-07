package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstrumentStoreTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("instr").toFile()

    @AfterTest
    fun tearDown() { temp.deleteRecursively() }

    private fun tone(hz: Double, seconds: Float = 0.6f): Snip =
        Snip(FloatArray((seconds * 44_100).toInt()) { (0.5 * Math.sin(2 * Math.PI * hz * it / 44_100)).toFloat() }, 1, 44_100)

    @Test
    fun `every exported instrument gets a sidecar the phone can read back`() {
        val root = File(temp, "Instruments")
        val result = OneNote.export("Bass", tone(110.0), root)
        val sidecar = InstrumentStore.sidecar(root, "Bass")
        assertTrue(sidecar.isFile, "the sidecar sits beside the .xty")
        val instrument = InstrumentStore.read(sidecar)
        assertEquals("Bass", instrument.name)
        assertEquals(1, instrument.zones.size)
        val zone = instrument.zones.single()
        assertEquals(result.rootMidi, zone.rootNote)
        assertEquals(0, zone.lowNote)
        assertEquals(127, zone.highNote)
        assertEquals(result.loopStartFrame, zone.loopStartFrame)
        assertEquals(result.sample.frameCount.toLong(), zone.frameCount)
        assertTrue(File(root, zone.sample).isFile, "the sample path resolves from the sidecar's folder: ${zone.sample}")
        assertEquals(result.program.volumeRelease, instrument.release)
        assertEquals(zone, instrument.zoneFor(60))
        assertEquals(result.rootMidi, instrument.rootNote)

        // A pad (looped) carries its loop start through.
        val pad = PadFromAnything.export("Pad", tone(220.0), root, PadFromAnything.Spec(depth = 20f))
        val padSide = InstrumentStore.read(InstrumentStore.sidecar(root, "Pad"))
        assertEquals(pad.loopStartFrame, padSide.zones.single().loopStartFrame)
        assertTrue(padSide.zones.single().loopStartFrame > 0)

        val listed = InstrumentStore.list(root)
        assertEquals(listOf("Bass", "Pad"), listed.map { it.second.name })
        assertEquals(emptyList(), InstrumentStore.list(File(temp, "nowhere")))
    }

    @Test
    fun `a broken sidecar is refused on read and skipped on list`() {
        val root = File(temp, "I").apply { mkdirs() }
        File(root, "Bad$SUFFIX").writeText("""{"version": 1, "name": "Bad"}""")
        assertFailsWith<JsonException> { InstrumentStore.read(File(root, "Bad$SUFFIX")) }
        File(root, "Old$SUFFIX").writeText("""{"version": 9, "name": "Old", "zones": []}""")
        assertFailsWith<JsonException> { InstrumentStore.read(File(root, "Old$SUFFIX")) }
        // Every v1 field is required: a half-written sidecar is refused, not defaulted onto the shelf.
        File(root, "NoRelease$SUFFIX").writeText("""{"version": 1, "name": "NoRelease", "zones": [{"low": 0, "high": 127, "root": 60, "sample": "x.wav", "frames": 10, "loopStart": 0}]}""")
        assertFailsWith<JsonException> { InstrumentStore.read(File(root, "NoRelease$SUFFIX")) }
        File(root, "NoFrames$SUFFIX").writeText("""{"version": 1, "name": "NoFrames", "release": 0.5, "zones": [{"low": 0, "high": 127, "root": 60, "sample": "x.wav", "loopStart": 0}]}""")
        assertFailsWith<JsonException> { InstrumentStore.read(File(root, "NoFrames$SUFFIX")) }
        File(root, "NoLoop$SUFFIX").writeText("""{"version": 1, "name": "NoLoop", "release": 0.5, "zones": [{"low": 0, "high": 127, "root": 60, "sample": "x.wav", "frames": 10}]}""")
        assertFailsWith<JsonException> { InstrumentStore.read(File(root, "NoLoop$SUFFIX")) }
        assertEquals(emptyList(), InstrumentStore.list(root))
        assertNull(InstrumentStore.Instrument("X", 0.5f, listOf(InstrumentStore.Zone(40, 50, 45, "x.wav", 10, 0))).zoneFor(60))
    }

    private companion object {
        const val SUFFIX = InstrumentStore.SUFFIX
    }
}
