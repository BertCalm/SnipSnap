package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BrineReferenceCymbalsTest {
    @Test fun `fitted sources are deterministic finite and copied from cache`() {
        for(v in listOf(BrineCymbals.Voice.RIDE,BrineCymbals.Voice.CRASH,BrineCymbals.Voice.CHOKED_CRASH)) {
            val x=BrineReferenceCymbals.source(v)
            assertContentEquals(x,BrineReferenceCymbals.source(v))
            assertTrue(x.all { it.isFinite() && abs(it)<=.901f })
            assertTrue(abs(x.average())<.001)
            assertTrue(abs(x.first())<.001 && abs(x.last())<.001)
            val copy=x.copyOf();x.fill(0f)
            assertContentEquals(copy,BrineReferenceCymbals.source(v))
        }
    }
    @Test fun `measured choke is shorter than sustained ride and crash`() {
        fun t90(x: FloatArray): Double {
            val total=x.sumOf { it.toDouble().pow(2) };var sum=0.0
            for(i in x.indices) { sum+=x[i].toDouble().pow(2);if(sum>=.9*total)return i/44100.0 }
            error("empty source")
        }
        val choke=t90(BrineReferenceCymbals.source(BrineCymbals.Voice.CHOKED_CRASH))
        val ride=t90(BrineReferenceCymbals.source(BrineCymbals.Voice.RIDE))
        val crash=t90(BrineReferenceCymbals.source(BrineCymbals.Voice.CRASH))
        assertTrue(choke in .18.. .40)
        assertTrue(ride>.6 && crash>.7)
    }
    @Test fun `recording and pedals remain bounded with fitted spectral sources`() {
        for(v in listOf(BrineCymbals.Voice.RIDE,BrineCymbals.Voice.CRASH,BrineCymbals.Voice.CHOKED_CRASH)) {
            val a=BrineReferenceCymbals.render(v)
            for(x in listOf(a,Brine.process(a,Brine.Settings(room=0f,grit=0f)))) {
                assertEquals(1,x.channels); assertEquals(44100,x.sampleRate)
                assertTrue(x.samples.all { it.isFinite() && abs(it)<=1f })
                assertTrue(abs(x.samples.average())<.005)
                assertTrue(abs(x.samples.last())<.001)
            }
        }
    }
}
