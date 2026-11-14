package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.Osteria
import com.snipsnap.synth.OsteriaPatch
import com.snipsnap.synth.OsteriaVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The browser bank's downloadable data must follow the app's actual receiving and hit paths. */
class OsteriaBankImportTest {
    private val temp = java.nio.file.Files.createTempDirectory("osteria-bank-import").toFile()
    private val velocities = listOf(.25f, .60f, 1f)
    private val windows = listOf(1 to 41, 42 to 84, 85 to 127)

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `a native hit applies authored velocity once and preserves each production voice balance`() {
        for (voice in OsteriaVoice.entries) {
            val base = patch("LEVEL CHECK", voice).copy(config = Osteria.configurationFor(voice).copy(
                seconds = 1.2, releaseSeconds = .3))
            val production = velocities.map { base.copy(velocity = it).render() }
            // The files carry timbre at the authored excitation. PadHit supplies
            // the runtime level, so the same velocity must not be baked twice.
            val prepared = production.mapIndexed { index, snip ->
                Snip(FloatArray(snip.samples.size) { snip.samples[it] / velocities[index] }, snip.channels, snip.sampleRate)
            }
            assertTrue(prepared.all { it.samples.all { sample -> sample.isFinite() && abs(sample) <= .990001f } })
            val directory = File(temp, "levels-${voice.name}")
            KitAssembler.assembleArranged("LEVEL CHECK", listOf(ArrangedPad(
                snip = prepared.last(), drumClass = DrumClass.TONAL,
                recipe = PadRecipe(patch = base).toJsonValue(), level = 1f,
                softVariants = prepared.dropLast(1), oneShot = true,
            )), directory)
            val pad = KitStore.load(directory).pads.single()
            assertEquals(windows, pad.velocityLayers.map { it.velStart to it.velEnd })
            for ((index, velocity) in velocities.withIndex()) {
                val hit = assertNotNull(PadHit.resolve(pad, velocity, 0) { filename ->
                    WavInfo.read(File(directory, filename)).frameCount
                })
                assertEquals(pad.velocityLayers[index].sampleFile, hit.sampleFile)
                val decoded = WavReader.read(File(directory, hit.sampleFile))
                assertEquals(production[index].samples.size, decoded.samples.size)
                var largestError = 0f
                for (sample in decoded.samples.indices) {
                    val gain = if (sample % 2 == 0) hit.gainLeft else hit.gainRight
                    largestError = maxOf(largestError, abs(decoded.samples[sample] * gain - production[index].samples[sample]))
                }
                // Production PCM includes Reed -1.5 dB and Pad +1.5 dB. Comparing
                // samples, rather than another gain formula, catches lost balance,
                // duplicate velocity attenuation and a wrong layer after decoding.
                assertTrue(largestError < .0000002f, "$voice velocity $velocity changed production PCM by $largestError")
            }
        }
    }

