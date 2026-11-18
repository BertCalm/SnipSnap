package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import java.security.MessageDigest
import kotlin.test.*
import kotlin.math.sqrt

class SunroomKitsTest {
    @Test fun `all 432 frozen WAVs retain audition hashes and frame counts`() {
        val manifest = Json.parse(SunroomKits.resource("manifest.json").decodeToString()).obj()
        val assets = manifest.getValue("samples").arr()
        assertEquals(432, assets.size)
        for (entry in assets) {
            val e = entry.obj(); val bytes = SunroomKits.resource(e.getValue("file").str())
            assertEquals(e.getValue("sha256").str(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val snip = WavReader.read(bytes)
            assertEquals(e.getValue("frames").int(), snip.frameCount)
            assertEquals(1, snip.channels); assertEquals(44100, snip.sampleRate)
            assertEquals(0f, snip.samples.last())
        }
    }
    @Test fun `genre kits have distinct core drums and instrument roles`() {
        val kits = SunroomKits.Choice.entries.map { SunroomKits.build(it) }
        for (i in kits.indices) for (j in i+1 until kits.size) {
            assertFalse(kits[i][0].snip.samples.contentEquals(kits[j][0].snip.samples))
            assertFalse(kits[i][1].snip.samples.contentEquals(kits[j][1].snip.samples))
            assertTrue(kits[i].zip(kits[j]).count { (a,b) -> a.displayName != b.displayName } >= 6)
        }
    }
    @Test fun `three genre grids own their buffers and retain finite gestures and hat groups`() {
        for (choice in SunroomKits.Choice.entries) {
            val a = SunroomKits.build(choice); val b = SunroomKits.build(choice)
            assertEquals(16,a.size)
            for ((i,p) in a.withIndex()) {
                assertEquals(3,p.velocityTakes.size); assertTrue(p.velocityTakes.all { it.size == 3 })
                assertEquals(if (choice == SunroomKits.Choice.EARLY && i in setOf(2,3,9)) 1 else 0,p.muteGroup)
                assertTrue(p.oneShot); assertTrue(p.softVariants.isEmpty()); assertNull(p.recipe)
                val energies = p.velocityTakes.map { row -> row.map { s -> s.samples.sumOf { it.toDouble()*it } }.average() }
                assertTrue(energies.zipWithNext().all { (x,y) -> x < y })
                for (row in p.velocityTakes) for ((a,b) in listOf(row[0] to row[1], row[1] to row[2], row[0] to row[2])) {
                    val n = minOf(a.frameCount, b.frameCount)
                    var aa = 0.0; var bb = 0.0; var ab = 0.0
                    for (j in 0 until n) { val x=a.samples[j].toDouble(); val y=b.samples[j].toDouble(); aa+=x*x; bb+=y*y; ab+=x*y }
                    val residual = sqrt((aa - ab*ab/bb).coerceAtLeast(0.0)/aa)
                    assertTrue(residual > .005, "${choice.id} pad ${i+1}: variants must differ beyond gain")
                }

            }
            if (choice == SunroomKits.Choice.PSYCH) {
                assertTrue(a[11].snip.frameCount > 7.9 * 44100)
                assertEquals("level-only over one measured crescendo", a[11].source["velocityKind"])
            }
            a[0].velocityTakes[0][0].samples.fill(0f)
            assertTrue(b[0].velocityTakes[0][0].peak() > .001f)
            assertTrue(b[15].snip.frameCount > b[14].snip.frameCount)
        }
    }
}
