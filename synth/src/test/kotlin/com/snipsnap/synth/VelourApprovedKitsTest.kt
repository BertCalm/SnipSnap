package com.snipsnap.synth

import com.snipsnap.json.Json
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.*

class VelourApprovedKitsTest {
    @Test fun `all frozen layers match the approved delivery hashes`() {
        val manifest = Json.parse(VelourApprovedKits.resource("manifest.json").decodeToString()).obj()
        var count = 0
        for (pads in manifest.getValue("kits").obj().values) for (pad in pads.arr()) {
            for (layer in pad.obj().getValue("layers").arr()) {
                val data = layer.obj()
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(VelourApprovedKits.resource(data.getValue("file").str()))
                    .joinToString("") { "%02x".format(it) }
                assertEquals(data.getValue("sha256").str(), hash)
                count++
            }
        }
        assertEquals(144, count)
    }

    @Test fun `approved kits preserve trimmed layers roles and owned sample buffers`() {
        for (t in VelourApprovedKits.Treatment.entries) {
            val pads = VelourApprovedKits.build(t)
            assertEquals(16, pads.size)
            assertEquals(listOf("Low Conga", "High Bongo", "Tambourine", "Woodblock"), pads.takeLast(4).map { it.displayName })
            for (pad in pads) {
                assertNull(pad.recipe, "Printed samples must not run through a second board")
                assertEquals(listOf("Soft", "Medium", "Hard"), pad.layerNames)
                val layers = pad.softVariants + pad.snip
                val energies = layers.map { s -> s.samples.sumOf { it.toDouble() * it } }
                assertTrue(energies.zipWithNext().all { (a, b) -> a < b })
                for (snip in layers) {
                    assertEquals(44100, snip.sampleRate)
                    assertEquals(1, snip.channels)
                    assertTrue(snip.samples.all { it.isFinite() && abs(it) <= .951f })
                    assertEquals(0f, snip.samples.last())
                }
                assertEquals("pending", pad.source["roundRobin"])
            }
            assertEquals("CC0-1.0", pads[14].source["referenceLicense"])
            if (t == VelourApprovedKits.Treatment.CLOSE) {
                assertTrue(pads[1].snip.frameCount in 14000..15000, "Close snare must not regain four seconds of padding")
                assertTrue(pads.all { it.snip.frameCount < 44100 })
            }
            val untouched = VelourApprovedKits.build(t)
            pads[14].snip.samples.fill(0f)
            assertTrue(untouched[14].snip.peak() > .01f)
            assertContentEquals(untouched[14].snip.samples, VelourApprovedKits.build(t)[14].snip.samples)
        }
    }
}
