package com.snipsnap.synth

import com.snipsnap.json.Json
import kotlin.test.*
import kotlin.math.*

class ParadeSambaTest {
    @Test fun `explicit fit bank leaves existing fitted synthesis exact`() {
        val name="agogo_hi"
        val bank=CalabashFits.HandData(CalabashFits.hand(name),CalabashFits.handBands,CalabashFits.handTimes,CalabashFits.handEarly)
        val old=CalabashFits.strike(name,.6,CalabashDsp.Rng(731),.4)
        val explicit=CalabashFits.strike(name,.6,CalabashDsp.Rng(731),.4,fit=bank)
        assertContentEquals(old.first,explicit.first);assertContentEquals(old.second,explicit.second)
    }
    @Test fun `all native voices replay their persisted candidate recipe`() {
        for(voice in ParadeSamba.Voice.entries) {
            val recipe=ParadeSamba.Recipe(voice,velocity=.6f,take=2)
            val replay=ParadeSamba.Recipe.fromJson(Json.parse(Json.write(recipe.json())))
            val a=recipe.render();val b=replay.render()
            assertContentEquals(a.samples,b.samples,voice.name)
            assertEquals(44100,a.sampleRate);assertEquals(1,a.channels)
            assertTrue(a.samples.all { it.isFinite() && abs(it)<=1f },voice.name)
            assertTrue(a.samples.maxOf { abs(it) }>.01f);assertTrue(abs(a.samples.average())<.005)
            assertTrue(a.samples.last()==0f)
        }
    }
    @Test fun `all binary control corners stay finite and bounded`() {
        for(voice in ParadeSamba.Voice.entries)for(bits in 0..7) {
            val m=ParadeSamba.defaults().keys.withIndex().associate { (i,key) -> key to ((bits shr i) and 1).toFloat() }
            val x=ParadeSamba.render(voice,m,if(bits%2==0).05f else 1f,bits%3).samples
            assertTrue(x.all { it.isFinite() && abs(it)<=1f },"$voice corner $bits")
            assertTrue(abs(x.average())<.005 && x.last()==0f,"$voice corner $bits DC/release")
            assertTrue(x.maxOf { abs(it) }>.01f,"$voice corner $bits silent")
        }
    }
    @Test fun `all three controls change each family and bad controls settle`() {
        val voices=listOf(ParadeSamba.Voice.SURDO_LOW_OPEN,ParadeSamba.Voice.CAIXA,ParadeSamba.Voice.REPINIQUE_RIM,
            ParadeSamba.Voice.TAMBORIM_OPEN,ParadeSamba.Voice.CHOCALHO_FORWARD,ParadeSamba.Voice.AGOGO_LOW)
        for(v in voices)for(key in ParadeSamba.defaults().keys) {
            val a=ParadeSamba.render(v,mapOf(key to .2f),.6f).samples
            val b=ParadeSamba.render(v,mapOf(key to .8f),.6f).samples
            val aa=sqrt(a.sumOf { it.toDouble()*it });val bb=sqrt(b.sumOf { it.toDouble()*it })
            val distance=sqrt(a.indices.sumOf { (a[it]/aa-b[it]/bb).pow(2) })
            assertTrue(distance>.01,"$v $key does not move: $distance")
        }
        assertEquals(ParadeSamba.defaults(),ParadeSamba.settled(mapOf("TUNE" to Float.NaN,"DAMP" to Float.POSITIVE_INFINITY,"ALIEN" to 1f)))
        assertFailsWith<IllegalArgumentException> { ParadeSamba.Recipe(ParadeSamba.Voice.CAIXA,model=2) }
        assertFailsWith<IllegalArgumentException> { ParadeSamba.Recipe(ParadeSamba.Voice.CAIXA,take=-1) }
    }
}
