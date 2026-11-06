package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VelocitySidecarIntegrityTest {
    private val root = createTempDirectory("velocity-sidecar").toFile()
    private val source = File(root, "source").apply { mkdirs() }
    private val recipe = JsonValue.Obj(mapOf("futureRecipe" to JsonValue.Str("native velocity sources")))

    @AfterTest
    fun cleanUp() { root.deleteRecursively() }

    private fun fixture(): Kit {
        val names = listOf("Layer?soft.wav", "Layer?medium.wav", "Layer?hard.wav")
        names.forEachIndexed { index, name ->
            WavWriter.write(File(source, name), Snip(FloatArray(4410) { i ->
                ((index + 1) * .12 * kotlin.math.sin(i / (17.0 + index))).toFloat()
            }, 1, 44_100))
        }
        return Kit("Native Layers", listOf(KitPad(
            1, names.last(), "Complete bank", DrumClass.TONAL, recipe = recipe,
            source = mapOf("nativeLayerRecipes" to "all three recipes travel together"),
            velocityLayers = names.mapIndexed { index, name ->
                KitLayer(name, listOf(1, 42, 85)[index], listOf(41, 84, 127)[index],
                    listOf("SOFT", "MEDIUM", "HARD")[index])
            },
        )))
    }

    private data class Exported(val program: File, val samples: File, val native: Boolean)

    private fun export(kit: Kit, native: Boolean, suffix: String): Exported {
        val card = File(root, "card-$suffix")
        return if (native) {
            val result = Mpc3Exporter.exportTrack(kit, source, card)
            Exported(result.program, File(card, "${kit.name}_[TrackData]"), true)
        } else {
            val result = KitExporter.exportProgramFolder(kit, source, card)
            Exported(result.program, result.directory, false)
        }
    }

    private fun import(exported: Exported, target: File, suffix: String, overwrite: Boolean = false): Pair<Kit, File> =
        if (exported.native) {
            Mpc3Importer.import(exported.program, target, overwrite).let { it.kit to it.directory }
        } else {
            // Repack after every mutation, so the ZIP's own CRC is valid. The
            // saved native-layer fingerprint, not archive corruption, refuses it.
            val archive = File(root, "$suffix.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (file in exported.samples.listFiles()!!.filter { it.isFile }.sortedBy { it.name }) {
                    zip.putNextEntry(ZipEntry("Native Layers/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            XpnImporter.import(archive, target, overwrite).let { it.kit to it.directory }
        }

    @Test
    fun `soft and middle sample edits refuse both imports before replacing a saved kit`() {
        val original = fixture()
        for (native in listOf(false, true)) {
            for (changed in listOf(0, 1)) {
                val suffix = "${if (native) "native" else "xml"}-$changed"
                val exported = export(original, native, suffix)
                val saved = KitStore.load(exported.samples).pad(1)!!
                val fingerprints = Json.parse(saved.source.getValue("rawVelocitySha256")).obj()
                assertEquals(setOf("Layer_soft.wav", "Layer_medium.wav", "Layer_hard.wav"), fingerprints.keys)
                for (layer in saved.velocityLayers) {
                    assertEquals(hash(File(exported.samples, layer.sampleFile)), fingerprints.getValue(layer.sampleFile).str())
                }
                assertEquals(hash(File(exported.samples, saved.sampleFile)), saved.source.getValue("rawSha256"))

                val target = File(root, "target-$suffix")
                val (landed, directory) = import(exported, target, "$suffix-good")
                assertEquals(recipe, landed.pad(1)!!.recipe)
                assertEquals(original.pad(1)!!.source.getValue("nativeLayerRecipes"),
                    landed.pad(1)!!.source.getValue("nativeLayerRecipes"))
                val keptHistory = File(directory, KitStore.FILE_NAME).readBytes()
                val keptAudio = saved.velocityLayers.associate { it.sampleFile to File(directory, it.sampleFile).readBytes() }
                val changedFile = File(exported.samples, saved.velocityLayers[changed].sampleFile)
                changedFile.writeBytes(changedFile.readBytes().also {
                    it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                })
                val error = assertFailsWith<IllegalArgumentException> {
                    import(exported, target, "$suffix-changed", overwrite = true)
                }
                assertTrue("velocity layer checksum disagrees at pad 1" in error.message.orEmpty(), error.message)
                assertContentEquals(keptHistory, File(directory, KitStore.FILE_NAME).readBytes(),
                    "a lower layer mismatch cannot replace editable history")
                for ((file, bytes) in keptAudio) assertContentEquals(bytes, File(directory, file).readBytes())
                assertEquals(listOf(directory.name), target.listFiles()!!.map { it.name },
                    "failed imports must not leave partial staging kits")
            }
        }
    }

    @Test
    fun `legacy layer sidecars remain valid and checksum names use case insensitive matching`() {
        val kit = fixture()
        for (native in listOf(false, true)) {
            val exported = export(kit, native, "compat-$native")
            val saved = KitStore.load(exported.samples)
            val pad = saved.pad(1)!!
            val upper = JsonValue.Obj(Json.parse(pad.source.getValue("rawVelocitySha256")).obj().entries.associate {
                it.key.uppercase(Locale.ROOT) to JsonValue.Str(it.value.str().uppercase(Locale.ROOT))
            })
            KitStore.save(saved.copy(pads = listOf(pad.copy(source = pad.source +
                ("rawVelocitySha256" to Json.write(upper))))), exported.samples)
            val withCase = import(exported, File(root, "upper-$native"), "upper-$native").first.pad(1)!!
            assertEquals(recipe, withCase.recipe)
            assertEquals(listOf("SOFT", "MEDIUM", "HARD"), withCase.velocityLayers.map { it.displayName })

            KitStore.save(saved.copy(pads = listOf(pad.copy(source = pad.source - "rawVelocitySha256"))), exported.samples)
            val legacy = import(exported, File(root, "legacy-$native"), "legacy-$native").first.pad(1)!!
            assertEquals(recipe, legacy.recipe)
            assertFalse("rawVelocitySha256" in legacy.source)
            assertEquals(pad.velocityLayers, legacy.velocityLayers)
        }
    }

    @Test
    fun `declared layer fingerprints require a complete unambiguous set of hexadecimal hashes`() {
        val exported = export(fixture(), true, "invalid-metadata")
        val saved = KitStore.load(exported.samples)
        val pad = saved.pad(1)!!
        val valid = Json.parse(pad.source.getValue("rawVelocitySha256")).obj()
        val soft = pad.velocityLayers.first().sampleFile
        fun encoded(entries: Map<String, JsonValue>) = Json.write(JsonValue.Obj(entries))
        val invalid = listOf(
            "{bad", "[]", "{}",
            encoded(valid - soft),
            encoded(valid + ("Other.wav" to valid.getValue(soft))),
            encoded(valid + (soft.uppercase(Locale.ROOT) to valid.getValue(soft))),
            encoded(valid + (soft to JsonValue.Str("abc"))),
            encoded(valid + (soft to JsonValue.Num(123.0))),
            encoded((valid - soft) + ("../$soft" to valid.getValue(soft))),
        )
        for (metadata in invalid) {
            val history = saved.copy(pads = listOf(pad.copy(source = pad.source + ("rawVelocitySha256" to metadata))))
            val sidecar = KitStore.save(history, File(root, "metadata")).readText()
            val error = assertFailsWith<IllegalArgumentException> {
                ImportSidecar.restore(saved, sidecar, exported.samples, "kit.json")
            }
            assertTrue("velocity layer checksum" in error.message.orEmpty(), error.message)
        }
        assertNotNull(pad.source["rawVelocitySha256"])
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
