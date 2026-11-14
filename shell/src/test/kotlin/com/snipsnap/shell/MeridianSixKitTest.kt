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
import com.snipsnap.synth.MeridianSixKits
import com.snipsnap.synth.MeridianSixVelocityRecipe
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

class MeridianSixKitTest {
    private fun synthetic(recipe: PadRecipe): Snip {
        val patch = recipe.patch as MeridianPatch
        return recipe.process(Snip(FloatArray(4096) { i ->
            ((.03 + .03 * patch.velocity) * sin(2 * PI * i / (17 + patch.voice.ordinal * 3) + patch.midi * .01)).toFloat()
        }, 1, 44_100))
    }
    private fun fixture(root: File): Kit = KitAssembler.assembleArranged("Meridian Six", MeridianSixKits.build(::synthetic), root)
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()
    }

    @Test fun `thirteen frozen finite factories and three MIDI only roles cover six voices`() {
        val names = listOf("FIRST ARC", "BACK ARC", "CLOSE GRAIN", "DEEP KNOCK", "STILL PAIR", "LONG TRAIL",
            "HARD GRAIN", "HOLLOW TONE", "BRIGHT EDGE", "OPEN LINE", "WIDE THREAD", "AFTER PRESS", "SOFT RETURN")
        val factories = MeridianSixKits.factoriesV2().associateBy { it.name }
        assertEquals(names, factories.keys.toList())
        for ((name, patch) in factories) assertEquals(MeridianPresets.all().single { it.name == name }.toJsonText(), patch.toJsonText())
        assertEquals(names + listOf("FIRST ARC", "OPEN LINE", "SOFT RETURN"), MeridianSixKits.entries().map { it.factoryPreset })
        assertEquals(List(13) { 57 } + listOf(45, 45, 69), MeridianSixKits.entries().map { it.midi })
        assertEquals(List(13) { "FACTORY" } + listOf("A2_SWEEP", "A2_DRAW", "A4_LIFT"), MeridianSixKits.entries().map { it.registerRole })
        val patches = MeridianSixKits.patches()
        assertEquals(16, patches.size)
        assertEquals(setOf(MeridianVoice.SWEEP, MeridianVoice.CLACK, MeridianVoice.CHIME, MeridianVoice.RASP,
            MeridianVoice.DRAW, MeridianVoice.LIFT), patches.map { it.voice }.toSet())
        assertEquals(factories.getValue("FIRST ARC").copy(midi = 45), patches[13])
        assertEquals(factories.getValue("OPEN LINE").copy(midi = 45), patches[14])
        assertEquals(factories.getValue("SOFT RETURN").copy(midi = 69), patches[15])
        assertEquals(.65f, factories.getValue("LONG TRAIL").macros.getValue("HOLD"))
        assertTrue(patches.none { Meridian.isLoop(it.macros, it.model) })
        assertTrue(patches.all { it.seedContext == 4810 && it.velocity == 1f })
        val arranged = MeridianSixKits.build(::synthetic)
        for ((index, pad) in arranged.withIndex()) {
            val value = assertNotNull(pad)
            val bank = MeridianSixVelocityRecipe.fromJsonText(value.source.getValue(MeridianSixVelocityRecipe.SOURCE_KEY))
            assertEquals(index + 1, bank.slot)
            assertEquals(patches[index], bank.hardPatch)
            assertEquals(listOf(.30f, .65f, 1f), bank.layers.map { it.nativeVelocity })
            assertEquals(MeridianSixKits.TOUCH_SHAPINGS, bank.layers.map { (it.recipe.patch as MeridianPatch).touchShaping })
            assertEquals(listOf(MeridianSixKits.SOFT_TOUCH_SHAPING, MeridianSixKits.SOFT_TOUCH_SHAPING, 0f), MeridianSixKits.TOUCH_SHAPINGS)
            assertEquals(MeridianSixVelocityRecipe.POLICY, bank.policy)
            assertEquals("3", value.source["bankVersion"])
            assertEquals(listOf(1, 42, 85), bank.layers.map { it.velocityStart })
            assertEquals(listOf(41, 84, 127), bank.layers.map { it.velocityEnd })
            assertTrue(bank.layers.all { it.recipe.terminalFadeMs == null })
            assertEquals(3, value.softVariants.size + 1)
            assertTrue(value.takes.isEmpty() && value.oneShot)
            assertEquals(0, value.muteGroup)
            assertEquals("NATIVE_FINITE_ENDING", value.source["capturePolicy"])
            assertEquals("meridian-six", value.source["bankId"])
            assertEquals(bank.toJsonValue(), MeridianSixVelocityRecipe.fromJsonText(bank.toJsonText()).toJsonValue())
        }
    }

    @Test fun `native layer recipe history is frozen against caller map and list mutation`() {
        val pad = MeridianSixKits.buildPad(7, ::synthetic)
        val original = MeridianSixVelocityRecipe.fromJsonText(pad.source.getValue(MeridianSixVelocityRecipe.SOURCE_KEY))
        val factoryMacros = original.factoryPatch.macros.toMutableMap()
        val cellMacros = (original.layers[0].recipe.patch as MeridianPatch).macros.toMutableMap()
        val layers = original.layers.toMutableList()
        layers[0] = layers[0].copy(recipe = layers[0].recipe.copy(patch =
            (layers[0].recipe.patch as MeridianPatch).copy(macros = cellMacros)))
        val frozen = MeridianSixVelocityRecipe(7, original.factoryPatch.copy(macros = factoryMacros), layers)
        val before = frozen.toJsonText()
        factoryMacros["HOLD"] = 1f; cellMacros["TUNE"] = .99f; layers.clear()
        assertEquals(before, frozen.toJsonText())
        assertEquals(3, frozen.layers.size)
        assertContentEquals(pad.softVariants[0].samples, frozen.replay(::synthetic)[0].samples)
    }

    @Test fun `actual assembler shelf files match streamed WAV hashes source recipes and real layer windows`() {
        val root = createTempDirectory("meridian-six-layout").toFile()
        try {
            val kit = fixture(root)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            val banks = MeridianSixKitFiles.validate(root, kit)
            assertEquals(48, banks.sumOf { it.layers.size })
            KitStore.save(KitStore.load(root), root)
            val reopened = KitStore.load(root)
            assertEquals(kit, reopened)
            assertEquals(banks.map { it.toJsonText() }, MeridianSixKitFiles.validate(root).map { it.toJsonText() })
            assertEquals(48, root.listFiles()!!.count { it.extension == "wav" })
            assertTrue(reopened.pads.all { it.velocityLayers.map { l -> l.velStart to l.velEnd } == listOf(1 to 41, 42 to 84, 85 to 127) })
            assertTrue(reopened.pads.all { it.source["playbackVelocityPolicy"] == "KIT_PLAYER_GAIN_ONCE" })
        } finally { root.deleteRecursively() }
    }

    @Test fun `native and legacy sidecar import preserves every native layer recipe and copied sample binding`() {
        val root = createTempDirectory("meridian-six-export").toFile()
        try {
            val samples = File(root, "kit")
            val kit = fixture(samples)
            val native = Mpc3Exporter.exportTrack(kit, samples, File(root, "native"), mpc2Twin = true)
            val importedNative = Mpc3Importer.import(native.program, File(root, "native-import"))
            val nativeBanks = MeridianSixKitFiles.validate(importedNative.directory, importedNative.kit)
            assertEquals(48, nativeBanks.sumOf { it.layers.size })
            for (pad in importedNative.kit.pads) {
                assertEquals(kit.pad(pad.slot)!!.source.getValue(MeridianSixVelocityRecipe.SOURCE_KEY),
                    pad.source.getValue(MeridianSixVelocityRecipe.SOURCE_KEY))
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
            val legacyBanks = MeridianSixKitFiles.validate(importedLegacy.directory, importedLegacy.kit)
            assertEquals(nativeBanks.map { it.toJsonText() }, legacyBanks.map { it.toJsonText() })
            // The common importer must refuse edited lower PCM before restoring history.
            val data = File(root, "native/Meridian Six_[TrackData]")
            val lower = KitStore.load(data).pads.first().velocityLayers.first().sampleFile
            File(data, lower).appendBytes(byteArrayOf(0))
            assertFailsWith<IllegalArgumentException> { Mpc3Importer.import(native.program, File(root, "bad-import")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `noncanonical unknown and incompatible independent source histories refuse explicitly`() {
        val bank = MeridianSixVelocityRecipe.fromJsonText(MeridianSixKits.buildPad(7, ::synthetic)
            .source.getValue(MeridianSixVelocityRecipe.SOURCE_KEY))
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
            editOuter { it["bankId"] = JsonValue.Str("meridian-materials") },
            editOuter { it["policy"] = JsonValue.Str("absolute-native-touch-materials-v1") },
            editOuter { it["policy"] = JsonValue.Str(MeridianSixVelocityRecipe.POLICY_V2) },
            editOuter { it["schema"] = JsonValue.Num(3.0) },
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
        for ((index, value) in bad.withIndex()) assertFails("source mutation $index") { MeridianSixVelocityRecipe.fromJsonValue(value) }
        println("MERIDIAN six refused ${bad.size} hostile independent-source histories")
    }

    @Test fun `bank v2 histories without touch shaping still replay and cannot be relabelled`() {
        val v3 = MeridianSixVelocityRecipe.fromJsonText(MeridianSixKits.buildPad(7, ::synthetic)
            .source.getValue(MeridianSixVelocityRecipe.SOURCE_KEY))
        fun shaped(index: Int, amount: Float) = v3.layers.mapIndexed { i, layer ->
            if (i != index) layer else layer.copy(recipe = layer.recipe.copy(
                patch = (layer.recipe.patch as MeridianPatch).copy(touchShaping = amount)))
        }
        // The synthetic fixture ignores shaping, so each layer keeps its WAV hash.
        val unshaped = v3.layers.map { layer -> layer.copy(recipe = layer.recipe.copy(
            patch = (layer.recipe.patch as MeridianPatch).copy(touchShaping = 0f))) }
        val v2 = MeridianSixVelocityRecipe(7, v3.factoryPatch, unshaped, MeridianSixVelocityRecipe.POLICY_V2)
        assertEquals(2, v2.bankVersion)
        assertEquals(3, v3.bankVersion)
        val text = v2.toJsonText()
        assertFalse("touchShaping" in text)
        assertEquals(text, MeridianSixVelocityRecipe.fromJsonText(text).toJsonText())
        assertEquals(MeridianSixVelocityRecipe.POLICY_V2, MeridianSixVelocityRecipe.fromJsonText(text).policy)
        assertEquals(3, v2.replay(::synthetic).size)
        // Shaped layers cannot claim v2, and unshaped ones cannot claim v3.
        assertFails { MeridianSixVelocityRecipe(7, v3.factoryPatch, v3.layers, MeridianSixVelocityRecipe.POLICY_V2) }
        assertFails { MeridianSixVelocityRecipe(7, v3.factoryPatch, unshaped) }
        assertFails { MeridianSixVelocityRecipe(7, v3.factoryPatch, v3.layers, "absolute-native-touch-six-v4") }
        // Soft and medium share one recorded amount, and the hard layer never has one.
        val other = if (MeridianSixKits.SOFT_TOUCH_SHAPING == .5f) 1f else .5f
        assertFails { MeridianSixVelocityRecipe(7, v3.factoryPatch, shaped(1, other)) }
        assertFails { MeridianSixVelocityRecipe(7, v3.factoryPatch, shaped(2, .5f)) }
        val both = shaped(0, other).let { layers -> layers.mapIndexed { i, layer ->
            if (i != 1) layer else layer.copy(recipe = layer.recipe.copy(patch = (layer.recipe.patch as MeridianPatch).copy(touchShaping = other)))
        } }
        val recorded = MeridianSixVelocityRecipe(7, v3.factoryPatch, both)
        assertEquals(listOf(other, other, 0f), MeridianSixVelocityRecipe.fromJsonText(recorded.toJsonText()).layers
            .map { (it.recipe.patch as MeridianPatch).touchShaping })
    }

    @Test fun `dedicated disk binding proof refuses changed PCM stale mapping and missing provenance`() {
        val root = createTempDirectory("meridian-six-hostile").toFile()
        try {
            val kit = fixture(root)
            val first = kit.pads.first()
            val variants = listOf(
                kit.copy(pads = kit.pads.dropLast(1)),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(source = it.source - MeridianSixVelocityRecipe.SOURCE_KEY) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(oneShot = false) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(loop = true) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(drumClass = com.snipsnap.audio.DrumClass.KICK) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(tuneCoarse = 12) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(recipe = PadRecipe(MeridianPresets.all()[1]).toJsonValue()) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(velocityLayers = it.velocityLayers.toMutableList().also { l -> l[0] = l[0].copy(sampleFile = l[1].sampleFile) }) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(velocityLayers = it.velocityLayers.toMutableList().also { l -> l[0] = l[0].copy(velEnd = 40) }) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(source = it.source + ("rootMidi" to "45")) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(source = it.source + ("bankId" to "meridian-materials")) else it }),
                kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(source = it.source + ("capturePolicy" to "FINITE_HELD_CAPTURE")) else it }),
            )
            for ((index, v) in variants.withIndex()) assertFails("disk mapping $index") { MeridianSixKitFiles.validate(root, v) }
            val wav = File(root, first.velocityLayers.first().sampleFile); val bytes = wav.readBytes()
            wav.appendBytes(byteArrayOf(0)); assertFails { MeridianSixKitFiles.validate(root, kit) }; wav.writeBytes(bytes)
            assertTrue(wav.delete()); assertFails { MeridianSixKitFiles.validate(root, kit) }; wav.writeBytes(bytes)
            File(root, "unbound.wav").writeBytes(bytes); assertFails { MeridianSixKitFiles.validate(root, kit) }
            println("MERIDIAN six refused ${variants.size + 3} hostile disk/mapping cases")
        } finally { root.deleteRecursively() }
    }

    @Test fun `synthetic render failures leave the existing shelf untouched before assembly begins`() {
        val root = createTempDirectory("meridian-six-render-failure").toFile()
        try {
            val previous = File(root, "previous").apply { mkdir() }
            File(previous, "keep.txt").writeText("existing asset")
            val before = snapshot(root)
            for (boundary in listOf(1, 2, 24, 47, 48)) {
                var count = 0
                val starter = StarterKits.Starter("synthetic-six", "MATERIALS", "fixture", false,
                    build = { MeridianSixKits.build { recipe ->
                        count++; if (count == boundary) throw IOException("injected render $boundary")
                        synthetic(recipe)
                    } })
                val destination = File(root, "new")
                assertFailsWith<IOException> { starter.render("Six", destination) }
                assertFalse(destination.exists())
                assertEquals(before, snapshot(root))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `cancellation brackets native render callbacks without altering shared source arrays`() {
        var called = false
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { MeridianSixKits.buildPad(7) { called = true; synthetic(it) } }
            assertFalse(called)
        } finally { Thread.interrupted() }
        val snip = synthetic(PadRecipe(MeridianPresets.all().first()))
        val original = snip.samples.copyOf()
        try {
            assertFailsWith<CancellationException> { MeridianSixKits.buildPad(7) { Thread.currentThread().interrupt(); snip } }
        } finally { Thread.interrupted() }
        assertContentEquals(original, snip.samples)
    }

    @Test fun `six voice registration appends a distinct starter and preserves both old Meridian banks`() {
        val original = assertNotNull(StarterKits.byId("meridian"))
        val materials = assertNotNull(StarterKits.byId("meridian-materials"))
        val six = assertNotNull(StarterKits.byId("meridian-six"))
        assertEquals("MERIDIAN", original.displayName)
        assertEquals("MERIDIAN MATERIALS", materials.displayName)
        assertEquals("MERIDIAN SIX", six.displayName)
        assertTrue(StarterKits.ALL.indexOf(six) > StarterKits.ALL.indexOf(materials))
        assertFalse(six.seeded)
        assertNull(six.key)
        val metal = StarterShelf.GROUPS.single { "meridian" in it.ids }
        assertEquals(metal.ids.indexOf("meridian") + 1, metal.ids.indexOf("meridian-materials"))
        assertEquals(metal.ids.indexOf("meridian-materials") + 1, metal.ids.indexOf("meridian-six"))
        assertEquals(14, com.snipsnap.synth.MeridianMaterialsKits.factoriesV1().size)
        assertEquals(16, com.snipsnap.synth.MeridianMaterialsKits.entries().size)
        val old = MeridianKits.patches()
        assertEquals(16, old.size)
        assertTrue(old.all { it.voice == MeridianVoice.SWEEP && it.model == 2 })
        assertEquals(listOf(60, 63, 65, 67, 70, 72, 75, 77), old.take(8).map { it.midi })
        assertEquals(MeridianPresets.forVoice(MeridianVoice.SWEEP), old.drop(8))
    }

    @Test fun `old materials and six voice bank histories cannot be relabelled as one another`() {
        val oldPad = com.snipsnap.synth.MeridianMaterialsKits.buildPad(7, ::synthetic)
        val oldText = oldPad.source.getValue(com.snipsnap.synth.MeridianMaterialsVelocityRecipe.SOURCE_KEY)
        val newPad = MeridianSixKits.buildPad(7, ::synthetic)
        val newText = newPad.source.getValue(MeridianSixVelocityRecipe.SOURCE_KEY)
        assertFails { MeridianSixVelocityRecipe.fromJsonText(oldText) }
        assertFails { com.snipsnap.synth.MeridianMaterialsVelocityRecipe.fromJsonText(newText) }
        assertEquals(oldText, com.snipsnap.synth.MeridianMaterialsVelocityRecipe.fromJsonText(oldText).toJsonText())
        // A valid MeridianPatch with recomputed checksums still cannot replace a fixed factory.
        val original = MeridianSixVelocityRecipe.fromJsonText(newText).toJsonValue()
        for (change in listOf<(MeridianPatch) -> MeridianPatch>(
            { it.copy(seedContext = it.seedContext + 1) },
            { it.copy(macros = it.macros + ("TUNE" to .6f)) },
            { it.copy(macros = it.macros + ("HOLD" to 1f)) },
        )) {
            val o = original.obj().toMutableMap()
            val patch = change(MeridianPatch.fromJsonValue(o.getValue("factoryPatch")) as MeridianPatch)
            o["factoryPatch"] = patch.toJsonValue()
            o["factoryPatchSha256"] = JsonValue.Str(java.security.MessageDigest.getInstance("SHA-256")
                .digest(patch.toJsonText().toByteArray()).joinToString("") { "%02x".format(it) })
            assertFails { MeridianSixVelocityRecipe.fromJsonValue(JsonValue.Obj(o)) }
        }
    }

    @Test fun `occupied native and legacy export destinations preserve prior shelf bytes`() {
        val root = createTempDirectory("meridian-six-occupied").toFile()
        try {
            val samples = File(root, "kit")
            val kit = fixture(samples)
            val first = Mpc3Exporter.exportTrack(kit, samples, File(root, "export"), mpc2Twin = true)
            val exported = snapshot(root)
            assertFails { Mpc3Exporter.exportTrack(kit, samples, File(root, "export"), mpc2Twin = true) }
            assertEquals(exported, snapshot(root))
            assertTrue(first.program.isFile)
            KitExporter.exportProgramFolder(kit, samples, File(root, "legacy"))
            val legacyBefore = snapshot(root)
            assertFails { KitExporter.exportProgramFolder(kit, samples, File(root, "legacy")) }
            assertEquals(legacyBefore, snapshot(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun `native three octave roles retain complete recipes touches and pitched finite output`() {
        for (slot in 14..16) {
            val pad = MeridianSixKits.buildPad(slot)
            val bank = MeridianSixVelocityRecipe.fromJsonText(pad.source.getValue(MeridianSixVelocityRecipe.SOURCE_KEY))
            val initial = pad.softVariants + pad.snip
            val replay = bank.replay()
            assertEquals(3, initial.size)
            for ((index, snip) in initial.withIndex()) {
                assertContentEquals(snip.samples, replay[index].samples)
                assertTrue(snip.samples.all { it.isFinite() && kotlin.math.abs(it) <= 1f })
                assertTrue(kotlin.math.abs(snip.samples.map { it.toDouble() }.average()) < .005)
                val patch = bank.layers[index].recipe.patch as MeridianPatch
                val estimate = assertNotNull(com.snipsnap.audio.Pitch.detect(snip, .18f, .35f))
                val cents = 1200 * kotlin.math.ln(estimate.hz / com.snipsnap.synth.Keys.midiHz(patch.midi).toDouble()) / kotlin.math.ln(2.0)
                assertTrue(estimate.confidence >= .3f && kotlin.math.abs(cents) < 50,
                    "slot$slot touch${patch.velocity}: root$cents/confidence${estimate.confidence}")
                // Float.equals distinguishes signed zeros; both are exact numeric silence.
                val ending = snip.samples.last()
                assertTrue(ending.isFinite() && ending == 0f,
                    "slot$slot touch${patch.velocity}: finite endpoint must be numeric zero, got $ending")
                println("MERIDIAN six native slot$slot ${patch.voice} MIDI${patch.midi} touch${patch.velocity} cents=$cents confidence=${estimate.confidence}")
            }
        }
    }
}
