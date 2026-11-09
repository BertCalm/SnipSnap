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
    @Test fun `jungle core is dry and distinct before any finishing rack`() {
        for (i in 0..9) {
            val r = SporeStation.genreRecipe(i, SporeStation.Genre.JUNGLE)
            assertNull(r.fx, "pad $i must get its identity from the source")
            val patch = assertIs<SkinPatch>(r.patch)
            assertEquals(1, patch.model, "no hidden recording rack inside model 2")
            val fresh = patch.render()
            val previous = SporeStation.parts()[i].patch.render()
            val n = minOf(fresh.samples.size, previous.samples.size)
            val energy = (0 until n).sumOf { previous.samples[it].toDouble() * previous.samples[it] }
            val difference = (0 until n).sumOf { val d = fresh.samples[it].toDouble() - previous.samples[it]; d*d }
            assertTrue(difference > energy * .01, "pad $i must change before FX")
            assertContentEquals(r.render().samples, PadRecipe.fromJsonValue(r.toJsonValue()).render().samples)
            if (i in listOf(1,3,6)) assertTrue(fresh.frameCount < previous.frameCount, "pad $i has a shorter tail")
        }
        assertIs<OnsetPatch>(SporeStation.genreRecipe(0, SporeStation.Genre.DUB).patch)
        assertIs<OnsetPatch>(SporeStation.genreRecipe(0, SporeStation.Genre.ELECTRO).patch)
    }

    @Test fun `bombastic accents gain body without raising the ghosts`() {
        val previous = SporeStation.restrainedParts()
        val current = SporeStation.jungleParts()
        for (i in listOf(0,2,4,7,9)) {
            assertTrue(current[i].patch.render().frameCount > previous[i].patch.render().frameCount)
            assertTrue(current[i].level > previous[i].level)
        }
        assertEquals(previous[3], current[3], "quiet ghosts retain their contrast")
        assertEquals(previous[1], current[1], "pickup kick stays restrained")
    }

}
