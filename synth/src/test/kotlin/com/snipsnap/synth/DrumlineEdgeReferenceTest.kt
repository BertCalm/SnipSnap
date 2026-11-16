package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class DrumlineEdgeReferenceTest {
    private fun tone(hz: Double)=FloatArray(65536) { i ->
        (sin(2*PI*hz*i/176400)*sin(PI*i/65535).pow(2)).toFloat()
    }

    @Test fun `smooth in-band tone agrees with the reference`() {
        val m=DrumlineEdgeReference.measure(tone(3000.0))
        assertTrue(m.referenceDb < -110,m.toString())
        assertTrue(m.edgeDb < -110,m.toString())
        assertTrue(m.decompositionDb < -110,m.toString())
    }

    @Test fun `boundary impulse exposes the edge contribution`() {
        val x=FloatArray(65536);x[0]=1f;x[x.lastIndex]=.5f
        val m=DrumlineEdgeReference.measure(x)
        assertTrue(m.edgeDb > -40,m.toString())
        assertTrue(m.decompositionDb < -110,m.toString())
    }

    @Test fun `high-tone reference error survives a doubled FFT window`() {
        val low=tone(3000.0);val high=tone(30000.0)
        val x=FloatArray(low.size) { low[it]+high[it] }
        val a=DrumlineEdgeReference.measure(x)
        // This guard forces the next power-of-two FFT size, not just a shifted signal.
        val b=DrumlineEdgeReference.measure(x,65536)
        assertTrue(a.referenceDb < -60 && a.referenceDb > -100,a.toString())
        assertTrue(abs(a.referenceDb-b.referenceDb) < .1,"$a / $b")
        assertTrue(abs(a.reference20To18kDb-a.referenceDb) < .1,a.toString())
        assertTrue(a.decompositionDb < -110,a.toString())
    }

    @Test fun `bright snare and long crash references converge with larger windows`() {
        for(voice in listOf(DrumlineVoice.SNARE,DrumlineVoice.CRASH)) {
            val x=Drumline.renderRaw(voice,mapOf("TUNE" to 1f,"STRIKE" to 1f,
                "DAMP" to if(voice==DrumlineVoice.SNARE)1f else 0f,"PLAYERS" to 1f,"TIGHT" to 1f),1f,2)
            Tide.bandLimit(x,176400)
            var size=1
            while(size < x.size+8192)size*=2
            val a=DrumlineEdgeReference.measure(x)
            val b=DrumlineEdgeReference.measure(x,size/2)
            assertTrue(abs(a.referenceDb-b.referenceDb) < .1,"$voice: $a / $b")
            assertTrue(abs(a.reference20To18kDb-b.reference20To18kDb) < .1,"$voice: $a / $b")
        }
    }
}
