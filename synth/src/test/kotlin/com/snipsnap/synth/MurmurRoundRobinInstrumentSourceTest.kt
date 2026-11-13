package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MurmurRoundRobinInstrumentSourceTest {
    @AfterTest fun clear() {
        MurmurRoundRobinInstrumentSource.clearCache()
        MurmurSelectedInstrumentSource.clearCache()
    }

    @Test fun canonicalGridAndLegacySeparation() {
        val keys = mutableSetOf<String>()
        for (selected in MurmurKeptSources.all()) {
            val source = selected.factoryPatch()
            for (midi in listOf(selected.supportedMidi.first, selected.supportedMidi.last))
                for (touch in 0..2) for (take in 0..3) {
                    val recipe = MurmurRoundRobinInstrumentSource.cellRecipe(source, midi, touch, take)
                    assertEquals(source, recipe.sourcePatch())
                    assertEquals(source.copy(midi = midi, velocity = listOf(.35f, .65f, 1f)[touch]), recipe.cellPatch())
                    assertEquals(take, recipe.take)
                    assertEquals(recipe, MurmurRoundRobinInstrumentSource.CellRecipe.fromJsonText(recipe.toJsonText()))
                    assertTrue(keys.add(recipe.toJsonText()))
                    assertFails { MurmurSelectedInstrumentSource.CellRecipe.fromJsonValue(recipe.toJsonValue()) }
                    val old = MurmurSelectedInstrumentSource.cellRecipe(source, midi, touch)
                    assertFails { MurmurRoundRobinInstrumentSource.CellRecipe.fromJsonValue(old.toJsonValue()) }
                }
        }
        val binding = MurmurRoundRobinInstrumentSource.rendererBinding().obj()
        assertEquals("9df2972d2b9b2f189c87b0fb3b995dbf423cb058", binding.getValue("nativeSourceCommit").str())
        val inputs = binding.getValue("sourceInputsSha256").obj()
        assertEquals("4b0fe6b6148001619c44dd35fa7f06da6a7183dae4959935451edc6a158cc98e",
            inputs.getValue("synth/src/main/kotlin/com/snipsnap/synth/Murmur.kt").str())
        assertNotEquals(MurmurSelectedInstrumentSource.rendererBinding(), MurmurRoundRobinInstrumentSource.rendererBinding())
        assertFalse(binding.getValue("runtimeAdmissionImplied").bool())
    }

    @Test fun strictCodecRejectsLossyAndWrongBinding() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()
        val recipe = MurmurRoundRobinInstrumentSource.cellRecipe(source, 36, 1, 2)
        fun rejects(key: String, value: JsonValue) {
            assertFails { MurmurRoundRobinInstrumentSource.CellRecipe.fromJsonValue(
                JsonValue.Obj(recipe.toJsonValue().entries + (key to value))) }
        }
        for (key in listOf("version", "take", "touchIndex", "onsetFrames")) {
            for (value in listOf(.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
                rejects(key, JsonValue.Num(value))
        }
        for (take in listOf(-1, 4)) {
            rejects("take", JsonValue.Num(take.toDouble()))
            assertFails { MurmurRoundRobinInstrumentSource.cellRecipe(source, 36, 1, take) }
        }
        rejects("version", JsonValue.Num(2.0))
        rejects("take", JsonValue.Str("2"))
        rejects("touchIndex", JsonValue.Num(0.0)) // Does not match saved cell's absolute .65 touch.
        rejects("touchIndex", JsonValue.Num(3.0))
        rejects("onsetFrames", JsonValue.Num(220.0))
        rejects("selectionId", JsonValue.Str(MurmurKeptSources.FINITE_WIRE))
        rejects("outputPolicy", JsonValue.Str("HOUSE_ADJACENT_CONTEXT_V1"))
        rejects("roundRobinPolicy", JsonValue.Str("GAIN_ONLY"))
        rejects("rendererBinding", MurmurSelectedInstrumentSource.rendererBinding())
        rejects("rendererBinding", JsonValue.Obj(emptyMap()))
        rejects("extra", JsonValue.Null)
        for (key in recipe.toJsonValue().entries.keys) assertFails {
            MurmurRoundRobinInstrumentSource.CellRecipe.fromJsonValue(JsonValue.Obj(recipe.toJsonValue().entries - key))
        }
        val release = recipe.releaseSeconds.toDouble()
        assertEquals(release.toFloat(), Math.nextUp(release).toFloat())
        for (value in listOf(Math.nextUp(release), Double.NaN, Double.POSITIVE_INFINITY, 0.0, 2.0))
            rejects("releaseSeconds", JsonValue.Num(value))
        val cell = recipe.cellPatch().toJsonValue().entries
        rejects("cellPatch", JsonValue.Obj(cell + ("midi" to JsonValue.Num(55.0))))
        rejects("cellPatch", JsonValue.Obj(cell + ("velocity" to JsonValue.Num(.9))))
        rejects("sourcePatch", JsonValue.Obj(recipe.sourcePatch().toJsonValue().entries - "config"))
        rejects("sourcePatch", JsonValue.Obj(recipe.sourcePatch().toJsonValue().entries + ("gain" to JsonValue.Num(2.0))))
        assertFails { MurmurRoundRobinInstrumentSource.cellRecipe(source, 55, 1, 1) }
        assertFails { MurmurRoundRobinInstrumentSource.cellRecipe(source.copy(velocity = 0f), 36, 1, 1) }
    }

    @Test fun deepRecipeOwnership() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch()
        val recipe = MurmurRoundRobinInstrumentSource.cellRecipe(source, 48, 1, 2)
        val original = recipe.toJsonText()
        (source.macros as MutableMap<String, Float>)["HOLD"] = 0f
        (source.config.ratios as MutableList<Double>)[1] = 8.0
        val read = recipe.cellPatch()
        (read.config.targets as MutableList<MurmurFormantTarget>).clear()
        (read.macros as MutableMap<String, Float>)["BREATH"] = 0f
        (recipe.toJsonValue().entries as MutableMap<String, JsonValue>).clear()
        (MurmurRoundRobinInstrumentSource.rendererBinding().obj() as MutableMap<String, JsonValue>).clear()
        assertEquals(original, recipe.toJsonText())
        assertEquals(1f, recipe.sourcePatch().macros.getValue("HOLD"))
        assertEquals(.65f, recipe.cellPatch().velocity)
    }

    @Test fun cacheOwnershipBudgetAndTakeSeparation() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()
        fun fake(midi: Int, take: Int, frames: Int): MurmurRoundRobinInstrumentSource.Capture {
            val recipe = MurmurRoundRobinInstrumentSource.cellRecipe(source, midi, 1, take)
            val pcm = FloatArray(frames) { .1f }
            val hash = MurmurSelectedInstrumentSource.floatSha256(pcm)
            return MurmurRoundRobinInstrumentSource.Capture(recipe, Snip(pcm, 1, Dsp.RATE), 0, frames,
                hash, MurmurSelectedInstrumentSource.floatSha256(FloatArray(0)), hash, 0.0, Keys.midiHz(midi).toDouble())
        }
        val first = fake(36, 1, 1024)
        MurmurRoundRobinInstrumentSource.retainCapture(first)
        first.snip.samples[0] = .9f
        val hit = assertNotNull(MurmurRoundRobinInstrumentSource.cachedCapture(first.recipe))
        assertEquals(.1f, hit.snip.samples[0])
        hit.snip.samples[0] = -.7f
        assertEquals(.1f, assertNotNull(MurmurRoundRobinInstrumentSource.cachedCapture(first.recipe)).snip.samples[0])
        assertNull(MurmurRoundRobinInstrumentSource.cachedCapture(first.recipe.copy(take = 2)))
        assertNull(MurmurRoundRobinInstrumentSource.cachedCapture(first.recipe.copy(releaseSeconds = .4f)))
        assertNull(MurmurSelectedInstrumentSource.cachedCapture(MurmurSelectedInstrumentSource.cellRecipe(source, 36, 1)))
        for (midi in 37..55) MurmurRoundRobinInstrumentSource.retainCapture(fake(midi, 1, 1024))
        assertEquals(16, MurmurRoundRobinInstrumentSource.cachedEntries())
        assertNull(MurmurRoundRobinInstrumentSource.cachedCapture(first.recipe))
        MurmurRoundRobinInstrumentSource.clearCache()
        for (midi in 36..38) MurmurRoundRobinInstrumentSource.retainCapture(fake(midi, 1, 1_500_000))
        assertTrue(MurmurRoundRobinInstrumentSource.cachedPcmBytes() <= 16L * 1024 * 1024)
        assertEquals(2, MurmurRoundRobinInstrumentSource.cachedEntries())
    }

    @Test fun interruptedCacheHitRefused() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()
        val recipe = MurmurRoundRobinInstrumentSource.cellRecipe(source, source.midi, 1, 1)
        val pcm = FloatArray(1024) { .1f }; val hash = MurmurSelectedInstrumentSource.floatSha256(pcm)
        MurmurRoundRobinInstrumentSource.retainCapture(MurmurRoundRobinInstrumentSource.Capture(recipe,
            Snip(pcm, 1, Dsp.RATE), 0, pcm.size, hash, hash, hash, 0.0, 261.625565))
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { MurmurRoundRobinInstrumentSource.renderCell(recipe) }
        } finally { Thread.interrupted() }
    }

    @Test fun finiteTakeZeroMatchesLegacyAndColdReplay() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()
        val recipe = MurmurRoundRobinInstrumentSource.cellRecipe(source, source.midi, 1, 0)
        val legacy = MurmurSelectedInstrumentSource.renderCell(MurmurSelectedInstrumentSource.cellRecipe(source, source.midi, 1))
        val first = MurmurRoundRobinInstrumentSource.renderCell(recipe)
        assertContentEquals(legacy.snip.samples, first.snip.samples)
        assertEquals(legacy.rawSampleSha256, first.rawSampleSha256)
        assertEquals(legacy.finishedFloatSha256, first.finishedFloatSha256)
        assertEquals(0, first.loopStartFrame); assertEquals(first.snip.frameCount, first.loopEndFrameExclusive)
        MurmurRoundRobinInstrumentSource.clearCache()
        val replay = MurmurRoundRobinInstrumentSource.replay(Json.parse(recipe.toJsonText()))
        assertContentEquals(first.snip.samples, replay.snip.samples)
        assertEquals(first.finishedFloatSha256, replay.finishedFloatSha256)
        record("finite-zero", JsonValue.Obj(linkedMapOf("capture" to measurement(first),
            "legacyFinishedFloatSha256" to JsonValue.Str(legacy.finishedFloatSha256),
            "legacyTakeZeroExact" to JsonValue.Bool(true), "coldReplayExact" to JsonValue.Bool(true))))
    }

    @Test fun finiteTouchAndRangeCellsHaveIndependentKeys() {
        val selected = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE)
        val source = selected.factoryPatch()
        val recipes = listOf(
            MurmurRoundRobinInstrumentSource.cellRecipe(source, selected.supportedMidi.first, 0, 1),
            MurmurRoundRobinInstrumentSource.cellRecipe(source, selected.supportedMidi.first, 2, 1),
            MurmurRoundRobinInstrumentSource.cellRecipe(source, selected.supportedMidi.last, 1, 2),
            MurmurRoundRobinInstrumentSource.cellRecipe(MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch(), 36, 0, 3),
        )
        val captures = recipes.map { MurmurRoundRobinInstrumentSource.renderCell(it) }
        assertEquals(4, captures.map { it.finishedFloatSha256 }.toSet().size)
        assertEquals(listOf(.35f, 1f, .65f, .35f), captures.map { it.recipe.cellPatch().velocity })
        assertTrue(captures.all { it.loopStartFrame == 0 && it.snip.samples.last() == 0f })
        val low = captures[0]; val high = captures[1]
        assertTrue(high.snip.peak() > low.snip.peak(), "absolute authored touch must remain in output")
        assertTrue(captures.all { abs(it.rootHz - Keys.midiHz(it.recipe.midi)) / Keys.midiHz(it.recipe.midi) < .01 })
        for (capture in captures) assertContentEquals(capture.snip.samples,
            assertNotNull(MurmurRoundRobinInstrumentSource.cachedCapture(capture.recipe)).snip.samples)
        record("finite-touch-range", JsonValue.Arr(captures.map(::measurement)))
    }

    @Test fun heldCaptureRetainsContextAndColdReplay() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch()
        val recipe = MurmurRoundRobinInstrumentSource.cellRecipe(source, source.midi, 1, 1)
        val raw = Murmur.probeRoundRobin(recipe.cellPatch(), recipe.take)
        assertEquals(raw.samples.size, raw.previousCycle.size)
        assertTrue(raw.samples.isNotEmpty())
        val expected = Murmur.finishHouseSnapshot(raw.previousCycle + raw.samples, recipe.cellPatch().velocity)
        val first = MurmurRoundRobinInstrumentSource.renderCell(recipe)
        assertEquals(raw.previousCycle.size, first.loopStartFrame)
        assertEquals(2 * raw.samples.size, first.loopEndFrameExclusive)
        assertEquals(MurmurSelectedInstrumentSource.floatSha256(raw.samples), first.rawSampleSha256)
        assertEquals(MurmurSelectedInstrumentSource.floatSha256(raw.previousCycle), first.rawPreviousSha256)
        assertContentEquals(expected.samples.copyOfRange(recipe.onsetFrames, expected.frameCount),
            first.snip.samples.copyOfRange(recipe.onsetFrames, first.snip.frameCount))
        assertEquals(0f, first.snip.samples.first())
        assertEquals(Keys.seamError(first.snip.samples, first.loopStartFrame), first.seamError)
        assertTrue(first.seamError < Keys.MAX_SEAM_ERROR)
        MurmurRoundRobinInstrumentSource.clearCache()
        val replay = MurmurRoundRobinInstrumentSource.replay(Json.parse(recipe.toJsonText()))
        assertContentEquals(first.snip.samples, replay.snip.samples)
        assertEquals(first.finishedFloatSha256, replay.finishedFloatSha256)
        assertEquals(first.loopStartFrame, replay.loopStartFrame)
        record("held-cell", JsonValue.Obj(linkedMapOf("capture" to measurement(first),
            "realPreviousAndCurrentContextExact" to JsonValue.Bool(true), "coldReplayExact" to JsonValue.Bool(true))))
    }

    private fun measurement(capture: MurmurRoundRobinInstrumentSource.Capture): JsonValue {
        assertTrue(capture.snip.samples.all { it.isFinite() })
        assertEquals(1, capture.snip.channels); assertEquals(Dsp.RATE, capture.snip.sampleRate)
        assertTrue(capture.snip.peak() in .001f..1f)
        assertEquals(MurmurSelectedInstrumentSource.floatSha256(capture.snip.samples), capture.finishedFloatSha256)
        return JsonValue.Obj(linkedMapOf("recipe" to capture.recipe.toJsonValue(),
            "plane" to JsonValue.Str("HOUSE_FINISHED_INSTRUMENT_FLOAT"),
            "rawFloatSha256" to JsonValue.Str(capture.rawSampleSha256),
            "previousFloatSha256" to JsonValue.Str(capture.rawPreviousSha256),
            "finishedFloatSha256" to JsonValue.Str(capture.finishedFloatSha256),
            "frames" to JsonValue.Num(capture.snip.frameCount.toDouble()),
            "loopStart" to JsonValue.Num(capture.loopStartFrame.toDouble()),
            "loopEnd" to JsonValue.Num(capture.loopEndFrameExclusive.toDouble()),
            "rootHz" to JsonValue.Num(capture.rootHz), "seamError" to JsonValue.Num(capture.seamError),
            "peak" to JsonValue.Num(capture.snip.peak().toDouble())))
    }

    private fun record(name: String, value: JsonValue) {
        val root = System.getProperty("snipsnap.murmur.rr.instrument.measurements") ?: return
        val directory = Path.of(root); Files.createDirectories(directory)
        val path = directory.resolve("$name.json")
        check(!Files.exists(path)) { "Do not overwrite retained MURMUR instrument measurement: $path" }
        Files.writeString(path, Json.write(value) + "\n")
    }
}
