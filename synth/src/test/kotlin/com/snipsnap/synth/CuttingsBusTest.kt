package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class CuttingsBusTest {
    @Test fun `compression is deterministic finite aligned and level matched without mutating source`() {
        val x=FloatArray(88200) { i ->
            val t=(i%11025)/44100.0
            ((sin(i*.31)*exp(-t*65)*.7+sin(i*.043)*.03)*doubleArrayOf(1.0,.2,.6,.3)[(i/11025)%4]).toFloat()
        }
        val before=x.copyOf();val a=CuttingsBus.comparisons(x);val b=CuttingsBus.comparisons(x)
        fun rms(v:FloatArray)=sqrt(v.sumOf { it.toDouble()*it }/v.size)
        assertEquals(CuttingsBus.Mode.GLUE,CuttingsBus.DEFAULT)
        for((key,v) in a) {
            assertEquals(x.size,v.size);assertContentEquals(v,b.getValue(key))
            assertTrue(v.all { it.isFinite() && abs(it)<=.880001f })
            assertTrue(abs(20*log10(rms(v)/rms(a.getValue("finished"))))<.001)
            if(key!="finished") assertTrue(rms(FloatArray(x.size){v[it]-a.getValue("finished")[it]})/rms(x)>.1)
        }
        assertContentEquals(before,x)
    }
    @Test fun `empty silence short impulses and invalid input have defined behavior`() {
        for(n in listOf(0,1,2,87,177,178,1000)) {
            assertTrue(CuttingsBus.comparisons(FloatArray(n)).values.all { v->v.size==n && v.all { it==0f } })
            if(n>0) for(at in listOf(0,n/2,n-1)) {
                val x=FloatArray(n);x[at]=.9f
                for(v in CuttingsBus.comparisons(x).values) {
                    assertTrue(v[at]>0);assertTrue(v.indices.all { it==at || v[it]==0f })
                }
            }
        }
        for(v in listOf(Float.NaN,Float.POSITIVE_INFINITY,1.1f))assertFailsWith<IllegalArgumentException>{CuttingsBus.comparisons(floatArrayOf(v))}
    }
}
