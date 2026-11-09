package com.snipsnap.synth
import kotlin.test.*
import kotlin.math.*
class TriadHandsTest {
    @Test fun `hand revisions preserve the approved rhythm and other stems`() {
        val a=Triad.source(Triad.Settings(bars=1,handRevision=1))
        val b=Triad.source(Triad.Settings(bars=1,handRevision=2))
        assertEquals(a.events,b.events)
        for(k in a.stems.keys-setOf("conga","bongo")) assertContentEquals(a.stems.getValue(k),b.stems.getValue(k))
        for(k in listOf("conga","bongo")) assertFalse(a.stems.getValue(k).contentEquals(b.stems.getValue(k)))
    }
    @Test fun `hand drums replay across tune and touch and settle before the cut`() {
        for(bongo in listOf(false,true)) for(tune in listOf(0f,.5f,1f)) for(v in listOf(.3f,1f)) {
            val a=TriadHands.render(bongo,tune,v,0)
            assertContentEquals(a,TriadHands.render(bongo,tune,v,0))
            assertTrue(a.all { it.isFinite() && abs(it)<1 })
            assertTrue(abs(a.average())<.005)
            fun rms(from:Int,to:Int)=sqrt(a.sliceArray(from until to).sumOf{it.toDouble()*it}/(to-from))
            assertTrue(rms(a.size-4410,a.size)<rms(441,4410)*.02)
        }
        assertFalse(TriadHands.render(true,.5f,.3f,0).contentEquals(TriadHands.render(true,.5f,1f,0)))
    }
}
