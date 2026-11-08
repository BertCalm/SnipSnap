package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.*

class FiredBronzeDryKitTest {
    @Test fun `recipes preserve accepted sources and replay all pads`() {
        for(p in FiredBronzeDryKit.Pad.entries) {
            val r=FiredBronzeDryKit.Recipe(p);val s=r.render()
            assertContentEquals(s.samples,FiredBronzeDryKit.Recipe.fromJsonText(r.toJsonText()).render().samples)
            assertEquals(44100,s.sampleRate);assertEquals(1,s.channels)
            assertTrue(s.samples.all { it.isFinite() && abs(it)<=1f },p.name)
            assertTrue(abs(s.samples.average())<.005,p.name)
            assertTrue(abs(s.samples.last())<.001,p.name)
            if(p.source!=null && p!=FiredBronzeDryKit.Pad.KETTLE_HIGH)
                assertContentEquals(FiredBronzeR2.Recipe(p.source).render().samples,s.samples)
        }
    }
    @Test fun `new drums have native velocity and safe control corners`() {
        for(p in listOf(FiredBronzeDryKit.Pad.KICK,FiredBronzeDryKit.Pad.SNARE,FiredBronzeDryKit.Pad.CLOSED_HAT,FiredBronzeDryKit.Pad.OPEN_HAT,FiredBronzeDryKit.Pad.HAT_PEDAL)) {
            val r=FiredBronzeDryKit.Recipe(p);val hard=r.render();val soft=r.copy(velocity=.3f).render()
            assertTrue(soft.peak()<hard.peak());assertEquals(0f,r.copy(velocity=0f).render().peak())
            assertFalse(Velocity.peakMatch(hard,soft).samples.contentEquals(hard.samples))
            for(a in listOf(0f,1f)) for(b in listOf(0f,1f)) {
                val s=r.copy(tune=a,touch=b,damp=b).render()
                assertTrue(s.samples.all{it.isFinite() && abs(it)<=1f})
            }
            for(r2 in listOf(r.copy(tune=.7f),r.copy(touch=.2f),r.copy(damp=.6f)))
                assertFalse(r2.render().samples.contentEquals(hard.samples))
        }
        assertFailsWith<IllegalArgumentException>{FiredBronzeDryKit.Recipe(FiredBronzeDryKit.Pad.KICK,tune=Float.NaN)}
    }
    private fun energy(x:FloatArray,from:Double,to:Double)=x.sliceArray((from*44100).toInt() until minOf(x.size,(to*44100).toInt())).sumOf{it.toDouble()*it}
    @Test fun `hat closure removes late energy and kick sustains a bass register`() {
        val open=FiredBronzeDryKit.Recipe(FiredBronzeDryKit.Pad.OPEN_HAT).render().samples
        val closed=FiredBronzeDryKit.Recipe(FiredBronzeDryKit.Pad.CLOSED_HAT).render().samples
        assertTrue(energy(open,.1,.2)/energy(open,0.0,.04)>5*energy(closed,.1,.2)/energy(closed,0.0,.04))
        val kick=FiredBronzeDryKit.Recipe(FiredBronzeDryKit.Pad.KICK).render().samples
        val peak=(40..100).maxBy { f ->
            var re=0.0;var im=0.0
            for(i in 4410 until 13230){val a=2*PI*f*i/44100;re+=kick[i]*cos(a);im+=kick[i]*sin(a)}
            re*re+im*im
        }
        assertTrue(peak in 56..65,"bass peak $peak Hz")
        assertTrue(energy(kick,.1,.3)>energy(kick,.5,.7)*20)
        assertEquals(1,FiredBronzeDryKit.Pad.OPEN_HAT.choke)
        assertEquals(FiredBronzeDryKit.Pad.OPEN_HAT.choke,FiredBronzeDryKit.Pad.CLOSED_HAT.choke)
    }
}
