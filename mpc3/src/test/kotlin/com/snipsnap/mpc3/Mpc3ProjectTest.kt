package com.snipsnap.mpc3

import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mpc3ProjectTest {

    /**
     * A project payload shaped like the real ones in
     * `reference/golden/mpc3-project/`: `data.version` 5, `padNoteMap` on
     * `program` rather than inside `drum`, `drum.version` 8.
     *
     * The previous fixture encoded the community write-up's schema — version
     * 28, `padNoteMap` nested in `drum` — both of which the harvest disproved.
     * It passed because the assertions matched the fixture; neither matched a
     * real file.
     */
    private val fixture = """
        {"data": {
            "version": 5,
            "tracks": [
                {"name": "Drums",
                 "program": {"version": 4, "name": "HipHop Kit", "type": 0,
                     "padNoteMap": {"noteForPad": {"value0": 36, "value1": 37}},
                     "drum": {"version": 8, "poliphony": 0, "instruments": []}}},
                {"name": "Bass",
                 "program": {"version": 4, "name": "OPx", "type": 1,
                     "keygroup": {"numKeygroups": 4},
                     "drum": {"version": 8, "instruments": []}}},
                {"name": "Return"}
            ]
        }}
    """.trimIndent()

    private fun project() = Mpc3Project.read(
        Acvs.write(AcvsHeader("3.7.0.56", "SerialisableProjectData", "json", "Linux"), fixture),
    )

    private fun golden(name: String) = File("../reference/golden/$name")
        .also { assertTrue(it.exists(), "reference corpus missing: ${it.absolutePath}") }

    @Test
    fun `reads a project's tracks`() {
        val p = project()
        assertTrue(p.isProject)
        assertTrue(!p.isTrack)
        assertEquals("3.7.0.56", p.firmware)
        assertEquals(5, p.schemaVersion)
        assertEquals(listOf("Drums", "Bass", "Return"), p.trackNames)
        // Two tracks carry a program; the return track doesn't.
        assertEquals(2, p.programs().size)
        assertEquals(2, p.drumPrograms().size)
        assertEquals(0, (p.drumPrograms().first()["poliphony"] as JsonValue.Num).value.toInt())
    }

    @Test
    fun `padNoteMap sits on program, not inside drum`() {
        val program = project().programs().first()
        assertTrue("padNoteMap" in program, "padNoteMap is a sibling of drum")
        assertTrue("padNoteMap" !in (program["drum"] as JsonValue.Obj).entries)
    }

    @Test
    fun `a real project file reads`() {
        val p = Mpc3Project.read(golden("mpc3-project/DD1 Chamber 92bpm.xpj"))
        assertTrue(p.isProject)
        assertEquals(4, p.tracks.size)
        assertTrue(p.trackNames.isNotEmpty())
        val drum = p.drumPrograms().first()
        assertEquals(128, (drum["instruments"] as JsonValue.Arr).items.size, "all 128 slots are written")
    }

    /**
     * The gap this class had: `.xtd`/`.xty` carry no `tracks` key, so every
     * accessor returned empty for a file with 128 populated instrument slots.
     * Silent, not a crash, which is the worse failure.
     */
    @Test
    fun `a real standalone track file reads as one track`() {
        val p = Mpc3Project.read(golden("mpc3-track/Inst-Bass-NI Bass Artisan.xty"))
        assertTrue(p.isTrack)
        assertTrue(!p.isProject)
        assertEquals(1, p.tracks.size, "a track file is one track hoisted to the top of data")
        assertEquals(listOf("Inst-Bass-NI Bass Artisan"), p.trackNames)

        val program = p.programs().single()
        assertEquals(1, (program["type"] as JsonValue.Num).value.toInt(), "type 1 = keygroup")
        // Keygroup zones live in program.drum.instruments, not program.keygroup.
        val instruments = (p.drumPrograms().single()["instruments"] as JsonValue.Arr).items
        assertTrue(instruments.isNotEmpty(), "zones are in program.drum.instruments")
        assertEquals(
            8,
            ((instruments.first() as JsonValue.Obj).entries["layersv"] as JsonValue.Arr).items.size,
            "eight layer slots per zone",
        )
        assertTrue(
            "instruments" !in (program["keygroup"] as JsonValue.Obj).entries,
            "program.keygroup holds global state only",
        )
    }

    @Test
    fun `absence degrades to null and empty, never a throw`() {
        val bare = Mpc3Project.read(
            Acvs.write(AcvsHeader("3.6", "SomethingElse", "json", "Linux"), "{}"),
        )
        assertTrue(!bare.isProject && !bare.isTrack)
        assertEquals(null, bare.schemaVersion)
        assertTrue(bare.tracks.isEmpty())
        assertTrue(bare.programs().isEmpty())
        assertTrue(bare.drumPrograms().isEmpty())
    }

    @Test
    fun `describe gives the one-screen summary for both containers`() {
        val d = project().describe()
        assertTrue("SerialisableProjectData" in d)
        assertTrue("3.7.0.56" in d)
        assertTrue("schema v5" in d)
        assertTrue("3 tracks" in d)
        assertTrue("(1 drum, 1 keygroup)" in d)

        val t = Mpc3Project.read(golden("mpc3-track/Inst-Bass-NI Bass Artisan.xty")).describe()
        assertTrue("SerialisableTrackData" in t)
        assertTrue("1 track \"Inst-Bass-NI Bass Artisan\"" in t, t)
    }
}
