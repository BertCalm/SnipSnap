package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import java.security.MessageDigest
import kotlin.test.*

class VelourRideBankTest {
    @Test fun `all 36 ride windows match the selected preview hashes and own their buffers`() {
        val manifest = Json.parse(VelourRideBank.resource("manifest.json").decodeToString()).obj()
        val assets = manifest.getValue("assets").arr()
        assertEquals(36, assets.size)
        for (value in assets) {
            val e = value.obj(); val bytes = VelourRideBank.resource(e.getValue("file").str())
            assertEquals(e.getValue("sha256").str(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val wave = WavReader.read(bytes)
            assertEquals(e.getValue("frames").int(), wave.frameCount)
            assertEquals(0f, wave.samples.last())
            val last = wave.samples.indexOfLast { it != 0f }
            assertTrue(wave.samples.size-1-last <= 1103, "No silent padding beyond 25 ms")
        }
        val a = VelourRideBank.grid(VelourApprovedKits.Treatment.CLOSE)
        val b = VelourRideBank.grid(VelourApprovedKits.Treatment.CLOSE)
        a[0][0].samples.fill(0f)
        assertTrue(b[0][0].peak() > .01f)
    }

    @Test fun `every nonride shelf layer stays exact and A12 uses B take one`() {
        val original = Json.parse(VelourApprovedKits.resource("manifest.json").decodeToString()).obj().getValue("kits").obj()
        for (t in VelourApprovedKits.Treatment.entries) {
            val pads = VelourApprovedKits.build(t)
            val grid = VelourRoundRobinKits.build(t)
            for ((slot, pad) in pads.withIndex()) {
                val layers = pad.softVariants + pad.snip
                if (slot == 11) {
                    for (z in 0..2) assertContentEquals(layers[z].samples, VelourRideBank.grid(t)[z][0].samples)
                    assertTrue(pad.source.getValue("approval").contains("round7 B"))
                    assertEquals(0, pad.muteGroup)
                } else {
                    val files = original.getValue(t.id).arr()[slot].obj().getValue("layers").arr()
                    for (z in 0..2) {
                        val reference = WavReader.read(VelourApprovedKits.resource(files[z].obj().getValue("file").str()))
                        assertContentEquals(reference.samples, layers[z].samples)
                        for (take in 0..3) assertContentEquals(VelourRoundRobinKits.variation(reference,take).samples, grid[slot].velocityTakes[z][take].samples)
                    }
                }
            }
        }
    }
}
