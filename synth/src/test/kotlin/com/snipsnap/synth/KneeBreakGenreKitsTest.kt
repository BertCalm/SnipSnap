package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import java.security.MessageDigest
import kotlin.test.*

class KneeBreakGenreKitsTest {
    @Test fun `all 432 frozen WAVs retain audition hashes and frame counts`() {
        val manifest = Json.parse(KneeBreakGenreKits.resource("manifest.json").decodeToString()).obj()
        val assets = manifest.getValue("samples").arr()
        assertEquals(432, assets.size)
        for (entry in assets) {
            val e = entry.obj(); val bytes = KneeBreakGenreKits.resource(e.getValue("file").str())
            assertEquals(e.getValue("sha256").str(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val snip = WavReader.read(bytes)
            assertEquals(e.getValue("frames").int(), snip.frameCount)
            assertEquals(1, snip.channels); assertEquals(44100, snip.sampleRate)
            assertEquals(0f, snip.samples.last())
        }
    }
    @Test fun `three genre grids own their buffers and retain finite gestures and hat groups`() {
        for (choice in KneeBreakGenreKits.Choice.entries) {
            val a = KneeBreakGenreKits.build(choice); val b = KneeBreakGenreKits.build(choice)
            assertEquals(16,a.size)
            for ((i,p) in a.withIndex()) {
                assertEquals(3,p.velocityTakes.size); assertTrue(p.velocityTakes.all { it.size == 3 })
                assertEquals(if (i in setOf(2,3,10)) 1 else 0,p.muteGroup)
                assertTrue(p.oneShot); assertTrue(p.softVariants.isEmpty()); assertNull(p.recipe)
                val energies = p.velocityTakes.map { row -> row.map { s -> s.samples.sumOf { it.toDouble()*it } }.average() }
                assertTrue(energies.zipWithNext().all { (x,y) -> x < y })
            }
            a[0].velocityTakes[0][0].samples.fill(0f)
            assertTrue(b[0].velocityTakes[0][0].peak() > .001f)
            assertTrue(b[15].snip.frameCount > b[14].snip.frameCount)
        }
    }
}
