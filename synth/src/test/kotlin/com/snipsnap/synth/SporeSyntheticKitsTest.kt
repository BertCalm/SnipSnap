package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class SporeSyntheticKitsTest {
    @Test fun `Jungle anchors preserve both approved sources and genres change musical roles`() {
        for((z,v)in SporeSyntheticKits.velocities.withIndex())for(t in 0..2) {
            assertContentEquals(SporeKick.render(velocity=v,take=t).samples,SporeSyntheticKits.renderPad(SporeSyntheticKits.Genre.JUNGLE,0,v,t).samples,"kick $z $t")
            assertContentEquals(SporeSnare.render(velocity=v,take=t).samples,SporeSyntheticKits.renderPad(SporeSyntheticKits.Genre.JUNGLE,2,v,t).samples,"snare $z $t")
        }
        assertEquals(3,SporeSyntheticKits.Genre.entries.map { SporeSyntheticKits.layout(it).map { p->p.name } }.toSet().size)
        assertFalse(SporeSyntheticKits.renderPad(SporeSyntheticKits.Genre.DUB,3).samples.contentEquals(SporeSyntheticKits.renderPad(SporeSyntheticKits.Genre.JUNGLE,3).samples))
        assertEquals(12,SporeSyntheticKits.gestureEvents(SporeSyntheticKits.Genre.DUB,false).size)
        assertEquals(16,SporeSyntheticKits.gestureEvents(SporeSyntheticKits.Genre.JUNGLE,false).size)
        val rise=SporeSyntheticKits.gestureEvents(SporeSyntheticKits.Genre.JUNGLE,true)
        assertTrue(rise.last().beat-rise[rise.lastIndex-1].beat<rise[1].beat-rise[0].beat)
        assertTrue(rise.last().tune>rise.first().tune && rise.last().force>rise.first().force)
    }
    @Test fun `every new voice and gesture has timbral velocity fresh repeats and a deterministic saved source`() {
        for(g in SporeSyntheticKits.Genre.entries)for(p in 0..17) {
            val recipe=SporeSyntheticKits.Recipe(g,p)
            assertEquals(recipe,SporeSyntheticKits.Recipe.fromJsonText(recipe.toJsonText()))
            val hard=recipe.render();val soft=recipe.copy(velocity=.3).render();val medium=recipe.copy(velocity=.65).render()
            assertContentEquals(hard.samples,SporeSyntheticKits.Recipe.fromJsonText(recipe.toJsonText()).render().samples)
            assertTrue(soft.peak()<medium.peak() && medium.peak()<hard.peak(),"$g $p velocity peaks ${soft.peak()} ${medium.peak()} ${hard.peak()}")
            val n=min(soft.samples.size,hard.samples.size)
            val distance=(0 until n).sumOf { (soft.samples[it]/soft.peak()-hard.samples[it]/hard.peak()).toDouble().pow(2) }
            assertTrue(distance>1e-4,"$g $p velocity changes only gain")
            for(t in 1..2)assertFalse(hard.samples.contentEquals(recipe.copy(take=t).render().samples),"$g $p cloned take")
            assertTrue(hard.samples.all { it.isFinite() } && hard.peak()<1.001f,"$g $p peak")
            assertTrue(abs(hard.samples.average())<.005,"$g $p DC")
            assertTrue(hard.samples.last()==0f,"$g $p tail")
            assertTrue(recipe.copy(velocity=0.0).render().samples.all { it==0f },"$g $p zero velocity")
        }
    }
}
