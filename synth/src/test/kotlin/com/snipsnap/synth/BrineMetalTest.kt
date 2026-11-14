package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BrineMetalTest {
    @Test fun `ride keeps resonant energy after the strike through recording`() {
        fun ratio(x: FloatArray): Double {
            val a=x.take(4410).sumOf { it.toDouble().pow(2) }
            val b=x.sliceArray(13230 until 44100).sumOf { it.toDouble().pow(2) }
            return b/a
        }
        val before=BrineCymbals.render(BrineCymbals.Voice.RIDE)
        val after=BrineMetal.render(BrineCymbals.Voice.RIDE)
        assertTrue(ratio(after.samples) > ratio(before.samples)*4)
        assertTrue(ratio(after.samples) > .02)
    }
    @Test fun `choke preserves struck metal onset and smoothly damps its ring`() {
        val crash=BrineMetal.source(BrineCymbals.Voice.CRASH)
        val choked=BrineMetal.source(BrineCymbals.Voice.CHOKED_CRASH)
        val at=(BrineMetal.CHOKE_SECONDS*Dsp.RATE).roundToInt()
        val end=at+(BrineMetal.CHOKE_RELEASE_SECONDS*Dsp.RATE).roundToInt()
        assertContentEquals(crash.copyOf(at), choked.copyOf(at))
        assertTrue(crash.drop(end).sumOf { it.toDouble().pow(2) } > .01)
        assertTrue(choked.drop(end).all { it==0f })
        assertTrue(choked.all { it.isFinite() })
        // Returned buffers may be changed by a mixer without corrupting the cached template.
        val baseline=crash.copyOf(); crash.fill(0f)
        assertContentEquals(baseline,BrineMetal.source(BrineCymbals.Voice.CRASH))
    }
    @Test fun `revised metal is deterministic bounded and survives the pedal board`() {
        for (v in listOf(BrineCymbals.Voice.RIDE,BrineCymbals.Voice.CRASH,BrineCymbals.Voice.CHOKED_CRASH)) {
            val a=BrineMetal.render(v)
            assertContentEquals(a.samples,BrineMetal.render(v).samples)
            for (x in listOf(a,Brine.process(a,Brine.Settings(room=0f,grit=0f)))) {
                assertEquals(44100,x.sampleRate); assertEquals(1,x.channels)
                assertTrue(x.peak() > .001f)
                assertTrue(x.samples.all { it.isFinite() && abs(it)<=1f })
                assertTrue(abs(x.samples.average())<.005)
                assertTrue(abs(x.samples.last())<.001)
            }
        }
    }
    @Test fun `revision leaves drums hats and approved tom untouched`() {
        for (style in BrineCymbals.Groove.entries) {
            val events=BrineCymbals.events(style)
            for (e in events) {
                val r=BrineMetal.revised(e)
                if (e.cymbal==null || e.cymbal==BrineCymbals.Voice.HALF_HAT) assertEquals(e,r)
                else { assertTrue(r.metal); assertEquals(e.beat,r.beat); assertEquals(e.velocity,r.velocity) }
            }
            val x=BrineMetal.renderGroove(style)
            assertTrue(x.samples.all { it.isFinite() && abs(it)<=1f })
        }
    }
}
