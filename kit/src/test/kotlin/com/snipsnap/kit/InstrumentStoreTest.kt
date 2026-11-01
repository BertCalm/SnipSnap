package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.VoiceMode
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

    @Test
    fun `v2 carries every MPC velocity window sample length and sustain loop into the phone`() {
        val root = File(temp, "Layered").apply { mkdirs() }
        val samples = linkedMapOf("soft" to tone(220.0, .1f), "mid" to tone(220.0, .2f), "hard" to tone(220.0, .3f))
        val layers = samples.entries.mapIndexed { index, (name, snip) ->
            VelocityLayer(name, snip.frameCount.toLong(), 1 + index * 42, if (index == 2) 127 else (index + 1) * 42,
                loopStartFrame = (index + 1) * 100L)
        }
        val program = KeygroupProgram("Velocity Keys", listOf(Keygroup(48, 72, 60, layers)), volumeRelease = .6f)
        OneNote.writePackage(program.name, program, samples, root, overwrite = false)
        val sidecar = InstrumentStore.sidecar(root, program.name)
        assertEquals(2, Json.parse(sidecar.readText()).obj().getValue("version").int())
        val loaded = InstrumentStore.read(sidecar)
        val zone = loaded.zones.single()
        assertEquals(listOf(1..42, 43..84, 85..127), zone.layers.map { it.velStart..it.velEnd })
        assertEquals(layers.map { it.frameCount }, zone.layers.map { it.frameCount })
        assertEquals(layers.map { it.loopStartFrame }, zone.layers.map { it.loopStartFrame })
        assertEquals(zone.layers.last().sample, zone.sample, "the compatibility headline stays the hard layer")
        assertEquals(zone.layers.map { it.sample }, loaded.sampleFiles)
        for ((index, layer) in zone.layers.withIndex()) {
            val wav = File(root, layer.sample)
            assertTrue(wav.isFile)
            assertEquals(SmplChunk(60, SmplChunk.Loop(layer.loopStartFrame, layer.frameCount)), WavReader.readSmpl(wav))
            assertEquals(layers[index].frameCount, WavReader.read(wav).frameCount.toLong())
        }
        val xpm = File(root, "${program.name}_[TrackData]/${program.name}.xpm").readText()
        for (layer in layers) {
            assertTrue(xpm.contains("<VelStart>${layer.velStart}</VelStart>"))
            assertTrue(xpm.contains("<VelEnd>${layer.velEnd}</VelEnd>"))
            assertTrue(xpm.contains("<SliceLoopStart>${layer.loopStartFrame}</SliceLoopStart>"))
        }
    }

    @Test
    fun `existing v1 sidecars remain a single full velocity sample`() {
        val file = File(temp, "Legacy$SUFFIX").apply { writeText("""{"version":1,"name":"Legacy","release":0.25,"zones":[{"low":48,"high":72,"root":60,"sample":"Legacy/data.wav","frames":100,"loopStart":40}]}""") }
        val instrument = InstrumentStore.read(file)
        val zone = instrument.zones.single()
        assertEquals(listOf(InstrumentStore.Layer("Legacy/data.wav", 100, 40)), zone.playableLayers)
        assertEquals(zone.playableLayers.single(), zone.layerFor(1))
        assertEquals(zone.playableLayers.single(), zone.layerFor(127))
        assertEquals(listOf("Legacy/data.wav"), instrument.sampleFiles)
    }

    @Test
    fun `v2 rejects incomplete overlapping or unsafe layers rather than falling back to the hard sample`() {
        fun layer(sample: String = "data/soft.wav", start: Int = 1, end: Int = 42) = JsonValue.Obj(linkedMapOf(
            "sample" to JsonValue.Str(sample), "frames" to JsonValue.Num(100.0), "loopStart" to JsonValue.Num(40.0),
            "velStart" to JsonValue.Num(start.toDouble()), "velEnd" to JsonValue.Num(end.toDouble()),
        ))
        val soft = layer()
        val hard = layer("data/hard.wav", 43, 127)
        val headline = linkedMapOf<String, JsonValue>("low" to JsonValue.Num(48.0), "high" to JsonValue.Num(72.0),
            "root" to JsonValue.Num(60.0), "sample" to JsonValue.Str("data/hard.wav"),
            "frames" to JsonValue.Num(100.0), "loopStart" to JsonValue.Num(40.0))
        val invalid = listOf(
            null,
            emptyList(),
            listOf(layer("../escape.wav"), hard),
            listOf(soft, layer("data/hard.wav", 42, 127)),
            listOf(soft, layer("data/hard.wav", 43, 128)),
            listOf(soft), // headline no longer matches the hardest available layer
        )
        for ((index, layers) in invalid.withIndex()) {
            val zone = JsonValue.Obj(LinkedHashMap(headline).also { fields -> layers?.let { fields["layers"] = JsonValue.Arr(it) } })
            val json = JsonValue.Obj(linkedMapOf("version" to JsonValue.Num(2.0), "name" to JsonValue.Str("Bad"),
                "release" to JsonValue.Num(.6), "zones" to JsonValue.Arr(listOf(zone))))
            val file = File(temp, "bad-$index$SUFFIX").apply { writeText(Json.write(json)) }
            assertFailsWith<JsonException>("case $index") { InstrumentStore.read(file) }
        }
    }

    @Test
    fun `voice mode round-trips through the sidecar`() {
        val root = File(temp, "Voiced").apply { mkdirs() }
        val program = KeygroupProgram("Glider", listOf(Keygroup(0, 127, 60, listOf(VelocityLayer("g", 100, 0, 127)))),
            voiceMode = VoiceMode.GLIDE, explicitNoteOn = true)
        InstrumentStore.write(root, "Glider", program, "Glider_[TrackData]")
        val sidecar = InstrumentStore.sidecar(root, "Glider")
        assertEquals("GLIDE", Json.parse(sidecar.readText()).obj().getValue("voiceMode").str())
        assertEquals(true, Json.parse(sidecar.readText()).obj().getValue("explicitNoteOn").bool())
        assertEquals(2, Json.parse(sidecar.readText()).obj().getValue("version").int())
        val loaded = InstrumentStore.read(sidecar)
        assertEquals(VoiceMode.GLIDE, loaded.voiceMode)
        assertTrue(loaded.explicitNoteOn)
        assertTrue(loaded.toProgram().explicitNoteOn)
    }

    @Test
    fun `a sidecar without a voice mode or with a stranger's value reads as POLY`() {
        fun text(extra: String) = """{"version":2,"name":"V","release":0.5,$extra"zones":[{"low":0,"high":127,"root":60,"sample":"d/h.wav","frames":100,"loopStart":0,"layers":[{"sample":"d/h.wav","frames":100,"loopStart":0,"velStart":0,"velEnd":127}]}]}"""
        val plain = File(temp, "Plain$SUFFIX").apply { writeText(text("")) }
        assertEquals(VoiceMode.POLY, InstrumentStore.read(plain).voiceMode)
        val stranger = File(temp, "Stranger$SUFFIX").apply { writeText(text("\"voiceMode\":\"WIBBLE\",")) }
        assertEquals(VoiceMode.POLY, InstrumentStore.read(stranger).voiceMode)
        val wrongType = File(temp, "WrongType$SUFFIX").apply { writeText(text("\"voiceMode\":3,")) }
        assertFailsWith<JsonException> { InstrumentStore.read(wrongType) }
    }

    @Test
    fun `an instrument becomes the program that wrote it`() {
        val root = File(temp, "Round").apply { mkdirs() }
        val program = KeygroupProgram(
            "Two Zones",
            listOf(
                Keygroup(0, 59, 48, listOf(VelocityLayer("lo_soft", 100, 0, 63, loopStartFrame = 10), VelocityLayer("lo_hard", 120, 64, 127, loopStartFrame = 20))),
                Keygroup(60, 127, 72, listOf(VelocityLayer("hi_soft", 200, 0, 63), VelocityLayer("hi_hard", 220, 64, 127, loopStartFrame = 30))),
            ),
            volumeRelease = 0.35f,
            voiceMode = VoiceMode.LEAD,
        )
        InstrumentStore.write(root, "Two Zones", program, "Two Zones_[TrackData]")
        val back = InstrumentStore.read(InstrumentStore.sidecar(root, "Two Zones")).toProgram()
        assertEquals(program.copy(), back)
    }

    @Test
    fun `a v1 instrument becomes a program with one full velocity layer`() {
        val file = File(temp, "Old$SUFFIX").apply { writeText("""{"version":1,"name":"Old","release":0.25,"zones":[{"low":48,"high":72,"root":60,"sample":"Old_[TrackData]/Old_A.wav","frames":100,"loopStart":40}]}""") }
        val program = InstrumentStore.read(file).toProgram()
        assertEquals(KeygroupProgram("Old", listOf(Keygroup(48, 72, 60, listOf(VelocityLayer("Old_A", 100, 0, 127, loopStartFrame = 40)))), volumeRelease = 0.25f), program)
    }

    private companion object {
        const val SUFFIX = InstrumentStore.SUFFIX
    }
}
