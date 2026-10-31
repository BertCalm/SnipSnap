package com.snipsnap.shell

import com.snipsnap.audio.Scale
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitMerge
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.AquariumOfflineRenderer
import com.snipsnap.synth.AquariumRecipe
import com.snipsnap.synth.AquariumRenderRecipe
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Host integration tests use an injected renderer; native parity is verified by the native harness. */
class PrintedPadsIntegrationTest {
    private val temp = kotlin.io.path.createTempDirectory("printed-pads-integration").toFile()
    @AfterTest fun cleanup() { temp.deleteRecursively() }

    private val context = AquariumRenderRecipe(
        settings = AquariumRecipe(mapOf("aqua_surface_mix" to .5f, "aqua_surface_gain" to -3f)),
        bpm = 113.0, tailSeconds = 0.0, fadeSeconds = 0.0, outputGain = 1f,
    )

    private val renderer = AquariumOfflineRenderer { source, recipe ->
        val frames = source.frameCount + recipe.tailFrames(source.sampleRate)
        Snip(FloatArray(frames * 2) { index ->
            val frame = index / 2
            if (frame >= source.frameCount) 0f else {
                val channel = index % 2
                val original = source.samples[frame * source.channels + if (source.channels == 1) 0 else channel]
                original * recipe.outputGain * if (channel == 0) .5f else .3f
            }
        }, 2, source.sampleRate)
    }

    private fun tone(hz: Double, gain: Double = .4): Snip = Snip(FloatArray(2205) { frame ->
        (gain * sin(2 * PI * hz * frame / 44100)).toFloat()
    }, 1, 44100)

    private fun opaque(label: String): JsonValue.Obj = Json.parse(
        """{"history":"$label","future":{"unknown":[1,null,"keep exactly"]}}""",
    ) as JsonValue.Obj

    private fun model(name: String, layered: Boolean = false): KitBuilderModel {
        val dir = File(temp, name).apply { mkdirs() }
        WavWriter.write(File(dir, "main.wav"), tone(220.0))
        WavWriter.write(File(dir, "destination.wav"), tone(660.0, .2))
        if (layered) WavWriter.write(File(dir, "soft.wav"), tone(330.0, .1))
        val layers = if (layered) listOf(KitLayer("soft.wav", 1, 63), KitLayer("main.wav", 64, 127)) else emptyList()
        KitStore.save(Kit(name, listOf(
            KitPad(1, "main.wav", recipe = opaque("source"), velocityLayers = layers,
                source = mapOf("origin" to "capture", "rawSha256" to "previous WAV")),
            KitPad(2, "destination.wav", recipe = opaque("destination")),
        )), dir)
        return KitBuilderModel.open(dir)
    }

