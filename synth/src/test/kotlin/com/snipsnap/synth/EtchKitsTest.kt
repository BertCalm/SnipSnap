package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** FRESH TAPE → ETCH's build renders its cells in parallel; it must land exactly the pads the serial path lands. */
class EtchKitsTest {
    @Test fun `the parallel build lands every pad byte for byte as the serial path, sooner`() {
        val pads = EtchKits.pads()
        var t0 = System.nanoTime()
        val serial = pads.map(EtchKits::arranged)
        val serialMs = (System.nanoTime() - t0) / 1e6
        t0 = System.nanoTime()
        val parallel = EtchKits.build()
        val parallelMs = (System.nanoTime() - t0) / 1e6
        println("ETCH kit build: ${"%.0f".format(serialMs)} ms serial, ${"%.0f".format(parallelMs)} ms parallel on " +
            "${Runtime.getRuntime().availableProcessors()} cores (this machine; not asserted)")
        assertEquals(serial.size, parallel.size)
        for ((i, pair) in serial.zip(parallel).withIndex()) {
            val (a, b) = pair
            assertEquals(a.source, b.source, "pad ${i + 1}")
            assertEquals(a.displayName, b.displayName)
            assertEquals(a.drumClass, b.drumClass)
            assertEquals(a.muteGroup, b.muteGroup)
            assertContentEquals(a.snip.samples, b.snip.samples, "pad ${i + 1} main sample")
            assertEquals(a.velocityTakes.size, b.velocityTakes.size)
            for ((z, row) in a.velocityTakes.withIndex()) for ((t, take) in row.withIndex())
                assertContentEquals(take.samples, b.velocityTakes[z][t].samples, "pad ${i + 1} zone $z take $t")
            assertEquals(a.softVariants.size, b.softVariants.size)
            for ((z, v) in a.softVariants.withIndex()) assertContentEquals(v.samples, b.softVariants[z].samples, "pad ${i + 1} soft zone $z")
        }
    }

    @Test fun `one-shot render time by model, printed for the record`() {
        for (voice in listOf(EtchVoice.SAW, EtchVoice.RUBBER, EtchVoice.DUB)) for (model in 1..2) {
            repeat(3) { Etch.render(voice, emptyMap(), 36, .65f, take = 1, model = model) }
            val t0 = System.nanoTime()
            repeat(8) { Etch.render(voice, emptyMap(), 36, .65f, take = 1, model = model) }
            println("ETCH one-shot $voice model $model: ${"%.0f".format((System.nanoTime() - t0) / 8e6)} ms (warm, this machine; not asserted)")
        }
    }
}
