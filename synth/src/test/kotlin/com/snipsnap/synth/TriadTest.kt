package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.*

class TriadTest {
    @Test fun sourceClockAndHatChokeAreDeterministic() {
        val a = Triad.source(Triad.Settings(bars=1))
        val b = Triad.source(Triad.Settings(bars=1))
        assertContentEquals(a.mix(),b.mix())
        assertEquals(a.events,b.events)
        val kick = a.events.filter { it.lane=="thud" }
        assertEquals(4,kick.size)
        assertEquals(a.events.filter { it.lane=="sub" }.map { it.frame },kick.map { it.frame })
        val straight = Triad.source(Triad.Settings(bars=1,swing=0f))
        assertEquals(kick.map { it.frame },straight.events.filter { it.lane=="thud" }.map { it.frame })
        assertNotEquals(a.events.filter { it.lane=="conga" },straight.events.filter { it.lane=="conga" })
        assertTrue(a.stems.getValue("open-hat").any { abs(it)>1e-4 })
    }
    @Test fun allChainsAreFiniteDeterministicAndHaveAudibleControlMovement() {
        val x = Triad.source(Triad.Settings(bars=1)).mix().copyOf(Dsp.RATE*4)
        for(rig in Triad.Rig.entries) {
            val a = Triad.process(x,124,rig)
            assertContentEquals(a.output,Triad.process(x,124,rig).output)
            assertEquals(x.size,a.output.size)
            assertTrue(a.output.all { it.isFinite() && abs(it)<4 })
            val s=Triad.finish(a.output)
            assertTrue(s.peak() in .01f..1f)
            assertTrue(abs(s.samples.average())<.005)
            if(rig!=Triad.Rig.DRY) {
                val low=Triad.process(x,124,rig,.1f,.1f).output
                val high=Triad.process(x,124,rig,.9f,.9f).output
                val diff=sqrt(low.indices.sumOf { (low[it]-high[it]).toDouble().pow(2) }/low.size)
                assertTrue(diff>.005,"$rig control difference $diff")
                assertFalse(a.first.contentEquals(a.output),"$rig second stage must do work")
            }
        }
    }
    @Test fun tapeDecaysAndSilenceCannotExciteAnyRig() {
        val x=FloatArray(Dsp.RATE*5); x[0]=.5f
        val out=Triad.process(x,124,Triad.Rig.SUBMERGED,1f,1f).output
        fun energy(from:Int,to:Int)=out.sliceArray(from until to).sumOf { it.toDouble()*it }
        assertTrue(energy(Dsp.RATE*4,Dsp.RATE*5)<energy(Dsp.RATE,Dsp.RATE*2)*.25)
        for(rig in Triad.Rig.entries) assertTrue(Triad.process(FloatArray(4410),124,rig).output.all { it==0f })
    }
    @Test fun controlsMoveIndependentlyAndBusDynamicsAreShared() {
        val x=Triad.source(Triad.Settings(bars=1)).mix().copyOf(Dsp.RATE*4)
        fun difference(a:FloatArray,b:FloatArray)=sqrt(a.indices.sumOf { (a[it]-b[it]).toDouble().pow(2) }/a.size)
        for(rig in Triad.Rig.entries.filter { it!=Triad.Rig.DRY }) {
            val base=Triad.process(x,124,rig,.5f,.5f).output
            assertTrue(difference(base,Triad.process(x,124,rig,.1f,.5f).output)>.001,"$rig DRIVE")
            assertTrue(difference(base,Triad.process(x,124,rig,.5f,.1f).output)>.001,"$rig SPACE")
        }
        val a=FloatArray(44100) { i -> (.2*sin(2*PI*60*i/44100)).toFloat() }
        val b=FloatArray(44100) { i -> (.1*sin(2*PI*3000*i/44100)).toFloat() }
        val combined=Triad.process(FloatArray(a.size) { a[it]+b[it] },124,Triad.Rig.WAREHOUSE).output
        val pa=Triad.process(a,124,Triad.Rig.WAREHOUSE).output
        val pb=Triad.process(b,124,Triad.Rig.WAREHOUSE).output
        assertTrue(difference(combined,FloatArray(a.size) { pa[it]+pb[it] })>.005)
    }
    @Test fun invalidSettingsCannotReachTheSignalPath() {
        assertFailsWith<IllegalArgumentException> { Triad.Settings(swing=Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Triad.process(floatArrayOf(0f),124,Triad.Rig.WAREHOUSE,Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Triad.Settings(bpm=0) }
    }
}
