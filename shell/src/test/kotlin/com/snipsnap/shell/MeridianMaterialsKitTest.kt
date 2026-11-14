package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Mpc3Importer
import com.snipsnap.kit.XpnImporter
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianKits
import com.snipsnap.synth.MeridianMaterialsKits
import com.snipsnap.synth.MeridianMaterialsVelocityRecipe
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.MeridianVoice
import com.snipsnap.synth.PadRecipe
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeridianMaterialsKitTest {
    private fun synthetic(recipe: PadRecipe): Snip {
        val patch = recipe.patch as MeridianPatch
        return recipe.process(Snip(FloatArray(4096) { i ->
            ((.03 + .03 * patch.velocity) * sin(2 * PI * i / (17 + patch.voice.ordinal * 3) + patch.midi * .01)).toFloat()
        }, 1, 44_100))
    }
    private fun fixture(root: File): Kit = KitAssembler.assembleArranged("Meridian Materials", MeridianMaterialsKits.build(::synthetic), root)
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()
    }

    @Test fun `all fourteen hard factory patches remain exact with only two declared octave pad roles`() {
        val factories = MeridianMaterialsKits.factoriesV1().associateBy { it.name }
        assertEquals(14, factories.size)
        // Additional future global factories cannot expand or reorder this saved v1 bank.
        for ((name, patch) in factories) assertEquals(patch.toJsonText(), MeridianPresets.all().single { it.name == name }.toJsonText())
        assertEquals(listOf("FIRST ARC", "BACK ARC", "CLOSE GRAIN", "DEEP KNOCK", "STILL PAIR", "LONG TRAIL", "HARD GRAIN", "HOLLOW TONE", "BRIGHT EDGE", "HARD GRAIN", "HOLLOW TONE", "ROUND THREAD", "FINE STRAND", "GRAIN THREAD", "LANTERN TONE", "EDGE THREAD"),
            MeridianMaterialsKits.entries().map { it.factoryPreset })
        assertEquals(listOf(57,57,57,57,57,57,57,57,57,45,69,57,57,57,57,57), MeridianMaterialsKits.entries().map { it.midi })
        val patches = MeridianMaterialsKits.patches()
        assertEquals(16, patches.size)
        assertEquals(setOf(MeridianVoice.SWEEP, MeridianVoice.CLACK, MeridianVoice.CHIME, MeridianVoice.RASP), patches.map { it.voice }.toSet())
        val unchanged = patches.filterIndexed { index, _ -> index !in listOf(9, 10) }
        assertEquals(factories.keys, unchanged.map { it.name }.toSet())
        for (patch in unchanged) assertEquals(factories.getValue(patch.name).toJsonText(), patch.toJsonText())
        assertEquals(factories.getValue("HARD GRAIN").copy(midi = 45), patches[9])
        assertEquals(factories.getValue("HOLLOW TONE").copy(midi = 69), patches[10])
        assertEquals(5, patches.count { Meridian.isLoop(it.macros, it.model) })
        assertTrue(patches.all { it.seedContext == 4810 && it.velocity == 1f })
        val arranged = MeridianMaterialsKits.build(::synthetic)
        assertEquals(16, arranged.size)
        for ((index, pad) in arranged.withIndex()) {
            val p = assertNotNull(pad)
            val bank = MeridianMaterialsVelocityRecipe.fromJsonText(p.source.getValue(MeridianMaterialsVelocityRecipe.SOURCE_KEY))
            assertEquals(index + 1, bank.slot)
            assertEquals(patches[index], bank.hardPatch)
            assertEquals(listOf(.30f, .65f, 1f), bank.layers.map { it.nativeVelocity })
            assertEquals(listOf(1, 42, 85), bank.layers.map { it.velocityStart })
            assertEquals(listOf(41, 84, 127), bank.layers.map { it.velocityEnd })
            assertEquals(3, p.softVariants.size + 1)
            assertTrue(p.takes.isEmpty() && p.oneShot)
            assertEquals(0, p.muteGroup)
            val held = Meridian.isLoop(bank.hardPatch.macros, bank.hardPatch.model)
            assertTrue(bank.layers.all { it.recipe.terminalFadeMs == if (held) 20f else null })
            assertEquals(bank.toJsonValue(), MeridianMaterialsVelocityRecipe.fromJsonText(bank.toJsonText()).toJsonValue())
        }
    }

    @Test fun `native layer recipe history is frozen against caller map and list mutation`() {
        val pad = MeridianMaterialsKits.buildPad(7, ::synthetic)
        val original = MeridianMaterialsVelocityRecipe.fromJsonText(pad.source.getValue(MeridianMaterialsVelocityRecipe.SOURCE_KEY))
        val factoryMacros = original.factoryPatch.macros.toMutableMap()
        val cellMacros = (original.layers[0].recipe.patch as MeridianPatch).macros.toMutableMap()
        val layers = original.layers.toMutableList()
        layers[0] = layers[0].copy(recipe = layers[0].recipe.copy(patch =
            (layers[0].recipe.patch as MeridianPatch).copy(macros = cellMacros)))
        val frozen = MeridianMaterialsVelocityRecipe(7, original.factoryPatch.copy(macros = factoryMacros), layers)
        val before = frozen.toJsonText()
        factoryMacros["HOLD"] = 1f; cellMacros["TUNE"] = .99f; layers.clear()
        assertEquals(before, frozen.toJsonText())
        assertEquals(3, frozen.layers.size)
        assertContentEquals(pad.softVariants[0].samples, frozen.replay(::synthetic)[0].samples)
    }

    @Test fun `actual assembler shelf files match streamed WAV hashes source recipes and real layer windows`() {
        val root = createTempDirectory("meridian-materials-layout").toFile()
        try {
            val kit = fixture(root)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            val banks = MeridianMaterialsKitFiles.validate(root, kit)
            assertEquals(48, banks.sumOf { it.layers.size })
            KitStore.save(KitStore.load(root), root)
            val reopened = KitStore.load(root)
            assertEquals(kit, reopened)
            assertEquals(banks.map { it.toJsonText() }, MeridianMaterialsKitFiles.validate(root).map { it.toJsonText() })
            assertEquals(48, root.listFiles()!!.count { it.extension == "wav" })
            assertTrue(reopened.pads.all { it.velocityLayers.map { l -> l.velStart to l.velEnd } == listOf(1 to 41, 42 to 84, 85 to 127) })
            assertTrue(reopened.pads.all { it.source["playbackVelocityPolicy"] == "KIT_PLAYER_GAIN_ONCE" })
        } finally { root.deleteRecursively() }
    }

    @Test fun `native and legacy sidecar import preserves every native layer recipe and copied sample binding`() {
        val root = createTempDirectory("meridian-materials-export").toFile()
        try {
            val samples = File(root, "kit")
            val kit = fixture(samples)
            val native = Mpc3Exporter.exportTrack(kit, samples, File(root, "native"), mpc2Twin = true)
            val importedNative = Mpc3Importer.import(native.program, File(root, "native-import"))
            val nativeBanks = MeridianMaterialsKitFiles.validate(importedNative.directory, importedNative.kit)
            assertEquals(48, nativeBanks.sumOf { it.layers.size })
            for (pad in importedNative.kit.pads) {
                assertEquals(kit.pad(pad.slot)!!.source.getValue(MeridianMaterialsVelocityRecipe.SOURCE_KEY),
                    pad.source.getValue(MeridianMaterialsVelocityRecipe.SOURCE_KEY))
                val hashes = Json.parse(pad.source.getValue("rawVelocitySha256")).obj()
                assertEquals(pad.velocityLayers.map { it.sampleFile }.toSet(), hashes.keys)
            }
            val legacy = KitExporter.exportProgramFolder(kit, samples, File(root, "legacy"))
            val archive = File(root, "legacy.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                legacy.directory.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(legacy.directory).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            val importedLegacy = XpnImporter.import(archive, File(root, "legacy-import"))
            val legacyBanks = MeridianMaterialsKitFiles.validate(importedLegacy.directory, importedLegacy.kit)
            assertEquals(nativeBanks.map { it.toJsonText() }, legacyBanks.map { it.toJsonText() })
            // The common importer must refuse edited lower PCM before restoring history.
            val data = File(root, "native/Meridian Materials_[TrackData]")
            val lower = KitStore.load(data).pads.first().velocityLayers.first().sampleFile
            File(data, lower).appendBytes(byteArrayOf(0))
            assertFailsWith<IllegalArgumentException> { Mpc3Importer.import(native.program, File(root, "bad-import")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `noncanonical unknown and incompatible independent source histories refuse explicitly`() {
        val bank = MeridianMaterialsVelocityRecipe.fromJsonText(MeridianMaterialsKits.buildPad(7, ::synthetic)
            .source.getValue(MeridianMaterialsVelocityRecipe.SOURCE_KEY))
        val original = bank.toJsonValue()
        fun editOuter(edit: (MutableMap<String, JsonValue>) -> Unit): JsonValue {
            val o = original.obj().toMutableMap(); edit(o); return JsonValue.Obj(o)
        }
        fun editLayer(edit: (MutableMap<String, JsonValue>) -> Unit) = editOuter { o ->
            val rows = o.getValue("layers").arr().toMutableList(); val row = rows.first().obj().toMutableMap()
            edit(row); rows[0] = JsonValue.Obj(row); o["layers"] = JsonValue.Arr(rows)
        }
        val bad = listOf(
            editOuter { it["future"] = JsonValue.Num(2.0) },
            editOuter { it["schema"] = JsonValue.Num(2.0) },
            editOuter { it["slot"] = JsonValue.Num(8.0) },
            editOuter { it["nativeMidi"] = JsonValue.Num(45.0) },
            editOuter { it["registerRole"] = JsonValue.Str("A2_OCTAVE") },
            editOuter { it["factoryPatchSha256"] = JsonValue.Str("0".repeat(64)) },
            editOuter { it["layers"] = JsonValue.Arr(it.getValue("layers").arr().dropLast(1)) },
            editLayer { it["future"] = JsonValue.Num(2.0) },
            editLayer { it["nativeVelocity"] = JsonValue.Num(.300000001) },
            editLayer { it["velocityStart"] = JsonValue.Num(0.0) },
            editLayer { it["recipeSha256"] = JsonValue.Str("0".repeat(64)) },
            editLayer { it["wavSha256"] = JsonValue.Str("bad") },
            editLayer { row -> val recipe = row.getValue("recipe").obj().toMutableMap(); recipe["future"] = JsonValue.Num(1.0); row["recipe"] = JsonValue.Obj(recipe) },
            editLayer { row -> val recipe = row.getValue("recipe").obj().toMutableMap(); recipe["terminalFadeMs"] = JsonValue.Num(20.0); row["recipe"] = JsonValue.Obj(recipe) },
        )
        for ((index, value) in bad.withIndex()) assertFails("source mutation $index") { MeridianMaterialsVelocityRecipe.fromJsonValue(value) }
        println("MERIDIAN materials refused ${bad.size} hostile independent-source histories")
    }

    @Test fun `dedicated disk binding proof refuses changed PCM stale mapping and missing provenance`() {
        val root = createTempDirectory("meridian-materials-hostile").toFile()
        try {
            val kit = fixture(root)
            val first = kit.pads.first()
            val variants = listOf(
                kit.copy(pads = kit.pads.dropLast(1)),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(source = it.source - MeridianMaterialsVelocityRecipe.SOURCE_KEY) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(oneShot = false) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(loop = true) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(drumClass = com.snipsnap.audio.DrumClass.KICK) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(tuneCoarse = 12) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(recipe = PadRecipe(MeridianPresets.all()[1]).toJsonValue()) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(velocityLayers = it.velocityLayers.toMutableList().also { l -> l[0] = l[0].copy(sampleFile = l[1].sampleFile) }) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(velocityLayers = it.velocityLayers.toMutableList().also { l -> l[0] = l[0].copy(velEnd = 40) }) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(source = it.source + ("rootMidi" to "45")) else it }),
            )
            for ((index, v) in variants.withIndex()) assertFails("disk mapping $index") { MeridianMaterialsKitFiles.validate(root, v) }
            val wav = File(root, first.velocityLayers.first().sampleFile); val bytes = wav.readBytes()
            wav.appendBytes(byteArrayOf(0)); assertFails { MeridianMaterialsKitFiles.validate(root, kit) }; wav.writeBytes(bytes)
            assertTrue(wav.delete()); assertFails { MeridianMaterialsKitFiles.validate(root, kit) }; wav.writeBytes(bytes)
            File(root, "unbound.wav").writeBytes(bytes); assertFails { MeridianMaterialsKitFiles.validate(root, kit) }
            println("MERIDIAN materials refused ${variants.size + 3} hostile disk/mapping cases")
        } finally { root.deleteRecursively() }
    }

    @Test fun `synthetic render failures leave the existing shelf untouched before assembly begins`() {
        val root = createTempDirectory("meridian-materials-render-failure").toFile()
        try {
            val previous = File(root, "previous").apply { mkdir() }
            File(previous, "keep.txt").writeText("existing asset")
            val before = snapshot(root)
            for (boundary in listOf(1, 2, 24, 47, 48)) {
                var count = 0
                val starter = StarterKits.Starter("synthetic-materials", "MATERIALS", "fixture", false,
                    build = { MeridianMaterialsKits.build { recipe ->
                        count++; if (count == boundary) throw IOException("injected render $boundary")
                        synthetic(recipe)
                    } })
                val destination = File(root, "new")
                assertFailsWith<IOException> { starter.render("Materials", destination) }
                assertFalse(destination.exists())
                assertEquals(before, snapshot(root))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `cancellation brackets native render callbacks without altering shared source arrays`() {
        var called = false
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { MeridianMaterialsKits.buildPad(7) { called = true; synthetic(it) } }
            assertFalse(called)
        } finally { Thread.interrupted() }
        val snip = synthetic(PadRecipe(MeridianPresets.all().first()))
        val original = snip.samples.copyOf()
        try {
            assertFailsWith<CancellationException> { MeridianMaterialsKits.buildPad(7) { Thread.currentThread().interrupt(); snip } }
        } finally { Thread.interrupted() }
        assertContentEquals(original, snip.samples)
    }

    @Test fun `materials registration is additive and legacy SWEEP metadata is unchanged`() {
        val original = assertNotNull(StarterKits.byId("meridian"))
        val materials = assertNotNull(StarterKits.byId("meridian-materials"))
        assertEquals("MERIDIAN", original.displayName)
        assertEquals("MERIDIAN MATERIALS", materials.displayName)
        assertTrue(StarterKits.ALL.indexOf(materials) > StarterKits.ALL.indexOf(original))
        assertFalse(materials.seeded)
        assertNull(materials.key)
        val metal = StarterShelf.GROUPS.single { "meridian" in it.ids }
        assertEquals(metal.ids.indexOf("meridian") + 1, metal.ids.indexOf("meridian-materials"))
        val old = MeridianKits.patches()
        assertEquals(16, old.size)
        assertTrue(old.all { it.voice == MeridianVoice.SWEEP && it.model == 2 })
        assertEquals(listOf(60, 63, 65, 67, 70, 72, 75, 77), old.take(8).map { it.midi })
        assertEquals(MeridianPresets.forVoice(MeridianVoice.SWEEP), old.drop(8))
    }
}
