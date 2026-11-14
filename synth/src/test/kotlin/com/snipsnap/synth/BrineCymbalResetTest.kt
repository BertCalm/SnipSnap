package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BrineCymbalResetTest {
    private fun energy(x: FloatArray,from: Int=0)=x.drop(from).sumOf {it.toDouble().pow(2)}
    @Test fun `reset preserves baseline and removes sustained energy without normalization`() {
        for(v in BrineCymbalReset.voices) {
            val current=BrineCymbalReset.source(v,BrineCymbalReset.Variant.CURRENT)
            assertContentEquals(BrineReferenceCymbals.source(v),current)
            val lean=BrineCymbalReset.source(v,BrineCymbalReset.Variant.LEAN)
            val focused=BrineCymbalReset.source(v,BrineCymbalReset.Variant.FOCUSED)
            assertTrue(energy(lean)<energy(current))
            if(v!=BrineCymbals.Voice.CHOKED_CRASH) {
                assertTrue(energy(focused,13230)<energy(lean,13230)*.5)
                assertContentEquals(lean.copyOf(1543),focused.copyOf(1543))
            } else assertContentEquals(lean,focused)
            assertContentEquals(focused,BrineCymbalReset.source(v,BrineCymbalReset.Variant.FOCUSED))
        }
    }
    @Test fun `ride ring extends sustain without restoring body or altering crashes`() {
        for(v in BrineCymbalReset.voices) {
            val focused=BrineCymbalReset.source(v,BrineCymbalReset.Variant.FOCUSED)
            val ring=BrineCymbalReset.source(v,BrineCymbalReset.Variant.RING)
            if(v==BrineCymbals.Voice.RIDE) {
                val lean=BrineCymbalReset.source(v,BrineCymbalReset.Variant.LEAN)
                assertContentEquals(focused.copyOf(1543),ring.copyOf(1543))
                assertTrue(energy(ring,13230)>energy(focused,13230)*1.5)
                assertTrue(energy(ring,13230)<energy(lean,13230)*.6)
                assertContentEquals(ring,BrineCymbalReset.source(v,BrineCymbalReset.Variant.RING))
            } else assertContentEquals(focused,ring)
        }
    }
    @Test fun `selected ride layers keep approved take and meaningful touch`() {
        assertContentEquals(BrineCymbalReset.hit(BrineCymbals.Voice.RIDE,BrineCymbalReset.Variant.RING).samples,
            BrineCymbalReset.playedRide(.78f,0).samples)
        for(take in 0..1) {
            val hits=BrinePerformance.velocities.map {BrineCymbalReset.playedRide(it,take).samples}
            assertTrue(hits.map {energy(it)}.zipWithNext().all {(a,b)->b>a*1.05})
            for(x in hits) {
                assertTrue(x.all {it.isFinite() && abs(it)<=1f})
                assertTrue(abs(x.average())<.005)
            }
        }
        val a=BrineCymbalReset.playedRide(.65f,1).samples
        assertContentEquals(a,BrineCymbalReset.playedRide(.65f,1).samples)
        assertFalse(a.contentEquals(BrineCymbalReset.playedRide(.65f,0).samples))
    }
    @Test fun `candidates survive shared recording and pedals with finite tails`() {
        for(v in BrineCymbalReset.voices) for(variant in BrineCymbalReset.Variant.entries) {
            val dry=BrineCymbalReset.hit(v,variant)
            for(x in listOf(dry,Brine.process(dry,Brine.Settings(room=0f,grit=0f)))) {
                assertEquals(44100,x.sampleRate);assertEquals(1,x.channels)
                assertTrue(x.samples.all {it.isFinite() && abs(it)<=1f})
                assertTrue(abs(x.samples.average())<.005)
                assertTrue(abs(x.samples.last())<.001)
            }
        }
    }
}