    private fun files(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(dir).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(it.readBytes())
            .joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }
    }

    private fun encoded(snip: Snip): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24)
    }.toByteArray()

    @Test
    fun `Aquarium blocks soft hit stack and spread changes before touching its saved source`() {
        for (layered in listOf(false, true)) {
            val model = model("Guards $layered", layered)
            val original = model.pad(1)!!
            val kept = AquariumPadSheet.apply(model, 1, context, renderer)
            val before = files(model.kitDir)
            val layerError = assertFailsWith<IllegalArgumentException> {
                if (layered) model.clearGhostLayers(1) else model.addGhostLayers(1)
            }
            assertEquals("REMOVE AQUARIUM BEFORE CHANGING SOFT HITS.", layerError.message)
            val take = KitBuilderModel.BinEntry("main.wav", 0L, File(model.kitDir, "absent-take.wav"))
            val stackError = assertFailsWith<IllegalArgumentException> { model.stackTakes(1, listOf(take)) }
            assertEquals("REMOVE AQUARIUM BEFORE STACK THE TAKES.", stackError.message)

            val plan = Spread.plan(model.kit, 60f, Spread.Options(60, Scale.MAJOR, padCount = 4, bank = 1, replaceFull = true))
            for (sound in listOf(
                Spread.Sound("Aquarium", kept.drumClass, "#19645B", recipe = kept.recipe),
                Spread.Sound("Aquarium", kept.drumClass, "#19645B", like = kept),
            )) {
                val error = assertFailsWith<IllegalArgumentException> { Spread.apply(model, tone(220.0), plan, sound) }
                assertEquals("REMOVE AQUARIUM BEFORE SPREAD.", error.message)
            }
            assertEquals(kept, model.pad(1))
            assertEquals(before, files(model.kitDir), "every refusal precedes audio, source and undo writes")
            assertEquals("AQUARIUM", KitDiff.recipeName(assertNotNull(kept.recipe)))
            assertEquals("AQUARIUM", PadSheetBoxes.summaries(kept, null)[PadSheetBoxes.Box.TREATMENT])
            assertTrue(PadSheetBoxes.isTouched(kept))
            assertNull(kept.source["rawSha256"], "the old current-WAV fingerprint cannot describe the print")
            val restored = AquariumPadSheet.remove(model, 1)
            assertEquals(original.sampleFile, restored.sampleFile)
            assertEquals(original.velocityLayers, restored.velocityLayers)
            assertEquals(original.recipe, restored.recipe)
        }
    }

    @Test
    fun `malformed Aquarium markers stay guarded and cannot fall through to another replay family`() {
        val model = model("Damaged")
        val malformed = JsonValue.Obj(mapOf("aquariumPad" to JsonValue.Num(99.0), "patch" to JsonValue.Str("not a patch")))
        model.update(1) { it.copy(recipe = malformed) }
        val before = files(model.kitDir)
        assertTrue(PrintedPads.has(malformed))
        assertEquals("AQUARIUM", PrintedPads.label(malformed))
        assertNull(AquariumPadSheet.read(malformed))
        assertIs<RecipeReplay.Plan.Refused>(RecipeReplay.plan(malformed))
        assertFailsWith<IllegalArgumentException> { model.addGhostLayers(1) }
        assertFailsWith<IllegalArgumentException> { model.clearGhostLayers(1) }
        assertEquals(before, files(model.kitDir))
    }

    @Test
    fun `damaged printed recipes name the guarded treatment rather than claiming untouched`() {
        for ((marker, label) in listOf("aquariumPad" to "AQUARIUM", "boardPad" to "BOARD")) {
            val recipe = JsonValue.Obj(mapOf(marker to JsonValue.Num(99.0)))
            val pad = KitPad(1, "main.wav", recipe = recipe)
            assertEquals("$label · CHECK RECIPE", PadSheetBoxes.summaries(pad, null)[PadSheetBoxes.Box.TREATMENT])
            assertTrue(PadSheetBoxes.isTouched(pad))
            assertTrue(PrintedPads.has(recipe), "NONE must not treat a damaged printed marker as absent")
        }
        val opaque = KitPad(1, "main.wav", recipe = Json.parse(
            """{"future":{"aquariumPad":99,"boardPad":99}}""",
        ) as JsonValue.Obj)
        assertEquals(PadSheetBoxes.UNTOUCHED, PadSheetBoxes.summaries(opaque, null)[PadSheetBoxes.Box.TREATMENT])
        assertFalse(PrintedPads.has(opaque.recipe), "nested marker text remains ordinary opaque history")
    }

    @Test
    fun `Aquarium replay needs a renderer and copies controls onto the destination baseline`() {
        val model = model("Replay")
        val destination = model.pad(2)!!
        val destinationBytes = File(model.kitDir, destination.sampleFile).readBytes()
        val destinationAudio = WavReader.read(File(model.kitDir, destination.sampleFile))
        val donor = AquariumPadSheet.apply(model, 1, context, renderer)
        val recipe = assertNotNull(donor.recipe)
        assertEquals(context, assertIs<RecipeReplay.Plan.Aquarium>(RecipeReplay.plan(recipe)).recipe)
        val before = files(model.kitDir)
        val missing = assertFailsWith<IllegalArgumentException> {
            RecipeReplay.apply(model, 2, recipe, "DESTINATION")
        }
        assertEquals(PrintedPads.AQUARIUM_RENDERER_REQUIRED, missing.message)
        assertEquals(before, files(model.kitDir), "the absent native host is refused before source capture")
        assertEquals(destination, model.pad(2))

        val done = RecipeReplay.apply(model, 2, recipe, "DESTINATION", aquariumRenderer = renderer)
        assertEquals(context, AquariumPadSheet.read(done.pad.recipe))
        assertContentEquals(encoded(renderer.render(destinationAudio, context)), File(model.kitDir, done.pad.sampleFile).readBytes())
        assertTrue(done.toast.startsWith("AQUARIUM DONE AGAIN"))
        val restored = AquariumPadSheet.remove(model, 2)
        assertEquals(destination.recipe, restored.recipe, "REMOVE restores the destination's prior recipe, never the donor's")
        assertContentEquals(destinationBytes, File(model.kitDir, restored.sampleFile).readBytes())
    }

    @Test
    fun `an unlabelled replacement clears Aquarium history and becomes its own new baseline`() {
        val model = model("New source")
        AquariumPadSheet.apply(model, 1, context, renderer)
        val replacement = model.replaceAudio(1, null) { tone(880.0, .1) }
        val replacementBytes = File(model.kitDir, replacement.sampleFile).readBytes()
        assertNull(replacement.recipe)
        assertFalse(AquariumPadSheet.canUndo(model, 1))
        AquariumPadSheet.apply(model, 1, context, renderer)
        val restored = AquariumPadSheet.remove(model, 1)
        assertNull(restored.recipe)
        assertContentEquals(replacementBytes, File(model.kitDir, restored.sampleFile).readBytes())
    }

    @Test
    fun `generic printed adoption refuses metadata changes and stale pad snapshots`() {
        val model = model("Adoption")
        val original = model.pad(1)!!
        assertFailsWith<IllegalArgumentException> {
            model.replacePrintedPad(1, original, original.copy(displayName = "unrelated change"))
        }
        model.update(1) { it.copy(level = .2f) }
        assertFailsWith<IllegalArgumentException> { model.replacePrintedPad(1, original, original) }
        assertEquals(.2f, model.pad(1)!!.level)
    }

    @Test
    fun `merge importer carries all Aquarium zones and preserves editing after the parent kit disappears`() {
        val source = model("Merge source", layered = true)
        val original = source.pad(1)!!
        val originalFiles = (listOf(original.sampleFile) + original.velocityLayers.map { it.sampleFile }).distinct()
            .associateWith { File(source.kitDir, it).readBytes() }
        AquariumPadSheet.apply(source, 1, context, renderer)
        source.save()
        val other = model("Merge partner")
        val target = File(temp, "Merged Aquarium")
        KitMerge.merge(source.kitDir, other.kitDir, target,
            preflightPad = PrintedPads::requireImportable, importPad = PrintedPads::importPad)
        source.kitDir.deleteRecursively()
        val merged = KitBuilderModel.open(target)
        assertNotNull(AquariumPadSheet.read(merged.pad(1)!!.recipe))
        val restored = AquariumPadSheet.remove(merged, 1)
        assertEquals(original.recipe, restored.recipe)
        assertEquals(original.velocityLayers.map { it.velStart to it.velEnd }, restored.velocityLayers.map { it.velStart to it.velEnd })
        val names = (listOf(restored.sampleFile) + restored.velocityLayers.map { it.sampleFile }).distinct()
        assertEquals(originalFiles.size, names.size)
        for ((before, after) in originalFiles.values.zip(names)) {
            assertContentEquals(before, File(target, after).readBytes())
        }
    }

    @Test
    fun `breed keeps a printed Aquarium pad and its layers instead of crossing an old patch recipe`() {
        val source = model("Breed source", layered = true)
        val kept = AquariumPadSheet.apply(source, 1, context, renderer)
        val keptFiles = (listOf(kept.sampleFile) + kept.velocityLayers.map { it.sampleFile }).distinct()
            .associateWith { File(source.kitDir, it).readBytes() }
        source.save()
        val other = model("Breed partner")
        val target = File(temp, "Bred Aquarium")
        val report = Breed.breed(source.kitDir, other.kitDir, target)
        assertTrue(1 in report.kept)
        assertFalse(1 in report.crossed)
        val bred = report.kit.pad(1)!!
        assertEquals(context, AquariumPadSheet.read(bred.recipe))
        val names = (listOf(bred.sampleFile) + bred.velocityLayers.map { it.sampleFile }).distinct()
        for ((before, after) in keptFiles.values.zip(names)) assertContentEquals(before, File(target, after).readBytes())
        assertEquals(kept.velocityLayers.map { it.velStart to it.velEnd }, bred.velocityLayers.map { it.velStart to it.velEnd })
        assertNotNull(AquariumPadSheet.remove(KitBuilderModel.open(target), 1).recipe)
    }

    @Test
    fun `nested Aquarium sources refuse breed and source-aware merge before either child is created`() {
        val source = model("Nested source")
        BoardSheet.apply(source, 1, .7f)
        AquariumPadSheet.apply(source, 1, context, renderer)
        source.save()
        val other = model("Nested partner")
        val sourceBefore = files(source.kitDir)
        val otherBefore = files(other.kitDir)
        val breedTarget = File(temp, "Nested breed refused")
        val mergeTarget = File(temp, "Nested merge refused")

        assertFailsWith<IllegalArgumentException> { Breed.breed(source.kitDir, other.kitDir, breedTarget) }
        assertFalse(breedTarget.exists(), "a refused immutable-source import must not leave an empty visible child kit")
        assertFailsWith<IllegalArgumentException> {
            KitMerge.merge(source.kitDir, other.kitDir, mergeTarget,
                preflightPad = PrintedPads::requireImportable, importPad = PrintedPads::importPad)
        }
        assertFalse(mergeTarget.exists(), "merge validates its selected sources before mkdir or WAV copies")
        assertEquals(sourceBefore, files(source.kitDir))
        assertEquals(otherBefore, files(other.kitDir))
    }

    @Test
    fun `breed does not preflight an unused nested source in parent B`() {
        val source = model("Ordinary parent A")
        val other = model("Nested unused B")
        BoardSheet.apply(other, 1, .7f)
        AquariumPadSheet.apply(other, 1, context, renderer)
        other.save()
        val report = Breed.breed(source.kitDir, other.kitDir, File(temp, "Ordinary kept child"))
        assertTrue(1 in report.kept)
        assertEquals(source.pad(1)!!.recipe, report.kit.pad(1)!!.recipe)
        assertFalse(PrintedPads.has(report.kit.pad(1)!!.recipe), "B's source-backed sound never enters the child")
    }
}
