package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.FiredBronzeDryKit.Pad as P
import com.snipsnap.synth.FiredBronzePush.Route as R
import kotlin.math.*
import kotlin.test.*

class FiredBronzePushTest {
    @Test fun `pushed recipes replay preserve tails and retain velocity`() {
        for(p in P.entries) {
            val r=FiredBronzePush.Recipe(FiredBronzeDryKit.Recipe(p));val x=r.render()
            assertContentEquals(x.samples,FiredBronzePush.Recipe.fromJsonText(r.toJsonText()).render().samples)
            assertTrue(x.samples.all{it.isFinite() && abs(it)<=1.000001f},p.name)
            assertTrue(abs(x.samples.average())<.005,p.name);assertTrue(abs(x.samples.last())<.001)
            assertTrue(x.frameCount>=r.source.render().frameCount)
        }
        val r=FiredBronzePush.Recipe(FiredBronzeDryKit.Recipe(P.SNARE))
        assertTrue(r.copy(source=r.source.copy(velocity=.3f)).render().peak()<r.render().peak()*.5f)
        assertEquals(0f,r.copy(source=r.source.copy(velocity=0f)).render().peak())
        for(a in listOf(0f,1f))for(b in listOf(0f,1f)) {
            val x=r.copy(settings=FiredBronzePush.Settings(press=a,heat=b,space=a,amp=b)).render()
            assertTrue(x.samples.all{it.isFinite() && abs(it)<=1.000001f})
        }
    }
    @Test fun `reference is unchanged and independent stage changes are audible candidates`() {
        val d=FiredBronzeDryKit.Recipe(P.TIN).render()
        assertContentEquals(FiredBronzeColor.process(d,P.TIN,FiredBronzeColor.Stage.AMP).samples,FiredBronzePush.process(d,P.TIN,R.REFERENCE).samples)
        val variants=R.entries.map{FiredBronzePush.process(d,P.TIN,it).samples}
        for(i in variants.indices)for(j in 0 until i)assertFalse(variants[i].contentEquals(variants[j]))
        assertFailsWith<IllegalArgumentException>{FiredBronzePush.Settings(amp=Float.NaN)}
    }
    @Test fun `each pushed nonlinear stage adds harmonics after level normalization`() {
        val d=Snip(FloatArray(22050){(.5*sin(2*PI*220*it/44100)).toFloat()},1,44100)
        fun ratio(s:Snip):Double {
            fun power(h:Int):Double {var re=0.0;var im=0.0
                for(i in 4410 until 17640){val w=.5-.5*cos(2*PI*(i-4410)/13229);val a=2*PI*220*h*i/44100;re+=s.samples[i]*w*cos(a);im+=s.samples[i]*w*sin(a)}
                return re*re+im*im
            }
            return (power(2)+power(3)+power(4)+power(5))/power(1)
        }
        val old=FiredBronzeColor.Settings(space=0f);val push=FiredBronzePush.Settings(space=0f)
        val oldP=ratio(FiredBronzeColor.processor(d,null,old));val newP=ratio(FiredBronzePush.processor(d,null,push))
        val oldA=ratio(FiredBronzeColor.amp(d,null,old));val newA=ratio(FiredBronzePush.amp(d,null,push))
        println("harmonic/fundamental power: processor $oldP -> $newP; amp $oldA -> $newA")
        assertTrue(newP>oldP*2);assertTrue(newA>oldA*2)
    }
}
