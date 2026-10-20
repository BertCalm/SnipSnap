package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImportSidecarTest {
    private val root = kotlin.io.path.createTempDirectory("import-sidecar").toFile()
    private val samples = File(root, "samples").apply { mkdirs() }
    private val recipe = JsonValue.Obj(mapOf("patch" to JsonValue.Obj(mapOf(
        "engine" to JsonValue.Str("ONSET"), "modelVersion" to JsonValue.Num(2.0),
    ))))

    @AfterTest
    fun cleanUp() { root.deleteRecursively() }

    private fun fixture(): Kit {
        for ((index, name) in listOf("Kick.wav", "Soft.wav", "Hard.wav").withIndex()) {
            WavWriter.write(File(samples, name), Snip(FloatArray(4410) { i ->
                (.3 * kotlin.math.sin(i / (12.0 + index))).toFloat()
            }, 1, 44_100))
        }
        return Kit("Editable", listOf(
            KitPad(1, "Kick.wav", displayName = "MY KICK", drumClass = DrumClass.KICK,
                colorHex = "#aabbcc", recipe = recipe,
                source = mapOf("rawSha256" to hash(File(samples, "Kick.wav")), "renderer" to "ONSET")),
            KitPad(2, "Hard.wav", displayName = "MY LAYERS", recipe = recipe,
                velocityLayers = listOf(KitLayer("Soft.wav", 0, 63), KitLayer("Hard.wav", 64, 127))),
        ))
    }

    private fun text(kit: Kit): String = KitStore.save(kit, File(root, "sidecar")).readText()

    @Test
    fun `history restores only identity and recipe while program playback controls remain authoritative`() {
        val saved = fixture()
        val imported = saved.copy(pads = saved.pads.map { it.copy(
            displayName = it.sampleStem, drumClass = DrumClass.UNKNOWN, colorHex = null,
            recipe = null, source = mapOf("importedFrom" to "Card.zip"),
            level = .24f, pan = .8f, tuneCoarse = 3, muteGroup = 7, attack = .2f,
        ) })
        assertSame(imported, ImportSidecar.restore(imported, null, samples, "kit.json"))
        val restored = ImportSidecar.restore(imported, text(saved), samples, "kit.json")
        for (pad in restored.pads) {
            val original = saved.pad(pad.slot)!!
            assertEquals(original.recipe, pad.recipe)
            assertEquals(original.displayName, pad.displayName)
            assertEquals(original.drumClass, pad.drumClass)
            assertEquals(original.colorHex, pad.colorHex)
            assertEquals(.24f, pad.level)
            assertEquals(.8f, pad.pan)
            assertEquals(3, pad.tuneCoarse)
            assertEquals(7, pad.muteGroup)
            assertEquals(.2f, pad.attack)
            assertEquals("Card.zip", pad.source["importedFrom"])
        }
    }

    @Test
    fun `a sidecar cannot move history between slots samples or velocity windows`() {
        val saved = fixture()
        val variants = listOf(
            saved.copy(pads = saved.pads.dropLast(1)),
            saved.copy(pads = saved.pads.map { if (it.slot == 1) it.copy(slot = 3) else it }),
            saved.copy(pads = saved.pads.map { if (it.slot == 1) it.copy(sampleFile = "Other.wav") else it }),
            saved.copy(pads = saved.pads.map { if (it.slot == 2) it.copy(velocityLayers = listOf(
                KitLayer("Soft.wav", 0, 62), KitLayer("Hard.wav", 63, 127),
            )) else it }),
        )
        for (variant in variants) {
            val error = assertFailsWith<IllegalArgumentException> {
                ImportSidecar.restore(saved, text(variant), samples, "kit.json")
            }
            assertTrue("disagree" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `known malformed sidecars and externally changed samples refuse in words`() {
        val saved = fixture()
        val malformed = assertFailsWith<IllegalArgumentException> {
            ImportSidecar.restore(saved, "{bad", samples, "kit.json")
        }
        assertTrue("not a readable kit sidecar" in malformed.message.orEmpty())
        val sidecar = text(saved)
        File(samples, "Kick.wav").appendBytes(byteArrayOf(1))
        val stale = assertFailsWith<IllegalArgumentException> {
            ImportSidecar.restore(saved, sidecar, samples, "kit.json")
        }
        assertTrue("checksum disagrees at pad 1" in stale.message.orEmpty())
    }

    @Test
    fun `native import restores an adjacent sidecar before landing unchanged audio`() {
        val saved = fixture()
        val card = File(root, "card")
        val actual = saved.copy(pads = saved.pads.map { it.copy(level = .24f, muteGroup = 7) })
        Mpc3Exporter.exportTrack(actual, samples, card)
        val data = File(card, "Editable_[TrackData]")
        KitStore.save(saved, data)
        val landed = Mpc3Importer.import(File(card, "Editable.xtd"), File(root, "landed"))
        assertEquals(recipe, landed.kit.pad(1)!!.recipe)
        assertEquals(DrumClass.KICK, landed.kit.pad(1)!!.drumClass)
        assertEquals(.24f, landed.kit.pad(1)!!.level)
        assertEquals(7, landed.kit.pad(1)!!.muteGroup)
        assertEquals(hash(File(samples, "Kick.wav")), hash(File(landed.directory, "Kick.wav")))

        File(data, "Kick.wav").appendBytes(byteArrayOf(1))
        val target = File(root, "refused")
        val error = assertFailsWith<IllegalArgumentException> {
            Mpc3Importer.import(File(card, "Editable.xtd"), target)
        }
        assertTrue("checksum" in error.message.orEmpty())
        assertFalse(File(target, "Editable").exists(), "invalid history must refuse before destination writes")
    }

    @Test
    fun `an archive sidecar belongs only to the program directly beside it`() {
        val saved = fixture()
        val folder = KitExporter.exportProgramFolder(saved, samples, File(root, "program")).directory
        KitStore.save(saved, folder)
        val archive = File(root, "history.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("kit.json"))
            zip.write("{unrelated malformed sidecar".toByteArray())
            zip.closeEntry()
            for (file in folder.listFiles()!!.sortedBy { it.name }) {
                zip.putNextEntry(ZipEntry("Nested/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val result = XpnImporter.import(archive, File(root, "archive-landed"))
        assertEquals(recipe, result.kit.pad(1)!!.recipe)
        assertEquals("MY KICK", result.kit.pad(1)!!.displayName)
    }

    @Test
    fun `chain exports retain their ordinary import path and remove history from a replaced nonchain export`() {
        val saved = fixture()
        val chained = saved.copy(pads = saved.pads.map { pad ->
            if (pad.slot == 1) pad.copy(chain = ChainInfo(listOf(0L, 1000L, 2000L), cycle = 2)) else pad
        })
        val unsupported = assertFailsWith<IllegalArgumentException> {
            ImportSidecar.restore(saved, text(chained), samples, "kit.json")
        }
        assertTrue("chain history" in unsupported.message.orEmpty(), "unsupported manually supplied history must be named")
        for (native in listOf(false, true)) {
            val target = File(root, if (native) "chain-native" else "chain-compatibility")
            val first = if (native) Mpc3Exporter.exportTrack(saved, samples, target)
                else KitExporter.exportProgramFolder(saved, samples, target)
            val data = if (native) File(target, "Editable_[TrackData]") else first.directory
            assertTrue(File(data, KitStore.FILE_NAME).isFile, "ordinary exports carry editable history")
            if (native) Mpc3Exporter.exportTrack(chained, samples, target, overwrite = true)
                else KitExporter.exportProgramFolder(chained, samples, target, overwrite = true)
            assertFalse(File(data, KitStore.FILE_NAME).exists(), "a previous nonchain sidecar must not survive overwrite")
            val imported = if (native) {
                Mpc3Importer.import(File(target, "Editable.xtd"), File(root, "chain-native-landed"))
                    .let { it.kit to it.directory }
            } else {
                val zipFile = File(root, "chain-compatibility.zip")
                ZipOutputStream(zipFile.outputStream()).use { zip ->
                    for (file in data.listFiles()!!.filter { it.isFile }.sortedBy { it.name }) {
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                XpnImporter.import(zipFile, File(root, "chain-compatibility-landed"))
                    .let { it.kit to it.directory }
            }
            assertEquals(saved.pads.map { it.slot }, imported.first.pads.map { it.slot })
            assertEquals(null, imported.first.pad(1)!!.recipe, "chain import does not invent regenerable history")
            assertEquals(hash(File(samples, "Kick.wav")), hash(File(imported.second, "Kick.wav")))
        }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
