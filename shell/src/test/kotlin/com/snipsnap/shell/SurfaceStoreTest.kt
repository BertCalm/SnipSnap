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
        val s = Settings(
            padSlot = 7,
            corners = listOf(Corner(0f, 1f, 0.5f, 0.25f), Corner.DARK, Corner.LOW, Corner(1f, 0f, 0f, 1f)),
            secondPadSlot = 12,
            thirdPadSlot = 34,
            fourthPadSlot = 56,
        )
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
    fun `a file saved before secondPad or thirdPad existed still loads, with no extra samples`() {
        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        val loaded = SurfaceStore.load(temp)
        assertEquals(3, loaded.padSlot)
        assertEquals(null, loaded.secondPadSlot)
        assertEquals(null, loaded.thirdPadSlot)
        assertEquals(null, loaded.fourthPadSlot)
    }

    @Test
    fun `a file saved before thirdPad existed still loads, with the second sample intact`() {
        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"secondPad":9,"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        val loaded = SurfaceStore.load(temp)
        assertEquals(9, loaded.secondPadSlot)
        assertEquals(null, loaded.thirdPadSlot)
        assertEquals(null, loaded.fourthPadSlot)
    }

    @Test
    fun `a file saved before fourthPad existed still loads, with the second and third samples intact`() {
        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"secondPad":9,"thirdPad":21,"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        val loaded = SurfaceStore.load(temp)
        assertEquals(9, loaded.secondPadSlot)
        assertEquals(21, loaded.thirdPadSlot)
        assertEquals(null, loaded.fourthPadSlot)
    }

    @Test
    fun `refusals are in words`() {
        assertFailsWith<IllegalArgumentException> { Corner(1.2f, 0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { Corner(Float.NaN, 0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { Settings(padSlot = 0) }
        assertFailsWith<IllegalArgumentException> { Settings(padSlot = 1, secondPadSlot = 0) }
        assertFailsWith<IllegalArgumentException> { Settings(padSlot = 1, thirdPadSlot = 0) }
        assertFailsWith<IllegalArgumentException> { Settings(padSlot = 1, fourthPadSlot = 0) }
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
    fun `the preset library names LBP+ECHO+ECHO-LBP- distinctly`() {
        // design/surface-vector's boards sketch these four names in this
        // order (LBP +, ECHO +, ECHO -, LBP -) - the order is a promise a
        // stepper can rely on, not an implementation detail.
        assertEquals(listOf("LBP +", "ECHO +", "ECHO -", "LBP -"), Corner.LIBRARY.map { it.name })
        // Four distinct sounds, not four names on the same one.
        assertEquals(4, Corner.LIBRARY.map { it.corner }.distinct().size)
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

    @Test
    fun `VECTOR captures a corner exactly like MORPH does - the same blend, not a different one`() {
        val atA = Reading(0f, 1f, 0f, 1f, 0f, 0f, 0f, touching = true)
        assertEquals(
            Corner.from(Mode.MORPH, atA, 0.8f, Corner.DEFAULTS),
            Corner.from(Mode.VECTOR, atA, 0.8f, Corner.DEFAULTS),
        )
        val centre = Reading(0.5f, 0.5f, 0f, 0.25f, 0.25f, 0.25f, 0.25f, touching = true)
        assertEquals(
            Corner.from(Mode.MORPH, centre, 0.3f, Corner.DEFAULTS),
            Corner.from(Mode.VECTOR, centre, 0.3f, Corner.DEFAULTS),
        )
        assertFailsWith<IllegalArgumentException> { Corner.from(Mode.VECTOR, centre, 0.5f, Corner.DEFAULTS.take(2)) }
    }

    @Test
    fun `tilt nudges MORPH resonance the same half-weighted amount XY gives it, and clamps at the ends`() {
        // CLEAN's resonance is 0 (Corner.CLEAN = Corner(0.5f, 1.0f, 0.0f, 0.0f)),
        // so the nudge alone is what shows up here - the same arithmetic
        // SurfaceEngine.cpp's morphed() applies at audio rate.
        val atA = Reading(0f, 1f, 0f, 1f, 0f, 0f, 0f, touching = true)
        near(0f, Corner.from(Mode.MORPH, atA, 0.5f, Corner.DEFAULTS).resonance)  // flat phone: no nudge
        near(0.25f, Corner.from(Mode.MORPH, atA, 1f, Corner.DEFAULTS).resonance)  // full tilt: +0.5 * 0.5
        near(0f, Corner.from(Mode.MORPH, atA, 0f, Corner.DEFAULTS).resonance)  // the other way clamps at 0, not negative

        // HOT already carries resonance 0.2 (Corner.HOT = Corner(0.75f, 0.85f, 0.2f, 0.9f));
        // a full-tilt nudge of +0.25 lands on top of that, not in place of it.
        // D is bottom-right (x=1, y=0 - see morphWeights), which the blend
        // now derives the weights from itself; a/b/c/d are set to match
        // that position rather than a mismatched one the old
        // implementation didn't check.
        val atD = Reading(1f, 0f, 0f, 0f, 0f, 0f, 1f, touching = true)
        near(0.45f, Corner.from(Mode.MORPH, atD, 1f, Corner.DEFAULTS).resonance)
        near(1f, Corner.from(Mode.MORPH, atD, 1f, listOf(Corner(0.5f, 0.5f, 0.9f, 0f), Corner.DARK, Corner.LOW, Corner(0.5f, 0.5f, 0.9f, 0f))).resonance)
    }
}
