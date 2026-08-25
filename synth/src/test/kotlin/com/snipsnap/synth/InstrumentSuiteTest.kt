package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstrumentSuiteTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("suite").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun zonesTile(program: KeygroupProgram) {
        val zones = program.keygroups
        for (i in 1 until zones.size) {
            assertEquals(
                zones[i - 1].highNote + 1, zones[i].lowNote,
                "${program.name}: zones must tile without gaps or overlaps",
            )
        }
        zones.forEach {
            assertTrue(it.rootNote in it.lowNote..it.highNote, "${program.name}: root inside its zone")
        }
    }

    /** Detected fundamental must match the zone's MIDI root (or its octave). */
    private fun assertPitched(snip: com.snipsnap.audio.Snip, midi: Int, name: String) {
        val expected = Keys.midiHz(midi)
        val got = Pitch.detect(snip)
        assertNotNull(got, "$name midi $midi: no pitch detected")
        val ratio = got.hz / expected
        val octaveFolded = listOf(ratio, ratio * 2, ratio / 2).minByOrNull { abs(it - 1f) }!!
        assertTrue(
            abs(octaveFolded - 1f) < 0.02f,
            "$name midi $midi: expected ${expected}Hz, detected ${got.hz}Hz",
        )
    }

    @Test
    fun `every instrument tiles its zones and lands its pitches`() {
        val ep = InstrumentSuite.renderEp(File(temp, "ep"))
        val organ = InstrumentSuite.renderOrgan(File(temp, "organ"))
        val harp = InstrumentSuite.renderHarp(File(temp, "harp"))
        val box = InstrumentSuite.renderMusicBox(File(temp, "box"))

        for (p in listOf(ep, organ, harp, box)) {
            zonesTile(p)
            assertEquals(9, p.keygroups.size)
        }
        // Spot-check true pitch at both ends of each range on fresh renders.
        assertPitched(Keys.ep(41, 0.8f), 41, "EP")
        assertPitched(Keys.ep(65, 0.8f), 65, "EP")
        assertPitched(Keys.organ(45).snip, 45, "Organ")
        assertPitched(Keys.organ(69).snip, 69, "Organ")
        assertPitched(Keys.harp(52), 52, "Harp")
    }

    @Test
    fun `the EP's soft layer is darker not just quieter`() {
        val soft = Keys.ep(53, bright = 0.35f)
        val hard = Keys.ep(53, bright = 0.8f)
        val softCentroid = com.snipsnap.audio.FeatureExtractor.extract(soft).centroidHz
        val hardCentroid = com.snipsnap.audio.FeatureExtractor.extract(hard).centroidHz
        assertTrue(
            softCentroid < hardCentroid * 0.85f,
            "soft strike must excite fewer partials: soft=$softCentroid hard=$hardCentroid",
        )
    }

    @Test
    fun `the organ loop seam is a whole number of periods`() {
        val note = Keys.organ(57)
        val s = note.snip.samples
        val loopStart = note.loopStartFrame.toInt()
        val loopLen = s.size - loopStart
        assertTrue(loopStart > 0 && loopLen > 0)
        // A loop of exactly N periods means the signal one loop-length apart
        // is the same signal: what plays after the wrap (s[loopStart..]) must
        // match what played before it (s[loopStart-256..loopStart]). Compare
        // the two, RMS, against the signal's own level.
        var diff = 0.0
        var level = 0.0
        for (i in loopStart - 256 until loopStart) {
            diff += (s[i + loopLen] - s[i].toDouble()).let { it * it }
            level += s[i].toDouble() * s[i]
        }
        assertTrue(
            diff < level * 0.001,
            "seam mismatch: diff rms²=$diff vs signal rms²=$level over the wrap window",
        )
    }

    @Test
    fun `loops survive into both generations' programs`() {
        val organ = InstrumentSuite.renderOrgan(File(temp, "organ2"))
        val loopStart = organ.keygroups[0].layers[0].loopStartFrame
        assertTrue(loopStart > 0, "organ zones must carry a loop")

        val xpm = KeygroupWriter().write(organ)
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm, "MPC 2 idiom: SliceLoop=1")
        assertTrue("<SliceLoopStart>$loopStart</SliceLoopStart>" in xpm)

        val xty = Mpc3TrackWriter().keygroupPayloadText(organ)
        assertTrue("\"LoopMode\": 1" in xty, "MPC 3 idiom: sliceInfo.LoopMode=1")
        assertTrue("\"LoopStart\": $loopStart" in xty)

        // And the decaying instruments carry none.
        val harp = InstrumentSuite.renderHarp(File(temp, "harp2"))
        assertTrue(KeygroupWriter().write(harp).contains("<SliceLoop>0</SliceLoop>"))
        assertTrue("\"LoopMode\": 1" !in Mpc3TrackWriter().keygroupPayloadText(harp))
    }

    @Test
    fun `the suite packages dual-generation and both writers accept every program`() {
        val out = File(temp, "Instruments")
        val name = "SnipSnap Music Box"
        val dataDir = File(out, Mpc3TrackWriter.trackDataDirName(name))
        val program = InstrumentSuite.renderMusicBox(dataDir)
        val xty = Mpc3TrackWriter().writeKeygroupTo(out, program)
        val xpm = KeygroupWriter().writeTo(dataDir, program)

        assertEquals("$name.xty", xty.name)
        assertTrue(xpm.parentFile.name.endsWith("_[TrackData]"), "the MPC 2 twin lives inside the data folder")
        val wavs = dataDir.listFiles { f -> f.extension == "wav" }!!
        assertEquals(9, wavs.size, "one WAV per zone for the single-layer box")
        val read = com.snipsnap.mpc3.Mpc3Project.read(xty)
        assertTrue(read.isTrack)
        assertTrue("1 keygroup" in read.describe())
    }

    @Test
    fun `the sidecar records what actually rendered and round-trips as JSON`() {
        val dir = File(temp, "sidecar")
        val programs = InstrumentSuite.renderAll(dir)
        val file = InstrumentSidecar.write(dir, programs)

        val root = com.snipsnap.json.Json.parse(file.readText()).let {
            it as com.snipsnap.json.JsonValue.Obj
        }
        assertEquals(1.0, (root.entries["version"] as com.snipsnap.json.JsonValue.Num).value)
        val instruments = (root.entries["instruments"] as com.snipsnap.json.JsonValue.Arr).items
        assertEquals(4, instruments.size)

        fun obj(v: com.snipsnap.json.JsonValue) = (v as com.snipsnap.json.JsonValue.Obj).entries
        fun num(v: com.snipsnap.json.JsonValue?) = (v as com.snipsnap.json.JsonValue.Num).value
        fun str(v: com.snipsnap.json.JsonValue?) = (v as com.snipsnap.json.JsonValue.Str).value

        instruments.forEachIndexed { i, inst ->
            val entries = obj(inst)
            assertEquals(programs[i].name, str(entries["name"]))
            val zones = (entries["zones"] as com.snipsnap.json.JsonValue.Arr).items
            assertEquals(programs[i].keygroups.size, zones.size)
            zones.forEachIndexed { z, zone ->
                val kg = programs[i].keygroups[z]
                assertEquals(kg.rootNote.toDouble(), num(obj(zone)["rootMidi"]))
                val samples = (obj(zone)["samples"] as com.snipsnap.json.JsonValue.Arr).items
                assertEquals(kg.layers.size, samples.size)
            }
        }

        // The organ's whole reason for a sidecar: its loop points survive.
        val organ = instruments.map(::obj).first { str(it["name"]) == "SnipSnap Organ" }
        val organZones = (organ["zones"] as com.snipsnap.json.JsonValue.Arr).items
        organZones.forEach { zone ->
            val samples = (obj(zone)["samples"] as com.snipsnap.json.JsonValue.Arr).items
            assertTrue(num(obj(samples.single())["loopStartFrame"]) > 0, "organ zones carry loop points")
        }

        // The EP's darkness promise: two layers, soft window below the main.
        val ep = instruments.map(::obj).first { str(it["name"]) == "SnipSnap EP" }
        val epZone = obj((ep["zones"] as com.snipsnap.json.JsonValue.Arr).items.first())
        val epSamples = (epZone["samples"] as com.snipsnap.json.JsonValue.Arr).items.map(::obj)
        assertEquals(2, epSamples.size)
        assertEquals(1.0, num(epSamples[0]["velStart"]))
        assertEquals(63.0, num(epSamples[0]["velEnd"]))
        assertEquals(127.0, num(epSamples[1]["velEnd"]))
    }
}
