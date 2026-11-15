package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class TimberContactTest {
    @Test fun `direct contact guard retains the slowest filter tail below minus 180 dB`() {
        val rate=Dsp.RATE*Dsp.OVERSAMPLE
        for(cutoff in listOf(740f,900f,11000f)) for(extended in listOf(false,true)) {
            val low=Dsp.FixedOnePole(cutoff,rate); val high=Dsp.FixedOnePole(900f,rate)
            var omitted=0f
            for(i in 0 until 20000) {
                val y=low.lp(if(i==0) 1f else 0f)
                val x=if(extended) y-high.lp(y) else y
                if(i>=TimberModel.DIRECT_GUARD+1) omitted=max(omitted,abs(x))
            }
            assertTrue(omitted<1e-9f,"$cutoff/$extended tail $omitted")
        }
    }

    @Test fun `contact filter guard discards less than minus 180 dB of full scale`() {
        val rate=Dsp.RATE*Dsp.OVERSAMPLE
        // Cover contacts shorter and longer than any authored gesture. The reference
        // continues filtering real zeros, rather than repeating the kept kernel.
        for(width in listOf(4,40,150,1200,1600)) {
            val long=FloatArray((.85*rate).roundToInt())
            for(i in 0 until width) long[i]=(sin(PI*i/(width-1)).pow(2)/width*2).toFloat()
            val short=long.copyOf(width+TimberModel.CONTACT_GUARD)
            Tide.bandLimit(long,rate); Tide.bandLimit(short,rate)
            assertContentEquals(long.copyOf(short.size),short)
            val omitted=long.copyOfRange(short.size,long.size).maxOf{abs(it)}
            assertTrue(omitted<1e-9f,"width $width omitted $omitted")
            println("TIMBER width $width contact guard omitted peak $omitted")
        }
    }
}
