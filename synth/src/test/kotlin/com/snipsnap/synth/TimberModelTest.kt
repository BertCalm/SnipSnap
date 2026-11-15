package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class TimberModelTest {
    private fun rms(x:FloatArray)=sqrt(x.sumOf { it.toDouble()*it }/x.size)
    private fun distance(a:FloatArray,b:FloatArray):Double {
        val aa=a.sumOf { it.toDouble()*it }; val bb=b.sumOf { it.toDouble()*it }
        val ab=a.indices.sumOf { a[it].toDouble()*b[it] }
        return 10*log10((bb-ab*ab/aa).coerceAtLeast(1e-30)/bb)
    }
    @Test fun `every approved instrument gesture has stable touch and distinct bounded takes`() {
        var spreadMax=0.0; var peak=0f; var closest=0.0; var touch=0.0
        for(ins in TimberKits.Instrument.entries) for(gesture in TimberKits.Gesture.entries) {
            val hit=TimberKits.Hit("probe",ins,gesture)
            val grid=TimberKits.velocities.map { v -> List(4) { t -> TimberModel.strike(hit,v,t).samples } }
            for(t in 0..3) {
                assertTrue(rms(grid[0][t])<rms(grid[1][t]) && rms(grid[1][t])<rms(grid[2][t]),"$ins/$gesture/$t dynamics")
                val d=distance(grid[0][t],grid[2][t]); touch=min(touch,d)
                assertTrue(d> -35,"$ins/$gesture/$t velocity shape $d")
            }
            for(row in grid) {
                val spread=20*log10(row.maxOf { rms(it) }/row.minOf { rms(it) }); spreadMax=max(spreadMax,spread)
                assertTrue(spread<1.5,"$ins/$gesture level spread $spread")
                for(a in 0..3) for(b in a+1..3) {
                    val d=distance(row[a],row[b]); closest=min(closest,d)
                    assertTrue(d> -45,"$ins/$gesture take clones $d")
                }
                for(x in row) {
                    peak=max(peak,x.maxOf { abs(it) }); assertTrue(x.all { it.isFinite() && abs(it)<1 })
                    assertTrue(abs(x.average())<.005); assertTrue(x.last()==0f,"$ins/$gesture tail reaches zero")
                    assertTrue(rms(x.takeLast(4410).toFloatArray())<rms(x.copyOfRange(0,8820))*.1)
                }
            }
            assertContentEquals(grid[1][2],TimberModel.strike(hit,.65f,2).samples)
            assertTrue(TimberModel.strike(hit,0f,0).samples.all { it==0f })
        }
        println("TIMBER model 2: peak=$peak; maximum take RMS spread=$spreadMax dB; closest take=$closest dB; closest soft-hard=$touch dB")
    }
    @Test fun `bounded modal tails agree with a continuing reference`() {
        for(ins in TimberKits.Instrument.entries) for(gesture in TimberKits.Gesture.entries) {
            val hit=TimberKits.Hit("tail reference",ins,gesture)
            val x=TimberModel.strike(hit,1f,0,.32f/.75f).samples
            val reference=TimberModel.strike(hit,1f,0,.32f/.75f,truncate=false).samples
            val error=x.indices.maxOf { abs(x[it]-reference[it]) }
            assertTrue(error<1e-8f,"$ins/$gesture truncated tail error $error")
        }
    }
    @Test fun `model one saved provenance still selects unchanged legacy renderer`() {
        val source=mapOf("engine" to "TIMBER","model" to "1","genre" to "POCKET","role" to "0","velocities" to ".3,.65,1.0")
            .toMutableMap().apply { put("velocities",TimberKits.velocities.joinToString(",")); put("takes","4") }
        val actual=TimberKits.replay(source)
        for(z in 0..2) for(t in 0..3) assertContentEquals(LegacyTimberKits.render(LegacyTimberKits.Genre.POCKET,0,TimberKits.velocities[z],t).samples,actual[z][t].samples)
        assertFailsWith<IllegalArgumentException> { TimberKits.replay(source+ ("model" to "999")) }
    }
}
