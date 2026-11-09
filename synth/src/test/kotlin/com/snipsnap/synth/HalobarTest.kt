package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class HalobarTest {
    private fun rms(x: FloatArray,from: Int=0,to: Int=x.size): Double {
        var sum=0.0;for(i in from until to)sum+=x[i].toDouble()*x[i]
        return sqrt(sum/(to-from).coerceAtLeast(1))
    }
    private fun distance(a: FloatArray,b: FloatArray): Double {
        var sum=0.0;val n=max(a.size,b.size)
        for(i in 0 until n) { val d=(a.getOrElse(i){0f}-b.getOrElse(i){0f}).toDouble();sum+=d*d }
        return sqrt(sum/n)/max(rms(a),1e-12)
    }
    private fun centroid(x: FloatArray): Double {
        val size=4096;val data=FloatArray(size)
        x.copyInto(data,0,0,min(x.size,(.08*Halobar.RATE).toInt()))
        val p=Fft.magnitudeSpectrum(data,size)
        var sum=0.0;var weighted=0.0
        for(i in p.indices) { val q=p[i].toDouble()*p[i];sum+=q;weighted+=q*i*Halobar.RATE/size }
        return weighted/sum
    }

    @Test fun `voices retain the root across three registers dry and processed`() {
        for(v in HalobarVoice.entries) for(note in when(v) {
            HalobarVoice.CROTALES -> listOf(84,96,108)
            HalobarVoice.GLOCKENSPIEL -> listOf(65,77,89)
            HalobarVoice.XYLOPHONE -> listOf(57,65,77)
            else -> listOf(53,65,77)
        }) for(space in listOf(0f,.35f)) {
            val m=mapOf("TUNE" to Halobar.tuneForMidi(v,note),"SPACE" to space)
            val p=Halobar.renderProbe(v,m,normalize=true)
            assertEquals(note,p.plan.midi,"register comparison must play the requested note")
            val snip=Snip(p.samples,1,Halobar.RATE)
            // The house detector stops at 1.2 kHz. Analyze high metal voices
            // at a slower clock, then rescale the estimate; no resampling
            // or expected-frequency search can force the requested note.
            var factor=1
            while(p.plan.hz/factor>1100)factor*=2
            val pitch=assertNotNull(Pitch.detect(Snip(p.samples,1,Halobar.RATE/factor),
                fromSec=.05f*factor,windowSec=.25f*factor),"$v MIDI $note SPACE $space has no root")
            val cents=1200*log2(pitch.hz*factor/p.plan.hz)
            assertTrue(abs(cents)<5,"$v MIDI $note SPACE $space root error $cents cents")
            assertTrue(pitch.confidence>=.3,"$v MIDI $note root confidence ${pitch.confidence}")
            assertTrue(p.rawPeak in .001f..4f,"$v raw peak ${p.rawPeak}")
            assertTrue(p.samples.all { it.isFinite() })
            assertTrue(p.samples.maxOf { abs(it) }<=.99001f)
            assertTrue(abs(p.samples.sumOf { it.toDouble() }/p.samples.size)<.005)
            assertTrue(Loudness.of(snip)>=.9*Dsp.MELODIC_LOUDNESS_TARGET || snip.peak()>=.95f)
            assertEquals(p.plan.frames,snip.frameCount)
        }
    }

    @Test fun `wooden contact and natural decay separate xylophone from marimba`() {
        for(note in listOf(57,65,77)) {
            fun controls(v: HalobarVoice)=mapOf("TUNE" to Halobar.tuneForMidi(v,note),"SPACE" to 0f)
            val wood=Halobar.renderProbe(HalobarVoice.MARIMBA,controls(HalobarVoice.MARIMBA))
            val xylo=Halobar.renderProbe(HalobarVoice.XYLOPHONE,controls(HalobarVoice.XYLOPHONE))
            assertTrue(xylo.plan.contactSeconds<.7*wood.plan.contactSeconds)
            assertTrue(xylo.plan.modeT60.first()<.6*wood.plan.modeT60.first())
            assertTrue(centroid(xylo.samples)>centroid(wood.samples),"MIDI $note xylophone attack lost its upper modes")
            val after=(.4*Halobar.RATE).toInt()
            assertTrue(rms(xylo.samples,after,min(xylo.samples.size,after+4410)) < rms(wood.samples,after,min(wood.samples.size,after+4410)))
        }
    }

    @Test fun `native finite plans and real classifier keep pitched voices off drum shelves`() {
        val drums=setOf(DrumClass.KICK,DrumClass.SNARE,DrumClass.CLAP,DrumClass.TOM,DrumClass.HAT_CLOSED,DrumClass.HAT_OPEN)
        val failures=mutableListOf<String>()
        for(v in HalobarVoice.entries) for(tune in listOf(0f,.5f,1f)) for(hold in listOf(0f,.98f)) for(space in listOf(0f,.35f)) {
            val m=mapOf("TUNE" to tune,"HOLD" to hold,"SPACE" to space)
            val snip=Halobar.render(v,m)
            val c=Classifier.classify(snip)
            if(c.drumClass in drums)failures+="$v $m classified ${c.drumClass}: ${c.features}"
            assertEquals(snip.frameCount>66150,Halobar.drumClassFor(v,m)==DrumClass.LOOP)
            assertEquals(snip.frameCount>66150,c.drumClass==DrumClass.LOOP)
        }
        assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }

    @Test fun `feedback decay guard fails with the deliberately unstable gain`() {
        val bounded=Halobar.feedbackEnergyProbe()
        val unstable=Halobar.feedbackEnergyProbe(1.08)
        fun decays(e: List<Double>)=e.zipWithNext().all { (a,b) -> b<a }
        assertTrue(decays(bounded),"bounded HALOBAR loop grows: $bounded")
        assertFalse(decays(unstable),"negative control did not defeat the decay guard: $unstable")
        assertTrue(unstable.last()>unstable.first(),"unstable loop failed to grow")
    }

    @Test fun `partitioned halo preserves impulse gain and delayed partition boundaries`() {
        val h=Halobar.haloKernelForProbe()
        val x=FloatArray(50000)
        val impulses=mapOf(0 to .7f,4093 to -.3f,8191 to .5f)
        for((i,value) in impulses)x[i]=value
        val actual=Halobar.convolveHaloForProbe(x)
        var error=0.0
        for(i in actual.indices) {
            var expected=0.0
            for((at,value) in impulses)if(i>=at && i-at<h.size)expected+=value*h[i-at]
            error=max(error,abs(expected-actual[i]))
        }
        assertTrue(error<2e-6,"partitioned FIR error $error")
        assertTrue(abs(rms(h)*sqrt(h.size.toDouble())-.24)<1e-5,"halo normalization changed")
    }

    @Test fun `all macros move each voice and required pairs interact`() {
        for(v in HalobarVoice.entries) for(name in Halobar.defaults(v).keys) {
            val a=Halobar.renderProbe(v,mapOf(name to 0f)).samples
            val b=Halobar.renderProbe(v,mapOf(name to .5f)).samples
            val c=Halobar.renderProbe(v,mapOf(name to 1f)).samples
            assertTrue(distance(a,b)>.005,"$v $name low to middle is dead")
            assertTrue(distance(b,c)>.005,"$v $name middle to high is dead")
        }
        for((a,b) in listOf("TOUCH" to "SPACE","RETURN" to "SPACE","RATE" to "RETURN","MOTION" to "RETURN","HOLD" to "RETURN")) {
            val corners=listOf(0f to 0f,0f to 1f,1f to 0f,1f to 1f).map { (x,y) -> Halobar.renderProbe(HalobarVoice.XYLOPHONE,mapOf(a to x,b to y)).samples }
            val n=corners.maxOf { it.size };var e=0.0
            for(i in 0 until n) {
                val d=corners[3].getOrElse(i){0f}-corners[2].getOrElse(i){0f}-corners[1].getOrElse(i){0f}+corners[0].getOrElse(i){0f}
                e+=d.toDouble()*d
            }
            assertTrue(sqrt(e/n)>.0001,"$a x $b has no interaction")
        }
    }

    @Test fun `velocity changes contact and returns exact silence at zero`() {
        for(v in HalobarVoice.entries) {
            val p=HalobarPatch("Touch",v,mapOf("SPACE" to .5f))
            val normal=p.render()
            val soft=Velocity.atVelocity(p,.25f)
            assertEquals(normal.frameCount,soft.frameCount)
            assertTrue(distance(normal.samples,soft.samples)>.05,"$v velocity only changed delivery level")
            assertTrue(Halobar.plan(v,velocity=.25f).contactSeconds>Halobar.plan(v).contactSeconds)
            val silent=Velocity.atVelocity(p,0f)
            assertEquals(normal.frameCount,silent.frameCount)
            assertTrue(silent.samples.all { it==0f })
        }
    }

    @Test fun `soft strikes do not invert the native attack brightness`() {
        for(v in HalobarVoice.entries) {
            val measured=listOf(.25f,.5f,.75f,1f).map { velocity ->
                centroid(Halobar.renderProbe(v,mapOf("SPACE" to 0f),velocity=velocity,normalize=true).samples)
            }
            assertTrue(measured.zipWithNext().all { (a,b) -> b>=a*.98 },"$v brightness inverted: $measured")
        }
    }

    @Test fun `hard bright renders remain close to an eight times reference`() {
        for(v in HalobarVoice.entries) {
            val m=mapOf("TUNE" to 1f,"TOUCH" to 1f,"RETURN" to 1f,"SPACE" to 1f,"HOLD" to 0f)
            val four=Halobar.renderProbe(v,m,oversample=4).samples
            val eight=Halobar.renderProbe(v,m,oversample=8).samples
            assertEquals(four.size,eight.size)
            assertTrue(distance(eight,four)<.1,"$v higher-rate residual ${distance(eight,four)}")
            val spectrum=Fft.magnitudeSpectrum(four,8192)
            val total=spectrum.sumOf { it.toDouble()*it }
            val high=spectrum.takeLast((spectrum.size*.05).toInt()).sumOf { it.toDouble()*it }
            assertTrue(high/total<1e-6,"$v near-Nyquist energy ${high/total}")
        }
    }

    @Test fun `reserved HOLD top is finite and cache owns its samples`() {
        for(v in HalobarVoice.entries) {
            Halobar.clearCache()
            val p=HalobarPatch("Finite",v,mapOf("HOLD" to 1f,"RETURN" to 1f))
            val first=p.render().samples
            val expected=first.copyOf()
            first[100]=123f
            assertContentEquals(expected,p.render().samples)
            Halobar.clearCache()
            assertContentEquals(expected,p.copy(macros=p.macros+("HOLD" to .98f)).render().samples)
            assertFalse(Halobar.isLoop(1f))
            assertTrue(expected.size<=12*Halobar.RATE)
            assertTrue(abs(expected.last())<1e-4,"finite ending leaves an audible discontinuity: ${expected.last()}")
        }
    }

    @Test fun `SPACE edits reuse private stems without changing fresh audio or other controls`() {
        for(v in HalobarVoice.entries) {
            Halobar.clearCache()
            Halobar.render(v,mapOf("SPACE" to .31f))
            // A different positive SPACE can reuse preparation, but it must
            // still apply its own gains, loudness and finite ending.
            val m=mapOf("SPACE" to .73f)
            val changed=Halobar.render(v,m).samples
            val fresh=Halobar.renderProbe(v,m,normalize=true).samples
            assertContentEquals(fresh,changed,"$v SPACE edit")
            changed.fill(123f)
            assertContentEquals(Halobar.renderProbe(v,mapOf("SPACE" to .57f),normalize=true).samples,
                Halobar.render(v,mapOf("SPACE" to .57f)).samples,"$v stem ownership")
            // Each dependency must invalidate preparation, including the
            // reserved HOLD top and the shorter dry-only duration.
            for(name in Halobar.defaults(v).keys) {
                Halobar.clearCache()
                Halobar.render(v,m)
                val edited=m+(name to 1f)
                assertContentEquals(Halobar.renderProbe(v,edited,normalize=true).samples,
                    Halobar.render(v,edited).samples,"$v $name invalidation")
            }
            assertContentEquals(Halobar.renderProbe(v,m,velocity=.41f,normalize=true).samples,
                Halobar.render(v,m,velocity=.41f).samples,"$v velocity invalidation")
            val dry=m+("SPACE" to 0f)
            assertContentEquals(Halobar.renderProbe(v,dry,normalize=true).samples,
                Halobar.render(v,dry).samples,"$v dry bypass")
        }
    }

    @Test fun `direct settlement is forgiving while saved patch validation is strict`() {
        for(v in HalobarVoice.entries) {
            assertEquals(Halobar.defaults(v),Halobar.settled(mapOf("TOUCH" to Float.NaN,"RETURN" to Float.POSITIVE_INFINITY,"UNKNOWN" to 1f),v))
            for(seed in 0..9)assertTrue(Halobar.scramble(v,Random(seed)).values.all { it.isFinite() && it in 0f..1f })
            assertTrue(Halobar.scramble(v,Random(5)).getValue("HOLD")<=.98f)
        }
        val bad=assertFailsWith<IllegalArgumentException> { HalobarPatch("Bad",HalobarVoice.MARIMBA,mapOf("UNKNOWN" to 1f)) }
        assertTrue(bad.message.orEmpty().contains("unknown macro UNKNOWN"))
    }

    @Test fun `JSON and editing preserve the audio model and reject unsupported models`() {
        val original=HalobarPatch("Saved",HalobarVoice.XYLOPHONE,mapOf("SPACE" to .7f))
        val back=Patches.fromJsonText(original.toJsonText()) as HalobarPatch
        assertEquals(original,back)
        assertContentEquals(original.render().samples,back.render().samples)
        val edited=Patches.edited(original,"Moved",original.macros+("SPACE" to .4f)) as HalobarPatch
        assertEquals(original.model,edited.model)
        val fields=LinkedHashMap(original.toJsonValue().entries)
        fields.remove("model")
        assertEquals(1,HalobarPatch.fromJsonValue(JsonValue.Obj(fields)).model)
        fields["model"]=JsonValue.Num(999.0)
        val error=assertFailsWith<JsonException> { HalobarPatch.fromJsonValue(JsonValue.Obj(fields)) }
        assertTrue(error.message.orEmpty().contains("unsupported HALOBAR model 999"))
    }

    @Test fun `TUNE round trips every note and one clock plans every delay head`() {
        for(v in HalobarVoice.entries)for(note in Halobar.minimumMidi(v)..Halobar.maximumMidi(v))
            assertEquals(note,Halobar.midiFor(v,Halobar.tuneForMidi(v,note)))
        val expected=listOf(1.25,5.0/6,.625,5.0/12,.3125,5.0/24)
        for(i in expected.indices) {
            val p=Halobar.plan(HalobarVoice.MARIMBA,mapOf("RATE" to (i+.2f)/6))
            assertTrue(abs(p.delayFrames.toDouble()/Halobar.RATE-expected[i])<=1.0/Halobar.RATE)
            assertEquals(p.delayFrames*2,p.headFrames[2])
            assertTrue(p.loopGain<=.84)
        }
    }
}
