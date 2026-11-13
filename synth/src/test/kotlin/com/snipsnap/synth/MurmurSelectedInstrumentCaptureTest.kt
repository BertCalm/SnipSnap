package com.snipsnap.synth

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Actual native assertions; run only after the independent pre-refactor baseline gate is granted. */
class MurmurSelectedInstrumentCaptureTest {
    @AfterTest fun clear() = MurmurSelectedInstrumentSource.clearCache()

    @Test
    fun `finite capture retains ordinary factory Float output with no introduced loop`() {
        for (id in listOf(MurmurKeptSources.FINITE_WIRE, MurmurKeptSources.STEADY_C2)) {
            val source = MurmurKeptSources.byId(id).factoryPatch()
            val recipe = MurmurSelectedInstrumentSource.cellRecipe(source, source.midi, 1)
            val capture = MurmurSelectedInstrumentSource.renderCell(recipe)
            assertEquals(0, capture.loopStartFrame)
            assertEquals(capture.snip.frameCount, capture.loopEndFrameExclusive)
            assertContentEquals(recipe.cellPatch().render().samples, capture.snip.samples)
            assertEquals(0f, kotlin.math.abs(capture.snip.samples.last()))
        }
    }

    @Test
    fun `held capture retains real preceding history and an unmodified complete finished loop period`() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch()
        val recipe = MurmurSelectedInstrumentSource.cellRecipe(source, source.midi, 1)
        val patch = recipe.cellPatch()
        val raw = Murmur.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config)
        val expected = Murmur.finishHouseSnapshot(raw.previousCycle + raw.samples, patch.velocity)
        val capture = MurmurSelectedInstrumentSource.renderCell(recipe)
        val start = raw.previousCycle.size
        assertEquals(start, capture.loopStartFrame)
        assertEquals(start * 2, capture.loopEndFrameExclusive)
        assertEquals(MurmurSelectedInstrumentSource.floatSha256(raw.samples), capture.rawSampleSha256)
        assertEquals(MurmurSelectedInstrumentSource.floatSha256(raw.previousCycle), capture.rawPreviousSha256)
        assertContentEquals(expected.samples.copyOfRange(recipe.onsetFrames, expected.frameCount),
            capture.snip.samples.copyOfRange(recipe.onsetFrames, capture.snip.frameCount))
        assertEquals(0f, capture.snip.samples.first())
        assertTrue(capture.seamError < Keys.MAX_SEAM_ERROR)
        assertEquals(MurmurSelectedInstrumentSource.floatSha256(capture.snip.samples), capture.finishedFloatSha256)
        assertEquals(MurmurSelectedInstrumentSource.OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1, recipe.outputPolicy)
    }
}
