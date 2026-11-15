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
    @Test fun `stamp heel toe and ball have different normalized attacks and v1 remains replayable`() {
        for(kit in FootworkKits.Kit.entries) {
            val voices=listOf(FootworkVoice.STAMP,FootworkVoice.HEEL,FootworkVoice.TOE,FootworkVoice.BALL)
            val x=voices.map { Footwork.render(it,1f,0,kit).samples }
            for(i in 0..2) for(j in i+1..3) {
                val ea=sqrt(x[i].sumOf { it.toDouble()*it }); val eb=sqrt(x[j].sumOf { it.toDouble()*it })
                val correlation=x[i].indices.sumOf { x[i][it].toDouble()*x[j][it] }/ea/eb
                assertTrue(abs(correlation)<.92,"$kit ${voices[i]} ${voices[j]} $correlation")
            }
        }
        for(voice in listOf(FootworkVoice.TOE,FootworkVoice.HEEL,FootworkVoice.BRUSH_FORWARD,FootworkVoice.BRUSH_BACK))
            assertContentEquals(Footwork.render(voice,.65f,2,model=1).samples,Footwork.render(voice,.65f,2,model=2).samples)
    }
    @Test fun `identity revision preserves unflagged single gestures across the touch grid`() {
        val retained=listOf(FootworkVoice.STAMP,FootworkVoice.TOE,FootworkVoice.BALL,
            FootworkVoice.BRUSH_FORWARD,FootworkVoice.BRUSH_BACK,FootworkVoice.SHUFFLE,
            FootworkVoice.FLAP,FootworkVoice.BROOM,FootworkVoice.METAL)
        for(voice in retained) for(v in FootworkKits.velocities) for(take in 0..3)
            assertContentEquals(Footwork.render(voice,v,take,model=2).samples,
                Footwork.render(voice,v,take,model=3).samples,"$voice $v $take")
    }

}
