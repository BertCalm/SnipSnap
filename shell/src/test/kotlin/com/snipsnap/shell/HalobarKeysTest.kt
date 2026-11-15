package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.OneNote
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.*
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VoiceMode
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.test.*

class HalobarKeysTest {
    @Test fun `six mallet captures survive package reload with velocity and cycling audio`() {
        val dir = Files.createTempDirectory("halobar-keys").toFile()
        try {
            for (voice in HalobarVoice.entries) {
                val spec = HalobarPadMaker.Spec(HalobarPatch("Keys", voice, mapOf("SPACE" to 0f, "HOLD" to 0f)), .1f, 17)
                assertEquals(spec, HalobarPadMaker.Spec.fromJsonText(spec.toJsonText()))
                val midi = Halobar.rootMidi(voice)
                val captures = HalobarPadMaker.renderZone(spec, midi)
                assertEquals(6, captures.size)
                assertTrue(captures.all { it.loopStartFrame == 0L })
                val name = "H${voice.ordinal}"
                // Package plumbing uses real root captures. The production generator renders each zone independently.
                val notes = HalobarPadMaker.zoneMidis(spec).map { captures }
                val program = HalobarPadMaker.export(name, spec, notes, dir)
                val inst = InstrumentStore.read(InstrumentStore.sidecar(dir, name))
                assertEquals(program, inst.toProgram())
                assertEquals(spec, HalobarPadMaker.Spec.fromJsonText(dir.resolve("$name${HalobarPadMaker.RECIPE_SUFFIX}").readText()))
                assertFailsWith<DestinationExists> { HalobarPadMaker.export(name, spec, notes, dir) }
                val cursor = KeyHit.CycleCursor()
                fun play(v: Float): Pair<String, FloatArray> {
                    val hit = KeyHit.resolveAll(inst, midi, v, cursor) { path -> WavReader.read(dir.resolve(path)).frameCount.toLong() }.single().hit
                    return hit.sampleFile to WavReader.read(dir.resolve(hit.sampleFile)).samples
                }
                val soft = List(4) { play(.4f) }
                assertEquals(3, soft.map { it.first }.distinct().size)
                assertContentEquals(soft[0].second, soft[3].second)
                assertFalse(soft[0].second.contentEquals(soft[1].second))
                val hard = play(1f)
                assertTrue(hard.second.maxOf { abs(it) } > soft[0].second.maxOf { abs(it) })
                for (chord in ChordStabs.stabs(KeySpec.parse("Cm"))) for (note in HalobarChords.notes(voice, chord))
                    assertNotNull(KeyHit.resolve(inst, note, .8f) { 256L })
                assertEquals(5, Json.parse(InstrumentStore.sidecar(dir, name).readText()).obj().getValue("version").int())
                assertTrue(program.keygroups.all { it.mpcLayers.size == 6 && it.mpcCycle && it.mpcVelocityCycle },
                    "HALOBAR production export must retain both three-strike windows")
                checkExport(dir, name, program)
                // KEYS rewrites descriptors when the voice mode changes. A
                // save/reload must not silently restore the old two-take export.
                val updated = inst.toProgram().copy(voiceMode = VoiceMode.LEAD)
                val audioBefore = inst.sampleFiles.associateWith { dir.resolve(it).readBytes() }
                OneNote.rewriteDescriptors(dir, name, updated)
                assertEquals(updated, InstrumentStore.read(InstrumentStore.sidecar(dir, name)).toProgram(),
                    "MPC cycle export policy must survive saved-program edits")
                checkExport(dir, name, updated)
                for ((path, bytes) in audioBefore) assertContentEquals(bytes, dir.resolve(path).readBytes())
                // Older delivered banks have all captures but v4/fallback
                // descriptors. Upgrade only those descriptors, without a render.
                OneNote.rewriteDescriptors(dir, name, updated.copy(keygroups = updated.keygroups.map {
                    it.copy(mpcVelocityCycle = false)
                }))
                assertTrue(InstrumentStore.read(InstrumentStore.sidecar(dir, name)).toProgram().keygroups.all {
                    it.mpcLayers.size == 2 && !it.mpcCycle
                })
                assertEquals(updated, HalobarPadMaker.upgradeCycleExport(dir, name))
                assertEquals(updated, HalobarPadMaker.upgradeCycleExport(dir, name), "Upgrade is idempotent")
                checkExport(dir, name, updated)
                for ((path, bytes) in audioBefore) assertContentEquals(bytes, dir.resolve(path).readBytes())
            }
        } finally { dir.deleteRecursively() }
    }

    private fun checkExport(dir: File, name: String, program: KeygroupProgram) {
        val payload = Acvs.read(dir.resolve("$name.xty")).payload!!.obj()
        val native = (payload.getValue("data").obj().getValue("program").obj()
            .getValue("drum").obj().getValue("instruments") as JsonValue.Arr).items
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(dir.resolve("${name}_[TrackData]/$name.xpm"))
        val instruments = doc.getElementsByTagName("Instrument")
        assertEquals(program.keygroups.size, instruments.length)
        for ((index, group) in program.keygroups.withIndex()) {
            val n = native[index].obj()
            assertEquals(0, n.getValue("zonePlayTime").int(), "native matching-window Cycle")
            val layers = (n.getValue("layersv") as JsonValue.Arr).items.map { it.obj() }
                .filter { it.getValue("sampleName").str().isNotEmpty() }
            val expectedNative = group.layers.drop(3) + group.layers.take(3)
            assertEquals(expectedNative.map { it.sampleName }, layers.map { it.getValue("sampleName").str() },
                "native hard-first windows must retain strike order")
            assertEquals(expectedNative.map { it.velStart to it.velEnd }, layers.map {
                it.getValue("velocityStart").int() to it.getValue("velocityEnd").int()
            })
            val xml = instruments.item(index) as Element
            assertEquals("0", xml.getElementsByTagName("ZonePlay").item(0).textContent)
            val all = xml.getElementsByTagName("Layer")
            val filled = (0 until all.length).map { all.item(it) as Element }
                .filter { it.getElementsByTagName("Active").item(0).textContent == "True" }
            assertEquals(group.layers.map { it.sampleName }, filled.map { it.getElementsByTagName("SampleName").item(0).textContent },
                "XPM must retain both complete velocity windows")
            for ((slot, layer) in filled.withIndex()) {
                assertEquals(group.layers[slot].velStart.toString(), layer.getElementsByTagName("VelStart").item(0).textContent)
                assertEquals(group.layers[slot].velEnd.toString(), layer.getElementsByTagName("VelEnd").item(0).textContent)
                assertEquals(group.rootNote.toString(), layer.getElementsByTagName("RootNote").item(0).textContent)
            }
        }
    }
}
