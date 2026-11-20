package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.*

class ParadeOutputAuditTest {
    @Test fun `Samba and Second Line spectral references converge with doubled FFT windows`() {
        val names=setOf("samba/AGOGO_HIGH","samba/TAMBORIM_OPEN","secondline/CYMBAL_CRASH","secondline/TRIANGLE","secondline/STICK_CLICK")
        for(source in ParadeAudit.sources().filter { it.name in names }) {
            val raw=source.raw(mapOf("TUNE" to 1f,"STRIKE" to 1f,"DAMP" to 0f),1f,2)
            Tide.bandLimit(raw,176400)
            var size=1;while(size<raw.size+8192)size*=2
            val a=DrumlineEdgeReference.measure(raw);val b=DrumlineEdgeReference.measure(raw,size/2)
            fun agrees(x: Double,y: Double)=abs(x-y)<.1 || maxOf(x,y)<-110
            assertTrue(agrees(a.referenceDb,b.referenceDb),"${source.name}: $a / $b")
            assertTrue(agrees(a.reference20To18kDb,b.reference20To18kDb),"${source.name}: $a / $b")
            assertTrue(a.decompositionDb < -110 && b.decompositionDb < -110,source.name)
            println("${source.name} FFT delta=${abs(a.referenceDb-b.referenceDb)} dB")
        }
    }
}
