package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.*
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

class StackedReleaseTest {
    @Test
    fun `legacy per-zone samples can reuse a filename without losing the selected loop`() {
        val inst = InstrumentStore.Instrument("legacy", .1f, listOf(
            InstrumentStore.Zone(48, 59, 48, "same.wav", 10, 0),
            InstrumentStore.Zone(60, 72, 60, "same.wav", 512, 16),
        ))
        val engine = InstrumentEngine(InstrumentEngine.Loaded(inst, 1000,
            listOf(FloatArray(10), FloatArray(512) { .1f })), 1000)
        assertTrue(engine.noteOn(60))
        val out = FloatArray(1200)
        engine.render(out, 600)
        assertTrue(out.last() > .03f)
        assertEquals(listOf(60), engine.activeNotes)
    }

    private fun program() = KeygroupProgram("Release proof", listOf(
        Keygroup(48, 72, 60, listOf(VelocityLayer("body", 512, 0, 127, loopStartFrame = 16)), .01f),
        Keygroup(48, 72, 60, listOf(VelocityLayer("halo", 512, 0, 127, loopStartFrame = 16)), .10f),
    ), explicitNoteOn = true, stackZones = true)

    private fun instrument(): InstrumentStore.Instrument {
        val dir = Files.createTempDirectory("stacked-release").toFile()
        try {
            InstrumentStore.write(dir, "Release proof", program(), "data")
            return InstrumentStore.read(InstrumentStore.sidecar(dir, "Release proof"))
        } finally { dir.deleteRecursively() }
    }
    private fun loaded(inst: InstrumentStore.Instrument) = InstrumentEngine.Loaded(inst, 1000,
        inst.sampleFiles.associateWith { FloatArray(512) { .1f } })

    @Test
    fun `sidecar and both writers retain two overlapping envelopes`() {
        val inst = instrument()
        assertTrue(inst.stackZones)
        assertEquals(listOf(.01f, .1f), inst.zones.map { it.release })
        assertEquals(program(), inst.toProgram())
        val xml = KeygroupWriter().write(program())
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
        val releases = document.getElementsByTagName("VolumeRelease")
        assertEquals(listOf(.01f, .1f), (0 until releases.length).map { releases.item(it).textContent.toFloat() })
        val payload = Json.parse(Mpc3TrackWriter().keygroupPayloadText(program())).obj()
        val root = payload.getValue("data").obj().getValue("program").obj()
        val instruments = root.getValue("drum").obj().getValue("instruments").arr()
        val actual = instruments.take(2).map { it.obj().getValue("synthSection").obj()
            .getValue("ampEnvelope").obj().getValue("Release").obj().getValue("value0").num().toFloat() }
        assertEquals(listOf(.01f, .1f), actual)
        assertFalse(root.getValue("keygroup").obj().getValue("ampEnvelopeGlobal").bool())
    }

    @Test
    fun `legacy overlap selects first and an incomplete or mismatched stack stays silent`() {
        val inst = instrument()
        val frames: (String) -> Long? = { 512L }
        assertEquals(2, KeyHit.resolveAll(inst, 60, .7f, frames).size)
        assertEquals(1, KeyHit.resolveAll(inst.copy(stackZones = false), 60, .7f, frames).size)
        assertTrue(KeyHit.resolveAll(inst, 60, 1f) { if ("body" in it) 512 else null }.isEmpty())
        assertTrue(KeyHit.resolveAll(inst, 60, 1f) { if ("body" in it) 512 else 511 }.isEmpty())
        val mismatch = inst.copy(zones = inst.zones.mapIndexed { i, zone ->
            if (i == 1) zone.copy(rootNote = 61) else zone
        })
        assertTrue(KeyHit.resolveAll(mismatch, 60, 1f, frames).isEmpty())
    }

    @Test
    fun `release leaves only halo and stealing removes a whole logical note`() {
        val inst = instrument()
        val engine = InstrumentEngine(loaded(inst), 1000, maxVoices = 1)
        assertTrue(engine.noteOn(60))
        assertEquals(listOf(60), engine.activeNotes)
        engine.render(FloatArray(40), 20)
        engine.noteOff(60)
        val tail = FloatArray(100)
        engine.render(tail, 50)
        // At frame 50 only the .1-second halo remains, at half its original level.
        assertTrue(abs(tail[98] - .0175f) < 1e-5f, "body did not stop independently: ${tail[98]}")
        assertTrue(engine.noteOn(62))
        engine.render(FloatArray(20), 10)
        assertEquals(listOf(62), engine.activeNotes)
        engine.allOff()
        val silence = FloatArray(240)
        engine.render(silence, 120)
        assertTrue(silence.takeLast(20).all { it == 0f })
        assertTrue(engine.activeNotes.isEmpty())
    }

    @Test
    fun `stack preserves velocity selection and eight keys count as eight notes`() {
        val base = instrument()
        val inst = base.copy(zones = base.zones.map { zone -> zone.copy(layers = listOf(
            InstrumentStore.Layer(zone.sample + ".soft", 512, 16, 1, 63),
            InstrumentStore.Layer(zone.sample, 512, 16, 64, 127),
        )) })
        val hits = KeyHit.resolveAll(inst, 60, .3f) { 512L }
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.hit.sampleFile.endsWith(".soft") })
        val engine = InstrumentEngine(loaded(inst), 1000)
        for (note in 54..61) assertTrue(engine.noteOn(note, .3f))
        assertEquals((54..61).toList(), engine.activeNotes)
        assertTrue(engine.noteOn(62, .3f))
        engine.render(FloatArray(20), 10)
        assertEquals((55..62).toList(), engine.activeNotes)
    }

    @Test
    fun `v3 cannot silently drop its stack declaration or a component release`() {
        val original = Json.parse(InstrumentStore.render(program(), "data")).obj()
        val dir = Files.createTempDirectory("invalid-stack").toFile()
        val file = dir.resolve("bad.instrument.json")
        try {
            val missingFlag = original.toMutableMap().apply { remove("stackZones") }
            file.writeText(Json.write(JsonValue.Obj(missingFlag)))
            assertTrue(assertFailsWith<JsonException> { InstrumentStore.read(file) }.message!!.contains("no stackZones"))
            for (value in listOf<JsonValue?>(null, JsonValue.Num(-1.0), JsonValue.Num(1e39))) {
                val zones = original.getValue("zones").arr().toMutableList()
                val broken = zones[0].obj().toMutableMap().apply {
                    if (value == null) remove("release") else put("release", value)
                }
                zones[0] = JsonValue.Obj(broken)
                file.writeText(Json.write(JsonValue.Obj(original + ("zones" to JsonValue.Arr(zones)))))
                val message = assertFailsWith<JsonException> { InstrumentStore.read(file) }.message!!
                assertTrue(message.contains(if (value == null) "no release" else "invalid zone release"), message)
            }
        } finally { dir.deleteRecursively() }
    }
}
