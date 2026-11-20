package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class ParadeRetuneTest {
    private fun aliasDb(x: DoubleArray): Double {
        val rate=176400
        val raw=FloatArray(x.size) { x[it].toFloat() };Tide.bandLimit(raw,rate)
        val out=Dsp.decimate(raw,44100)
        fun amp(hz: Double): Double {
            var re=0.0;var im=0.0
            // A one-second coherent window excludes buffer boundaries and the truncated release.
            for(i in 11025 until 55125) { val phase=2*PI*hz*i/44100;re+=out[i]*cos(phase);im+=out[i]*sin(phase) }
            return sqrt(re*re+im*im)*2/44100
        }
        return 20*log10(max(amp(1140.0),1e-20)/amp(16500.0))
    }
    @Test fun `band limited tuning suppresses the fold that linear interpolation exposes`() {
        val x=DoubleArray(352800) { sin(2*PI*15000*it/176400) }
        val good=aliasDb(ParadeRetune.sample(x,1.1))
        val linear=DoubleArray(x.size) { i -> val at=i*1.1;val a=at.toInt();if(a>=x.lastIndex)0.0 else x[a]+(x[a+1]-x[a])*(at-a) }
        val bad=aliasDb(linear)
        assertTrue(bad > -65,"Negative control did not expose interpolation folding: $bad")
        assertTrue(good < -95,"Band-limited tuning alias: $good dB; linear: $bad dB")
        println("retune 15kHz x1.1: linear=$bad dB, band-limited=$good dB")
    }
    @Test fun `unity is exact and DC survives fractional sampling before the finite end`() {
        val x=DoubleArray(1000) { it.toDouble()/1000 }
        assertContentEquals(x,ParadeRetune.sample(x,1.0))
        for(ratio in listOf(.70710678,1.1,1.41421356)) {
            val y=ParadeRetune.sample(DoubleArray(2000) { .3 },ratio)
            assertTrue(y.take(1300).all { abs(it-.3)<1e-12 })
        }
    }
}
