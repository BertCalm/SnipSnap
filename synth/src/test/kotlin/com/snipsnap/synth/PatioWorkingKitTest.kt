package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.*

class PatioWorkingKitTest {
    @Test fun `working kit uses selected sources and chewed deterministically`() {
        assertEquals("chewed", PatioWorkingKit.treatment.id)
        val recipes = PatioStudy.recipes()
        assertEquals(16, recipes.size)
        for (r in recipes) {
            val a = PatioWorkingKit.render(r)
            assertContentEquals(a.samples, PatioWorkingKit.render(r).samples)
            assertContentEquals(a.samples, PatioTreatments.process(PatioWorkingKit.dry(r), PatioWorkingKit.treatment, r.seed).samples)
            assertTrue(a.samples.all { it.isFinite() })
            assertTrue(a.peak() in .001f..1f)
            assertTrue(abs(a.samples.average()) < .001)
        }
        assertContentEquals(PatioScrape.render(PatioScrape.Direction.RETURN, modelVersion = 3).samples,
            PatioWorkingKit.dry(recipes.single { it.id == "scrape-short" }).samples)
        assertContentEquals(PatioHand.render(PatioHand.Stroke.OPEN, reference = true).samples,
            PatioWorkingKit.dry(recipes.single { it.id == "alegre-open" }).samples)
    }
}
