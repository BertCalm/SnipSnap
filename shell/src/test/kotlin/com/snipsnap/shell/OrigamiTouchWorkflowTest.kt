package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.OrigamiExpression
import com.snipsnap.synth.OrigamiGesture
import com.snipsnap.synth.OrigamiKeys
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiPresets
import com.snipsnap.synth.OrigamiVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Velocity
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real middle-C captures exercise touch and live gain; fixtures cover the nine-root export. */
class OrigamiTouchWorkflowTest {
    private val rate = WavWriter.MPC_SAMPLE_RATE

    private fun authored(voice: OrigamiVoice = OrigamiVoice.FAN) =
        OrigamiPresets.references().first { it.voice == voice }.copy(
            name = "MY TOUCH PAPER", midi = 72, velocity = .85f,
            seed = Long.MIN_VALUE + 1701L,
            macros = mapOf("TUNE" to 0f, "CREASE" to .5f, "FOLD" to .9f,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to .4f),
            gesture = OrigamiGesture.BLOOM, expression = OrigamiExpression.TOUCH,
        )

    private fun fixtures(spec: OrigamiPadMaker.Spec): List<List<KeyNote>> =
        OrigamiPadMaker.zoneMidis(spec).mapIndexed { zone, _ ->
            List(spec.layerCount) { layer ->
                KeyNote(Snip(FloatArray(512 + zone * 32 + layer * 64) { frame ->
                    (.12 * sin(2 * PI * frame / (32 + layer * 8))).toFloat()
                }, 1, rate), (64 + zone * 8 + layer * 4).toLong())
            }
        }

    private fun loaded(spec: OrigamiPadMaker.Spec, notes: List<KeyNote>): InstrumentEngine.Loaded {
        val windows = listOf(1..42, 43..84, 85..127)
        val layers = notes.mapIndexed { index, note ->
            InstrumentStore.Layer("touch$index.wav", note.snip.frameCount.toLong(), note.loopStartFrame,
                windows[index].first, windows[index].last)
        }
        val hard = layers.last()
        val zone = InstrumentStore.Zone(60, 60, 60, hard.sample, hard.frameCount, hard.loopStartFrame, layers)
        return InstrumentEngine.Loaded(InstrumentStore.Instrument("MY TOUCH PAPER", spec.releaseSeconds, listOf(zone)),
            rate, layers.mapIndexed { index, layer -> layer.sample to notes[index].snip.samples }.toMap())
    }

    @Test
    fun `TOUCH keys retain three relative captures the old hard endpoint and one live gain`() {
        val patch = authored()
        val spec = OrigamiPadMaker.Spec(patch, .2f)
        val notes = OrigamiPadMaker.renderVelocityZone(spec, 60)
        assertEquals(3, spec.layerCount)
        assertEquals(3, notes.size)
        assertEquals(listOf(.3f, .6f, 1f), OrigamiPadMaker.RELATIVE_VELOCITIES)
        val hard = notes.last()
        val oldHard = OrigamiKeys.note(patch.copy(expression = OrigamiExpression.FOLD), 60)
        assertEquals(oldHard.loopStartFrame, hard.loopStartFrame)
        assertContentEquals(oldHard.snip.samples, hard.snip.samples,
            "the .85 authored hard endpoint must retain the accepted FOLD capture")

        val bank = loaded(spec, notes)
        for ((index, relative) in listOf(.3f, .6f, 1f).withIndex()) {
            val note = notes[index]
            assertTrue(note.loopStartFrame in 1 until note.snip.frameCount.toLong())
            assertEquals(peak(hard.snip.samples), peak(note.snip.samples), 1e-6f)
            if (index < 2) {
                val source = OrigamiKeys.note(patch.copy(velocity = patch.velocity * relative), 60)
                assertEquals(source.loopStartFrame, note.loopStartFrame)
                assertContentEquals(Velocity.peakMatch(hard.snip, source.snip).samples, note.snip.samples,
                    "TOUCH layer $index must use .85 × $relative rather than an absolute velocity")
            }
            val hit = assertNotNull(KeyHit.resolve(bank.instrument, 60, relative) { file ->
                bank.layerSamples[file]?.size?.toLong()
            })
            assertEquals("touch$index.wav", hit.sampleFile)
            assertEquals(relative * InstrumentEngine.VOICE_LEVEL, hit.gain)
            val player = InstrumentEngine(bank, rate)
            assertTrue(player.noteOn(60, relative))
            val frames = minOf(8192, note.snip.frameCount)
            val out = FloatArray(frames * 2)
            player.render(out, frames)
            assertTrue((0 until frames).all { frame ->
                abs(out[frame * 2] - note.snip.samples[frame] * hit.gain) < 1e-6f &&
                    out[frame * 2] == out[frame * 2 + 1]
            }, "TOUCH PCM must receive the player's live gain exactly once")
        }
        assertTrue((0 until minOf(notes.first().snip.samples.size, hard.snip.samples.size)).sumOf {
            abs(notes.first().snip.samples[it].toDouble() - hard.snip.samples[it])
        } / minOf(notes.first().snip.samples.size, hard.snip.samples.size) > 1e-4,
            "peak matching must retain a real difference between soft and hard TOUCH captures")
        assertEquals(patch, spec.patch, "keyboard pitch and relative strikes cannot rewrite the saved source")
    }

