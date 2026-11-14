package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.abs
import com.snipsnap.synth.FiredBronzeDryKit.Pad as P

class FiredBronzePerformanceTest {
    @Test fun `full strength remains byte identical on every pad`() {
        for(p in P.entries){
            val source=FiredBronzeDryKit.Recipe(p)
            assertContentEquals(FiredBronzePush.Recipe(source).render().samples,FiredBronzePerformance.Recipe(source).render().samples)
        }
    }
    @Test fun `four velocity layers retain ordered energy replay and safe tails`() {
        for(p in P.entries) {
            var previous=0.0
            for(v in FiredBronzePerformance.layerVelocities) {
                val r=FiredBronzePerformance.Recipe(FiredBronzeDryKit.Recipe(p,velocity=v));val s=r.render()
                val e=s.samples.sumOf{it.toDouble()*it}
                assertTrue(e>previous,"$p $v energy $e <= $previous");previous=e
                assertTrue(s.samples.all{it.isFinite() && abs(it)<=1.000001f},"$p $v")
                assertTrue(abs(s.samples.average())<.005);assertTrue(abs(s.samples.last())<.001)
                assertEquals(r,FiredBronzePerformance.Recipe.fromJsonText(r.toJsonText()))
            }
        }
        val r=FiredBronzePerformance.Recipe(FiredBronzeDryKit.Recipe(P.SNARE,velocity=0f))
        assertEquals(0f,r.render().peak())
    }
    @Test fun `soft drive is independent of source attenuation`() {
        val source=FiredBronzeDryKit.Recipe(P.TIN,velocity=.45f)
        val old=FiredBronzePush.Recipe(source).render();val response=FiredBronzePerformance.Recipe(source).render()
        assertFalse(Velocity.peakMatch(old,response).samples.contentEquals(old.samples))
        val low=FiredBronzePerformance.settings(.2f);val high=FiredBronzePerformance.settings(1f)
        assertTrue(low.heat<high.heat && low.amp<high.amp && low.press<high.press)
        assertEquals(low.space,high.space)
        assertFailsWith<IllegalArgumentException>{FiredBronzePerformance.settings(Float.NaN)}
    }
}
