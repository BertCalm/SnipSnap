package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.*

/** Pin raw Float bits, before PCM quantization, from the approved renderer before optimization. */
class DrumlineRenderIdentityTest {
    @Test fun `coefficient reuse retains every voice and figure sample for sample`() {
        val rows=requireNotNull(javaClass.getResourceAsStream("/drumline-render-float-goldens.csv"))
            .bufferedReader().use { it.readLines() }.drop(1).map { it.split(',') }
        assertEquals(42,rows.size)
        assertEquals(DrumlineVoice.entries.toSet(),rows.map { DrumlineVoice.valueOf(it[0]) }.toSet())
        for((name,case,expected) in rows) {
            val macros=if(case=="default") emptyMap() else mapOf("DAMP" to 1f,"STRIKE" to 0f)
            val samples=Drumline.render(DrumlineVoice.valueOf(name),macros,
                velocity=if(case=="default")1f else .3f,take=if(case=="default")0 else 2,model=4).samples
            val bytes=ByteBuffer.allocate(samples.size*4).order(ByteOrder.LITTLE_ENDIAN)
            for(x in samples)bytes.putInt(x.toRawBits())
            val actual=MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(expected,actual,"$name $case changed its unquantized audio")
        }
    }
}
