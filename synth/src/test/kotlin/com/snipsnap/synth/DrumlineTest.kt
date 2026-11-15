package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.*

class DrumlineTest {
    @Test fun `all voices replay their versioned recipes with finite bounded audio`() {
        for(v in DrumlineVoice.entries) {
            val p=DrumlinePatch("Section",v,Drumline.defaults(v));val decoded=Patches.fromJsonText(p.toJsonText())
            assertEquals(p,decoded)
            val a=p.render();assertContentEquals(a.samples,decoded.render().samples,v.name)
            assertEquals(44100,a.sampleRate);assertEquals(1,a.channels)
            assertTrue(a.samples.all { it.isFinite() && abs(it)<=1f },v.name)
            assertTrue(a.samples.maxOf { abs(it) }>.01f)
            assertTrue(abs(a.samples.average())<.005,v.name)
            assertTrue(a.samples.last() == 0f, v.name) // Both signed zeros are silence.
        }
    }
    @Test fun `invalid controls settle and unknown keys have no effect`() {
        val v=DrumlineVoice.SNARE;val a=Drumline.render(v).samples
        assertContentEquals(a,Drumline.render(v,mapOf("UNKNOWN" to 1f)).samples)
        assertContentEquals(a,Drumline.render(v,mapOf("DAMP" to Float.NaN)).samples)
        assertFailsWith<IllegalArgumentException> { DrumlinePatch("Bad",v,emptyMap(),Drumline.MODEL_VERSION+1) }
    }
    @Test fun `fresh takes and softer strokes change the waveform beyond a gain`() {
        fun likeness(a: FloatArray,b: FloatArray): Double {
            var aa=0.0;var bb=0.0;var ab=0.0
            for(i in 0 until minOf(a.size,b.size)) { aa+=a[i]*a[i].toDouble();bb+=b[i]*b[i].toDouble();ab+=a[i]*b[i].toDouble() }
            return ab/sqrt(aa*bb)
        }
        for(v in DrumlineVoice.entries) {
            val p=DrumlinePatch("Hit",v,emptyMap());val a=p.render().samples
            assertTrue(likeness(a,p.render(.3f).samples)<.99999,"$v volume-only velocity")
            assertTrue(likeness(a,p.render(1f,1).samples)<.99999,"$v copied take")
            assertContentEquals(p.render(.6f,2).samples,Restrike.take(p,.6f,42,2)!!.samples)
        }
    }
    @Test fun `each exposed macro changes a representative source`() {
        for(v in listOf(DrumlineVoice.SNARE,DrumlineVoice.TENOR_1,DrumlineVoice.BASS_5,DrumlineVoice.CRASH))
            for(m in Drumline.macrosFor(v)) {
                val a=Drumline.render(v,mapOf(m.name to 0f)).samples
                val b=Drumline.render(v,mapOf(m.name to 1f)).samples
                assertFalse(a.contentEquals(b),"$v ${m.name} is dead")
            }
    }
    @Test fun `choke progressively releases and crash retains its ring`() {
        fun rms(x: FloatArray,a: Double,b: Double): Double {
            val s=x.copyOfRange((a*44100).toInt(),minOf(x.size,(b*44100).toInt()))
            return sqrt(s.sumOf { it.toDouble()*it }/s.size)
        }
        val choke=Drumline.render(DrumlineVoice.CHOKE).samples
        val crash=Drumline.render(DrumlineVoice.CRASH).samples
        assertTrue(rms(choke,.28,.4)>.001)
        assertTrue(rms(choke,1.0,1.3)<.0001)
        assertTrue(rms(crash,.8,1.2)>.001)
        val losses=(0..1500).map { Drumline.contactIntegral(it/1000.0,.18) }
        assertTrue(losses.zipWithNext().all { (a,b)->b>=a })
    }
    @Test fun `rudiments retain rebound dynamics grace timing and separate bass sizes`() {
        val roll=Drumline.score(DrumlineVoice.ROLL)
        assertEquals(33,roll.size);assertTrue(roll[1].strength<roll[0].strength)
        assertEquals(listOf(0,0,1,1),roll.take(4).map { it.hand })
        assertEquals(.025,Drumline.score(DrumlineVoice.FLAM)[1].seconds)
        assertEquals(5,Drumline.score(DrumlineVoice.BASS_UNISON).map { it.voice }.toSet().size)
    }
}
