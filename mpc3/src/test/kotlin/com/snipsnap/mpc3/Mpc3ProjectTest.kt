package com.snipsnap.mpc3

import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mpc3ProjectTest {

    /** A fixture shaped like the documented `.xpj` payload (docs/MPC3_FORMAT.md). */
    private val fixture = """
        {"data": {
            "version": 28,
            "tracks": [
                {"name": "Drums", "type": 0,
                 "program": {"version": 4, "name": "HipHop Kit", "type": 0,
                     "drum": {"version": 2, "drumVersion": 12, "poliphony": 6,
                         "padNoteMap": {"noteForPad": {"value0": 36, "value1": 37}},
                         "instruments": []}}},
                {"name": "Bass", "type": 3,
                 "program": {"version": 4, "name": "OPx", "type": 3}},
                {"name": "Return", "type": 7}
            ]
        }}
    """.trimIndent()

    private fun project() = Mpc3Project.read(
        Acvs.write(AcvsHeader("3.7.0.56", "SerialisableProjectData", "json", "Linux"), fixture),
    )

    @Test
    fun `reads the shape the knowledge base documents`() {
        val p = project()
        assertTrue(p.isProject)
        assertEquals("3.7.0.56", p.firmware)
        assertEquals(28, p.schemaVersion)
        assertEquals(listOf("Drums", "Bass", "Return"), p.trackNames)
    }

    @Test
    fun `finds exactly the drum programs`() {
        val drums = project().drumPrograms()
        assertEquals(1, drums.size)
        // The documented "poliphony" misspelling is data, not a typo to fix.
        assertEquals(6, (drums.single()["poliphony"] as JsonValue.Num).value.toInt())
        val map = ((drums.single()["padNoteMap"] as JsonValue.Obj)
            .entries["noteForPad"] as JsonValue.Obj).entries
        assertEquals(36, (map["value0"] as JsonValue.Num).value.toInt())
    }

    @Test
    fun `absence degrades to null and empty, never a throw`() {
        val bare = Mpc3Project.read(
            Acvs.write(AcvsHeader("3.6", "SomethingElse", "json", "Linux"), "{}"),
        )
        assertTrue(!bare.isProject)
        assertEquals(null, bare.schemaVersion)
        assertTrue(bare.tracks.isEmpty())
        assertTrue(bare.drumPrograms().isEmpty())
    }

    @Test
    fun `describe gives the one-screen summary`() {
        val d = project().describe()
        assertTrue("SerialisableProjectData" in d)
        assertTrue("3.7.0.56" in d)
        assertTrue("schema v28" in d)
        assertTrue("3 tracks" in d)
        assertTrue("(1 drum)" in d)
    }
}
