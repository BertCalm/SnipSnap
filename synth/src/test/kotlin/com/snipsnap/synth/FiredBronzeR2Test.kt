package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.*

class FiredBronzeR2Test {
    @Test fun `dry recipes replay and remain bounded at extremes`() {
        for(v in FiredBronzeR2.Voice.entries) {
            val r=FiredBronzeR2.Recipe(v)
            val s=r.render()
            assertContentEquals(s.samples,FiredBronzeR2.Recipe.fromJsonText(r.toJsonText()).render().samples)
            assertEquals(44100,s.sampleRate);assertEquals(1,s.channels)
            assertTrue(s.samples.all{it.isFinite() && abs(it)<=1f},v.name)
            assertTrue(s.peak()>.01f,v.name)
            assertTrue(abs(s.samples.average())<.005,v.name)
            assertTrue(abs(s.samples.last())<.001,v.name)
            for(edge in listOf(0f,1f)) {
                val x=r.copy(tune=edge,touch=edge,damp=edge,motion=edge).render()
                assertTrue(x.samples.all{it.isFinite() && abs(it)<=1f},"$v $edge")
            }
        }
        assertFailsWith<IllegalArgumentException>{FiredBronzeR2.Recipe(FiredBronzeR2.Voice.TIN,touch=Float.NaN)}
        assertFailsWith<IllegalArgumentException>{FiredBronzeR2.Recipe.fromJsonText(FiredBronze.Recipe(FiredBronze.Part.DAYAN_OPEN).toJsonText())}
    }
    private fun power(x:FloatArray,from:Double,to:Double):Double =
        x.sliceArray((from*44100).toInt() until minOf(x.size,(to*44100).toInt())).sumOf{it.toDouble()*it}
    @Test fun `gong retains meaningful energy after the old kit cutoff`() {
        val s=FiredBronzeR2.Recipe(FiredBronzeR2.Voice.GONG).render()
        assertEquals(8f,s.durationSeconds)
        assertTrue(power(s.samples,1.5,4.0)/power(s.samples,0.0,8.0)>.015)
        assertEquals(listOf(595.0,904.0,2030.0,2326.0),FiredBronzeR2.kettleHz.toList())
    }
    @Test fun `fitted strokes retain distinct spectral emphasis`() {
        fun strongest(v:FiredBronzeR2.Voice):Int {
            val x=FiredBronzeR2.Recipe(v).render().samples
            // Independent narrowband projection over the useful recorded span.
            return (100..1500 step 2).maxBy { f ->
                var re=0.0;var im=0.0
                for(i in x.indices) {val w=.5-.5*cos(2*PI*i/(x.size-1));val a=2*PI*f*i/44100
                    re+=x[i]*w*cos(a);im+=x[i]*w*sin(a)}
                re*re+im*im
            }
        }
        assertTrue(abs(strongest(FiredBronzeR2.Voice.TUN)-322)<8)
        assertTrue(abs(strongest(FiredBronzeR2.Voice.TIN)-588)<8)
        assertTrue(abs(strongest(FiredBronzeR2.Voice.NA)-883)<8)
    }
    @Test fun `pressure also changes decay and cavity covering changes pitch`() {
        val r=FiredBronzeR2.Recipe(FiredBronzeR2.Voice.PRESSURE)
        val still=r.copy(motion=0f).render();val moving=r.copy(motion=1f).render()
        assertTrue(power(moving.samples,.8,1.5)/power(moving.samples,0.0,.1) < power(still.samples,.8,1.5)/power(still.samples,0.0,.1)*.8)
        val open=FiredBronzeR2.Recipe(FiredBronzeR2.Voice.CLAY_OPEN).render()
        val covered=FiredBronzeR2.Recipe(FiredBronzeR2.Voice.CLAY_CLOSED).render()
        fun cavityPeak(x: FloatArray): Int = (60..240 step 2).maxBy { f ->
            var re=0.0; var im=0.0
            for(i in 2205 until 13230) { val a=2*PI*f*i/44100; re+=x[i]*cos(a); im+=x[i]*sin(a) }
            re*re+im*im
        }
        assertTrue(cavityPeak(open.samples) > cavityPeak(covered.samples) * 1.5)
    }
    @Test fun `velocity changes timbre and zero is silent`() {
        val r=FiredBronzeR2.Recipe(FiredBronzeR2.Voice.NA)
        val hard=r.render();val soft=r.copy(velocity=.3f).render()
        assertTrue(soft.peak()<hard.peak())
        assertFalse(Velocity.peakMatch(hard,soft).samples.contentEquals(hard.samples))
        assertEquals(0f,r.copy(velocity=0f).render().peak())
    }
}
