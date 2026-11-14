package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BrinePerformanceTest {
    @Test fun `touch increases energy and takes stay deterministic and distinct`() {
        for (ride in listOf(false,true)) {
            val energies = BrinePerformance.velocities.map { v ->
                val a=BrinePerformance.hit(ride,v,0).samples
                val b=BrinePerformance.hit(ride,v,1).samples
                assertContentEquals(a,BrinePerformance.hit(ride,v,0).samples)
                assertFalse(a.contentEquals(b))
                for(x in listOf(a,b)) {
                    assertTrue(x.all {it.isFinite() && abs(it)<=1f})
                    assertTrue(abs(x.average())<.005)
                    assertTrue(abs(x.last())<.001)
                }
                a.sumOf {it.toDouble().pow(2)}
            }
            assertTrue(energies.zipWithNext().all {(a,b)->b>a*1.05},"$ride: $energies")
        }
    }
}
