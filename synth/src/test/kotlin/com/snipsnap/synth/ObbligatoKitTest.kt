package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Importer
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.MpcXRay
import com.snipsnap.mpc3.SafeXml
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/** Verify the actual kit generator and written MPC assets, including their native velocity zones. */
class ObbligatoKitTest {
    private val notes = listOf(60, 63, 65, 67)
    private val relationships = listOf("Harmony" to 0f, "Play" to (1f / 7f), "Fight" to (3f / 7f), "Protect" to (6f / 7f))

    @Test
    fun `the starter matrix and shared preset dispatcher retain the accepted model five duet`() {
        val patches = ObbligatoKits.patches()
        assertEquals(16, patches.size)
        assertEquals(16, patches.map { it.name }.distinct().size)
        for ((row, relationship) in relationships.withIndex()) for ((column, midi) in notes.withIndex()) {
            val patch = patches[row * 4 + column]
            assertTrue(patch.name.startsWith(relationship.first + " "), "pad ${row * 4 + column + 1} names the wrong player relationship")
            assertEquals(midi, patch.midi)
            assertEquals(relationship.second, patch.macros.getValue("BOND"))
            assertEquals(5, patch.model, "the accepted audition must not change when a later model is introduced")
            assertEquals(.8f, patch.velocity)
            assertEquals(0, patch.seed)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        }
        val presets = ObbligatoPresets.all()
        assertEquals(presets, Presets.forVoice("OBBLIGATO", "AIR_REED"))
        assertTrue(Presets.all().containsAll(presets), "the shared preset browser omits the duet")
        assertTrue(Presets.forVoice("OBBLIGATO", "UNKNOWN").isEmpty())
        for (preset in presets) {
            assertEquals(preset, Presets.byName("OBBLIGATO", "AIR_REED", preset.name))
            assertNull(Presets.landingFor("OBBLIGATO", "AIR_REED", preset.macros), "the accepted duet is dry")
        }
    }

    /** Residual after fitting one gain: reject a filtered or frozen substitute for native excitation. */
    private fun gainInvariantError(expected: FloatArray, actual: FloatArray): Double {
        assertEquals(expected.size, actual.size)
        var referenceEnergy = 0.0
        var actualEnergy = 0.0
        var cross = 0.0
        for (i in expected.indices) {
            referenceEnergy += expected[i].toDouble() * expected[i]
            actualEnergy += actual[i].toDouble() * actual[i]
            cross += expected[i].toDouble() * actual[i]
        }
        require(referenceEnergy > 1e-10 && actualEnergy > 1e-10) { "native velocity fixture is silent" }
        val gain = cross / referenceEnergy
        var error = 0.0
        for (i in expected.indices) {
            val difference = actual[i] - gain * expected[i]
            error += difference * difference
        }
        return sqrt(error / actualEnergy)
    }

