package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.ScannedChainPresets
import com.snipsnap.synth.ScannedChainVoice
import com.snipsnap.xpm.VoiceMode
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScannedChainKeysMakerTest {
    private val root = kotlin.io.path.createTempDirectory("scanned-chain-keys").toFile()
    private val spec = ScannedChainKeysMaker.Spec(ScannedChainKeysMaker.shortPluck().let {
        it.copy(config = it.config.copy(seconds = .25f))
    })

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    /** Cheap finite captures isolate packaging from the already-tested mechanical solver. */
    private fun captures(current: ScannedChainKeysMaker.Spec = spec): List<List<ScannedChainKeysMaker.Capture>> {
        val frames = (current.patch.config.seconds * 44_100).roundToInt()
        return ScannedChainKeysMaker.zoneMidis(current).map { midi ->
            ScannedChainKeysMaker.TOUCHES.map { touch ->
                val patch = ScannedChainKeysMaker.sourcePatch(current, midi, touch)
                val source = Snip(FloatArray(frames) { i ->
                    (.2 * touch * sin(2 * PI * (i % 100) / 100)).toFloat()
                }, 1, 44_100)
                ScannedChainKeysMaker.Capture(patch, ScannedChainKeysMaker.hostSample(source, touch))
            }
        }
    }

    @Test fun `native roots cover exactly C2 to C5 with all three velocity windows and no loops`() {
        val before = spec.patch.toJsonText()
        val notes = captures()
        val (program, samples) = ScannedChainKeysMaker.assemble("Short Pluck Keys", spec, notes)
        assertEquals((36..72 step 3).toList(), program.keygroups.map { it.rootNote })
        assertEquals((36..72).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertEquals(39, samples.size)
        assertEquals(.05f, program.volumeRelease)
        assertEquals(VoiceMode.POLY, program.voiceMode)
        assertTrue(program.explicitNoteOn)
        for (group in program.keygroups) {
            assertTrue((group.lowNote..group.highNote).all { abs(it - group.rootNote) <= 1 })
            assertEquals((0..127).toList(), group.layers.flatMap { (it.velStart..it.velEnd).toList() })
            assertTrue(group.layers.all { it.loopStartFrame == 0L && it.frameCount == 11_025L })
        }
        val instrument = InstrumentStore.read(writeSidecar(program))
        assertEquals(39, instrument.sampleFiles.size)
        assertEquals(null, instrument.zoneFor(35))
        assertEquals(null, instrument.zoneFor(73))
        assertEquals(before, spec.patch.toJsonText(), "bank roots and touches never rewrite the saved source")
    }

    @Test fun `capture velocity is removed exactly once without changing source or matching loudness`() {
        for (touch in ScannedChainKeysMaker.TOUCHES) {
            val source = Snip(floatArrayOf(-.12f * touch, 0f, .3f * touch, .07f * touch), 1, 44_100)
            val before = source.samples.copyOf()
            val prepared = ScannedChainKeysMaker.hostSample(source, touch)
            assertContentEquals(before, source.samples)
            source.samples.indices.forEach { index ->
                assertEquals(before[index] / touch, prepared.samples[index])
            }
            assertTrue(abs(prepared.peak() - .3f) < 3e-8f, "no independent peak normalization")
        }
        assertFailsWith<IllegalArgumentException> {
            ScannedChainKeysMaker.hostSample(Snip(floatArrayOf(.9f, 0f), 1, 44_100), .3f)
        }
    }

    @Test fun `a real finite zone retains each exact physical recipe and uses only the scalar gain rule`() {
        val notes = ScannedChainKeysMaker.renderZone(spec, 60)
        assertEquals(3, notes.size)
        notes.forEachIndexed { index, capture ->
            assertEquals(ScannedChainKeysMaker.sourcePatch(spec, 60, ScannedChainKeysMaker.TOUCHES[index]), capture.patch)
            assertEquals(11_025, capture.snip.frameCount)
            assertTrue(capture.snip.samples.all { it.isFinite() && it in -1f..1f })
        }
        val capture = notes.first()
        val replay = capture.patch.render()
        assertContentEquals(FloatArray(replay.samples.size) { replay.samples[it] / capture.patch.velocity }, capture.snip.samples)
    }

    @Test fun `package preserves constrained zones no-loop WAV metadata MPC note on and all editable recipes`() {
        val name = "Short Pluck Package"
        val notes = captures()
        val program = ScannedChainKeysMaker.export(name, spec, notes, root)
        val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
        val instrument = InstrumentStore.read(InstrumentStore.sidecar(root, name))
        assertEquals(36, instrument.zones.first().lowNote)
        assertEquals(72, instrument.zones.last().highNote)
        assertEquals(program, instrument.toProgram())
        assertEquals(Mpc3TrackWriter().keygroupPayloadText(program), Acvs.read(File(root, "$name.xty")).payloadText)
        assertEquals(spec.patch, ScannedChainPatch.fromJsonText(File(data, ScannedChainKeysMaker.PATCH_FILE).readText()))
        val manifest = Json.parse(File(data, ScannedChainKeysMaker.MANIFEST_FILE).readText()).obj()
        assertEquals(1, manifest.getValue("version").int())
        assertFalse(manifest.getValue("spanKeyboard").bool())
        assertTrue(manifest.getValue("finite").bool())
        assertTrue(manifest.getValue("gainRule").str().contains("exactly once"))
        val zones = manifest.getValue("zones").arr()
        assertEquals(13, zones.size)
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val xmlZones = factory.newDocumentBuilder().parse(File(data, "$name.xpm")).getElementsByTagName("Instrument")
        assertEquals(13, xmlZones.length)
        for ((zoneIndex, zone) in instrument.zones.withIndex()) {
            val xmlZone = xmlZones.item(zoneIndex) as Element
            assertEquals(zone.lowNote, text(xmlZone, "LowNote").toInt())
            assertEquals(zone.highNote, text(xmlZone, "HighNote").toInt())
            assertEquals(2, text(xmlZone, "TriggerMode").toInt())
            assertEquals(.05f, text(xmlZone, "VolumeRelease").toFloat())
            val layers = zones[zoneIndex].obj().getValue("layers").arr()
            for ((touchIndex, layer) in zone.playableLayers.withIndex()) {
                val wav = File(root, layer.sample)
                assertEquals(SmplChunk(zone.rootNote, null), WavReader.readSmpl(wav))
                val decoded = WavReader.read(wav)
                assertEquals(11_025, decoded.frameCount)
                assertTrue(decoded.samples.indices.all { abs(decoded.samples[it] - notes[zoneIndex][touchIndex].snip.samples[it]) < 2e-7f })
                val recipe = layers[touchIndex].obj()
                assertEquals(ScannedChainKeysMaker.TOUCHES[touchIndex], recipe.getValue("physicalVelocity").num().toFloat())
                assertEquals(1.0 / ScannedChainKeysMaker.TOUCHES[touchIndex], recipe.getValue("sampleScale").num())
                assertEquals(notes[zoneIndex][touchIndex].patch,
                    ScannedChainPatch.fromJsonText(File(data, recipe.getValue("sourceRecipe").str()).readText()))
                assertEquals(0L, layer.loopStartFrame)
                assertEquals(0, recipe.getValue("loopStartFrame").int())
                val xmlLayer = xmlZone.getElementsByTagName("Layer").item(touchIndex) as Element
                assertEquals(0, text(xmlLayer, "SliceLoop").toInt())
            }
        }
        assertEquals(39, File(data, ScannedChainKeysMaker.RECIPE_DIRECTORY).listFiles()!!.size)
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".scanned-chain-keys-") })
    }

    @Test fun `finite keys stop at source end while early note off uses the short player fade`() {
        val (program, samples) = ScannedChainKeysMaker.assemble("Finite Player", spec, captures())
        val instrument = InstrumentStore.read(writeSidecar(program))
        val byFile = samples.mapKeys { "data/${it.key}.wav" }.mapValues { it.value.samples }
        fun engine() = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, byFile), 44_100)
        val held = engine()
        assertTrue(held.noteOn(60, .65f))
        held.render(FloatArray(44_100), 22_050)
        assertTrue(held.activeNotes.isEmpty(), "a held key never invents a sustain loop")
        val released = engine()
        assertTrue(released.noteOn(60, 1f))
        released.render(FloatArray(882), 441)
        released.noteOff(60)
        released.render(FloatArray(5_292), 2_646)
        assertTrue(released.activeNotes.isEmpty(), "key-up stops the finite sample within the .05 second fade")
    }

    @Test fun `invalid or cancelled work leaves no package and no overwrite preserves every delivered byte`() {
        assertFailsWith<CancellationException> { ScannedChainKeysMaker.renderZone(spec, 60) { true } }
        assertFailsWith<IllegalArgumentException> { ScannedChainKeysMaker.renderZone(spec, 37) }
        assertFailsWith<IllegalArgumentException> { ScannedChainKeysMaker.sourcePatch(spec, 60, 0f) }
        assertFailsWith<IllegalArgumentException> {
            ScannedChainKeysMaker.Spec(ScannedChainPresets.forVoice(ScannedChainVoice.METAL).first())
        }
        for (release in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1f))
            assertFailsWith<IllegalArgumentException> { ScannedChainKeysMaker.Spec(spec.patch, release) }
        val good = captures()
        assertFailsWith<IllegalArgumentException> { ScannedChainKeysMaker.export("Bad", spec, good.dropLast(1), root) }
        assertFailsWith<IllegalArgumentException> { ScannedChainKeysMaker.export("Bad", spec, good.map { it.dropLast(1) }, root) }
        val wrongRoot = good.mapIndexed { index, captures ->
            if (index != 0) captures else captures.map { it.copy(patch = it.patch.copy(midi = 39)) }
        }
        assertFailsWith<IllegalArgumentException> { ScannedChainKeysMaker.export("Bad", spec, wrongRoot, root) }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 1.1f)) {
            val broken = good.mapIndexed { index, captures -> if (index != 0) captures else captures.map {
                it.copy(snip = it.snip.copy(samples = it.snip.samples.copyOf().also { samples -> samples[0] = bad }))
            } }
            assertFailsWith<IllegalArgumentException> { ScannedChainKeysMaker.export("Bad", spec, broken, root) }
        }
        assertTrue(root.listFiles()!!.isEmpty(), "validation happens before any package file is written")
        val name = "Safe Export"
        ScannedChainKeysMaker.export(name, spec, good, root)
        val saved = snapshot()
        assertFailsWith<DestinationExists> { ScannedChainKeysMaker.export(name, spec, good, root) }
        assertEquals(saved.keys, snapshot().keys)
        saved.forEach { (path, bytes) -> assertContentEquals(bytes, File(root, path).readBytes()) }
        val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
        File(data, "obsolete.txt").writeText("old")
        ScannedChainKeysMaker.export(name, spec, good, root, overwrite = true)
        assertFalse(File(data, "obsolete.txt").exists())
        assertEquals(saved.keys, snapshot().keys)
    }

    private fun writeSidecar(program: com.snipsnap.xpm.KeygroupProgram): File =
        File(root, "fixture${InstrumentStore.SUFFIX}").also { it.writeText(InstrumentStore.render(program, "data")) }

    private fun snapshot(): Map<String, ByteArray> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readBytes() }

    private fun text(element: Element, tag: String): String =
        assertNotNull(element.getElementsByTagName(tag).item(0)).textContent.trim()
}
