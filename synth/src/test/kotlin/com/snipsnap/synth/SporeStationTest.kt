package com.snipsnap.synth

import kotlin.test.*
import org.junit.jupiter.api.Test

class SporeStationTest {
    @Test fun `baked echoes follow the documented tempo within one frame`() {
        for(beats in listOf(.5f,.75f)) assertTrue(kotlin.math.abs(Echo.delayFrames(SporeStation.echoTime(beats),Dsp.RATE) - 60.0/SporeStation.BPM*beats*Dsp.RATE)<1.1)
    }
    @Test fun `every recipe replays and stays finite`() {
        for(mutation in listOf(false,true)) for(i in 0..15) {
            val r=SporeStation.recipe(i,mutation);val a=r.render();val b=PadRecipe.fromJsonValue(r.toJsonValue()).render()
            assertContentEquals(a.samples,b.samples);assertTrue(a.samples.all { it.isFinite() });assertTrue(a.peak() in .00001f..1.00001f)
        }
    }
    @Test fun `ghost is a different instrument setting and not just a lower mixer gain`() {
        val p=SporeStation.parts();assertNotEquals(p[2].patch.macros,p[3].patch.macros);assertTrue(p[3].level<p[2].level)
    }
    @Test fun `protected kick and hats never gain an echo`() {
        for(i in listOf(0,1,6,7)) assertNull(SporeStation.recipe(i,true,SporeStation.Controls(space=1f)).fx?.echo)
    }
    @Test fun `all five controls move the eligible metal voice`() {
        val base=SporeStation.Controls()
        val low=listOf(base.copy(body=.1f),base.copy(bite=.1f),base.copy(dust=.1f),base.copy(twist=.1f),base.copy(space=.1f))
        val high=listOf(base.copy(body=.9f),base.copy(bite=.9f),base.copy(dust=.9f),base.copy(twist=.9f),base.copy(space=.9f))
        low.zip(high).forEach { (a,b) -> assertFalse(SporeStation.recipe(12,true,a).render().samples.contentEquals(SporeStation.recipe(12,true,b).render().samples)) }
    }
    @Test fun `all control extremes remain legal rack recipes`() {
        for(i in 0..15) for(mutation in listOf(false,true)) for(value in listOf(0f,1f))
            SporeStation.recipe(i,mutation,SporeStation.Controls(value,value,value,value,value))
    }
    @Test fun `velocity changes snare waveform shape after peak matching`() {
        val p=SporeStation.parts()[2].patch;val hard=p.render();val soft=Velocity.peakMatch(hard,Velocity.atVelocity(p,.3f))
        val frames = minOf(hard.samples.size, soft.samples.size)
        val signalEnergy = (0 until frames).sumOf { hard.samples[it].toDouble() * hard.samples[it] }
        val differenceEnergy = (0 until frames).sumOf {
            val difference = hard.samples[it].toDouble() - soft.samples[it]
            difference * difference
        }
        // Float rounding after a gain-only change must not qualify as a new articulation.
        assertTrue(differenceEnergy > signalEnergy * .0001)
    }
    @Test fun `recorded jungle core bypasses synthesis and finishing racks`() {
        for(i in 0..10) {
            assertContentEquals(SporeAcoustic.render(i).samples,SporeStation.take(i,SporeStation.Genre.JUNGLE,1f,0).samples)
            assertFailsWith<IllegalArgumentException> { SporeStation.genreRecipe(i,SporeStation.Genre.JUNGLE) }
        }
        assertIs<OnsetPatch>(SporeStation.genreRecipe(0,SporeStation.Genre.DUB).patch)
        assertIs<OnsetPatch>(SporeStation.genreRecipe(0,SporeStation.Genre.ELECTRO).patch)
    }

    @Test fun `recorded source selection follows the saved MIDI velocity bands`() {
        for(i in 0..10) {
            assertEquals(SporeAcoustic.sourceId(i,.3f,0),SporeAcoustic.sourceId(i,41f/127,0))
            assertEquals(SporeAcoustic.sourceId(i,.6f,0),SporeAcoustic.sourceId(i,42f/127,0))
            assertEquals(SporeAcoustic.sourceId(i,.6f,0),SporeAcoustic.sourceId(i,84f/127,0))
            assertEquals(SporeAcoustic.sourceId(i,1f,0),SporeAcoustic.sourceId(i,85f/127,0))
        }
    }

    @Test fun `recorded core retains distinct source recordings and natural dynamics`() {
        for(i in 0..10) {
            val ids=listOf(.3f,.6f,1f).flatMap { v -> (0..2).map { t -> SporeAcoustic.sourceId(i,v,t) } }
            assertEquals(9,ids.toSet().size,"pad $i must retain nine real recordings")
            for(v in listOf(.3f,.6f,1f)) {
                val takes=(0..2).map { SporeAcoustic.render(i,v,it) }
                takes.forEach { assertTrue(it.samples.all { x -> x.isFinite() });assertTrue(it.peak() in .00001f..1f) }
                assertFalse(takes[0].samples.contentEquals(takes[1].samples))
                assertContentEquals(takes[0].samples,SporeAcoustic.render(i,v,3).samples)
            }
        }
        val soft=SporeAcoustic.render(2,.3f);val hard=SporeAcoustic.render(2,1f)
        assertTrue(soft.peak()<hard.peak(),"recorded dynamics are not peak-normalized away")
        val grid=SporeStation.performance()[2]
        assertContentEquals(soft.samples,grid.velocityTakes[0][0].samples)
        assertContentEquals(hard.samples,grid.velocityTakes[2][0].samples)
    }
}
