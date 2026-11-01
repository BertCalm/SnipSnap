package com.snipsnap.synth

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drum captures and true held keygroups must remain different, replayable export shapes. */
class MeridianKitsTest {
    @Test
    fun `the sixteen-pad bank walks native minor-pentatonic notes then retains the eight presets with held material last`() {
        val presets = MeridianPresets.all()
        val patches = MeridianKits.patches()
        val notes = listOf(60, 63, 65, 67, 70, 72, 75, 77)
        assertEquals(8, presets.size)
        assertEquals(16, patches.size)
        val first = presets.first { !Meridian.isLoop(it.macros, it.model) }
        assertEquals(notes, patches.take(8).map { it.midi })
        assertEquals(notes, patches.take(8).map {
            Meridian.midiFor(it.voice, it.macros["TUNE"] ?: .5f, it.midi)
        }, "the native note walk must not inherit a second TUNE transposition")
        for (patch in patches.take(8)) {
            assertEquals(first.voice, patch.voice)
            assertEquals(first.macros + ("TUNE" to .5f), patch.macros)
            assertEquals(first.seedContext, patch.seedContext)
            assertFalse(Meridian.isLoop(patch.macros, patch.model))
        }
        assertTrue(patches.take(8).map { it.velocity }.distinct().size >= 3,
            "the scale row needs meaningful authored excitation dynamics")
        assertTrue(patches.take(8).maxOf { it.velocity } - patches.take(8).minOf { it.velocity } >= .2f)
        assertEquals(presets, patches.drop(8), "the second half must preserve the complete preset recipes")
        val held = patches.drop(8).map { Meridian.isLoop(it.macros, it.model) }
        assertEquals(held.sorted(), held, "held material belongs after the finite presets")
        assertTrue(held.last(), "the bank needs a real held example")
        assertTrue(patches.all { it.model == 2 && it.velocity.isFinite() && it.velocity in 0f..1f })
        for (patch in patches) {
            val recipe = MeridianKits.sourceRecipe(patch)
            assertEquals(patch, recipe.patch)
            assertNull(recipe.fx)
            assertNull(recipe.board)
            assertEquals(if (Meridian.isLoop(patch.macros, patch.model)) 20f else null, recipe.terminalFadeMs,
                "drum pads need a finite ending while their separately exported source remains held")
        }
    }

    @Test
    fun `native MPC twins retain finite kit captures and separate held keygroups retain real sampler loops`() {
        val root = createTempDirectory("meridian-kit").toFile()
        try {
            val archive = MeridianKitGenerator.generate(root)
            val destination = File(root, MeridianKits.NAME)
            val data = File(destination, "${MeridianKits.NAME}_[TrackData]")
            val kit = KitStore.load(data)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertFalse(Preflight.check(kit, data).blocked())
            val patches = MeridianKits.patches()
            val program = Mpc3Project.read(File(destination, "${MeridianKits.NAME}.xtd"))
            assertTrue(program.isTrack)
            assertEquals(0, program.programs().single().getValue("type").int())
            val instruments = program.drumPrograms().single().getValue("instruments").arr()
            val xml = xml(File(data, "${MeridianKits.NAME}.xpm"))
            val xmlInstruments = xml.getElementsByTagName("Instrument")
            for ((index, pad) in kit.pads.withIndex()) {
                val patch = patches[index]
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                assertEquals(MeridianKits.sourceRecipe(patch), recipe)
                assertEquals(recipe, PadRecipe.fromJsonText(File(destination, "recipes/A%02d.json".format(pad.slot)).readText()))
                assertEquals(patch, assertIs<MeridianPatch>(recipe.patch))
                assertEquals(Meridian.drumClassFor(patch.voice, patch.macros, patch.model), pad.drumClass)
                assertTrue(pad.oneShot, "a drum pad is an honest finite capture, including a held source")
                assertEquals(0, pad.muteGroup)
                assertTrue(pad.velocityLayers.isEmpty())
                assertNull(pad.chain)
                val sample = File(data, pad.sampleFile)
                val sheet = assertNotNull(WavReader.readSmpl(sample))
                assertEquals(Meridian.midiFor(patch.voice, patch.macros["TUNE"] ?: .5f, patch.midi), sheet.rootNote)
                assertNull(sheet.loop, "kit pad ${pad.slot} must not pretend a drum program sustains its source")
                val replay = recipe.render()
                assertEquals(1, replay.channels)
                assertEquals(Dsp.RATE, replay.sampleRate)
                assertTrue(replay.samples.all { it.isFinite() })
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, replay, smpl = sheet) }
                assertContentEquals(encoded.toByteArray(), sample.readBytes(), "pad ${pad.slot} cannot replay its native PCM24 and sampler root")
                val info = WavInfo.read(sample)
                assertEquals(24, info.bitsPerSample)
                assertEquals(replay.frameCount.toLong(), info.frameCount)
                val instrument = instruments[index].obj()
                assertEquals(0, instrument.getValue("triggerMode").int())
                val layer = instrument.getValue("layersv").arr().first().obj()
                assertEquals(pad.sampleFile, layer.getValue("sampleFile").str())
                assertEquals(0, layer.getValue("sliceInfo").obj().getValue("LoopMode").int())
                val xmlInstrument = xmlInstruments.item(index) as Element
                assertEquals("True", xmlInstrument.text("OneShot"))
                assertEquals("0", xmlInstrument.text("SliceLoop"))
            }

