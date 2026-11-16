package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class CuttingsKitsTest {
    @Test fun `approved assets retain hashes and each voice has three distinct dynamic four-take zones`() {
        val bank=CuttingsKits.build(CuttingsKits.Genre.POCKET)
        assertEquals(20,bank.size)
        val voices=CuttingsKits.variants.map { it.voice }.distinct()
        for ((slot,voice) in voices.withIndex()) {
            val pad=bank[slot]
            for(d in 0..2) {
                val row=CuttingsKits.variants.filter { it.voice==voice && it.dynamic==d }.sortedBy { it.take }
                assertEquals(listOf(0,1,2,3),row.map { it.take })
                assertEquals(4,row.map { it.sha256 }.distinct().size)
                for ((take,v) in row.withIndex()) {
                    val bytes=CuttingsKits.resource(v.file)
                    val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    assertEquals(v.sha256,hash)
                    assertContentEquals(WavReader.read(bytes).samples,pad.velocityTakes[d][take].samples)
                }
            }
        }
    }
    @Test fun `genre roles differ and every hit and phrase remains finite dynamic and diverse`() {
        val builds=CuttingsKits.Genre.entries.map { genre -> genre to CuttingsKits.build(genre) }
        assertEquals(3,builds.map { (_,pads)->pads.take(14).map { it.source["role"] } }.distinct().size)
        for ((genre,pads) in builds) {
            assertEquals(20,pads.size)
            for(pad in pads) {
                assertEquals(3,pad.velocityTakes.size)
                val energy=pad.velocityTakes.map { row ->
                    assertEquals(4,row.size)
                    assertEquals(4,row.map { it.samples.contentHashCode() }.distinct().size)
                    row.map { snip ->
                        assertTrue(snip.samples.all { it.isFinite() && abs(it)<1f })
                        assertTrue(snip.samples.last() == 0f) // Signed zero is also a silent endpoint.
                        assertTrue(abs(snip.samples.average()) < .002, "DC offset in ${genre.name} ${pad.displayName}")
                        sqrt(snip.samples.sumOf { it.toDouble()*it }/snip.frameCount)
                    }.average()
                }
                assertTrue(energy[0]<energy[1] && energy[1]<energy[2],"${genre.name} ${pad.displayName}: $energy")
            }
            for((i,bars) in listOf(1,2,4).withIndex()) for(offset in 0..1) {
                val pad=pads[14+2*i+offset]
                assertEquals("$bars",pad.source["bars"])
                val expected=bars*4*60.0/genre.bpm+.32
                assertTrue(abs(pad.snip.frameCount.toDouble()/Dsp.RATE-expected)<.0001)
            }
            assertEquals(2,pads.count { it.muteGroup==1 })
        }
    }
}
