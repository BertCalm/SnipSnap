package com.snipsnap.mpc3

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Mpc3ProjectWriterTest {

    private val writer = Mpc3ProjectWriter()

    private fun clip() = Mpc3Clip(
        "SnipSnap Groove", 2,
        listOf(Mpc3Note(36, 0L, 0.9f), Mpc3Note(38, 4 * Mpc3Clip.PULSES_PER_16TH, 0.85f)),
    )

    private fun tracks() = listOf(
        Mpc3ProjectTrack.Drum(
            DrumProgram(
                "Session Kit",
                listOf(Pad("SS_Kick_01", 22_050L, color = 0xE8542E), Pad("SS_Snare_01", 18_000L)),
            ),
            clip = clip(),
        ),
        Mpc3ProjectTrack.Keys(
            KeygroupProgram(
                "Session Keys",
                listOf(Keygroup(0, 127, rootNote = 60, layers = listOf(VelocityLayer("Keys_C4", 44_100L, 0, 127)))),
            ),
        ),
    )

    private fun data(): Map<String, JsonValue> {
        val root = Json.parse(writer.payloadText("SnipSnap Session", tracks(), tempoBpm = 92f)) as JsonValue.Obj
        return (root.entries["data"] as JsonValue.Obj).entries
    }

    @Test
    fun `the reader accepts the project and sees every track`() {
        val bytes = writer.write("SnipSnap Session", tracks())
        assertTrue(Acvs.isGzip(bytes))
        val project = Mpc3Project.read(bytes)
        assertTrue(project.isProject)
        assertEquals(
            listOf("Session Kit", "Session Keys", "Submix 1", "Out 1/2", "Out 3/4"),
            project.trackNames,
            "content tracks plus the mixer infrastructure every real project carries",
        )
        val types = project.programs().mapNotNull { (it["type"] as? JsonValue.Num)?.value?.toInt() }
        assertEquals(listOf(0, 1, 8, 9, 9), types)
        assertTrue("1 drum, 1 keygroup" in project.describe(), project.describe())
    }

    @Test
    fun `output is deterministic and the skeleton carries the real schema version`() {
        assertTrue(writer.write("P", tracks()).contentEquals(writer.write("P", tracks())))
        assertEquals(28, (data()["version"] as JsonValue.Num).value.toInt(), "data.version 28 is real at project level")
    }

    @Test
    fun `the project pools samples across tracks and sets the tempo`() {
        val d = data()
        val pool = (d["samples"] as JsonValue.Arr).items.map { ((it as JsonValue.Obj).entries)["name"]!!.str() }
        assertEquals(listOf("SS_Kick_01", "SS_Snare_01", "Keys_C4"), pool)
        assertEquals(92.0, (d["masterTempo"] as JsonValue.Num).value)
    }

    @Test
    fun `the drum clip becomes the sequence with every track mapped`() {
        val d = data()
        val seqs = (d["sequences"] as JsonValue.Arr).items.map { (it as JsonValue.Obj).entries }
        assertEquals(1, seqs.size)
        val v = (seqs[0]["value"] as JsonValue.Obj).entries
        assertEquals("SnipSnap Groove", v["name"]!!.str())
        assertEquals(92.0, (v["bpm"] as JsonValue.Num).value)
        assertEquals(2, v["lengthBars"]!!.int())
        assertEquals(2 * Mpc3Clip.PULSES_PER_BAR, (v["lengthPulses"] as JsonValue.Num).value.toLong())
        val row = ((v["trackClipMaps"] as JsonValue.Arr).items[0] as JsonValue.Arr).items
            .map { (it as JsonValue.Obj).entries }
        assertEquals(5, row.size, "every track mapped, like the real project")
        val kitEntry = row.first { it["key"]!!.str() == "Session Kit" }
        val events = ((((kitEntry["value"] as JsonValue.Obj).entries["eventList"]) as JsonValue.Obj)
            .entries["events"] as JsonValue.Arr).items
        assertEquals(2, events.size, "the groove's notes ride on the kit track's clip")
        val subEntry = row.first { it["key"]!!.str() == "Submix 1" }
        val subEvents = ((((subEntry["value"] as JsonValue.Obj).entries["eventList"]) as JsonValue.Obj)
            .entries["events"] as JsonValue.Arr).items
        assertTrue(subEvents.isEmpty(), "infrastructure tracks get empty clips")
    }

    @Test
    fun `project tracks omit solo like the harvested project`() {
        val d = data()
        val t0 = ((d["tracks"] as JsonValue.Arr).items[0] as JsonValue.Obj).entries
        assertFalse("solo" in t0)
        // ...while standalone track files keep it.
        assertTrue("\"solo\": false" in Mpc3TrackWriter().payloadText(tracks()[0].let { (it as Mpc3ProjectTrack.Drum).program }))
    }

    @Test
    fun `emits no key path absent from the real project`() {
        fun collect(v: JsonValue, prefix: String, out: MutableSet<String>) {
            when (v) {
                is JsonValue.Obj -> v.entries.forEach { (k, child) ->
                    val norm = if (Regex("value\\d+").matches(k)) "value*" else k
                    val p = "$prefix.$norm"
                    out.add(p)
                    collect(child, p, out)
                }
                is JsonValue.Arr -> v.items.forEach { collect(it, "$prefix[*]", out) }
                else -> {}
            }
        }

        fun corpusPaths(file: File): Set<String> {
            assertTrue(file.isFile, "reference file missing: ${file.absolutePath}")
            val text = GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            return mutableSetOf<String>().also { collect(Json.parse(text.split("\n", limit = 6)[5]), "", it) }
        }

        val projectPaths = corpusPaths(File("../reference/golden/mpc3-project/DD1 Chamber 92bpm.xpj"))
        // A project track element is a hoisted track file (proven identical
        // shape), so paths under tracks[*] are also legitimised by the track
        // corpus — the single old-build project lacks fields the newer track
        // files carry.
        val trackPaths = File("../reference/golden/mpc3-track").listFiles { f -> f.extension.startsWith("xt") }!!
            .flatMap { corpusPaths(it) }.toSet()

        val ours = mutableSetOf<String>()
        collect(Json.parse(writer.payloadText("SnipSnap Session", tracks())), "", ours)

        val unexplained = ours.filter { path ->
            if (path in projectPaths) return@filter false
            if (path.startsWith(".data.tracks[*]")) {
                path.replaceFirst(".data.tracks[*]", ".data") !in trackPaths
            } else {
                true
            }
        }
        assertEquals(emptyList(), unexplained, "key paths that appear in no real project or track file")
    }
}