            val manifest = Json.parse(File(destination, "kit-manifest.json").readText()).obj()
            assertEquals(16, manifest.getValue("padCount").int())
            val heldInstruments = manifest.getValue("heldInstruments").arr()
            assertEquals(patches.drop(8).count { Meridian.isLoop(it.macros, it.model) }, heldInstruments.size)
            assertTrue(heldInstruments.isNotEmpty())
            for (entry in heldInstruments) {
                val heldInstrument = entry.obj()
                val zones = heldInstrument.getValue("zones").arr()
                assertEquals(13, zones.size, "each held preset needs native minor-third zones from C2 to C5")
                assertEquals((36..72 step 3).toList(), zones.map { it.obj().getValue("midi").int() })
                val heldProgram = Mpc3Project.read(File(destination, heldInstrument.getValue("programFile").str()))
                assertEquals(1, heldProgram.programs().single().getValue("type").int())
                val heldSlots = heldProgram.drumPrograms().single().getValue("instruments").arr()
                val heldXml = xml(File(destination, heldInstrument.getValue("mpc2ProgramFile").str()))
                val heldXmlSlots = heldXml.getElementsByTagName("Instrument")
                for ((zoneIndex, zoneValue) in zones.withIndex()) {
                    val zone = zoneValue.obj()
                    val midi = zone.getValue("midi").int()
                    // The manifest records the rendered zones; the written program spans the
                    // keyboard, its edge zones running to 0 and 127 so the sampler pitch-shifts
                    // from the nearest root, as Akai's own keygroups do.
                    val lowNote = if (zoneIndex == 0) 36 else midi - 1
                    val highNote = if (zoneIndex == zones.lastIndex) 72 else midi + 1
                    assertEquals(lowNote, zone.getValue("lowNote").int())
                    assertEquals(highNote, zone.getValue("highNote").int())
                    val slot = heldSlots[zoneIndex].obj()
                    val spannedLow = if (zoneIndex == 0) 0 else lowNote
                    val spannedHigh = if (zoneIndex == zones.lastIndex) 127 else highNote
                    assertEquals(spannedLow, slot.getValue("lowNote").int())
                    assertEquals(spannedHigh, slot.getValue("highNote").int())
                    assertEquals(2, slot.getValue("triggerMode").int(), "held keygroup zones must obey note release")
                    val layers = slot.getValue("layersv").arr().map { it.obj() }
                        .filter { it.getValue("sampleName").str().isNotEmpty() }
                    assertEquals(3, layers.size)
                    assertEquals(listOf(85, 43, 0), layers.map { it.getValue("velocityStart").int() },
                        "MPC 3 velocity zones descend loudest first")
                    val xmlSlot = heldXmlSlots.item(zoneIndex) as Element
                    assertEquals(spannedLow.toString(), xmlSlot.text("LowNote"))
                    assertEquals(spannedHigh.toString(), xmlSlot.text("HighNote"))
                    val xmlNodes = xmlSlot.getElementsByTagName("Layer")
                    val xmlLayers = (0 until xmlNodes.length).map { xmlNodes.item(it) as Element }
                        .filter { it.text("SampleName").isNotEmpty() }.associateBy { it.text("SampleName") }
                    assertEquals(3, xmlLayers.size)
                    val amp = slot.getValue("synthSection").obj().getValue("ampEnvelope").obj()
                    assertFalse(amp.getValue("OneShot").obj().getValue("value0").bool())
                    assertEquals(1.0, amp.getValue("Sustain").obj().getValue("value0").num())
                    assertTrue(amp.getValue("Release").obj().getValue("value0").num() > 0)
                    assertEquals(1.0, xmlSlot.text("VolumeSustain").toDouble())
                    assertTrue(xmlSlot.text("VolumeRelease").toDouble() > 0)
                    val savedLayers = zone.getValue("layers").arr()
                    assertEquals(3, savedLayers.size)
                    assertEquals(listOf(.3f, .65f, 1f), savedLayers.map { it.obj().getValue("nativeVelocity").num().toFloat() })
                    assertEquals(listOf(0, 43, 85), savedLayers.map { it.obj().getValue("velStart").int() })
                    assertEquals(listOf(42, 84, 127), savedLayers.map { it.obj().getValue("velEnd").int() })
                    for ((layerIndex, savedLayerValue) in savedLayers.withIndex()) {
                        val savedLayer = savedLayerValue.obj()
                        val sample = File(destination, savedLayer.getValue("sampleFile").str())
                        val sheet = assertNotNull(WavReader.readSmpl(sample))
                        val loop = assertNotNull(sheet.loop, "a held package needs an actual WAV sustain loop")
                        assertEquals(midi, sheet.rootNote)
                        assertEquals(savedLayer.getValue("loopStartFrame").long(), loop.startFrame)
                        assertEquals(savedLayer.getValue("loopEndFrameExclusive").long(), loop.endFrameExclusive)
                        val decoded = WavReader.read(sample)
                        assertTrue(loop.startFrame >= 256)
                        assertEquals(decoded.frameCount.toLong(), loop.endFrameExclusive)
                        assertEquals(2 * loop.startFrame, loop.endFrameExclusive,
                            "the real preceding cycle must remain available for exported seam evidence")
                        assertTrue(Keys.seamError(decoded.samples, loop.startFrame.toInt()) < Keys.MAX_SEAM_ERROR,
                            "encoded held zone $zoneIndex layer $layerIndex lost its real previous-cycle seam")
                        val layer = layers.single { it.getValue("sampleName").str() == sample.nameWithoutExtension }
                        assertEquals(midi, layer.getValue("rootNote").int())
                        assertEquals(savedLayer.getValue("velStart").int(), layer.getValue("velocityStart").int())
                        assertEquals(savedLayer.getValue("velEnd").int(), layer.getValue("velocityEnd").int())
                        val slice = layer.getValue("sliceInfo").obj()
                        assertEquals(1, slice.getValue("LoopMode").int())
                        assertEquals(loop.startFrame, slice.getValue("LoopStart").long())
                        assertEquals(loop.endFrameExclusive, slice.getValue("End").long())
                        val xmlLayer = xmlLayers.getValue(sample.nameWithoutExtension)
                        assertEquals(midi.toString(), xmlLayer.text("RootNote"))
                        assertEquals(savedLayer.getValue("velStart").int().toString(), xmlLayer.text("VelStart"))
                        assertEquals(savedLayer.getValue("velEnd").int().toString(), xmlLayer.text("VelEnd"))
                        assertEquals("1", xmlLayer.text("SliceLoop"))
                        assertEquals(loop.startFrame.toString(), xmlLayer.text("SliceLoopStart"))
                        assertEquals(loop.endFrameExclusive.toString(), xmlLayer.text("SliceEnd"))

                        if (zoneIndex == 0 && layerIndex == 0 || zoneIndex == zones.lastIndex && layerIndex == savedLayers.lastIndex) {
                            val recipe = PadRecipe.fromJsonText(File(destination, savedLayer.getValue("recipeFile").str()).readText())
                            val patch = assertIs<MeridianPatch>(recipe.patch)
                            assertTrue(Meridian.isLoop(patch.macros, patch.model))
                            assertNull(recipe.fx)
                            assertNull(recipe.terminalFadeMs, "instrument recipes must retain their seamless held source")
                            assertEquals(midi, Meridian.midiFor(patch.voice, patch.macros["TUNE"] ?: .5f, patch.midi))
                            assertEquals(savedLayer.getValue("nativeVelocity").num().toFloat(), patch.velocity)
                            val held = Meridian.renderHeld(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seedContext)
                            assertEquals(held.loopStartFrame.toLong(), loop.startFrame)
                            assertEquals(held.loopEndFrameExclusive.toLong(), loop.endFrameExclusive)
                            val encoded = ByteArrayOutputStream().also {
                                WavWriter.write(it, held.snip, smpl = SmplChunk(midi, loop))
                            }
                            assertContentEquals(encoded.toByteArray(), sample.readBytes(),
                                "held endpoint layers must replay at their native note, velocity and seed")
                        }
                    }
                }
                val instrumentArchive = File(destination, heldInstrument.getValue("archiveFile").str())
                assertTrue(instrumentArchive.isFile)
                assertTrue(instrumentArchive.length() < 25_000_000L,
                    "each independently downloadable held instrument must stay below 25 MB")
                ZipFile(instrumentArchive).use { zip ->
                    val names = zip.entries().asSequence().map { it.name }.toList()
                    assertEquals(39, names.count { it.endsWith(".wav") })
                    assertEquals(39, names.count { it.startsWith("${instrumentArchive.nameWithoutExtension.removePrefix("SnipSnap-Meridian-")}/recipes/") })
                    assertEquals(1, names.count { it.endsWith(".xty") })
                    assertEquals(1, names.count { it.endsWith(".xpm") })
                    assertFalse(names.any { ".." in it.split('/') })
                    val packageManifestName = names.single { it.endsWith("/instrument-manifest.json") }
                    val packageBase = packageManifestName.substringBeforeLast('/')
                    val packageManifest = Json.parse(zip.getInputStream(zip.getEntry(packageManifestName))
                        .bufferedReader().use { it.readText() }).obj()
                    for (field in listOf("programFile", "mpc2ProgramFile")) {
                        assertNotNull(zip.getEntry("$packageBase/${packageManifest.getValue(field).str()}"),
                            "a separately extracted keyboard must resolve its own program links")
                    }
                    for (zone in packageManifest.getValue("zones").arr()) {
                        for (layer in zone.obj().getValue("layers").arr()) {
                            for (field in listOf("sampleFile", "recipeFile")) {
                                assertNotNull(zip.getEntry("$packageBase/${layer.obj().getValue(field).str()}"),
                                    "a separately extracted keyboard must resolve its own $field")
                            }
                        }
                    }
                    val firstSample = zones.first().obj().getValue("layers").arr().first().obj()
                        .getValue("sampleFile").str()
                    val entryName = firstSample.removePrefix("held/")
                    assertContentEquals(File(destination, firstSample).readBytes(),
                        zip.getInputStream(zip.getEntry(entryName)).use { it.readBytes() })
                }
            }
            assertTrue(archive.length() < 25_000_000L, "the independently downloadable finite-pad kit must stay below 25 MB")
            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(names.toSet().size, names.size)
                assertTrue(names.all { it.startsWith("${MeridianKits.NAME}/") && ".." !in it.split('/') })
                assertTrue("${MeridianKits.NAME}/${MeridianKits.NAME}.xtd" in names)
                assertTrue("${MeridianKits.NAME}/${data.name}/${MeridianKits.NAME}.xpm" in names)
                assertEquals(16, names.count { it.endsWith(".wav") })
                assertFalse(names.any { "/held/" in it }, "held instruments travel in their separate size-bounded archives")
                for (pad in kit.pads) {
                    val name = "${MeridianKits.NAME}/${data.name}/${pad.sampleFile}"
                    assertContentEquals(File(data, pad.sampleFile).readBytes(), zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun xml(file: File): Document = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file)

    private fun Element.text(tag: String): String = requireNotNull(getElementsByTagName(tag).item(0)) {
        "missing $tag in $tagName"
    }.textContent
}
