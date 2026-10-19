package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.XpmWriter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OnsetIntegrationTest {
    private val temp = Files.createTempDirectory("onset-integration").toFile()

    @AfterTest
    fun cleanUp() { temp.deleteRecursively() }

    @Test
    fun `dry audition kit retains exact raw bytes and recipes through save edit and both MPC formats`() {
        val source = File(temp, "source")
        val patches = OnsetKits.patches()
        val kit = KitAssembler.assembleArranged("ONSET Acceptance", OnsetKits.acceptance(), source)
        assertEquals(16, kit.pads.size)
        assertEquals(kit, KitStore.load(source))
        assertEquals(listOf("KICK", "SNARE", "HAT_CLOSED", "HAT_OPEN"), patches.take(4).map { it.voiceName })
        assertEquals(listOf(0f, .25f, .5f, 1f), patches.chunked(4).map { it.first().macros.getValue("BLEND") })

        // Make the same boundary the audition uses: delivered PCM24, its source
        // hash and complete saved settings. The exporter must never encode this
        // decoded PCM a second time or attach a merely approximate recipe.
        val clips = kit.pads.mapIndexed { index, pad ->
            val patch = patches[index]
            assertEquals(patch, PadRecipe.fromJsonValue(assertNotNull(pad.recipe)).patch)
            obj(
                "kind" to str("hit"), "voice" to str(patch.voiceName),
                "blend" to num(patch.macros.getValue("BLEND").toDouble()),
                "raw" to str(pad.sampleFile),
                "sha256" to obj("raw" to str(hash(File(source, pad.sampleFile)))),
                "audition" to obj("rawExportGain" to num(1.0)),
                "settings" to obj(
                    "voice" to str(patch.voiceName), "modelVersion" to num(patch.modelVersion.toDouble()),
                    "macros" to assertNotNull(patch.toJsonValue().entries["macros"]),
                ),
            )
        }
        val manifest = File(source, "manifest.json").also { writeManifest(it, clips) }
        val settings = File(source, "settings.json").also { it.writeText(manifest.readText()) }
        val delivered = kit.pads.mapIndexed { index, pad ->
            (patches[index].voice to patches[index].macros.getValue("BLEND")) to WavReader.read(File(source, pad.sampleFile))
        }.toMap()
        val archive = OnsetKitExport.write(source, delivered, settings)
        val unpacked = File(temp, "unpacked")
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = File(unpacked, entry.name)
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        val exportedRoot = File(unpacked, "SnipSnap ONSET Audition")
        val data = File(exportedRoot, Mpc3TrackWriter.trackDataDirName(exportedRoot.name))
        val restored = KitStore.load(data)
        assertEquals(16, restored.pads.size)
        for ((index, pad) in restored.pads.withIndex()) {
            val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            assertEquals(patches[index], recipe.patch)
            assertEquals(1f, pad.level)
            assertTrue(pad.oneShot)
            assertEquals(if (index % 4 >= 2) 1 else 0, pad.muteGroup)
            assertContentEquals(File(source, kit.pads[index].sampleFile).readBytes(), File(data, pad.sampleFile).readBytes())
        }
        assertPrograms(exportedRoot, restored, data)

        // Reopen the midpoint row, edit all four voice recipes through the
        // common dispatcher, regenerate their WAVs and save the kit again.
        // The exported source samples remain untouched in the other rows.
        val changedSlots = (9..12).toSet()
        val changed = restored.copy(pads = restored.pads.map { pad ->
            if (pad.slot !in changedSlots) return@map pad
            val saved = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            val oldPatch = saved.patch as OnsetPatch
            val edited = Patches.edited(oldPatch, "Edited ${oldPatch.voiceName}", oldPatch.macros + ("BLEND" to 1f) + ("DECAY" to .6f))
            assertEquals(oldPatch.modelVersion, (edited as OnsetPatch).modelVersion)
            val recipe = saved.copy(patch = edited)
            val rendered = PadRecipe.fromJsonText(recipe.toJsonText()).render()
            val sample = File(data, pad.sampleFile)
            val oldBytes = sample.readBytes()
            WavWriter.write(sample, rendered)
            assertFalse(oldBytes.contentEquals(sample.readBytes()), "macro edit must change ${oldPatch.voiceName} PCM")
            pad.copy(displayName = edited.name, recipe = recipe.toJsonValue())
        })
        KitStore.save(changed, data)
        val reopened = KitStore.load(data)
        assertEquals(changed, reopened)
        for (pad in reopened.pads.filter { it.slot in changedSlots }) {
            val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            val regenerated = WavWriter.write(File(temp, "regenerated-${pad.slot}.wav"), recipe.render())
            assertContentEquals(regenerated.readBytes(), File(data, pad.sampleFile).readBytes())
        }
        val editedRoot = File(temp, "edited")
        val editedData = File(editedRoot, Mpc3TrackWriter.trackDataDirName(reopened.name))
        val program = Mpc3Exporter.stageTrack(reopened, data, editedData).copy(level = 1f)
        Mpc3TrackWriter().writeTo(editedRoot, program)
        XpmWriter().writeTo(editedData, program.copy(pads = program.pads.map { it?.copy(decay = 0f) }))
        KitStore.save(reopened, editedData)
        assertEquals(reopened, KitStore.load(editedData))
        for (pad in reopened.pads) {
            assertContentEquals(File(data, pad.sampleFile).readBytes(), File(editedData, pad.sampleFile).readBytes())
        }
        assertPrograms(editedRoot, reopened, editedData)

        // A bare patch must not be attached to an attenuated or mislabeled
        // listening file. Rejection also leaves the valid download intact.
        val validArchive = archive.readBytes()
        val first = clips.first().entries
        writeManifest(manifest, listOf(JsonValue.Obj(first + ("audition" to obj("rawExportGain" to num(.5))))) + clips.drop(1))
        assertFailsWith<IllegalArgumentException> { OnsetKitExport.write(source, delivered, settings) }
        val firstSettings = first.getValue("settings").obj()
        val wrongMacros = firstSettings.getValue("macros").obj() + ("BODY" to num(.99))
        val wrongSettings = JsonValue.Obj(firstSettings + ("macros" to JsonValue.Obj(wrongMacros)))
        writeManifest(manifest, listOf(JsonValue.Obj(first + ("settings" to wrongSettings))) + clips.drop(1))
        assertFailsWith<IllegalStateException> { OnsetKitExport.write(source, delivered, settings) }
        assertContentEquals(validArchive, archive.readBytes())
    }

    /** Read the actual formats: full windows, unity levels, cross-row choke and one-shot tails. */
    private fun assertPrograms(root: File, kit: Kit, samples: File) {
        val track = Mpc3Project.read(File(root, "${kit.name}.xtd"))
        assertTrue(track.isTrack)
        assertEquals(1.0, track.programs().single().getValue("mixable").obj().getValue("volume").num())
        val instruments = track.drumPrograms().single().getValue("instruments").arr().map { it.obj() }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(samples, "${kit.name}.xpm"))
        val xmlInstruments = document.getElementsByTagName("Instrument")
        assertEquals(16, xmlInstruments.length)
        for (pad in kit.pads) {
            val native = instruments[pad.slot - 1]
            val xml = xmlInstruments.item(pad.slot - 1) as Element
            fun value(tag: String) = xml.getElementsByTagName(tag).item(0).textContent
            val frames = WavReader.read(File(samples, pad.sampleFile)).frameCount.toLong()
            assertEquals(pad.muteGroup, native.getValue("whichMuteGroup").int())
            assertEquals(pad.muteGroup, value("MuteGroup").toInt())
            assertEquals(0, native.getValue("triggerMode").int())
            assertEquals("True", value("OneShot"))
            assertEquals(1.0, native.getValue("mixable").obj().getValue("volume").num())
            assertEquals(1.0, value("Volume").toDouble())
            assertEquals(frames, native.getValue("layersv").arr().first().obj().getValue("sliceInfo").obj().getValue("End").long())
            assertEquals(frames, value("SliceEnd").toLong())
            val envelope = native.getValue("synthSection").obj().getValue("ampEnvelope").obj()
            assertEquals(1.0, envelope.getValue("Decay").obj().getValue("value0").num())
            assertEquals(1.0, envelope.getValue("Sustain").obj().getValue("value0").num())
            assertEquals(0.0, value("VolumeDecay").toDouble())
            assertEquals(1.0, value("VolumeSustain").toDouble())
        }
    }

    private fun writeManifest(file: File, clips: List<JsonValue>) =
        file.writeText(Json.write(obj("clips" to JsonValue.Arr(clips))))

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun obj(vararg values: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*values))
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Double) = JsonValue.Num(value)
}
