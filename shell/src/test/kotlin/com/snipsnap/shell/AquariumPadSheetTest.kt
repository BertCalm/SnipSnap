package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.AquariumOfflineRenderer
import com.snipsnap.synth.AquariumRecipe
import com.snipsnap.synth.AquariumRenderRecipe
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Storage/facade integration using an injected deterministic gain fixture, not an Aquarium DSP substitute. */
class AquariumPadSheetTest {
    private val temp = java.nio.file.Files.createTempDirectory("aquarium-pad-sheet").toFile()
    @AfterTest fun cleanup() { temp.deleteRecursively() }

    private fun sound(gain: Float = .4f, channels: Int = 1): Snip = Snip(FloatArray(256 * channels) { index ->
        val frame = index / channels
        if (channels == 1 || index % 2 == 0) (gain * sin(2 * PI * frame / 37)).toFloat()
        else (-gain * .43 * cos(2 * PI * frame / 23)).toFloat()
    }, channels, 44_100)

    private fun opaqueRecipe(): JsonValue.Obj = Json.parse("""{"future":{"unknown":[1,"preserve",null]},"sourceLabel":"my take"}""") as JsonValue.Obj

    private fun model(
        name: String = "Aquarium",
        channels: Int = 1,
        layered: Boolean = false,
        shared: Boolean = false,
        recipe: JsonValue.Obj? = opaqueRecipe(),
        smpl: SmplChunk? = null,
    ): KitBuilderModel {
        val dir = File(temp, name).apply { mkdirs() }
        WavWriter.write(File(dir, "main.wav"), sound(channels = channels), smpl = smpl)
        if (layered) WavWriter.write(File(dir, "soft.wav"), sound(.1f, channels))
        val layers = if (layered) listOf(KitLayer("soft.wav", 0, 41), KitLayer("main.wav", 42, 83), KitLayer("main.wav", 84, 127)) else emptyList()
        val pad = KitPad(1, "main.wav", recipe = recipe, velocityLayers = layers)
        KitStore.save(Kit(name, if (shared) listOf(pad, pad.copy(slot = 2)) else listOf(pad)), dir)
        return KitBuilderModel.open(dir)
    }

    private fun context(mix: Float = 1f, gain: Float = .5f, tail: Double = 0.0, fade: Double = 0.0): AquariumRenderRecipe = AquariumRenderRecipe(
        AquariumRecipe(mapOf("aqua_surface_mix" to mix), seed = 0xffff_ffffL),
        bpm = 113.25, tailSeconds = tail, fadeSeconds = fade, outputGain = gain,
    )

    /** Same scalar for every source/zone; preserves independent stereo and obeys only the host contract. */
    private fun fixture(source: Snip, recipe: AquariumRenderRecipe): Snip {
        val frames = source.frameCount + recipe.tailFrames(source.sampleRate)
        val gain = recipe.outputGain * (.5f + .5f * recipe.settings.parameters.getValue("aqua_surface_mix"))
        val out = FloatArray(frames * 2)
        for (frame in 0 until source.frameCount) for (channel in 0..1) {
            out[frame * 2 + channel] = source.samples[frame * source.channels + if (source.channels == 1) 0 else channel] * gain
        }
        val fade = recipe.fadeFrames(source.sampleRate).coerceAtMost(frames)
        for (i in 0 until fade) for (channel in 0..1) out[(frames - 1 - i) * 2 + channel] *= i.toFloat() / fade
        return Snip(out, 2, source.sampleRate)
    }

