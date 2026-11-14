package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Metadata/synthetic checks only; this class never calls native rendering. */
class MurmurSelectedInstrumentSourceTest {
    @AfterTest fun clear() = MurmurSelectedInstrumentSource.clearCache()

    @Test
    fun `only selected shapes qualify while names roots and positive authored velocities remain independent`() {
        for (selected in MurmurKeptSources.all()) {
            val patch = selected.factoryPatch()
            val initial = assertNotNull(MurmurSelectedInstrumentSource.eligibility(patch))
            assertEquals(selected.id, initial.selectionId)
            assertEquals(selected.supportedMidi, initial.supportedMidi)
            for (midi in listOf(initial.supportedMidi.first, initial.supportedMidi.last)) {
                assertEquals(initial, MurmurSelectedInstrumentSource.eligibility(patch.copy(name = "MY SOURCE", midi = midi, velocity = .37f)))
            }
            assertNull(MurmurSelectedInstrumentSource.eligibility(patch.copy(velocity = 0f)))
            assertNull(MurmurSelectedInstrumentSource.eligibility(patch.copy(macros = patch.macros + ("MOUTH" to .01f))))
            assertNull(MurmurSelectedInstrumentSource.eligibility(patch.copy(config = patch.config.copy(release = .36))))
        }
        val low = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()
        assertNull(MurmurSelectedInstrumentSource.eligibility(low.copy(macros = low.macros + ("HOLD" to 1f))))
        assertFalse(MurmurSelectedInstrumentSource.supports(MurmurPresets.original().first()))
    }

    @Test
    fun `cell snapshots use absolute native touches and preserve authored source and output policy`() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch()
        val cells = (0..2).map { MurmurSelectedInstrumentSource.cellRecipe(source, 48, it, .4f) }
        assertEquals(listOf(.35f, .65f, 1f), cells.map { it.cellPatch().velocity })
        assertTrue(cells.all { it.sourcePatch() == source && it.sourcePatch().velocity == .86f })
        assertTrue(cells.all { it.cellPatch() == source.copy(midi = 48, velocity = it.cellPatch().velocity) })
        assertTrue(cells.all { it.outputPolicy == MurmurSelectedInstrumentSource.OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1 })
        assertTrue(cells.all { it.onsetFrames == MurmurSelectedInstrumentSource.HELD_ONSET_FRAMES })
        for (cell in cells) {
            assertEquals(cell, MurmurSelectedInstrumentSource.CellRecipe.fromJsonText(cell.toJsonText()))
            assertFalse("output_gain" in cell.toJsonValue().entries)
            assertEquals(.4f, cell.releaseSeconds)
        }
        val frozen = cells[1].sourcePatchJson
        (source.macros as MutableMap<String, Float>)["HOLD"] = 0f
        assertEquals(frozen, cells[1].sourcePatchJson)
        assertEquals(1f, cells[1].sourcePatch().macros.getValue("HOLD"))
    }

    @Test
    fun `cell recipe refuses ignored scalars unknown fields wrong renderer shape mode note and touch`() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()
        assertFailsWith<IllegalArgumentException> { MurmurSelectedInstrumentSource.cellRecipe(source, 55, 1) }
        assertFailsWith<IllegalArgumentException> { MurmurSelectedInstrumentSource.cellRecipe(source, 36, 3) }
        val cell = MurmurSelectedInstrumentSource.cellRecipe(source, 36, 1)
        fun rejects(change: (MutableMap<String, JsonValue>) -> Unit) {
            val obj = LinkedHashMap(cell.toJsonValue().entries)
            change(obj)
            assertTrue(runCatching { MurmurSelectedInstrumentSource.CellRecipe.fromJsonValue(JsonValue.Obj(obj)) }.isFailure)
        }
        rejects { it["output_gain"] = JsonValue.Num(3.3389108180999756) }
        rejects { it["version"] = JsonValue.Num(2.0) }
        rejects { it["rendererBinding"] = JsonValue.Obj(emptyMap()) }
        rejects { it["outputPolicy"] = JsonValue.Str("HOUSE_ADJACENT_CONTEXT_V1") }
        rejects { it["onsetFrames"] = JsonValue.Num(220.0) }
        rejects { it["touchIndex"] = JsonValue.Num(0.0) }
        rejects {
            val changed = LinkedHashMap(it.getValue("cellPatch").obj())
            changed["output_gain"] = JsonValue.Num(2.0)
            it["cellPatch"] = JsonValue.Obj(changed)
        }
        assertEquals(cell, MurmurSelectedInstrumentSource.CellRecipe.fromJsonText(Json.write(cell.toJsonValue())))
    }

    @Test
    fun `cache owns independent PCM and enforces entry and byte caps without native rendering`() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()
        fun fake(midi: Int, frames: Int): MurmurSelectedInstrumentSource.Capture {
            val recipe = MurmurSelectedInstrumentSource.cellRecipe(source, midi, 1)
            val pcm = FloatArray(frames) { .1f }
            val hash = MurmurSelectedInstrumentSource.floatSha256(pcm)
            return MurmurSelectedInstrumentSource.Capture(recipe, Snip(pcm, 1, Dsp.RATE), 0, frames,
                hash, MurmurSelectedInstrumentSource.floatSha256(FloatArray(0)), hash, 0.0, Keys.midiHz(midi).toDouble())
        }
        val first = fake(36, 1024)
        MurmurSelectedInstrumentSource.retainCapture(first)
        first.snip.samples[0] = .9f
        val hit = assertNotNull(MurmurSelectedInstrumentSource.cachedCapture(first.recipe))
        assertEquals(.1f, hit.snip.samples[0])
        hit.snip.samples[0] = -.7f
        assertEquals(.1f, assertNotNull(MurmurSelectedInstrumentSource.cachedCapture(first.recipe)).snip.samples[0])
        for (midi in 37..55) MurmurSelectedInstrumentSource.retainCapture(fake(midi, 1024))
        assertEquals(16, MurmurSelectedInstrumentSource.cachedEntries())
        MurmurSelectedInstrumentSource.clearCache()
        for (midi in 36..38) MurmurSelectedInstrumentSource.retainCapture(fake(midi, 1_500_000))
        assertTrue(MurmurSelectedInstrumentSource.cachedPcmBytes() <= 16L * 1024 * 1024)
        assertEquals(2, MurmurSelectedInstrumentSource.cachedEntries())
    }

    @Test
    fun `interrupted lookup refuses even a warm synthetic cache result`() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()
        val recipe = MurmurSelectedInstrumentSource.cellRecipe(source, source.midi, 1)
        val samples = FloatArray(1024) { .1f }
        val hash = MurmurSelectedInstrumentSource.floatSha256(samples)
        MurmurSelectedInstrumentSource.retainCapture(MurmurSelectedInstrumentSource.Capture(recipe,
            Snip(samples, 1, Dsp.RATE), 0, samples.size, hash, hash, hash, 0.0, 261.625565))
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { MurmurSelectedInstrumentSource.cachedCapture(recipe) }
        } finally { Thread.interrupted() }
    }
}
