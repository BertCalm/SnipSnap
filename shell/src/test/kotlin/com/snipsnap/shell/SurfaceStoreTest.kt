package com.snipsnap.shell

import com.snipsnap.json.JsonException
import com.snipsnap.shell.SurfaceStore.Corner
import com.snipsnap.shell.SurfaceStore.Settings
import com.snipsnap.shell.TouchSurface.Mode
import com.snipsnap.shell.TouchSurface.Reading
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SurfaceStoreTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("surface").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun near(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 1e-5f, "expected $expected, got $actual")

    @Test
    fun `a kit without a surface file has the defaults`() {
        assertEquals(Settings.DEFAULT, SurfaceStore.load(temp))
        assertEquals(Corner.DEFAULTS, Settings.DEFAULT.corners)
    }

    @Test
    fun `settings round-trip and the file is byte-stable`() {
        val s = Settings(padSlot = 7, corners = listOf(Corner(0f, 1f, 0.5f, 0.25f), Corner.DARK, Corner.LOW, Corner(1f, 0f, 0f, 1f)))
        val first = SurfaceStore.save(temp, s).readBytes()
        assertEquals(s, SurfaceStore.load(temp))
        val second = SurfaceStore.save(temp, SurfaceStore.load(temp)).readBytes()
        assertTrue(first.contentEquals(second), "a load-save cycle changed the bytes")
    }

    @Test
    fun `a null pad reads back as the kit's lowest`() {
        SurfaceStore.save(temp, Settings(padSlot = null))
        assertEquals(null, SurfaceStore.load(temp).padSlot)
    }

    @Test
    fun `refusals are in words`() {
        assertFailsWith<IllegalArgumentException> { Corner(1.2f, 0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { Corner(Float.NaN, 0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { Settings(padSlot = 0) }
        assertFailsWith<IllegalArgumentException> { Settings(padSlot = 1, corners = Corner.DEFAULTS.take(3)) }
        File(temp, SurfaceStore.FILE_NAME).writeText("""{"version":2,"pad":1,"corners":[]}""")
        assertFailsWith<JsonException> { SurfaceStore.load(temp) }
        File(temp, SurfaceStore.FILE_NAME).writeText("""{"version":1,"pad":"one"}""")
        assertFailsWith<JsonException> { SurfaceStore.load(temp) }
        File(temp, SurfaceStore.FILE_NAME).writeText("""{"version":1,"pad":1,"corners":[{"pitch":0.5}]}""")
        assertFailsWith<JsonException> { SurfaceStore.load(temp) }
        File(temp, SurfaceStore.FILE_NAME).writeText("""{"version":1,"pad":1,"corners":[{"pitch":2,"cutoff":0,"resonance":0,"drive":0}]}""")
        assertFailsWith<IllegalArgumentException> { SurfaceStore.load(temp) }
    }

    @Test
    fun `a corner captured from XY and XYZ follows the engine's map`() {
        val r = Reading(0.2f, 0.9f, 0.6f, 0.25f, 0.25f, 0.25f, 0.25f, touching = true)
        val xy = Corner.from(Mode.XY, r, tilt = 0.8f, corners = Corner.DEFAULTS)
        near(0.2f, xy.pitch); near(0.9f, xy.cutoff); near(0.4f, xy.resonance); near(0f, xy.drive)
        val xyz = Corner.from(Mode.XYZ, r, tilt = 0.8f, corners = Corner.DEFAULTS)
        near(0.8f, xyz.resonance); near(0.6f, xyz.drive)
    }

    @Test
    fun `a corner captured in MORPH is the blend, and a corner is itself`() {
        val atA = Reading(0f, 1f, 0f, 1f, 0f, 0f, 0f, touching = true)
        assertEquals(Corner.CLEAN, Corner.from(Mode.MORPH, atA, 0.5f, Corner.DEFAULTS))
        val centre = Reading(0.5f, 0.5f, 0f, 0.25f, 0.25f, 0.25f, 0.25f, touching = true)
        val mid = Corner.from(Mode.MORPH, centre, 0.5f, Corner.DEFAULTS)
        near(Corner.DEFAULTS.map { it.pitch }.average().toFloat(), mid.pitch)
        near(Corner.DEFAULTS.map { it.drive }.average().toFloat(), mid.drive)
        assertFailsWith<IllegalArgumentException> { Corner.from(Mode.MORPH, centre, 0.5f, Corner.DEFAULTS.take(2)) }
    }
}
