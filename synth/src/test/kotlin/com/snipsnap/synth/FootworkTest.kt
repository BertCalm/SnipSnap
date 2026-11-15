package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.*

class FootworkTest {
    @Test fun `finite contacts are deterministic silent at zero and reject invalid performance`() {
        for(voice in FootworkVoice.entries) {
            assertContentEquals(Footwork.render(voice,.3f,2).samples,Footwork.render(voice,.3f,2).samples)
            assertTrue(Footwork.render(voice,0f,2).samples.all { it==0f })
        }
        assertFailsWith<IllegalArgumentException> { Footwork.render(FootworkVoice.TOE,Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Footwork.render(FootworkVoice.TOE,1f,4) }
    }
    @Test fun `genre surfaces change normalized contact shape rather than only gain or tempo`() {
        for(voice in listOf(FootworkVoice.TOE,FootworkVoice.HEEL,FootworkVoice.CLAP,FootworkVoice.BRUSH_FORWARD)) {
            val sounds=FootworkKits.Kit.entries.map { Footwork.render(voice,1f,0,it).samples }
            for(i in 0..1) for(j in i+1..2) {
                val a=sounds[i];val b=sounds[j]
                val ea=sqrt(a.sumOf { it.toDouble()*it });val eb=sqrt(b.sumOf { it.toDouble()*it })
                assertTrue(sqrt(a.indices.sumOf { (a[it]/ea-b[it]/eb).pow(2) })>.01,"$voice $i $j")
            }
        }
    }
}
