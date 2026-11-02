package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class ErosionBankTest {
    @Test fun `ARC and CRATER retain all 198 frozen corner and touch renders`() {
        val lines = javaClass.getResourceAsStream("/erosion/arc-crater-v1.sha256")!!.bufferedReader().readLines()
        var count = 0
        for (line in lines.filterNot { it.startsWith("#") || it.isBlank() }) {
            val (voiceText, bitsText, velocityText, expected) = line.split(' ')
            val voice = ErosionVoice.valueOf(voiceText)
            val bits = bitsText.toInt()
            val m = if (bits == -1) emptyMap() else Erosion.defaults(voice).keys.mapIndexed { i,k ->
                k to ((bits shr i) and 1).toFloat()
            }.toMap()
            val x = Erosion.render(voice,m,velocityText.toFloat()).samples
            val buffer = ByteBuffer.allocate(x.size*4).order(ByteOrder.LITTLE_ENDIAN)
            x.forEach { buffer.putFloat(it) }
            val actual = MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
            assertEquals(expected,actual,"$voice corner=$bits velocity=$velocityText changed")
            count++
        }
        assertEquals(198,count)
    }
    @Test fun `bank has sixteen complete dry ARC CRATER recipes and three touch layers`() {
        val entries=ErosionBank.entries()
        assertEquals(16,entries.size)
        assertEquals(16,entries.map { it.name }.toSet().size)
        assertEquals(listOf(.35f,.65f,1f),ErosionBank.velocities)
        assertEquals(8,entries.count { it.patch.voice==ErosionVoice.ARC })
        assertEquals(8,entries.count { it.patch.voice==ErosionVoice.CRATER })
        for(entry in entries) {
            assertEquals(Erosion.defaults(entry.patch.voice).keys,entry.patch.macros.keys)
            assertTrue(entry.patch.macros.values.all { it in 0f..1f })
            for(v in ErosionBank.velocities) {
                val p=entry.patch.copy(velocity=v)
                assertEquals(p,Patches.fromJsonText(p.toJsonText()))
                assertContentEquals(p.render().samples,PadRecipe.fromJsonText(PadRecipe(p).toJsonText()).render().samples)
            }
        }
    }
    @Test fun `SCAR reserves aftershock energy and retains an actual source gap`() {
        val m=Erosion.defaults(ErosionVoice.SCAR)
        val full=Erosion.schedule(ErosionVoice.SCAR,m)
        val off=Erosion.schedule(ErosionVoice.SCAR,m,scarAftershock=false)
        val stages=assertNotNull(full.scarStages)
        assertTrue(full.conservationError < 1e-12 && off.conservationError < 1e-12)
        assertEquals(.12,stages.reservedEnergy)
        assertTrue(full.emitted-off.emitted in .08.. .14)
        assertEquals(off.events,full.events.filter { it.seconds < stages.aftershockStart })
        assertTrue(full.events.any { it.seconds >= stages.aftershockStart })
        assertTrue(full.events.none { it.seconds in stages.clusterEnd..(stages.aftershockStart-1e-8) })
        val raw=Erosion.source(ErosionVoice.SCAR)
        val ablated=Erosion.source(ErosionVoice.SCAR,scarAftershock=false)
        val start=(stages.aftershockStart*Dsp.RATE*4).toInt()
        assertContentEquals(raw.copyOfRange(0,start),ablated.copyOfRange(0,start))
        assertTrue((start until raw.size).any { raw[it]!=ablated[it] })
        fun energy(a: Int,b: Int)=raw.copyOfRange(a,b).sumOf { it.toDouble()*it }/(b-a)
        val quietA=((stages.aftershockStart-.02)*Dsp.RATE*4).toInt()
        val quietB=((stages.aftershockStart-.005)*Dsp.RATE*4).toInt()
        val attack=energy(0,(.05*Dsp.RATE*4).toInt())
        val quiet=energy(quietA,quietB)
        val after=energy(start,min(raw.size,start+(.05*Dsp.RATE*4).toInt()))
        println("SCAR default attackPower=$attack quietPower=$quiet afterPower=$after after/attack=${after/attack}")
        assertTrue(quiet < attack*1e-4,"SCAR recovery gap has not cleared the primary tail")
        assertTrue(after < attack,"SCAR aftershock should be quieter at defaults")
    }
}
