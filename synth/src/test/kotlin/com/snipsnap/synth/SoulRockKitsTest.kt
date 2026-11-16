package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class SoulRockKitsTest {
    @Test fun `every factory strike is the approved PCM with bounded output and full tails`() {
        val m = Json.parse(SoulRockKits.resource("manifest.json").decodeToString()).obj()
        val assets = m.getValue("samples").arr()
        assertEquals(432, assets.size)
        val hashes = mutableSetOf<String>()
        for (entry in assets) {
            val a = entry.obj(); val bytes = SoulRockKits.resource(a.getValue("file").str())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(a.getValue("sha256").str(), hash); assertTrue(hashes.add(hash))
            val s = WavReader.read(bytes); val x = s.samples
            assertEquals(a.getValue("frames").int(), s.frameCount)
            assertEquals(44100, s.sampleRate); assertEquals(1, s.channels)
            assertTrue(x.all { it.isFinite() && abs(it) < .9f })
            assertTrue(abs(x.average()) < .001)
            assertTrue(sqrt(x.takeLast(441).sumOf { it.toDouble()*it }/441) < .002)
            assertTrue(x.last() == 0f)
        }
    }
    @Test fun `kits retain distinct dynamics and takes and own their playback buffers`() {
        for (choice in SoulRockKits.Choice.entries) {
            val a = SoulRockKits.build(choice); val b = SoulRockKits.build(choice)
            assertEquals(16, a.size)
            for ((i,p) in a.withIndex()) {
                assertNull(p.recipe); assertTrue(p.oneShot)
                assertEquals(if (i in 4..6) 1 else 0, p.muteGroup)
                assertEquals(3,p.velocityTakes.size)
                val energies = p.velocityTakes.map { row ->
                    assertEquals(3,row.size)
                    val rms = row.map { rms(it.samples) }
                    assertTrue(20*log10(rms.max()/rms.min()) < .01, "$choice pad $i take levels")
                    for (t in 1..2) assertTrue(residual(row[0].samples,row[t].samples) > .005, "$choice pad $i take $t")
                    rms.average()
                }
                assertTrue(energies.zipWithNext().all { (x,y) -> x < y })
                assertTrue(residual(p.velocityTakes[0][0].samples,p.velocityTakes[2][0].samples) > .1)
                assertContentEquals(a[i].snip.samples,b[i].snip.samples)
            }
            a[0].velocityTakes[0][0].samples.fill(0f)
            assertTrue(b[0].velocityTakes[0][0].peak() > .01f)
            assertTrue(b[14].snip.frameCount > 44100 && b[15].snip.frameCount > 44100)
        }
    }
    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble()*it }/x.size)
    private fun residual(a: FloatArray,b: FloatArray): Double {
        val n = minOf(a.size,b.size)
        val aa = (0 until n).sumOf { a[it].toDouble()*a[it] }
        val bb = (0 until n).sumOf { b[it].toDouble()*b[it] }
        val ab = (0 until n).sumOf { a[it].toDouble()*b[it] }
        return sqrt(max(0.0,aa-ab*ab/bb)/aa)
    }
}