    @Test
    fun `TOUCH SOFT HITS preserve complete preset and pad recipes beneath the saved ceiling`() {
        val temp = createTempDirectory("origami-touch-soft-hits").toFile()
        try {
            val patch = authored().copy(name = "SOFT TOUCH PAPER", midi = 60, velocity = .35f)
            val shelf = File(temp, "shelf")
            UserPresets.save(shelf, patch, nowMillis = 1701L)
            val restored = assertIs<OrigamiPatch>(UserPresets.read(shelf).single().patch)
            assertEquals(patch, restored)
            val savedFields = Json.parse(restored.toJsonText()).obj()
            assertEquals("TOUCH", savedFields.getValue("expression").obj().getValue("mode").str())
            assertEquals(1, savedFields.getValue("expression").obj().getValue("model").int())
            assertEquals(patch.seed.toString(), savedFields.getValue("seed").str())
            val line = UserPresets.rosterLine(restored)
            val prefix = "OrigamiPatch.fromJsonText("
            assertTrue(line.startsWith(prefix) && line.endsWith("),"), line)
            assertEquals(patch, OrigamiPatch.fromJsonText(Json.parse(line.removePrefix(prefix).removeSuffix("),")).str()))

            val recipe = PadRecipe.fromJsonText(PadRecipe(restored).toJsonText())
            val dir = File(temp, "kit")
            val model = KitBuilderModel.create("Soft Touch Paper", dir)
            model.assign(1, recipe.render(), DrumClass.TONAL, patch.name, knownRootMidi = 60)
            model.update(1) { it.copy(recipe = recipe.toJsonValue()) }
            model.save()
            val reopened = KitBuilderModel.open(dir)
            val original = assertNotNull(reopened.pad(1))
            assertEquals(recipe, PadRecipe.fromJsonValue(assertNotNull(original.recipe)))
            val mainFile = File(dir, original.sampleFile)
            val mainBytes = mainFile.readBytes()
            val main = WavReader.read(mainFile)
            reopened.addGhostLayers(1, softZones = 2)
            reopened.save()
            val pad = assertNotNull(KitBuilderModel.open(dir).pad(1))
            assertEquals(recipe, PadRecipe.fromJsonValue(assertNotNull(pad.recipe)))
            assertEquals(mapOf("rootMidi" to "60", "rootNote" to "C4", "rootOrigin" to "authored"), pad.source)
            assertEquals(listOf(1..41, 42..84, 85..127), pad.velocityLayers.map { it.velStart..it.velEnd })
            assertEquals(original.sampleFile, pad.velocityLayers.last().sampleFile)
            assertContentEquals(mainBytes, mainFile.readBytes(), "SOFT HITS must preserve its authored main sample")

            for ((index, relative) in listOf(.3f, .6f).withIndex()) {
                val expected = Velocity.peakMatch(main, patch.copy(velocity = patch.velocity * relative).render())
                val encoded = ByteArrayOutputStream().also {
                    WavWriter.write(it, expected, depth = WavWriter.BitDepth.PCM_24)
                }.toByteArray()
                assertContentEquals(encoded, File(dir, pad.velocityLayers[index].sampleFile).readBytes(),
                    "TOUCH SOFT HITS must use .35 × $relative and one color application before PCM24")
            }
            val recordings = pad.velocityLayers.associate { layer ->
                layer.sampleFile to WavReader.read(File(dir, layer.sampleFile))
            }
            val full = assertNotNull(PadHit.resolve(pad, 1f, 0) { recordings[it]?.frameCount?.toLong() })
            for ((index, relative) in listOf(.3f, .6f, 1f).withIndex()) {
                val hit = assertNotNull(PadHit.resolve(pad, relative, 0) { recordings[it]?.frameCount?.toLong() })
                assertEquals(pad.velocityLayers[index].sampleFile, hit.sampleFile)
                assertEquals(full.gainLeft * relative, hit.gainLeft, 1e-6f)
                assertEquals(full.gainRight * relative, hit.gainRight, 1e-6f)
                assertEquals(-1L, hit.loopStartFrame)
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `TOUCH exports keep twenty seven sampler sheets and complete held recipe in actual packages`() {
        val dir = createTempDirectory("origami-touch-export").toFile()
        try {
            val spec = OrigamiPadMaker.Spec(authored(OrigamiVoice.STILL)
                .copy(velocity = .61f, gesture = OrigamiGesture.UNFOLD), .73f)
            val notes = fixtures(spec)
            assertEquals(3, spec.layerCount)
            val refusal = assertFailsWith<IllegalArgumentException> {
                OrigamiPadMaker.export("MY TOUCH PAPER", spec, notes.map { it.last() }, File(dir, "refused"))
            }
            assertTrue(refusal.message.orEmpty().contains("TOUCH expression"))
            assertFalse(File(dir, "refused").exists())
            val program = OrigamiPadMaker.exportLayers("MY TOUCH PAPER", spec, notes, dir)
            val data = File(dir, Mpc3TrackWriter.trackDataDirName(program.name))
            assertEquals(27, data.listFiles()!!.count { it.extension == "wav" })
            val instrument = InstrumentStore.read(InstrumentStore.sidecar(dir, program.name))
            assertEquals(27, instrument.sampleFiles.size)
            assertEquals(.73f, instrument.release)
            assertEquals(0, instrument.zones.first().lowNote)
            assertEquals(127, instrument.zones.last().highNote)
            val native = Mpc3Project.read(File(dir, "${program.name}.xty")).tracks.single()
                .getValue("program").obj().getValue("drum").obj().getValue("instruments").arr().take(9)
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val xmlZones = factory.newDocumentBuilder().parse(File(data, "${program.name}.xpm"))
                .getElementsByTagName("Instrument")
            assertEquals(9, xmlZones.length)
            for ((zoneIndex, zone) in instrument.zones.withIndex()) {
                assertEquals(OrigamiPadMaker.zoneMidis(spec)[zoneIndex], zone.rootNote)
                assertEquals(listOf(1..42, 43..84, 85..127), zone.layers.map { it.velStart..it.velEnd })
                val nativeZone = native[zoneIndex].obj()
                assertEquals(zone.lowNote, nativeZone.getValue("lowNote").int())
                assertEquals(zone.highNote, nativeZone.getValue("highNote").int())
                val xmlZone = xmlZones.item(zoneIndex) as Element
                assertEquals(zone.lowNote, xmlZone.getElementsByTagName("LowNote").item(0).textContent.toInt())
                assertEquals(zone.highNote, xmlZone.getElementsByTagName("HighNote").item(0).textContent.toInt())
                val nativeLayers = nativeZone.getValue("layersv").arr().take(3)
                val xmlLayers = xmlZone.getElementsByTagName("Layer")
                for ((layerIndex, layer) in zone.layers.withIndex()) {
                    val note = notes[zoneIndex][layerIndex]
                    val wav = File(dir, layer.sample)
                    assertEquals(SmplChunk(zone.rootNote, SmplChunk.Loop(note.loopStartFrame,
                        note.snip.frameCount.toLong())), WavReader.readSmpl(wav))
                    val expected = ByteArrayOutputStream().also {
                        WavWriter.write(it, note.snip, smpl = SmplChunk(zone.rootNote,
                            SmplChunk.Loop(note.loopStartFrame, note.snip.frameCount.toLong())))
                    }.toByteArray()
                    assertContentEquals(expected, wav.readBytes())
                    val nativeLayer = nativeLayers[layerIndex].obj()
                    val slice = nativeLayer.getValue("sliceInfo").obj()
                    assertEquals(layer.sample.substringAfterLast('/').removeSuffix(".wav"), nativeLayer.getValue("sampleName").str())
                    assertEquals(zone.rootNote, nativeLayer.getValue("rootNote").int())
                    assertEquals(note.loopStartFrame, slice.getValue("LoopStart").long())
                    assertEquals(note.snip.frameCount.toLong(), slice.getValue("End").long())
                    assertEquals(1, slice.getValue("LoopMode").int())
                    val xmlLayer = xmlLayers.item(layerIndex) as Element
                    assertEquals(note.loopStartFrame, xmlLayer.getElementsByTagName("SliceLoopStart").item(0).textContent.toLong())
                    assertEquals(note.snip.frameCount.toLong(), xmlLayer.getElementsByTagName("SliceEnd").item(0).textContent.toLong())
                }
            }
            val savedText = OrigamiPadMaker.recipeFile(dir, program.name).readText()
            assertEquals(spec, OrigamiPadMaker.Spec.fromJsonText(savedText))
            val fields = Json.parse(savedText).obj()
            assertEquals(1, fields.getValue("version").int())
            assertEquals(1, fields.getValue("heldModel").int())
            assertEquals(spec.patch.toJsonValue(), fields.getValue("patch"))
            assertEquals("TOUCH", fields.getValue("patch").obj().getValue("expression").obj().getValue("mode").str())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun peak(samples: FloatArray): Float = samples.maxOf { abs(it) }
}