    @Test
    fun `a stored backup wrapper imports velocity layers and twelve complete editable user presets`() {
        val source = File(temp, "source")
        val tiny = Snip(FloatArray(4410) { index -> (.1 * kotlin.math.sin(index * .11)).toFloat() }, 1, 44_100)
        val padRecipe = PadRecipe(patch = patch("BANK PAD", OsteriaVoice.BOWED))
        val kit = KitAssembler.assembleArranged("OSTERIA IMPORT", listOf(ArrangedPad(
            tiny, DrumClass.TONAL, recipe = padRecipe.toJsonValue(), level = 1f,
            oneShot = true, softVariants = listOf(tiny, tiny),
        )), source)
        val exported = Mpc3Exporter.exportTrack(kit, source, File(temp, "mpc"), mpc2Twin = true)
        val data = exported.samples.first().parentFile
        // Native export writes its own sidecar with the exported sample names.
        // Import the delivered bank, not the pre-export source metadata.
        assertTrue(File(data, KitStore.FILE_NAME).isFile)
        val baseBank = File(temp, "mpc-bank.zip")
        ZipOutputStream(baseBank.outputStream()).use { zip ->
            exported.directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(exported.directory).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        // Use all six voices and non-default saved state: importing only macro
        // maps would silently lose the root, shore starts, seed or articulation.
        val names = listOf("ANCHOR BASS", "ROAMING BASS", "ROUNDED PLUCK", "BRIGHT PLUCK",
            "STILL REED", "REMEMBER REED", "WARM SWELL", "MEMORY DRIFT",
            "ANCHOR NOTE", "LINGER COLOR", "WARM BOW", "ROSIN BOW")
        val voices = listOf(OsteriaVoice.BASS, OsteriaVoice.BASS, OsteriaVoice.PLUCK, OsteriaVoice.PLUCK,
            OsteriaVoice.REED, OsteriaVoice.REED, OsteriaVoice.PAD, OsteriaVoice.PAD,
            OsteriaVoice.QUARTET, OsteriaVoice.QUARTET, OsteriaVoice.BOWED, OsteriaVoice.BOWED)
        val presets = names.mapIndexed { index, name ->
            patch(name, voices[index]).copy(midi = if (voices[index] == OsteriaVoice.BASS) 36 else 48,
                velocity = .8f, config = Osteria.configurationFor(voices[index]).copy(
                    initialShores = listOf(.2, 1.3, 2.7, 3.8), seed = 173 + index,
                    seconds = 5.2 + Osteria.configurationFor(voices[index]).releaseSeconds,
                    direction = if (index % 2 == 0) 1 else -1))
        }
        assertTrue(presets.all { UserPresets.check(it.name, it.engine, it.voiceName, emptyList()) is UserPresets.Check.Fresh })
        val library = Json.write(JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(UserPresets.VERSION.toDouble()),
            "presets" to JsonValue.Arr(presets.map { patch -> JsonValue.Obj(linkedMapOf(
                "savedAt" to JsonValue.Num(0.0), "patch" to patch.toJsonValue(),
            )) }),
        )))
        val backup = File(temp, "snipsnap-bank.zip")
        ZipOutputStream(backup.outputStream()).use { zip ->
            for ((name, bytes) in listOf("Osteria-Six-Voice.xpn" to baseBank.readBytes(), "presets.json" to library.toByteArray())) {
                // The web page can wrap a verified bank Blob without recompressing
                // its WAVs. This exercises the same ZIP_STORED receiving shape.
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong(); compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }

        val shelf = File(temp, "shelf")
        val landed = ShelfImport.land(backup, backup.name, shelf)
        assertTrue(landed.skipped.isEmpty(), landed.skipped.toString())
        assertEquals(12, landed.presets)
        val (_, landedDirectory) = landed.kits.single()
        val landedKit = KitStore.load(landedDirectory)
        val landedPad = landedKit.pads.single()
        assertEquals(windows, landedPad.velocityLayers.map { it.velStart to it.velEnd })
        assertTrue(landedPad.oneShot)
        assertTrue(landedPad.velocityLayers.all { File(landedDirectory, it.sampleFile).isFile })
        assertEquals(presets, UserPresets.read(shelf).map { it.patch })
        // The exported sidecar preserves its bound source recipe alongside the
        // layered audio. YOURS separately carries all twelve editable presets.
        assertEquals(padRecipe.toJsonValue(), landedPad.recipe)
        assertTrue(presets.all { saved -> UserPresets.forVoice(UserPresets.read(shelf), saved.engine, saved.voiceName).any { it.patch == saved } })

        val again = ShelfImport.land(backup, backup.name, shelf)
        assertTrue(again.skipped.isEmpty())
        assertEquals(0, again.presets)
        assertEquals(presets, UserPresets.read(shelf).map { it.patch })
    }

    private fun patch(name: String, voice: OsteriaVoice) = OsteriaPatch(
        name, voice, Osteria.defaults(voice) + mapOf("ROOM" to 0f, "HOLD" to 0f, "MEMORY" to .6f),
        midi = 48, velocity = 1f,
    )
}
