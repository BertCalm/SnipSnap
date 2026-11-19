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
                    // The accepted coupled snare retains natural strike-level variation.
                    // The crash also retains small natural differences; original pads remain take-levelled.
                    val spreadLimit = when {
                        choice == SoulRockKits.Choice.ROADHOUSE && i == 1 -> 4.0
                        choice == SoulRockKits.Choice.ROADHOUSE && i == 11 -> .5
                        else -> .01
                    }
                    assertTrue(20*log10(rms.max()/rms.min()) < spreadLimit, "$choice pad $i take levels")
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
    @Test fun `accepted coupled snare bytes and source identity are pinned`() {
        val expected = mapOf(
            "roadhouse-rock/2-0-0.wav.gz" to "9c28c7df3b55851048d5809ec29a45f5b304ad6857025424994e9647667b5dda",
            "roadhouse-rock/2-0-1.wav.gz" to "419997696db6186a68a5195d27ea7df868116eff9a8d50ecf49a650cf9e7b7c5",
            "roadhouse-rock/2-0-2.wav.gz" to "c24e9b969344fb329f501cf8a3a19afd9199458916cb1b89078000e9b8d53b9f",
            "roadhouse-rock/2-1-0.wav.gz" to "57f5476b144ebf8f3602913f5a74fd1927dc8ad5e97107466c18563c6055f17a",
            "roadhouse-rock/2-1-1.wav.gz" to "71297fbc64c6b8258a7bd44aca82e09e5e7494014b2d9fde0e0dd168fff37235",
            "roadhouse-rock/2-1-2.wav.gz" to "900f4386ed59289d6067c8ec679661c58b34e5d9df0fce2932d18591010ea860",
            "roadhouse-rock/2-2-0.wav.gz" to "81b073e8781c1d366f84930abf744e60d1256469b4f63ebb7ac36b8a6a1f2be2",
            "roadhouse-rock/2-2-1.wav.gz" to "028ac59d41d97ce731d8c26c747e37ce53ec6b4a617451dcf36d5594dfc91926",
            "roadhouse-rock/2-2-2.wav.gz" to "3d34553be79faa3d5aac79172a5fadc5c320ff771b36986d461d27f163881422"
        )
        for ((name, hash) in expected) {
            val actual = MessageDigest.getInstance("SHA-256").digest(SoulRockKits.resource(name)).joinToString("") { "%02x".format(it) }
            assertEquals(hash, actual)
        }
        val snare = SoulRockKits.build(SoulRockKits.Choice.ROADHOUSE)[1]
        assertEquals("coupled-snare-snap-rattle", snare.source["sourceModel"])
        assertTrue(snare.source.keys.none { it.startsWith("synthesis.") })
    }
    @Test fun `accepted crash bytes label and source identity are pinned`() {
        val expected = mapOf(
            "roadhouse-rock/12-0-0.wav.gz" to "2b5c51519ac66f73f3b5be11b035b74ec68935536b9ff0bc54857e02c1a269e6",
            "roadhouse-rock/12-0-1.wav.gz" to "42f569082f67f4000985de27f0d0310024c1ab562a08efa099dae15659c4173d",
            "roadhouse-rock/12-0-2.wav.gz" to "8093163fe94adbb1e99eaea0979850aa92bdec5e18858e24f022903359467e53",
            "roadhouse-rock/12-1-0.wav.gz" to "3fa55bb6aecbc0b5001100c3b536cba42dccbac6a76a7f9bd35ddb94301d15b3",
            "roadhouse-rock/12-1-1.wav.gz" to "04d37710545309731c013a6715fa07536bab0ae6815617e689f61fd55440707b",
            "roadhouse-rock/12-1-2.wav.gz" to "1f12861a8574f7b02f6a5462be7470c53d0b4431810d8b25a7c21bc5530be399",
            "roadhouse-rock/12-2-0.wav.gz" to "9715f513724eb8fce4b184f188351d71a4a160fa6943344785200754311935de",
            "roadhouse-rock/12-2-1.wav.gz" to "26e294be07a13938216f63565fec60e5649a5293f0e9a0e8621c82073c289800",
            "roadhouse-rock/12-2-2.wav.gz" to "99d42871425fb6e1e0c3f54533aa161bb3ef62aa2b8d9535b2808781f80befa5"
        )
        for ((name, hash) in expected) {
            val actual = MessageDigest.getInstance("SHA-256").digest(SoulRockKits.resource(name)).joinToString("") { "%02x".format(it) }
            assertEquals(hash, actual)
        }
        val crash = SoulRockKits.build(SoulRockKits.Choice.ROADHOUSE)[11]
        assertEquals("Crash", crash.displayName)
        assertEquals("CRASH", crash.source["voice"])
        assertEquals("statistical-modal-rock-crash-bloom", crash.source["sourceModel"])
        assertTrue(crash.source.keys.none { it.startsWith("synthesis.") })
        assertEquals(198450, crash.snip.frameCount)
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
