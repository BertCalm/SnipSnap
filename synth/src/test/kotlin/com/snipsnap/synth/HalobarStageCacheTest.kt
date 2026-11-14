package com.snipsnap.synth

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HalobarStageCacheTest {
    @Test fun `stage reuse preserves uncached audio across every source and return control`() {
        for(voice in HalobarVoice.entries) {
            Halobar.clearCache()
            val base=mapOf("HOLD" to .05f,"RATE" to .9f,"RETURN" to .12f,"SPACE" to .3f)
            val first=Halobar.render(voice,base).samples
            val retained=first.copyOf()
            val edits=listOf(emptyMap(),mapOf("RATE" to .65f),mapOf("RETURN" to .28f),
                mapOf("MOTION" to .75f),mapOf("TOUCH" to .8f),mapOf("HOLD" to .18f),
                mapOf("TUNE" to .65f),mapOf("SPACE" to 0f),emptyMap())
            for(edit in edits) {
                val controls=base+edit
                val actual=Halobar.render(voice,controls).samples
                val reference=Halobar.renderProbe(voice,controls,normalize=true).samples
                assertContentEquals(reference,actual,"$voice $edit")
            }
            for(velocity in listOf(.42f,1f,0f)) {
                assertContentEquals(Halobar.renderProbe(voice,base,velocity,normalize=true).samples,
                    Halobar.render(voice,base,velocity).samples,"$voice velocity $velocity")
            }
            assertContentEquals(retained,first,"Later edits must not mutate earlier audio")
            // A caller owns the returned audio, not a cached source or return.
            first.fill(1f)
            val changed=base+mapOf("RETURN" to .2f)
            assertContentEquals(Halobar.renderProbe(voice,changed,normalize=true).samples,
                Halobar.render(voice,changed).samples)
        }
    }

    @Test fun `concurrent motion and delay edits share only immutable completed stages`() {
        val voice=HalobarVoice.MARIMBA
        val base=mapOf("HOLD" to .05f,"RATE" to .9f,"RETURN" to .12f,"SPACE" to .3f)
        val controls=listOf(base,base+mapOf("MOTION" to .7f),base+mapOf("RATE" to .65f),
            base+mapOf("RETURN" to .3f))
        val expected=controls.map { Halobar.renderProbe(voice,it,normalize=true).samples }
        Halobar.clearCache()
        Halobar.render(voice,base)
        val pool=Executors.newFixedThreadPool(4)
        try {
            val tasks=controls.map { c -> pool.submit<FloatArray> { Halobar.render(voice,c).samples } }
            tasks.forEachIndexed { i, task -> assertContentEquals(expected[i],task.get(30,TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
    }

    @Test fun `stage budgets stay bounded across root eviction and clear releases all stage arrays`() {
        Halobar.clearCache()
        for(voice in HalobarVoice.entries) for(tune in listOf(0f,1f)) {
            Halobar.render(voice,mapOf("TUNE" to tune,"HOLD" to .05f,"RATE" to .9f,"RETURN" to .12f))
            assertTrue(Halobar.stageCacheBytesForProbe().all { it in 1..8*1024*1024 })
        }
        Halobar.clearCache()
        assertEquals(listOf(0,0),Halobar.stageCacheBytesForProbe())
    }

    @Test fun `fresh landings and interrupted requests cannot use a cached reference strike`() {
        val voice=HalobarVoice.XYLOPHONE
        val controls=mapOf("HOLD" to .05f,"RATE" to .9f,"RETURN" to .12f)
        Halobar.clearCache()
        val reference=Halobar.render(voice,controls).samples
        val take=Halobar.renderTake(voice,controls,.7f,17,1).samples
        assertFalse(reference.contentEquals(take))
        Halobar.clearCache()
        assertContentEquals(take,Halobar.renderTake(voice,controls,.7f,17,1).samples)
        assertEquals(listOf(0,0),Halobar.stageCacheBytesForProbe())
        Thread.currentThread().interrupt()
        try { assertFailsWith<java.util.concurrent.CancellationException> { Halobar.render(voice,controls) } }
        finally { Thread.interrupted() }
        assertEquals(listOf(0,0),Halobar.stageCacheBytesForProbe())
    }
}
