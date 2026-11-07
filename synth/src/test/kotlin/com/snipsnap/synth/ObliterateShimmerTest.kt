package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.*
import org.junit.jupiter.api.Test

class ObliterateShimmerTest {
    private val rate = ObliterateShimmer.RATE
    private fun source(frames: Int = 9000, channels: Int = 2, gain: Double = .12): Snip = Snip(
        FloatArray(frames * channels) { i ->
            val n = i / channels
            (gain * sin(2 * PI * (if (i % channels == 0) 220 else 331) * n / rate) * kotlin.math.exp(-n / 2300.0)).toFloat()
        }, channels, rate,
    )
    private fun bytes(name: String): ByteArray = javaClass.getResourceAsStream("/obliterate-shimmer/$name")!!.use { it.readBytes() }
    private fun floats(data: ByteArray): FloatArray = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test fun `independent Python fixtures preserve full tank and octave histories`() {
        val manifest = (Json.parse(bytes("manifest.json").toString(Charsets.UTF_8)) as JsonValue.Obj).entries
        val inputBytes = bytes("input.f32")
        assertEquals((manifest.getValue("inputSha256") as JsonValue.Str).value, hash(inputBytes))
        val input = Snip(floats(inputBytes), 2, rate)
        for (entry in (manifest.getValue("rows") as JsonValue.Arr).items) {
            val row = (entry as JsonValue.Obj).entries
            val data = bytes((row.getValue("file") as JsonValue.Str).value)
            assertEquals((row.getValue("sha256") as JsonValue.Str).value, hash(data))
            val expected = floats(data)
            val result = ObliterateShimmer.render(input, ObliterateShimmer.Settings(
                (row.getValue("mix") as JsonValue.Num).value, (row.getValue("pitched") as JsonValue.Bool).value,
            )).audio.samples
            assertEquals(expected.size, result.size)
            var largest = 0.0; var squared = 0.0
            for (i in result.indices) { val d = result[i].toDouble() - expected[i]; largest = maxOf(largest, abs(d)); squared += d*d }
            assertTrue(largest <= 2e-6, "fixture max $largest")
            assertTrue(sqrt(squared / result.size) <= 2e-7, "fixture RMS")
        }
    }
    @Test fun `zero is exact same Snip with signed zeros and no tail`() {
        val input = Snip(floatArrayOf(-0.0f,.2f,-.1f,0f), 1, rate)
        val result = ObliterateShimmer.render(input, ObliterateShimmer.Settings(0.0), true)
        assertSame(input, result.audio); assertEquals(0,result.diagnostics.tailFrames)
        assertEquals(input.samples.map { it.toRawBits() },result.audio.samples.map { it.toRawBits() })
        assertTrue(result.wet!!.samples.all { it == 0f })
    }
    @Test fun `invalid PCM controls rate and budget are refused`() {
        for (mix in listOf(-.01,.501,Double.NaN,Double.POSITIVE_INFINITY)) assertFailsWith<IllegalArgumentException> { ObliterateShimmer.Settings(mix) }
        for (samples in listOf(FloatArray(0),floatArrayOf(Float.NaN),floatArrayOf(Float.POSITIVE_INFINITY),floatArrayOf(1.001f))) {
            assertFailsWith<IllegalArgumentException> { ObliterateShimmer.render(Snip(samples,1,rate)) }
        }
        assertFailsWith<IllegalArgumentException> { ObliterateShimmer.render(Snip(floatArrayOf(.1f),1,48000)) }
        assertFailsWith<IllegalArgumentException> { ObliterateShimmer.render(Snip(FloatArray(9*rate+1),1,rate)) }
    }
    @Test fun `silence has finite stereo output and exact tail`() {
        val r=ObliterateShimmer.render(Snip(FloatArray(500),1,rate),includeWet=true)
        assertEquals(500+66150,r.audio.frameCount); assertEquals(2,r.audio.channels)
        assertTrue(r.audio.samples.all { it==0f });assertEquals(0.0,r.diagnostics.statePeak)
        assertEquals(0.76,r.diagnostics.contractionBound);assertFalse(r.diagnostics.limiterInsideTank)
    }
    @Test fun `fresh renders and optional wet capture do not retain state`() {
        val x=source();val a=ObliterateShimmer.render(x);val b=ObliterateShimmer.render(x,includeWet=true)
        assertContentEquals(a.audio.samples,b.audio.samples)
        assertContentEquals(a.audio.samples,ObliterateShimmer.render(x).audio.samples)
        assertNull(a.wet); assertNotNull(b.wet)
    }
    @Test fun `attack stays dry before first delayed return`() {
        val x=source();val r=ObliterateShimmer.render(x,ObliterateShimmer.Settings(.3))
        assertEquals(1.0,r.diagnostics.guardGain)
        assertContentEquals(x.samples.copyOfRange(0,1493*2),r.audio.samples.copyOfRange(0,1493*2))
    }
    @Test fun `quiet scaling and send preserve linear wet dynamics`() {
        val x=source();val a=ObliterateShimmer.render(x,ObliterateShimmer.Settings(.15),true)
        val b=ObliterateShimmer.render(x,ObliterateShimmer.Settings(.3),true)
        val q=ObliterateShimmer.render(Snip(FloatArray(x.samples.size) { x.samples[it]*.1f },2,rate),ObliterateShimmer.Settings(.3))
        for(i in b.audio.samples.indices) {
            assertTrue(abs(b.wet!!.samples[i]-2*a.wet!!.samples[i])<1e-7f)
            assertTrue(abs(q.audio.samples[i]-.1f*b.audio.samples[i])<1e-7f)
        }
    }
    @Test fun `stereo excitation and room crossfeed are real`() {
        val x=FloatArray(6000*2);x[0]=.5f
        val r=ObliterateShimmer.render(Snip(x,2,rate),ObliterateShimmer.Settings(.3),true)
        val wet=requireNotNull(r.wet).samples
        assertTrue(wet.indices.filter { it%2==1 }.maxOf { abs(wet[it]) }>1e-5f)
        assertTrue((0 until r.audio.frameCount).any { wet[it*2]!=wet[it*2+1] })
    }
    @Test fun `room and octave feedback remain distinct`() {
        val a=ObliterateShimmer.render(source(),ObliterateShimmer.Settings(.3,false),true)
        val b=ObliterateShimmer.render(source(),ObliterateShimmer.Settings(.3,true),true)
        assertEquals(0.0,a.diagnostics.shiftedFeedbackPeak)
        assertTrue(b.diagnostics.shiftedFeedbackPeak>0)
        val aw=requireNotNull(a.wet).samples;val bw=requireNotNull(b.wet).samples
        assertTrue(aw.indices.maxOf { abs(aw[it]-bw[it]) }>.001f)
    }
    @Test fun `unforced state releases before boundary fade`() {
        val x=FloatArray(rate*2);x[0]=.5f;x[1]=.5f
        val r=ObliterateShimmer.render(Snip(x,2,rate),ObliterateShimmer.Settings(.3),true)
        fun rms(first:Int,last:Int)=sqrt((first*2 until last*2).sumOf { val v=r.wet!!.samples[it].toDouble();v*v }/((last-first)*2))
        val decay=20*ln(rms(40000,44000)/rms(2000,12000))/ln(10.0)
        assertTrue(decay< -90,"unforced decay $decay dB")
        assertTrue(r.diagnostics.last100msStatePeak<1e-10)
        assertEquals(0f,r.audio.samples.last())
    }
    @Test fun `peak guard is linked and only attenuates`() {
        val r=ObliterateShimmer.render(Snip(FloatArray(10000*2) { .99f },2,rate),ObliterateShimmer.Settings(.5))
        assertTrue(r.diagnostics.guardGain<1.0)
        assertTrue(r.audio.samples.all { it.isFinite() && abs(it)<=.9800001f })
    }
    @Test fun `nine seconds is valid and delay storage does not grow with source length`() {
        val a=ObliterateShimmer.render(source(500))
        val b=ObliterateShimmer.render(Snip(FloatArray(9*rate),1,rate))
        assertEquals(9*rate+66150,b.audio.frameCount)
        assertEquals(a.diagnostics.delayHistorySamples,b.diagnostics.delayHistorySamples)
        assertTrue(b.diagnostics.delayHistorySamples*8<310000)
    }
    @Test fun `whole chain composes public Snips and its finite budgets`() {
        val x=source(500,1)
        for (mix in listOf(0.0,.15)) for (amount in listOf(0.0,.35)) {
            val settings=Obliterate.Settings(ObliterateShimmer.Settings(mix),ObliterateCore.Settings(amount=amount))
            val r=Obliterate.render(x,settings)
            val sequential=ObliterateCore.render(ObliterateShimmer.render(x,settings.shimmer).audio,settings.core)
            assertContentEquals(sequential.audio.samples,r.audio.samples)
            assertEquals(500+(if(mix>0)66150 else 0)+(if(amount>0)33075 else 0),r.audio.frameCount)
            if(mix==0.0 && amount==0.0) assertSame(x,r.audio)
        }
    }
    @Test fun `core-only composition preserves the existing processor exactly`() {
        val x=source();val settings=Obliterate.Settings(shimmer=ObliterateShimmer.Settings(0.0))
        assertContentEquals(ObliterateCore.render(x,settings.core).audio.samples,Obliterate.render(x,settings).audio.samples)
    }
}
