package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.*
import kotlin.test.*

class SporeKickTest {
    private fun oracle(id:String):FloatArray {
        val stream=checkNotNull(javaClass.getResourceAsStream("/spore-kick-approved/$id.f32.gz"))
        val bytes=GZIPInputStream(stream).use { it.readBytes() }
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
    private fun parity(id:String,c:SporeKick.Controls=SporeKick.Controls(),v:Double=1.0,t:Int=0) {
        val expected=oracle(id);val actual=SporeKick.render(c,v,t).samples
        assertEquals(expected.size,actual.size)
        val maxError=actual.indices.maxOf { abs(actual[it]-expected[it]) }
        val relative=sqrt(actual.indices.sumOf { (actual[it]-expected[it]).toDouble().pow(2) }/expected.sumOf { it.toDouble().pow(2) })
        println("Spore parity $id: max=$maxError relativeRms=$relative")
        assertTrue(maxError<1e-7,"$id maximum error $maxError")
        assertTrue(relative<1e-5,"$id relative RMS $relative")
    }
    @Test fun `approved Python waveforms survive Kotlin port at all nine grid cells and control edges`() {
        for((z,v)in listOf(.3,.65,1.0).withIndex()) for(t in 0..2)parity("z$z-t$t",v=v,t=t)
        for(v in listOf(0.0,1.0)) {
            parity("tune-$v",SporeKick.Controls(tune=v));parity("strike-$v",SporeKick.Controls(strike=v))
            parity("body-$v",SporeKick.Controls(body=v));parity("click-$v",SporeKick.Controls(click=v))
        }
    }
    @Test fun `versioned source recipes replay deterministic distinct takes and timbral velocity`() {
        for(t in 0..2) {
            val soft=SporeKick.Recipe(velocity=.3,take=t)
            assertEquals(soft,SporeKick.Recipe.fromJsonText(soft.toJsonText()))
            assertContentEquals(soft.render().samples,SporeKick.Recipe.fromJsonText(soft.toJsonText()).render().samples)
            val a=soft.render();val b=SporeKick.render(velocity=.65,take=t);val c=SporeKick.render(take=t)
            assertTrue(a.peak()<b.peak() && b.peak()<c.peak())
            val distance=a.samples.indices.sumOf { (a.samples[it]/a.peak()-c.samples[it]/c.peak()).toDouble().pow(2) }
            assertTrue(distance>1.0)
            if(t>0)assertFalse(c.samples.contentEquals(SporeKick.render().samples))
        }
        assertTrue(SporeKick.render(velocity=0.0).samples.all { it==0f })
        assertFailsWith<IllegalArgumentException>{SporeKick.Recipe(model=2)}
        assertFailsWith<IllegalArgumentException>{SporeKick.render(velocity=Double.NaN)}
    }
    @Test fun `fixed output gain stays finite bounded and silent at the end across canonical takes`() {
        for(t in 0..32) {
            val s=SporeKick.render(take=t)
            assertTrue(s.samples.all { it.isFinite() });assertTrue(s.peak() in .01f..1f)
            assertTrue(abs(s.samples.average())<.001);assertTrue(s.samples.last()==0f)
            assertEquals(19845,s.frameCount)
        }
    }
}
