package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class VelourRoundRobinKitsTest {
    @Test fun `all candidate grids preserve references pitch timing tails and velocity ordering`() {
        for (t in VelourApprovedKits.Treatment.entries) {
            val baseline = VelourApprovedKits.build(t)
            val candidates = VelourRoundRobinKits.build(t)
            val repeat = VelourRoundRobinKits.build(t)
            for ((slot, pad) in candidates.withIndex()) {
                val references = baseline[slot].softVariants + baseline[slot].snip
                val energies = pad.velocityTakes.mapIndexed { zone, row ->
                    assertEquals(4, row.size)
                    assertContentEquals(references[zone].samples, row[0].samples)
                    for ((take, snip) in row.withIndex()) {
                        assertContentEquals(snip.samples, repeat[slot].velocityTakes[zone][take].samples)
                        assertEquals(references[zone].frameCount, snip.frameCount)
                        assertTrue(snip.samples.all { it.isFinite() && abs(it) <= .950001f })
                        assertTrue(abs(snip.samples.average()) < .005)
                        assertEquals(0f, snip.samples.last())
                        val tail = minOf(snip.samples.size, ceil(.24 * snip.sampleRate).toInt())
                        assertContentEquals(references[zone].samples.copyOfRange(tail, snip.samples.size),
                            snip.samples.copyOfRange(tail, snip.samples.size))
                    }
                    // Remove optimal gain before testing diversity: renamed or louder copies fail.
                    for (a in 0..2) for (b in a+1..3) {
                        val x = row[a].samples; val y = row[b].samples
                        val xx = x.sumOf { it.toDouble().pow(2) }
                        val scale = x.indices.sumOf { x[it].toDouble() * y[it] } / xx
                        val residual = x.indices.sumOf { (y[it] - scale*x[it]).pow(2) } / xx
                        assertTrue(residual > 1e-6, "$t slot ${slot+1} zone $zone pair $a/$b: $residual")
                    }
                    row.map { snip -> snip.samples.sumOf { it.toDouble().pow(2) } }
                }
                for (take in 0..3) assertTrue(energies[0][take] < energies[1][take] && energies[1][take] < energies[2][take])
            }
        }
    }
}
