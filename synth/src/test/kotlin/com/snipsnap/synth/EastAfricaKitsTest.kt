package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class EastAfricaKitsTest {
    @Test fun `all 990 strikes replay with timbral dynamics distinct takes and bounded tails`() {
        var worstTouch=0.0; var worstTake=0.0; var peak=0f
        for(g in EastAfricaKits.Genre.entries) {
            val pads=EastAfricaKits.build(g)
            assertEquals(22,pads.size)
            for((r,pad) in pads.withIndex()) {
                val replay=EastAfricaKits.replay(pad.source)
                val grid=pad.velocityTakes
                for(z in 0..2) for(t in 0..2) {
                    val x=grid[z][t].samples
                    assertContentEquals(x,replay[z][t].samples,"$g/$r/$z/$t replay")
                    assertTrue(x.all { it.isFinite() && abs(it)<1 },"$g/$r/$z/$t finite bounded")
                    peak=max(peak,x.maxOf { abs(it) })
                    assertTrue(abs(x.average())<.001,"$g/$r DC")
                    assertTrue(x.last()==0f,"$g/$r ends at zero")
                    assertTrue(rms(x)>.00001,"$g/$r audible")
                    assertTrue(rms(x.takeLast(441).toFloatArray())<.003,"$g/$r tail")
                }
                for(t in 0..2) {
                    assertTrue(rms(grid[0][t].samples)<rms(grid[1][t].samples) && rms(grid[1][t].samples)<rms(grid[2][t].samples),"$g/$r/$t velocity order")
                    val d=shape(grid[0][t].samples,grid[2][t].samples)
                    assertTrue(d> -35,"$g/$r velocity shape $d");worstTouch=min(worstTouch,d)
                }
                for(row in grid) {
                    val spread=20*log10(row.maxOf { rms(it.samples) }/row.minOf { rms(it.samples) })
                    assertTrue(spread<3.0,"$g/$r take loudness spread $spread dB")
                }
                for(row in grid) for(a in 0..1) for(b in a+1..2) {
                    val d=shape(row[a].samples,row[b].samples)
                    assertTrue(d> -50,"$g/$r take shape $d");worstTake=min(worstTake,d)
                }
            }
            println("EAST AFRICA ${g.slug}: 198 strikes replayed and checked")
        }
        println("EAST AFRICA 990 replayed strikes: peak=$peak, minimum matched shape touch=$worstTouch dB, take=$worstTake dB")
    }
    @Test fun `invalid identity cannot silently choose another sound`() {
        val g=EastAfricaKits.Genre.BONGO_FLAVA
        assertFailsWith<IllegalArgumentException> { EastAfricaKits.render(g,22,1f,0) }
        assertFailsWith<IllegalArgumentException> { EastAfricaKits.render(g,0,Float.NaN,0) }
        assertFailsWith<IllegalArgumentException> { EastAfricaKits.render(g,0,1f,3) }
        assertFailsWith<IllegalArgumentException> { EastAfricaKits.replay(mapOf("engine" to "EAST_AFRICA","model" to "99")) }
        assertTrue(EastAfricaKits.render(g,0,0f,0).samples.all { it==0f })
    }
    companion object {
        fun rms(x:FloatArray)=sqrt(x.sumOf { it.toDouble()*it }/x.size)
        fun shape(a:FloatArray,b:FloatArray):Double {
            assertEquals(a.size,b.size)
            val aa=a.sumOf { it.toDouble()*it }.coerceAtLeast(1e-20)
            val bb=b.sumOf { it.toDouble()*it }.coerceAtLeast(1e-20)
            val ab=a.indices.sumOf { a[it].toDouble()*b[it] }
            return 10*log10((bb-ab*ab/aa).coerceAtLeast(1e-20)/bb)
        }
    }
}
