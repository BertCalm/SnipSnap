package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.KitPad
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Libration
import com.snipsnap.synth.LibrationKeys
import com.snipsnap.synth.LibrationPatch
import com.snipsnap.synth.LibrationVoice
import com.snipsnap.synth.PadRecipe
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.*

class LibrationPadMakerTest {
    private companion object {
        val patch = LibrationPatch("ORBIT KEYS", LibrationVoice.GATHER,
            Libration.defaults(LibrationVoice.GATHER) + mapOf("SWARM" to .1f, "RESONANCE" to .3f),
            midi = 48, velocity = .7f, sourceSeconds = .42)
        val spec = LibrationPadMaker.Spec(patch, .4f)
        val notes by lazy { LibrationPadMaker.zoneMidis(spec).map { LibrationPadMaker.renderZone(spec, it) } }
    }

    @Test fun `versioned instrument spec preserves the full source and samples only a supported contiguous keyboard span`() {
        assertEquals(spec, LibrationPadMaker.Spec.fromJsonText(spec.toJsonText()))
        for (root in listOf(36, 37, 60, 83, 84)) {
            val setting = LibrationPadMaker.Spec(patch.copy(midi = root))
            val midis = LibrationPadMaker.zoneMidis(setting)
            assertTrue(root in midis)
            assertEquals(maxOf(36, root - 12), midis.first())
            assertEquals(minOf(84, root + 12), midis.last())
            val fixture = KeyNote(Snip(FloatArray(1024), 1, 44100), 512)
            val (program, samples) = LibrationPadMaker.assemble("ORBIT RANGE", setting, midis.map { fixture })
            assertEquals(midis.size, samples.size)
            assertEquals(midis, program.keygroups.map { it.rootNote })
            assertEquals((midis.first()..midis.last()).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
            assertTrue(program.keygroups.all { g -> (g.lowNote..g.highNote).all { abs(it - g.rootNote) <= 1 } })
        }
        assertFalse(patch.toJsonText().contains("heldModel"))
        assertFailsWith<IllegalArgumentException> { LibrationPadMaker.Spec(patch.copy(model = 2, sourceSeconds = 6.0)) }
        assertFailsWith<IllegalArgumentException> { LibrationPadMaker.Spec(patch, Float.NaN) }
        assertFailsWith<CancellationException> { LibrationPadMaker.renderZone(spec, patch.midi) { true } }
        assertFailsWith<IllegalArgumentException> { LibrationPadMaker.renderZone(spec, 35) }
    }

    @Test fun `real onset sustain and release survive both MPC packages WAV sampler metadata and phone import`() {
        val dir = kotlin.io.path.createTempDirectory("libration-keys-").toFile()
        try {
            val before = patch.toJsonText()
            val program = LibrationPadMaker.export(patch.name, spec, notes, dir)
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(spec.releaseSeconds, instrument.release)
            assertTrue(instrument.explicitNoteOn)
            val data = File(dir, Mpc3TrackWriter.trackDataDirName(patch.name))
            val xpm = File(data, "${patch.name}.xpm").readText()
            val xty = Acvs.read(File(dir, "${patch.name}.xty")).payloadText
            assertTrue("<VolumeAttack>0.000000</VolumeAttack>" in xpm)
            assertTrue("<VolumeRelease>0.400000</VolumeRelease>" in xpm)
            assertTrue("<TriggerMode>2</TriggerMode>" in xpm)
            assertTrue("\"LoopMode\": 1" in xty)
            for ((i, zone) in instrument.zones.withIndex()) {
                val file = File(sidecar.parentFile, zone.sample)
                val smpl = assertNotNull(WavReader.readSmpl(file))
                val loop = assertNotNull(smpl.loop)
                val wav = WavReader.read(file)
                assertEquals(program.keygroups[i].rootNote, smpl.rootNote)
                assertEquals(notes[i].loopStartFrame, loop.startFrame)
                assertEquals(notes[i].snip.frameCount.toLong(), loop.endFrameExclusive)
                assertEquals(44100, wav.sampleRate)
                assertEquals(1, wav.channels)
                assertTrue(wav.samples.indices.all { abs(wav.samples[it] - notes[i].snip.samples[it]) < 2e-7f })
                assertTrue(Keys.seamError(wav.samples, zone.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
                assertTrue("<SliceLoopStart>${zone.loopStartFrame}</SliceLoopStart>" in xpm)
                assertTrue("\"LoopStart\": ${zone.loopStartFrame}" in xty)
            }
            val recipe = LibrationPadMaker.Spec.fromJsonText(LibrationPadMaker.recipeFile(dir, patch.name).readText())
            assertEquals(spec, recipe)
            val replay = LibrationPadMaker.renderZone(recipe, patch.midi)
            assertContentEquals(notes[LibrationPadMaker.zoneMidis(spec).indexOf(patch.midi)].snip.samples, replay.snip.samples)
            assertEquals(before, patch.toJsonText())

            val loaded = InstrumentEngine.Loaded(instrument, 44100,
                instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples })
            val player = InstrumentEngine(loaded, 48000)
            assertTrue(player.noteOn(49, .8f))
            val heldSeconds = notes.maxOf { it.snip.durationSeconds } + 2f
            val held = render(player, heldSeconds, 48000)
            val detected = assertNotNull(Pitch.detect(held, heldSeconds - .8f, .5f))
            assertTrue(abs(1200 * log2(detected.hz / Keys.midiHz(49))) < 12)
            assertTrue(held.samples.takeLast(960).any { abs(it) > .001f })
            player.noteOff(49)
            val released = render(player, spec.releaseSeconds + .15f, 48000)
            assertTrue(released.samples.takeLast(960).all { it == 0f })
            assertTrue(player.activeNotes.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `export descriptors keep the clipped authored range rather than spanning the whole keyboard`() {
        val dir = kotlin.io.path.createTempDirectory("libration-range-").toFile()
        try {
            for (root in listOf(36, 37, 60, 83, 84)) {
                val setting = LibrationPadMaker.Spec(patch.copy(midi = root))
                val fixture = KeyNote(Snip(FloatArray(1024), 1, 44100), 512)
                val name = "ORBIT RANGE $root"
                val program = LibrationPadMaker.export(name, setting,
                    LibrationPadMaker.zoneMidis(setting).map { fixture }, dir)
                val expected = program.keygroups.map { it.lowNote to it.highNote }
                val phone = InstrumentStore.read(InstrumentStore.sidecar(dir, name))
                assertEquals(expected, phone.zones.map { it.lowNote to it.highNote },
                    "phone descriptor retains the rendered LIBRATION range at root $root")
                assertNull(phone.zoneFor(expected.first().first - 1))
                assertNull(phone.zoneFor(expected.last().second + 1))
                assertTrue(phone.explicitNoteOn)

                val data = File(dir, Mpc3TrackWriter.trackDataDirName(name))
                val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(data, "$name.xpm"))
                val instruments = xml.getElementsByTagName("Instrument")
                val xpmRanges = (0 until program.keygroups.size).map { index ->
                    val node = instruments.item(index) as org.w3c.dom.Element
                    assertEquals("2", node.getElementsByTagName("TriggerMode").item(0).textContent,
                        "MPC 2 zone $index sustains until key-up")
                    node.getElementsByTagName("LowNote").item(0).textContent.toInt() to
                        node.getElementsByTagName("HighNote").item(0).textContent.toInt()
                }
                assertEquals(expected, xpmRanges, "MPC 2 retains the rendered LIBRATION range at root $root")

                val decoded = Json.parse(Acvs.read(File(dir, "$name.xty")).payloadText) as JsonValue.Obj
                val nativeData = decoded.entries.getValue("data") as JsonValue.Obj
                val nativeProgram = nativeData.entries.getValue("program") as JsonValue.Obj
                val drum = nativeProgram.entries.getValue("drum") as JsonValue.Obj
                val zones = drum.entries.getValue("instruments") as JsonValue.Arr
                val nativeRanges = zones.items.take(program.keygroups.size).map { value ->
                    val zone = (value as JsonValue.Obj).entries
                    assertEquals(2, (zone.getValue("triggerMode") as JsonValue.Num).value.toInt(),
                        "MPC 3 zone sustains until key-up")
                    (zone.getValue("lowNote") as JsonValue.Num).value.toInt() to
                        (zone.getValue("highNote") as JsonValue.Num).value.toInt()
                }
                assertEquals(expected, nativeRanges, "MPC 3 retains the rendered LIBRATION range at root $root")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `held pads and spread retain explicit gating without losing authored transposition`() {
        val dir = kotlin.io.path.createTempDirectory("libration-spread-").toFile()
        try {
            val held = patch.copy(macros = patch.macros + ("HOLD" to 1f), heldModel = LibrationKeys.MODEL_VERSION)
            val shaped = KitPad(slot = 1, sampleFile = "orbit.wav", tuneCoarse = 3, tuneFine = 7)
            assertEquals(3, LibrationPad.configure(shaped, held).tuneCoarse)
            assertEquals(0, LibrationPad.configure(shaped, held, resetHeldTuning = true).tuneCoarse)
            for (source in listOf(patch, held)) {
                val model = KitBuilderModel.create("ORBITS", File(dir, "hold-${source.macros["HOLD"]}"))
                val plan = Spread.plan(model.kit, 48f, Spread.Options(48, Scale.MINOR, padCount = 4))
                val signal = Snip(FloatArray(4410) { kotlin.math.sin(it * .03).toFloat() * .2f }, 1, 44100)
                Spread.apply(model, signal, plan, Spread.Sound("ORBIT", DrumClass.TONAL, "#aabbcc", PadRecipe(source).toJsonValue()))
                val looping = source.macros["HOLD"] == 1f
                assertTrue(model.kit.pads.all { it.loop == looping && it.oneShot != looping })
                assertEquals(plan.notes.map { it.tuneCoarse }, model.kit.pads.map { it.tuneCoarse })
                assertTrue(model.kit.pads.all { PadRecipe.fromJsonValue(requireNotNull(it.recipe)).patch == source })
            }
        } finally { dir.deleteRecursively() }
    }

    private fun render(player: InstrumentEngine, seconds: Float, rate: Int): Snip {
        val out = FloatArray((seconds * rate).toInt() * 2)
        var frame = 0
        while (frame < out.size / 2) {
            val count = minOf(512, out.size / 2 - frame)
            val block = FloatArray(count * 2)
            player.render(block, count)
            block.copyInto(out, frame * 2)
            frame += count
        }
        return Snip(out, 2, rate)
    }
}