    private val renderer = AquariumOfflineRenderer(::fixture)
    private fun read(model: KitBuilderModel, file: String = model.pad(1)!!.sampleFile): Snip = WavReader.read(File(model.kitDir, file))
    private fun bytes(model: KitBuilderModel, file: String = model.pad(1)!!.sampleFile): ByteArray = File(model.kitDir, file).readBytes()
    private fun encoded(snip: Snip, smpl: SmplChunk? = null): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24, smpl = smpl)
    }.toByteArray()
    private fun liveBytes(model: KitBuilderModel): Map<String, List<Byte>> = model.kitDir.listFiles()!!.filter {
        it.isFile && (it.extension.equals("wav", ignoreCase = true) || it.name == "kit.json")
    }.associate { it.name to it.readBytes().toList() }
    private fun allFiles(dir: File): Map<String, List<Byte>> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).path to it.readBytes().toList() }
    private fun sourceId(pad: KitPad): String = pad.recipe!!.entries.getValue("source").str()

    @Test fun `mono and independent stereo print at source rate and zero mixes restore opaque originals`() {
        for (channels in listOf(1, 2)) {
            val m = model(name = "Channels-$channels", channels = channels)
            val original = m.pad(1)!!
            val originalBytes = bytes(m)
            val dry = read(m)
            val wet = context(tail = .001, fade = .0001)
            val applied = AquariumPadSheet.apply(m, 1, wet, renderer)
            assertEquals(wet, AquariumPadSheet.read(applied.recipe))
            assertTrue(encoded(fixture(dry, wet)).contentEquals(bytes(m)))
            assertEquals(2, read(m).channels)
            assertEquals(dry.sampleRate, read(m).sampleRate)
            assertEquals(dry.frameCount + wet.tailFrames(dry.sampleRate), read(m).frameCount)
            if (channels == 2) assertTrue(read(m).samples.indices.filter { it % 2 == 0 }.any { read(m).samples[it] != read(m).samples[it + 1] })
            val neverRender = AquariumOfflineRenderer { _, _ -> error("zero mixes must not invoke the renderer") }
            val preview = AquariumPadSheet.preview(m, 1, AquariumRenderRecipe(), neverRender)
            assertEquals(channels, preview.channels)
            assertTrue(encoded(preview).contentEquals(originalBytes))
            assertEquals(original, AquariumPadSheet.apply(m, 1, AquariumRenderRecipe(), neverRender))
            assertTrue(originalBytes.contentEquals(bytes(m)))
        }
    }

    @Test fun `each distinct velocity file prints once shared files stay intact and zone gain ratios survive`() {
        val m = model(layered = true, shared = true, channels = 2)
        val dryMain = read(m, "main.wav")
        val drySoft = read(m, "soft.wav")
        val originals = liveBytes(m)
        val other = m.pad(2)!!
        val seen = mutableListOf<Float>()
        val counting = AquariumOfflineRenderer { source, recipe -> seen += source.peak(); fixture(source, recipe) }
        val active = AquariumPadSheet.apply(m, 1, context(), counting)
        assertEquals(2, seen.size, "main reused by two zones is one distinct source")
        assertEquals(listOf(0 to 41, 42 to 83, 84 to 127), active.velocityLayers.map { it.velStart to it.velEnd })
        assertEquals(active.sampleFile, active.velocityLayers[1].sampleFile)
        assertEquals(active.sampleFile, active.velocityLayers[2].sampleFile)
        assertEquals(other, m.pad(2))
        assertEquals(originals.getValue("main.wav"), bytes(m, "main.wav").toList())
        assertEquals(originals.getValue("soft.wav"), bytes(m, "soft.wav").toList())
        val main = read(m)
        val soft = read(m, active.velocityLayers.first().sampleFile)
        assertTrue(encoded(fixture(dryMain, context())).contentEquals(bytes(m)))
        assertTrue(encoded(fixture(drySoft, context())).contentEquals(bytes(m, active.velocityLayers.first().sampleFile)))
        assertTrue(abs(main.peak() / soft.peak() - dryMain.peak() / drySoft.peak()) < .0001f, "no independent zone normalization")
        val removed = AquariumPadSheet.remove(m, 1)
        assertEquals(other.velocityLayers, removed.velocityLayers)
        assertEquals(other.recipe, removed.recipe)
    }

    @Test fun `settings and context edits replay the immutable source after save reopen and undo`() {
        val m = model(channels = 2)
        val baseline = read(m)
        val first = AquariumPadSheet.apply(m, 1, context(gain = .4f, tail = .001), renderer)
        val firstBytes = bytes(m)
        m.save()
        val reopened = KitBuilderModel.open(m.kitDir)
        val secondRecipe = context(mix = .25f, gain = .7f, tail = .002, fade = .001)
        val seen = mutableListOf<List<Float>>()
        val second = AquariumPadSheet.apply(reopened, 1, secondRecipe, AquariumOfflineRenderer { input, recipe ->
            seen += input.samples.toList(); fixture(input, recipe)
        })
        assertEquals(listOf(baseline.samples.toList()), seen)
        assertNotEquals(first.sampleFile, second.sampleFile)
        assertTrue(encoded(fixture(baseline, secondRecipe)).contentEquals(bytes(reopened)))
        reopened.save()
        val again = KitBuilderModel.open(reopened.kitDir)
        assertEquals(secondRecipe.toJsonText(), AquariumPadSheet.read(again.pad(1)!!.recipe)!!.toJsonText())
        again.update(1) { it.copy(level = .2f, pan = .8f, attack = .3f, cutoff = .4f, displayName = "Current name") }
        assertTrue(AquariumPadSheet.canUndo(again, 1))
        val undone = AquariumPadSheet.undo(again, 1)
        assertEquals(first.recipe, undone.recipe)
        assertTrue(firstBytes.contentEquals(bytes(again)))
        assertEquals(.2f, undone.level)
        assertEquals(.8f, undone.pan)
        assertEquals(.3f, undone.attack)
        assertEquals(.4f, undone.cutoff)
        assertEquals("Current name", undone.displayName)
        val removed = AquariumPadSheet.remove(again, 1)
        assertEquals(opaqueRecipe(), removed.recipe)
        assertEquals(.2f, removed.level)
        assertEquals(.8f, removed.pan)
        assertEquals("Current name", removed.displayName)
        assertTrue(encoded(baseline).contentEquals(bytes(again)))
    }

    @Test fun `failure on a second source leaves pad references live wavs and saved kit untouched`() {
        val m = model(layered = true, shared = true)
        val before = m.kit
        val live = liveBytes(m)
        var calls = 0
        assertFailsWith<IllegalStateException> {
            AquariumPadSheet.apply(m, 1, context(), AquariumOfflineRenderer { input, recipe ->
                calls++
                if (calls == 2) error("injected second-source failure")
                fixture(input, recipe)
            })
        }
        assertEquals(2, calls)
        assertEquals(before, m.kit)
        assertEquals(live, liveBytes(m), "immutable backup artifacts may exist, but adoption and live audio are unchanged")
    }

    @Test fun `wrong channels rate tail nonfinite peak and missing fade refuse before adopting audio`() {
        for (failure in listOf("channels", "rate", "tail", "nonfinite", "ceiling", "fade")) {
            val m = model(name = "Bad-$failure", layered = true)
            val before = m.kit
            val live = liveBytes(m)
            val recipe = context(fade = if (failure == "fade") .001 else 0.0)
            val bad = AquariumOfflineRenderer { input, descriptor ->
                val out = fixture(input, descriptor)
                when (failure) {
                    "channels" -> Snip(FloatArray(out.frameCount), 1, out.sampleRate)
                    "rate" -> Snip(out.samples.copyOf(), 2, 48_000)
                    "tail" -> Snip(out.samples.copyOf(out.samples.size - 2), 2, out.sampleRate)
                    "nonfinite" -> Snip(out.samples.copyOf().also { it[0] = Float.NaN }, 2, out.sampleRate)
                    "ceiling" -> Snip(out.samples.copyOf().also { it[0] = 1f }, 2, out.sampleRate)
                    else -> Snip(out.samples.copyOf().also { it[it.lastIndex] = .1f }, 2, out.sampleRate)
                }
            }
            assertFailsWith<IllegalArgumentException>(failure) { AquariumPadSheet.apply(m, 1, recipe, bad) }
            assertEquals(before, m.kit, failure)
            assertEquals(live, liveBytes(m), failure)
        }
    }

    @Test fun `saved baseline and current audio content mismatches refuse replay removal and undo`() {
        for (changed in listOf("snapshot", "backup", "current")) {
            val m = model(name = "Hash-$changed")
            val active = AquariumPadSheet.apply(m, 1, context(), renderer)
            val snapshot = File(m.kitDir, ".aquarium/states/${sourceId(active)}.json")
            when (changed) {
                "snapshot" -> snapshot.appendText(" ")
                "backup" -> {
                    val inventory = Json.parse(snapshot.readText()).obj().getValue("inventory").obj()
                    val hash = inventory.getValue("files").arr().first().obj().getValue("sha256").str()
                    WavWriter.write(File(m.kitDir, ".aquarium/audio/$hash.wav"), sound(.2f))
                }
                else -> WavWriter.write(File(m.kitDir, active.sampleFile), sound(.2f, 2))
            }
            val before = m.kit
            val live = liveBytes(m)
            val never = AquariumOfflineRenderer { _, _ -> error("bad hashes must refuse before rendering") }
            assertFailsWith<IllegalArgumentException> { AquariumPadSheet.preview(m, 1, context(mix = .5f), never) }
            assertFailsWith<IllegalArgumentException> { AquariumPadSheet.apply(m, 1, context(mix = .5f), never) }
            assertFailsWith<IllegalArgumentException> { AquariumPadSheet.remove(m, 1) }
            assertFalse(AquariumPadSheet.canUndo(m, 1))
            assertFailsWith<IllegalArgumentException> { AquariumPadSheet.undo(m, 1) }
            assertEquals(before, m.kit)
            assertEquals(live, liveBytes(m))
        }
    }

    @Test fun `finite pitched source keeps smpl root while sustain loop refuses before storage changes`() {
        val m = model(name = "Pitched", smpl = SmplChunk(57))
        val original = bytes(m)
        AquariumPadSheet.apply(m, 1, context(), renderer)
        val smpl = WavReader.readSmpl(File(m.kitDir, m.pad(1)!!.sampleFile))!!
        assertEquals(57, smpl.rootNote)
        assertNull(smpl.loop)
        AquariumPadSheet.remove(m, 1)
        assertTrue(original.contentEquals(bytes(m)))

        val looped = model(name = "Looped", smpl = SmplChunk(60, SmplChunk.Loop(4, 200)))
        val before = allFiles(looped.kitDir)
        val kit = looped.kit
        assertFailsWith<IllegalArgumentException> { AquariumPadSheet.preview(looped, 1, context(), renderer) }
        assertFailsWith<IllegalArgumentException> { AquariumPadSheet.apply(looped, 1, context(), renderer) }
        assertEquals(kit, looped.kit)
        assertEquals(before, allFiles(looped.kitDir))
    }

    @Test fun `standalone import carries layered baseline and removes without donor kit or name collisions`() {
        val source = model(name = "Donor", channels = 2, layered = true)
        val dry = read(source)
        val drySoft = read(source, "soft.wav")
        val active = AquariumPadSheet.apply(source, 1, context(), renderer)
        val targetDir = File(temp, "Imported").apply { mkdirs() }
        val unique = (listOf(active.sampleFile) + active.velocityLayers.map { it.sampleFile }).distinct()
        val mapping = unique.mapIndexed { index, name -> name to "copy-$index.wav" }.toMap()
        for ((old, name) in mapping) File(source.kitDir, old).copyTo(File(targetDir, name))
        val copied = active.copy(sampleFile = mapping.getValue(active.sampleFile), velocityLayers = active.velocityLayers.map { it.copy(sampleFile = mapping.getValue(it.sampleFile)) })
        WavWriter.write(File(targetDir, "main.wav"), sound(.05f))
        val unrelated = File(targetDir, "main.wav").readBytes()
        val imported = AquariumPadSheet.importPad(source.kitDir, targetDir, active, copied)
        KitStore.save(Kit("Imported", listOf(imported, KitPad(2, "main.wav"))), targetDir)
        source.kitDir.deleteRecursively()
        val target = KitBuilderModel.open(targetDir)
        assertFalse(AquariumPadSheet.canUndo(target, 1))
        assertTrue(encoded(dry).contentEquals(encoded(AquariumPadSheet.preview(target, 1, AquariumRenderRecipe(), renderer))))
        AquariumPadSheet.apply(target, 1, context(mix = .5f, gain = .4f), renderer)
        assertTrue(encoded(fixture(dry, context(mix = .5f, gain = .4f))).contentEquals(bytes(target)))
        val removed = AquariumPadSheet.remove(target, 1)
        assertEquals(opaqueRecipe(), removed.recipe)
        assertTrue(removed.sampleFile.startsWith("WS_"))
        assertTrue(removed.sampleFile.length <= 64)
        assertTrue(encoded(dry).contentEquals(bytes(target)))
        assertTrue(encoded(drySoft).contentEquals(bytes(target, removed.velocityLayers.first().sampleFile)))
        assertTrue(unrelated.contentEquals(File(targetDir, "main.wav").readBytes()))
    }

    @Test fun `recipe replay copies controls and context onto destination source without donor provenance`() {
        val donor = model(name = "RecipeDonor", channels = 2)
        val active = AquariumPadSheet.apply(donor, 1, context(mix = .5f, gain = .4f, tail = .001), renderer)
        val target = model(name = "RecipeTarget")
        WavWriter.write(File(target.kitDir, "main.wav"), sound(.15f))
        val destinationDry = read(target)
        val recipe = active.recipe!!
        val plan = RecipeReplay.plan(recipe) as RecipeReplay.Plan.Aquarium
        assertEquals(AquariumPadSheet.read(recipe), plan.recipe)
        val done = RecipeReplay.apply(target, 1, recipe, "Target", aquariumRenderer = renderer)
        assertEquals(plan.recipe, AquariumPadSheet.read(done.pad.recipe))
        assertNotEquals(sourceId(active), sourceId(done.pad), "the destination saves its own baseline")
        assertTrue(encoded(fixture(destinationDry, plan.recipe)).contentEquals(bytes(target)))
        val targetJson = Json.write(done.pad.recipe!!)
        assertFalse(targetJson.contains(sourceId(active)))
        val donorHashes = recipe.entries.getValue("inventory").obj().getValue("files").arr().map { it.obj().getValue("sha256").str() }
        assertTrue(donorHashes.none { targetJson.contains(it) })
        donor.kitDir.deleteRecursively()
        AquariumPadSheet.remove(target, 1)
        assertTrue(encoded(destinationDry).contentEquals(bytes(target)))
    }

    /** The aquarium's own store keeps the treated source; the kit bin has to keep what that source's undo reads. */
    @Test fun `an aquarium print over a treated pad keeps the source's original in the kit bin, and after REMOVE, undo gives it back`() {
        val m = model(name = "BinKept", recipe = null)
        val original = bytes(m)
        m.treatPad(1, "crushed")
        AquariumPadSheet.apply(m, 1, context(), renderer)
        m.save()

        val swept = KitBuilderModel.open(m.kitDir).purgeBin(olderThanDays = 0.0, nowMillis = System.currentTimeMillis() + 1000)
        assertEquals(0, swept, "the sweep took the original the print's treated source needs")
        assertEquals(listOf<Int?>(1), m.binHolds().map { it.keptFor }, "kept for the printed pad's slot")
        AquariumPadSheet.remove(m, 1)
        m.untreatPad(1)
        assertTrue(original.contentEquals(bytes(m)), "undo after REMOVE gives the original back")
    }

    /** A print over a print: the sweep follows both sources down to the treated pad, whose undo reads the kit bin. */
    @Test fun `an aquarium over a board over a treated pad keeps the original underneath both, and after both REMOVEs, undo gives it back`() {
        val m = model(name = "BinKeptNested", recipe = null)
        val original = bytes(m)
        m.treatPad(1, "crushed")
        val board = BoardSheet.apply(m, 1, .35f)
        AquariumPadSheet.apply(m, 1, context(), renderer)
        m.save()
        assertEquals(setOf(board.sampleFile, "main.wav"), m.filesUnderPrints(), "both levels under the aquarium: the board's file and the treated pad's")

        val swept = KitBuilderModel.open(m.kitDir).purgeBin(olderThanDays = 0.0, nowMillis = System.currentTimeMillis() + 1000)
        assertEquals(0, swept, "the sweep took the original the board's treated source needs")
        assertEquals(listOf<Int?>(1), m.binHolds().map { it.keptFor }, "kept for the printed pad's slot")
        AquariumPadSheet.remove(m, 1)
        BoardSheet.remove(m, 1)
        m.untreatPad(1)
        assertTrue(original.contentEquals(bytes(m)), "undo after both REMOVEs gives the original back")
    }

    /**
     * An ejected aquarium over a board, past its tombstone's days, whose inner board source will not
     * read: the whole print is skipped, as binHolds skips it, and the level that did read (the board's
     * own file) is not left behind to take BACK off a row it has no claim on.
     */
    @Test fun `an overdue print over a print with a damaged inner source names nothing, not the level it read`() {
        val m = model(name = "UnderOverdueNested", recipe = null)
        m.treatPad(1, "crushed")
        val board = BoardSheet.apply(m, 1, .35f)
        val source = (board.recipe!!.entries.getValue("source") as JsonValue.Str).value
        AquariumPadSheet.apply(m, 1, context(), renderer)
        m.clear(1) // the tombstone holds the aquarium, over the board, over the treated pad
        m.save()
        val now = System.currentTimeMillis()
        assertEquals(setOf(board.sampleFile, "main.wav"), m.filesUnderPrints(nowMillis = now), "test setup: both levels while BACK can bring it back")
        File(m.kitDir, ".boards/states/$source.json").appendText(" ")
        assertEquals(emptySet(), m.filesUnderPrints(nowMillis = now + 31L * 24 * 60 * 60 * 1000), "past its days, nothing of it, not even the aquarium level that read")
    }

    @Test fun `nested board source can remove locally but standalone import refuses before destination writes`() {
        val source = model(name = "Nested")
        val board = BoardSheet.apply(source, 1, .35f)
        val boardBytes = bytes(source)
        val active = AquariumPadSheet.apply(source, 1, context(), renderer)
        val target = File(temp, "NestedCopy").apply { mkdirs() }
        File(source.kitDir, active.sampleFile).copyTo(File(target, "copied.wav"))
        val before = allFiles(target)
        val error = assertFailsWith<IllegalArgumentException> {
            AquariumPadSheet.importPad(source.kitDir, target, active, active.copy(sampleFile = "copied.wav"))
        }
        assertTrue(error.message.orEmpty().contains("complete kit folder"))
        assertEquals(before, allFiles(target))
        assertEquals(board.recipe, AquariumPadSheet.remove(source, 1).recipe)
        assertTrue(boardBytes.contentEquals(bytes(source)))
        assertEquals(opaqueRecipe(), BoardSheet.remove(source, 1).recipe)
    }

    @Test fun `pad changes during a render refuse stale adoption and retain current controls and saved live bytes`() {
        val m = model(name = "Concurrent")
        val original = m.pad(1)!!
        val live = liveBytes(m)
        var changed = false
        assertFailsWith<IllegalArgumentException> {
            AquariumPadSheet.apply(m, 1, context(), AquariumOfflineRenderer { input, recipe ->
                if (!changed) {
                    changed = true
                    m.update(1) { it.copy(level = .23f, displayName = "Edited while rendering") }
                }
                fixture(input, recipe)
            })
        }
        assertEquals(original.sampleFile, m.pad(1)!!.sampleFile)
        assertEquals(original.recipe, m.pad(1)!!.recipe)
        assertEquals(.23f, m.pad(1)!!.level)
        assertEquals("Edited while rendering", m.pad(1)!!.displayName)
        for ((name, oldBytes) in live) assertEquals(oldBytes, bytes(m, name).toList(), name)
        assertFalse(AquariumPadSheet.canUndo(m, 1))
    }

    @Test fun `stored bypass descriptor is malformed and refuses edits without changing the current pad`() {
        val m = model(name = "StoredBypass")
        val active = AquariumPadSheet.apply(m, 1, context(), renderer)
        val malformed = JsonValue.Obj(active.recipe!!.entries + ("render" to AquariumRenderRecipe().toJsonValue()))
        m.update(1) { it.copy(recipe = malformed) }
        m.save()
        val reopened = KitBuilderModel.open(m.kitDir)
        val before = reopened.kit
        val files = allFiles(reopened.kitDir)
        assertNull(AquariumPadSheet.read(reopened.pad(1)!!.recipe))
        val never = AquariumOfflineRenderer { _, _ -> error("malformed stored descriptors must refuse before rendering") }
        val refusal = assertFailsWith<IllegalArgumentException> { AquariumPadSheet.apply(reopened, 1, context(), never) }
        assertTrue(refusal.message.orEmpty().contains("active effect"))
        assertFailsWith<IllegalArgumentException> { AquariumPadSheet.preview(reopened, 1, context(), never) }
        assertFailsWith<IllegalArgumentException> { AquariumPadSheet.remove(reopened, 1) }
        assertFailsWith<IllegalArgumentException> { AquariumPadSheet.undo(reopened, 1) }
        assertFalse(AquariumPadSheet.canUndo(reopened, 1))
        assertEquals(before, reopened.kit)
        assertEquals(files, allFiles(reopened.kitDir))
    }
}
