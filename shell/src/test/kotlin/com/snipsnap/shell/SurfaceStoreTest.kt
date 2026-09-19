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
    fun `a file saved before crush, echo or spring existed still loads, transparent and dry`() {
        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        val loaded = SurfaceStore.load(temp)
        for (c in loaded.corners) {
            near(0f, c.crush)
            near(0f, c.echo)
            near(0f, c.spring)
        }
    }

    @Test
    fun `a present but malformed crush, echo or spring is refused, not silently read as off`() {
        // Unlike an absent key (the test above), a key that is *there*
        // with the wrong type is exactly what pitch/cutoff/resonance/drive
        // already refuse - crush/echo/spring follow the same rule rather
        // than quietly defaulting a torn value to 0.
        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0,"crush":0,"echo":"bad"},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        assertFailsWith<JsonException> { SurfaceStore.load(temp) }

        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0,"crush":null,"echo":0},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        assertFailsWith<JsonException> { SurfaceStore.load(temp) }

        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0,"crush":0,"echo":0,"spring":"bad"},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        assertFailsWith<JsonException> { SurfaceStore.load(temp) }
    }

    @Test
    fun `crush, echo and spring round-trip alongside the older macros`() {
        val withFx = Corner(0.5f, 0.5f, 0.5f, 0.5f, crush = 0.4f, echo = 0.7f, spring = 0.6f)
        SurfaceStore.save(temp, Settings(padSlot = 1, corners = listOf(withFx, withFx, withFx, withFx)))
        val loaded = SurfaceStore.load(temp).corners.first()
        near(0.4f, loaded.crush)
        near(0.7f, loaded.echo)
        near(0.6f, loaded.spring)
    }

    @Test
    fun `grain knobs round-trip, and a file saved before GRAIN existed loads with the defaults`() {
        val knobs = SurfaceStore.Grain(size = 0.2f, density = 0.9f, spray = 0.35f)
        SurfaceStore.save(temp, Settings(padSlot = 1, grain = knobs))
        val loaded = SurfaceStore.load(temp).grain
        near(0.2f, loaded.size); near(0.9f, loaded.density); near(0.35f, loaded.spray)

        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        assertEquals(SurfaceStore.Grain.DEFAULT, SurfaceStore.load(temp).grain)
        assertEquals(SurfaceStore.Grain.DEFAULT, Settings.DEFAULT.grain)
    }

    @Test
    fun `a present but malformed grain knob is refused, not silently read as its default`() {
        // The crush/echo rule again: absent means "before this existed",
        // present-and-wrong means a torn file, and those are different.
        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"grain":{"size":0.5,"density":"lots","spray":0},"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        assertFailsWith<JsonException> { SurfaceStore.load(temp) }
        File(temp, SurfaceStore.FILE_NAME).writeText(
            """{"version":1,"pad":3,"grain":{"size":0.5,"density":0.5},"corners":[
                {"pitch":0.5,"cutoff":1,"resonance":0,"drive":0},
                {"pitch":0.5,"cutoff":0.25,"resonance":0.3,"drive":0.1},
                {"pitch":0.25,"cutoff":0.6,"resonance":0.5,"drive":0.4},
                {"pitch":0.75,"cutoff":0.85,"resonance":0.2,"drive":0.9}
            ]}""",
        )
        assertFailsWith<JsonException> { SurfaceStore.load(temp) }
        assertFailsWith<IllegalArgumentException> { SurfaceStore.Grain(size = 1.5f) }
        assertFailsWith<IllegalArgumentException> { SurfaceStore.Grain(density = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { SurfaceStore.Grain(spray = -0.1f) }
    }

    @Test
    fun `a corner captured in GRAIN is the chain the cloud ran through, not the finger`() {
        // GRAIN's finger is position and pitch, which a corner cannot hold;
        // what SET keeps is XY's rest with the roll's resonance - the
        // macros the engine actually applies in mode 4 - so morphing toward
        // it later sounds like the cloud's *chain* did, not like some
        // arbitrary pitch/cutoff the x/y happened to spell.
        val r = Reading(0.2f, 0.9f, 0.6f, 0.25f, 0.25f, 0.25f, 0.25f, touching = true)
        val captured = Corner.from(Mode.GRAIN, r, tilt = 0.8f, corners = Corner.DEFAULTS)
        assertEquals(Corner(0.5f, 1f, 0.4f, 0f), captured)
        assertEquals(Corner.CLEAN, Corner.from(Mode.GRAIN, r, tilt = 0.5f, corners = Corner.DEFAULTS))
    }

    @Test
    fun `refusals are in words`() {
        assertFailsWith<IllegalArgumentException> { Corner(1.2f, 0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { Corner(Float.NaN, 0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { Corner(0f, 0f, 0f, 0f, crush = 1.2f) }
        assertFailsWith<IllegalArgumentException> { Corner(0f, 0f, 0f, 0f, echo = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Corner(0f, 0f, 0f, 0f, spring = 1.2f) }
        assertFailsWith<IllegalArgumentException> { Corner(0f, 0f, 0f, 0f, spring = Float.NaN) }
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
        // stepper can rely on, not an implementation detail. CRUSH, GLITCH
        // (stage 6) and SPRING (stage 7) are appended after them, not
        // interleaved, so this original ordering promise still holds.
        assertEquals(
            listOf(
                "LBP +", "ECHO +", "ECHO -", "LBP -", "CRUSH +", "CRUSH -",
                "GLITCH +", "GLITCH -", "SPRING +", "SPRING -",
            ),
            Corner.LIBRARY.map { it.name },
        )
        // Ten distinct sounds, not ten names on fewer.
        assertEquals(10, Corner.LIBRARY.map { it.corner }.distinct().size)
        // The ECHO pair is the one telling itself apart from LBP by
        // actually using the delay the engine now has - LBP stays a pure
        // filter pair, crush/echo both off.
        fun byName(name: String) = Corner.LIBRARY.first { it.name == name }.corner
        assertTrue(byName("ECHO +").echo > 0f)
        assertTrue(byName("ECHO -").echo > 0f)
        near(0f, byName("LBP +").echo); near(0f, byName("LBP +").crush)
        near(0f, byName("LBP -").echo); near(0f, byName("LBP -").crush)
        // CRUSH is a pure bitcrush pair - echo stays off, same shape as LBP
        // being a pure filter pair.
        assertTrue(byName("CRUSH +").crush > 0f)
        assertTrue(byName("CRUSH -").crush > byName("CRUSH +").crush)
        near(0f, byName("CRUSH +").echo); near(0f, byName("CRUSH -").echo)
        // GLITCH is the one pair reaching for both macros together - a
        // territory neither LBP nor ECHO nor CRUSH alone can reach.
        assertTrue(byName("GLITCH +").crush > 0f); assertTrue(byName("GLITCH +").echo > 0f)
        assertTrue(byName("GLITCH -").crush > byName("GLITCH +").crush)
        assertTrue(byName("GLITCH -").echo > byName("GLITCH +").echo)
        // SPRING is a pure reverb pair - crush and echo both stay off, the
        // same shape CRUSH has for the bitcrusher.
        assertTrue(byName("SPRING +").spring > 0f)
        assertTrue(byName("SPRING -").spring > byName("SPRING +").spring)
        for (name in listOf("SPRING +", "SPRING -")) {
            near(0f, byName(name).crush); near(0f, byName(name).echo)
        }
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
    fun `crush and echo blend in MORPH exactly like pitch and drive already do`() {
        val corners = listOf(
            Corner(0.5f, 0.5f, 0f, 0f, crush = 1f, echo = 0f),
            Corner(0.5f, 0.5f, 0f, 0f, crush = 0f, echo = 1f),
            Corner.LOW,
            Corner.HOT,
        )
        val atA = Reading(0f, 1f, 0f, 1f, 0f, 0f, 0f, touching = true)  // 100% corner A
        val captured = Corner.from(Mode.MORPH, atA, 0.5f, corners)
        near(1f, captured.crush); near(0f, captured.echo)
        val centre = Reading(0.5f, 0.5f, 0f, 0.25f, 0.25f, 0.25f, 0.25f, touching = true)
        val mid = Corner.from(Mode.MORPH, centre, 0.5f, corners)
        near(0.25f, mid.crush); near(0.25f, mid.echo)  // an even quarter each, same as pitch/drive above
    }

    @Test
    fun `spring blends in MORPH exactly like crush and echo already do`() {
        val corners = listOf(
            Corner(0.5f, 0.5f, 0f, 0f, spring = 1f),
            Corner.DARK,
            Corner.LOW,
            Corner.HOT,
        )
        val atA = Reading(0f, 1f, 0f, 1f, 0f, 0f, 0f, touching = true)  // 100% corner A
        near(1f, Corner.from(Mode.MORPH, atA, 0.5f, corners).spring)
        val centre = Reading(0.5f, 0.5f, 0f, 0.25f, 0.25f, 0.25f, 0.25f, touching = true)
        near(0.25f, Corner.from(Mode.MORPH, centre, 0.5f, corners).spring)  // an even quarter, same as crush/echo above
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
