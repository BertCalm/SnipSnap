package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.*
import com.snipsnap.synth.FiredBronzeDryKit.Pad as P
import com.snipsnap.synth.FiredBronzeColor.Stage as S

class FiredBronzeColorTest {
    @Test fun `stages preserve dry sources and forward order with complete long tails`() {
        for(p in P.entries) {
            val source=FiredBronzeDryKit.Recipe(p);val dry=source.render()
            val r=FiredBronzeColor.Recipe(source)
            assertContentEquals(dry.samples,r.copy(stage=S.DRY).render().samples)
            val proc=FiredBronzeColor.processor(dry,p,r.settings)
            val amp=FiredBronzeColor.amp(proc,p,r.settings)
            assertContentEquals(amp.samples,FiredBronzeColor.process(dry,p,S.AMP).samples)
            for(x in listOf(proc,amp)) {
                assertTrue(x.frameCount>=dry.frameCount && x.frameCount<=dry.frameCount+44100)
                assertTrue(x.samples.all{it.isFinite() && abs(it)<=1.000001f},p.name)
                assertTrue(abs(x.samples.average())<.005,p.name)
                assertTrue(abs(x.samples.last())<.001,p.name)
            }
            if(p==P.GONG) {
                fun e(x:FloatArray,a:Int,b:Int)=x.sliceArray(a until b).sumOf{it.toDouble()*it}
                assertTrue(e(amp.samples,44100*2,44100*5)/e(amp.samples,0,44100)>.003)
            }
        }
    }
    @Test fun `recipe replay silence velocity and control edges`() {
        val r=FiredBronzeColor.Recipe(FiredBronzeDryKit.Recipe(P.SNARE))
        assertEquals(r,FiredBronzeColor.Recipe.fromJsonText(r.toJsonText()))
        assertContentEquals(r.render().samples,FiredBronzeColor.Recipe.fromJsonText(r.toJsonText()).render().samples)
        assertTrue(r.copy(source=r.source.copy(velocity=.3f)).render().peak()<r.render().peak()*.5f)
        assertEquals(0f,r.copy(source=r.source.copy(velocity=0f)).render().peak())
        for(a in listOf(0f,1f))for(b in listOf(0f,1f)) {
            val x=r.copy(settings=FiredBronzeColor.Settings(a,b,a,if(b==0f)60f else 180f)).render()
            assertTrue(x.samples.all{it.isFinite() && abs(it)<=1.000001f})
        }
        for(s in listOf(r.settings.copy(press=.8f),r.settings.copy(heat=.8f),r.settings.copy(space=.8f)))
            assertFalse(r.copy(settings=s).render().samples.contentEquals(r.render().samples))
        assertFailsWith<IllegalArgumentException>{FiredBronzeColor.Settings(heat=Float.NaN)}
    }
    @Test fun `amp placement matters and shared bus differs from summed pad treatment`() {
        val r=FiredBronzeColor.Recipe(FiredBronzeDryKit.Recipe(P.TIN));val d=r.source.render()
        val wrong=FiredBronzeColor.processor(FiredBronzeColor.amp(d,P.TIN,r.settings),P.TIN,r.settings)
        assertFalse(wrong.samples.contentEquals(r.render().samples))
        val a=FiredBronzeDryKit.Recipe(P.KICK).render();val b=FiredBronzeDryKit.Recipe(P.SNARE).render()
        fun sum(x:com.snipsnap.audio.Snip,y:com.snipsnap.audio.Snip)=x.copy(samples=FloatArray(maxOf(x.frameCount,y.frameCount)){(x.samples.getOrElse(it){0f}+y.samples.getOrElse(it){0f})*.4f})
        val bus=FiredBronzeColor.process(sum(a,b),null,S.AMP)
        val pads=sum(FiredBronzeColor.process(a,P.KICK,S.AMP),FiredBronzeColor.process(b,P.SNARE,S.AMP))
        assertFalse(bus.samples.contentEquals(pads.samples))
    }
}
