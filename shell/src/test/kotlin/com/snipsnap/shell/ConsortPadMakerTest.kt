package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.ChordStabs
import com.snipsnap.synth.ConsortChords
import com.snipsnap.synth.ConsortKeys
import com.snipsnap.synth.ConsortPatch
import com.snipsnap.synth.ConsortPresets
import com.snipsnap.synth.ConsortVoice
import com.snipsnap.synth.KeyNote
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * CONSORT held through its maker ([ConsortPadMaker]): nine zones, three touch windows a zone, two takes a
 * window dealt in turn, every sample an attack and a sustain loop; the program the phone, MPC 3 and MPC 2
 * read; a held key that keeps sounding until it is let go; every chord-tool chord on real key samples. The
 * DSP claims (the joins, the touches, the takes) are `:synth`'s `ConsortKeysTest`.
 */
class ConsortPadMakerTest {
    private val dest: File = java.nio.file.Files.createTempDirectory("consortpad").toFile()

    @AfterTest
    fun cleanUp() {
        dest.deleteRecursively()
    }

    private companion object {
        val patch: ConsortPatch = ConsortPresets.forVoice(ConsortVoice.CLARINET).first { it.chairs.isEmpty() }
        val spec = ConsortPadMaker.Spec(patch, .3f)

        /** One CLARINET instrument, every zone and layer: 54 held notes, shared by the tests below. */
        val notes: List<List<KeyNote>> by lazy {
            val t0 = System.nanoTime()
            ConsortPadMaker.zoneMidis(spec).map { ConsortPadMaker.renderZone(spec, it) }.also {
                println("CONSORT held CLARINET: 9 zones, 54 layers in %.1f s".format(java.util.Locale.ROOT, (System.nanoTime() - t0) / 1e9))
            }
        }

        const val RATE = 44_100

        fun rms(samples: FloatArray, start: Int, end: Int): Double =
            sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start))
    }

    @Test
    fun `nine zones in the cycle layout, each layer its attack then its own loop`() {
        val (program, samples) = ConsortPadMaker.assemble("Consort Keys", spec, notes)
        assertEquals(ConsortKeys.padMidis(ConsortVoice.CLARINET), program.keygroups.map { it.rootNote })
        assertEquals(54, samples.size)
        assertEquals(.3f, program.volumeRelease)
        for ((zone, group) in program.keygroups.withIndex()) {
            assertEquals(6, group.layers.size)
            assertTrue(group.cycleLayers && group.mpcVelocityCycle && group.mpcCycle)
            assertEquals(6, group.mpcLayers.size, "MPC export keeps both takes of all three touches")
            assertEquals(listOf(0..42, 0..42, 43..84, 43..84, 85..127, 85..127), group.layers.map { it.velStart..it.velEnd })
            for ((layer, l) in group.layers.withIndex()) {
                assertEquals(notes[zone][layer].loopStartFrame, l.loopStartFrame)
                assertTrue(l.loopStartFrame in 1 until l.frameCount)
            }
            assertEquals((1..3).flatMap { t -> (1..2).map { r -> "ConsortKeys_${com.snipsnap.audio.Scales.nameOf(group.rootNote)}_T${t}_R$r" } }
                .map { com.snipsnap.kit.Names.sanitizeStem(it) }, group.layers.map { it.sampleName })
        }
        assertEquals(program.keygroups.first().rootNote - 9, program.keygroups.first().lowNote)
        assertEquals(program.keygroups.last().rootNote + 9, program.keygroups.last().highNote)
        // What assemble refuses: an unsafe name, a missing zone, a missing layer, a layer without a loop.
        assertFailsWith<IllegalArgumentException> { ConsortPadMaker.assemble("Consort/Keys", spec, notes) }
        assertFailsWith<IllegalArgumentException> { ConsortPadMaker.assemble("Consort Keys", spec, notes.drop(1)) }
        assertFailsWith<IllegalArgumentException> { ConsortPadMaker.assemble("Consort Keys", spec, notes.mapIndexed { i, z -> if (i == 0) z.take(5) else z }) }
        assertFailsWith<IllegalArgumentException> {
            ConsortPadMaker.assemble("Consort Keys", spec, notes.mapIndexed { i, z -> if (i == 0) listOf(z[0].copy(loopStartFrame = 0)) + z.drop(1) else z })
        }
    }

    @Test
    fun `the package keeps three touches of two looping takes through the phone, MPC 3 and MPC 2`() {
        val name = "Consort Keys"
        val program = ConsortPadMaker.export(name, spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        assertEquals(program.keygroups.map { it.rootNote }, instrument.zones.map { it.rootNote })
        assertEquals(spec.releaseSeconds, instrument.release, 1e-6f)
        val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
        val track = Mpc3Project.read(File(dest, "$name.xty"))
        val native = track.drumPrograms().single().getValue("instruments").arr()
        val legacyFile = File(dest, Mpc3TrackWriter.trackDataDirName(name) + "/$name.xpm")
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(legacyFile).getElementsByTagName("Instrument")
        assertEquals(instrument.zones.size, xml.length)
        for ((index, zone) in instrument.zones.withIndex()) {
            val group = program.keygroups[index]
            assertEquals(6, zone.layers.size)
            for ((layer, l) in zone.layers.withIndex()) {
                assertEquals(notes[index][layer].loopStartFrame, l.loopStartFrame, "phone loop, zone ${zone.rootNote} layer $layer")
                assertEquals(notes[index][layer].snip.frameCount, samples.getValue(l.sample).size)
                val sheet = assertNotNull(WavReader.readSmplStrict(File(sidecar.parentFile, l.sample)))
                assertEquals(zone.rootNote, sheet.rootNote)
            }
            // MPC 3: matching-window Cycle, every layer looping from its own start.
            val n = native[index].obj()
            assertEquals(0, n.getValue("zonePlayTime").int(), "native matching-window Cycle")
            val layers = n.getValue("layersv").arr().map { it.obj() }.filter { it.getValue("sampleName").str().isNotEmpty() }
            assertEquals(group.layers.map { it.sampleName }.toSet(), layers.map { it.getValue("sampleName").str() }.toSet())
            for (written in layers) {
                val source = group.layers.single { it.sampleName == written.getValue("sampleName").str() }
                val info = written.getValue("sliceInfo").obj()
                assertEquals(1, info.getValue("LoopMode").int(), "${source.sampleName} loops on MPC 3")
                assertEquals(source.loopStartFrame, info.getValue("LoopStart").int().toLong(), "${source.sampleName} loop start on MPC 3")
                assertEquals(source.velStart to source.velEnd, written.getValue("velocityStart").int() to written.getValue("velocityEnd").int())
            }
            // MPC 2: the same six layers and windows, ZonePlay Cycle, each looping from its own start.
            val instrumentXml = xml.item(index) as Element
            assertEquals("0", instrumentXml.getElementsByTagName("ZonePlay").item(0).textContent)
            val all = instrumentXml.getElementsByTagName("Layer")
            val filled = (0 until all.length).map { all.item(it) as Element }
                .filter { it.getElementsByTagName("Active").item(0).textContent == "True" }
            assertEquals(group.layers.map { it.sampleName }, filled.map { it.getElementsByTagName("SampleName").item(0).textContent })
            for ((slot, layer) in filled.withIndex()) {
                assertEquals(group.layers[slot].velStart.toString(), layer.getElementsByTagName("VelStart").item(0).textContent)
                assertEquals(group.layers[slot].velEnd.toString(), layer.getElementsByTagName("VelEnd").item(0).textContent)
                assertEquals("1", layer.getElementsByTagName("SliceLoop").item(0).textContent)
                assertEquals(group.layers[slot].loopStartFrame.toString(), layer.getElementsByTagName("SliceLoopStart").item(0).textContent)
            }
            // Phone: each touch window deals its own two takes in turn, each a looping hit.
            for ((midiVelocity, touch) in listOf(1 to 0, 42 to 0, 43 to 1, 84 to 1, 85 to 2, 127 to 2)) {
                val cursor = KeyHit.CycleCursor()
                val hits = List(3) {
                    KeyHit.resolveAll(instrument, zone.rootNote, midiVelocity / 127f, cursor) { samples[it]?.size?.toLong() }.single().hit
                }
                assertEquals(listOf(touch * 2, touch * 2 + 1, touch * 2).map { zone.layers[it].sample }, hits.map { it.sampleFile })
                assertTrue(hits.all { it.loops }, "a CONSORT key sustains")
            }
        }
    }

    @Test
    fun `a held key keeps sounding past its loop's wraps until it is let go, then fades over the release`() {
        val name = "Consort Hold"
        ConsortPadMaker.export(name, spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
        val midi = ConsortKeys.padMidis(ConsortVoice.CLARINET)[4]
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, RATE, samples), RATE)
        assertTrue(player.noteOn(midi, .65f))
        val hold = 9 * RATE
        val held = FloatArray(hold * 2)
        player.render(held, hold)
        val mono = FloatArray(hold) { held[it * 2] }
        val early = rms(mono, RATE, 2 * RATE)
        for (second in 2 until 9) {
            val level = rms(mono, second * RATE, (second + 1) * RATE)
            assertTrue(level > .7 * early && level < 1.4 * early, "second $second of a held key reads $level against $early")
        }
        player.noteOff(midi)
        val fade = FloatArray(RATE * 2)
        player.render(fade, RATE)
        assertTrue(player.activeNotes.isEmpty(), "the key let go still sounds after a second")
    }

    @Test
    fun `every chord-tool chord sounds on the real keys and lets go`() {
        val name = "Consort Chords"
        ConsortPadMaker.export(name, spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
        val keys = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val chords = keys.flatMap { key -> listOf("", "m").flatMap { ChordStabs.stabs(KeySpec.parse(key + it)) } } +
            (0..11).flatMap { tonic -> ChordStabs.Mode.entries.flatMap { ChordStabs.modalStabs(tonic, it) } } +
            keys.flatMap { ChordStabs.borrowedStabs(KeySpec.parse(it)) }
        assertEquals(384 + 384 + 192, chords.size)
        for (chord in chords) {
            val voiced = ConsortChords.notes(ConsortVoice.CLARINET, chord)
            val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, RATE, samples), RATE)
            assertTrue(voiced.all { player.noteOn(it, .65f) }, chord.name)
            assertEquals(voiced.toSet(), player.activeNotes.toSet(), chord.name)
            val audio = FloatArray(1024 * 2)
            player.render(audio, 1024)
            assertTrue(audio.any { it != 0f } && audio.all { it.isFinite() }, chord.name)
            voiced.forEach { player.noteOff(it) }
            val fade = FloatArray(RATE * 2)
            player.render(fade, RATE)
            assertTrue(player.activeNotes.isEmpty(), "${chord.name} released")
        }
    }

    @Test
    fun `a section makes keys too, the preview sustains, and both can be cancelled`() {
        val section = ConsortPresets.all().first { it.chairs.size == 3 }
        val sectionSpec = ConsortPadMaker.Spec(section)
        assertTrue(ConsortPadMaker.supports(section))
        assertTrue("section of 4" in ConsortPadMaker.instrumentNote(sectionSpec))
        val zone = ConsortPadMaker.renderZone(sectionSpec, ConsortPadMaker.zoneMidis(sectionSpec)[3])
        assertEquals(6, zone.size)
        assertTrue(zone.all { it.loopStartFrame in 1 until it.snip.frameCount.toLong() })
        val preview = ConsortPadMaker.preview(ConsortPadMaker.Spec(patch, .1f))
        val holdFrames = (ConsortPadMaker.PREVIEW_HOLD_SECONDS * RATE).toInt()
        assertTrue(rms(preview.samples, holdFrames - RATE / 10, holdFrames) > .01, "the preview stopped before its key was let go")
        assertTrue(preview.samples.all { it.isFinite() && it in -1f..1f })
        assertFailsWith<CancellationException> { ConsortPadMaker.preview(sectionSpec) { true } }
        assertFailsWith<CancellationException> { ConsortPadMaker.renderZone(sectionSpec, ConsortPadMaker.zoneMidis(sectionSpec)[0]) { true } }
        assertFalse(ConsortPadMaker.instrumentNote(spec).contains("section"))
    }
}