    @Test
    fun `generated kit recipes and native velocity samples survive MPC export loading and a short sequence`() {
        val temp = createTempDirectory("obbligato-kit").toFile()
        try {
            ObbligatoKitGenerator.main(arrayOf(temp.absolutePath))
            val destination = File(temp, "SnipSnap Obbligato Kit")
            val program = File(destination, "SnipSnap Obbligato Kit.xtd")
            val data = File(destination, "SnipSnap Obbligato Kit_[TrackData]")
            val twin = File(data, "SnipSnap Obbligato Kit.xpm")
            val kit = KitStore.load(data)
            assertEquals("SnipSnap Obbligato Kit", kit.name)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertEquals("C minor pentatonic", kit.key?.label)
            val findings = Preflight.check(kit, data)
            assertTrue(!findings.blocked(), "generated kit fails preflight: $findings")
            val wavs = data.listFiles().orEmpty().filter { it.extension == "wav" }
            assertEquals(48, wavs.size, "each pad needs two native soft layers and its main sample")

            for (pad in kit.pads) {
                val expectedPatch = ObbligatoKits.patches()[pad.slot - 1]
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = assertIs<ObbligatoPatch>(recipe.patch)
                assertEquals(expectedPatch, patch, "pad ${pad.slot} lost its note, seed, velocity, model or relationship")
                assertNull(recipe.fx)
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot, "finite duet notes must not become an unsupported sample loop")
                assertEquals(0, pad.muteGroup, "a new duet must not choke another relationship")
                assertEquals(0, pad.tuneCoarse, "the explicitly rendered note must not be transposed twice")
                assertEquals(0, pad.tuneFine)
                assertEquals(listOf(1 to 41, 42 to 84, 85 to 127), pad.velocityLayers.map { it.velStart to it.velEnd })
                assertEquals(listOf("0.3", "0.6", "0.8"), listOf("velocitySoft", "velocityMid", "velocityMain").map { pad.source[it] })
                assertEquals("peak-match-main", pad.source["velocityNormalization"])
                assertEquals("5", pad.source["model"])
                assertEquals(patch.midi.toString(), pad.source["midi"])
                assertEquals("0", pad.source["seed"])

                val main = WavReader.read(File(data, pad.sampleFile))
                val regenerated = recipe.render()
                assertEquals(regenerated.frameCount, main.frameCount)
                for (i in main.samples.indices) assertTrue(abs(regenerated.samples[i] - main.samples[i]) < 2.4e-7f,
                    "pad ${pad.slot} main WAV does not regenerate within 24-bit quantization at sample $i")
                val velocities = listOf(.3f, .6f, .8f)
                for ((index, layer) in pad.velocityLayers.withIndex()) {
                    val file = File(data, layer.sampleFile)
                    val info = WavInfo.read(file)
                    val decoded = WavReader.read(file)
                    assertEquals(44_100, info.sampleRate)
                    assertEquals(24, info.bitsPerSample)
                    assertEquals(1, info.channels)
                    assertEquals(main.frameCount.toLong(), info.frameCount)
                    assertTrue(decoded.samples.all { it.isFinite() && it in -1f..1f })
                    assertTrue(abs(decoded.peak() - main.peak()) < 2e-6f,
                        "pad ${pad.slot} native zone $index is not peak-matched for hardware velocity")
                    val native = if (index == 2) regenerated else patch.copy(velocity = velocities[index]).render()
                    assertTrue(gainInvariantError(native.samples, decoded.samples) < 2e-5,
                        "pad ${pad.slot} zone $index loses native excitation or saved source state")
                    if (index < 2) assertTrue(gainInvariantError(main.samples, decoded.samples) > .005,
                        "pad ${pad.slot} zone $index changes only level rather than native excitation")
                }
            }

            for ((format, primary) in listOf(ExportFormat.MPC3_TRACK to program, ExportFormat.PROGRAM_FOLDER to data)) {
                val checks = ExportReadBack.verify(kit, ExportOutcome(format, primary, data, emptyList()))
                assertTrue(!checks.blocked(), "$format disagrees with saved pad metadata: $checks")
            }
            for ((file, xmlSlots) in listOf(program to false, twin to true)) {
                val reading = MpcXRay.read(file)
                assertNull(reading.unreadable, "${file.name} will not parse")
                val saved = reading.programs.single()
                assertEquals(kit.name, saved.trackName)
                assertTrue(!saved.isKeygroup)
                val populated = saved.pads.filter { it.hasSample }
                assertEquals((1..16).toList(), populated.map { if (xmlSlots) it.slot + 1 else it.slot })
                for (pad in populated) {
                    val layers = pad.layers.filter { it.sampleName != null }
                    assertEquals(listOf(1 to 41, 42 to 84, 85 to 127), layers.map { it.velocityStart to it.velocityEnd })
                    for (layer in layers) {
                        val reference = requireNotNull(layer.sampleName)
                        assertTrue('/' !in reference && '\\' !in reference, "sample reference must resolve by bare name")
                        val wav = File(data, reference.removeSuffix(".wav") + ".wav")
                        assertTrue(wav.isFile, "${file.name} references a missing velocity sample: $reference")
                    }
                }
            }
            verifyXmlFrameCounts(twin, data)

            val restored = Mpc3Importer.import(program, File(temp, "imported"))
            assertEquals((1..16).toList(), restored.kit.pads.map { it.slot })
            for (pad in kit.pads) {
                val loaded = requireNotNull(restored.kit.pad(pad.slot))
                assertEquals(pad.velocityLayers, loaded.velocityLayers, "pad ${pad.slot} velocity windows changed on load")
                assertEquals(pad.oneShot, loaded.oneShot)
                assertEquals(pad.muteGroup, loaded.muteGroup)
                for (layer in pad.velocityLayers) assertContentEquals(File(data, layer.sampleFile).readBytes(),
                    File(restored.directory, layer.sampleFile).readBytes(), "loaded sample ${layer.sampleFile} changed")
            }
            // These high velocity hits all address the main zone. KitPreview is the
            // standard sample playback path; native soft timbres are verified above.
            val clip = Mpc3Clip("Duet rows", 1, listOf(1, 5, 9, 13).mapIndexed { i, slot ->
                Mpc3Note(Mpc3Note.noteFor(slot), i * 960L, .9f)
            })
            val before = KitPreview.render(kit, data, clip = clip, tempoBpm = 120f)
            val after = KitPreview.render(restored.kit, restored.directory, clip = clip, tempoBpm = 120f)
            assertEquals(2, before.channels)
            assertEquals(44_100, before.sampleRate)
            assertTrue(before.samples.all { it.isFinite() && it in -1f..1f })
            assertTrue(before.peak() > .01f)
            assertContentEquals(before.samples, after.samples, "loaded MPC kit plays a different short sequence")

            ZipFile(File(temp, "SnipSnap-Obbligato-Kit.zip")).use { zip ->
                val entries = zip.entries().toList().filter { !it.isDirectory }
                val files = destination.walkTopDown().filter { it.isFile }
                    .associateBy { destination.name + "/" + it.relativeTo(destination).invariantSeparatorsPath }
                assertEquals(files.keys, entries.map { it.name }.toSet(), "portable kit omits recipes or program assets")
                for (entry in entries) assertContentEquals(files.getValue(entry.name).readBytes(),
                    zip.getInputStream(entry).use { it.readBytes() }, "ZIP asset ${entry.name} differs from the exported kit")
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun verifyXmlFrameCounts(program: File, data: File) {
        val document = SafeXml.newBuilder().parse(program)
        val layers = document.getElementsByTagName("Layer")
        var checked = 0
        for (i in 0 until layers.length) {
            val layer = layers.item(i) as Element
            val sample = layer.getElementsByTagName("SampleName").item(0)?.textContent.orEmpty()
            if (sample.isBlank()) continue
            val end = layer.getElementsByTagName("SliceEnd").item(0).textContent.toLong()
            assertEquals(WavInfo.read(File(data, "$sample.wav")).frameCount, end,
                "$sample has a sample-end boundary that truncates or overruns its WAV")
            checked++
        }
        assertEquals(48, checked)
    }
}
