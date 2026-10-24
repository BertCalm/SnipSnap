package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.json.Json
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.Snip
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class RimeTest {
    @Test fun `model two published starters and angle comparisons keep their exact PCM`() {
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/rime-model2/$name")).bufferedReader().use { it.readText() }
        val hashes = Json.parse(resource("matched-pcm-sha256.json")).obj()
        for ((id, expected) in hashes) {
            val original = RimePatch.fromJsonText(resource("${id.substringBefore('-')}-patch.json"))
            assertEquals(2, original.model)
            val patch = if (id.endsWith("angle-25")) original.copy(macros = original.macros + ("ANGLE" to .25f)) else original
            val native = patch.render().samples
            Dsp.levelTo(native, Dsp.RATE, .10f, ceiling = .9f)
            val wav = ByteArrayOutputStream().also { WavWriter.write(it, Snip(native, 1, Dsp.RATE)) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) }
            assertEquals(expected.str(), hash, "$id model 2 must retain its point pickup")
        }
    }

    @Test fun `finite pickup aperture closes fine structure without cancelling the root`() {
        val ordinary = RimeContour.prepare(130.8128, .9, .8)
        val closed = RimeContour.prepare(130.8128, .9, .8, PI)
        val open = RimeContour.prepare(130.8128, .9, .8, 0.0)
        assertSame(ordinary.bands.first(), closed.bands.first())
        assertTrue(closed.bands.drop(1).all { band -> listOf(band.x, band.y, band.dx, band.dy).all { table -> table.all { it == 0f } } })
        assertTrue(open.bands.drop(1).any { band -> band.dx.any { abs(it) > .01f } })
        assertEquals(ordinary.coefficients.filter { abs(it.n) == 1 }, closed.coefficients.filter { abs(it.n) == 1 })
    }

    @Test fun `model one published recipes reproduce their original matched PCM exactly`() {
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/rime-model1/$name")).bufferedReader().use { it.readText() }
        val hashes = Json.parse(resource("matched-pcm-sha256.json")).obj()
        for (voice in listOf("loop", "glint", "vessel")) {
            val patch = RimePatch.fromJsonText(resource("$voice-patch.json"))
            assertEquals(1, patch.model)
            val native = patch.render().samples
            Dsp.levelTo(native, Dsp.RATE, .10f, ceiling = .9f)
            val wav = ByteArrayOutputStream().also { WavWriter.write(it, Snip(native, 1, Dsp.RATE)) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) }
            assertEquals(hashes.getValue(voice).str(), hash, "$voice model 1 must not inherit the revoicing")
        }
    }

    @Test fun `body changes spectral shape throughout the range on every normal voice`() {
        for (voice in RimeVoice.entries) {
            val p = RimePresets.forVoice(voice).single()
            val notes = listOf(0f, .5f, 1f).map { value -> p.copy(macros = p.macros + ("BODY" to value)).render().samples }
            for ((a, b) in listOf(0 to 1, 1 to 2, 0 to 2)) {
                val distance = RimeControlMeasure.distance(notes[a], notes[b], p.gateSeconds.toDouble())
                println("RIME $voice BODY $a/$b power-spectrum magnitude distance $distance")
                assertTrue(distance > if (a == 0 && b == 2) .20 else .06, "$voice BODY $a/$b remained a gain/phase change: $distance")
            }
        }
    }

    @Test fun `loop unfold changes fine structure during a normal bass note`() {
        val p = RimePresets.forVoice(RimeVoice.LOOP).single()
        val immediate = p.copy(macros = p.macros + ("UNFOLD" to 0f)).render().samples
        val unfolding = p.copy(macros = p.macros + ("UNFOLD" to 1f)).render().samples
        val distance = RimeControlMeasure.distance(immediate, unfolding, p.gateSeconds.toDouble())
        val early = RimeControlMeasure.fineShare(unfolding, Keys.midiHz(p.midi).toDouble(), .025)
        val later = RimeControlMeasure.fineShare(unfolding, Keys.midiHz(p.midi).toDouble(), .43)
        println("RIME LOOP UNFOLD spectral distance $distance; early fine share $early; later $later")
        assertTrue(distance > .08, "normal LOOP unfold remains too weak: $distance")
        assertTrue(later > early * 4 && later > .01, "fine structure must emerge within the note: $early -> $later")
    }

    @Test fun `angle quarter turns change normal preset spectra while a whole turn closes`() {
        for (voice in RimeVoice.entries) {
            val p = RimePresets.forVoice(voice).single()
            val zero = p.copy(macros = p.macros + ("ANGLE" to 0f)).render().samples
            val turns = listOf(.25f, .5f, .75f).map { value -> p.copy(macros = p.macros + ("ANGLE" to value)).render().samples }
            val distance = turns.maxOf { RimeControlMeasure.distance(zero, it, p.gateSeconds.toDouble()) }
            println("RIME $voice largest ANGLE quarter-turn distance $distance")
            assertTrue(distance > .06, "$voice intermediate ANGLE positions need useful spectral activity: $distance")
            assertContentEquals(zero, p.copy(macros = p.macros + ("ANGLE" to 1f)).render().samples)
        }
    }

    @Test fun `angle open and rounded positions change overtone power on every normal voice`() {
        for (voice in RimeVoice.entries) {
            val p = RimePresets.forVoice(voice).single()
            val open = p.copy(macros = p.macros + ("ANGLE" to 0f)).render().samples
            val rounded = p.copy(macros = p.macros + ("ANGLE" to .5f)).render().samples
            val distance = RimeControlMeasure.distance(open, rounded, p.gateSeconds.toDouble())
            val hz = Keys.midiHz(p.midi).toDouble()
            val openFine = RimeControlMeasure.fineShare(open, hz, .43)
            val roundedFine = RimeControlMeasure.fineShare(rounded, hz, .43)
            println("RIME $voice ANGLE open/round spectral distance $distance; fine power $openFine / $roundedFine")
            assertTrue(distance > .25, "$voice ANGLE needs a substantial level-independent contrast: $distance")
            assertTrue(openFine > .02 && openFine > 10 * roundedFine,
                "$voice ANGLE must move overtone power, not just phase: $openFine / $roundedFine")
        }
    }

    @Test fun `finite reference converges and signed pickup formula matches direct projection`() {
        val path = RimeContour.generate()
        assertEquals(3072, path.edges)
        assertTrue(abs(path.x.average()) < 1e-12 && abs(path.y.average()) < 1e-12)
        val coefficients = RimeContour.extract(path)
        val dense = RimeContour.extract(RimeContour.generate(samples = 32768))
        val relative = sqrt(coefficients.zip(dense).sumOf { (a,b) -> (a.re-b.re).pow(2)+(a.im-b.im).pow(2) } / dense.sumOf { it.energy })
        assertTrue(relative < 3e-5, "density coefficient error $relative")
        var previous = Double.POSITIVE_INFINITY
        for (cutoff in listOf(1,8,32,128,512)) {
            val selected = coefficients.filter { abs(it.n) <= cutoff }
            val error = (0 until 257).sumOf { k ->
                val index = k * path.x.size / 257
                val z = RimeContour.evaluate(selected, index.toDouble()/path.x.size)
                (z[0]-path.x[index]).pow(2)+(z[1]-path.y[index]).pow(2)
            } / 257
            println("RIME cutoff $cutoff RMS radius ${sqrt(error)}")
            assertTrue(error < previous); previous = error
        }
        val byN = coefficients.associateBy { it.n }
        for (u in listOf(.0,.017,.38,.999)) for (alpha in listOf(.0,.43,1.8,2*PI)) {
            val z = RimeContour.evaluate(coefficients,u)
            var projected = 0.0
            for(n in 1..512) {
                val p=byN.getValue(n); val q=byN.getValue(-n)
                val ar=.5*(cos(alpha)*(p.re+q.re)+sin(alpha)*(p.im+q.im))
                val ai=.5*(cos(alpha)*(p.im-q.im)+sin(alpha)*(q.re-p.re))
                projected += 2*(ar*cos(2*PI*n*u)-ai*sin(2*PI*n*u))
            }
            assertEquals(cos(alpha)*z[0]+sin(alpha)*z[1], projected, 2e-14)
        }
        val prepared = RimeContour.prepare(65.406, .8, .7)
        for(u in listOf(.0,.017,.38,.999)) {
            val exact=RimeContour.evaluate(prepared.coefficients,u)
            val x=prepared.bands.sumOf{it.read(it.x,u)}
            val y=prepared.bands.sumOf{it.read(it.y,u)}
            assertEquals(exact[0],x,2e-4); assertEquals(exact[1],y,2e-4)
        }
    }

    @Test fun `complete patch and diagnostic-free render round trip exactly`() {
        for (voice in RimeVoice.entries) {
            val supplied=Rime.defaults(voice).toMutableMap()
            val patch=RimePatch("Saved $voice",voice,supplied,53,.6f,.25f,2718)
            val before=patch.toJsonText(); supplied.clear()
            assertEquals(before,patch.toJsonText())
            val restored=assertIs<RimePatch>(Patches.fromJsonText(before))
            assertEquals(patch,restored)
            assertContentEquals(patch.render().samples,restored.render().samples)
            assertEquals(patch.copy(name="Edited",macros=patch.macros+("DETAIL" to .3f)), Patches.edited(patch,"Edited",patch.macros+("DETAIL" to .3f)))
            assertContentEquals(patch.copy(velocity=.3f).render().samples,Velocity.atVelocity(patch,.3f).samples)
        }
    }

    @Test fun `unsupported sustain malformed model and nonfinite performance are refused`() {
        val patch=RimePresets.all().first()
        assertFailsWith<IllegalArgumentException>{patch.copy(macros=mapOf("HOLD" to 1f))}
        assertFailsWith<IllegalArgumentException>{patch.copy(macros=mapOf("EDGE" to Float.NaN))}
        assertFailsWith<IllegalArgumentException>{patch.copy(macros=mapOf("UNKNOWN" to .5f))}
        assertFailsWith<IllegalArgumentException>{patch.copy(velocity=Float.POSITIVE_INFINITY)}
        assertFailsWith<IllegalArgumentException>{patch.copy(gateSeconds=-1f)}
        assertFailsWith<IllegalArgumentException>{patch.copy(midi=110)}
        for((key,value) in listOf("model" to 9.0,"midi" to 60.5,"seed" to 1.2)) {
            assertFailsWith<JsonException>{Patches.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries+(key to JsonValue.Num(value))))}
        }
    }

    @Test fun `zero velocity and periodic angle endpoints retain their geometric meaning`() {
        val base=Rime.defaults(RimeVoice.GLINT)
        val a=Rime.render(RimeVoice.GLINT,base+("ANGLE" to 0f),gateSeconds=.25f).samples
        val b=Rime.render(RimeVoice.GLINT,base+("ANGLE" to 1f),gateSeconds=.25f).samples
        assertContentEquals(a,b)
        assertTrue(Rime.render(RimeVoice.GLINT,velocity=0f,gateSeconds=.25f).samples.all{it==0f})
        val circleA=Rime.render(RimeVoice.GLINT,base+mapOf("DETAIL" to 0f,"UNFOLD" to 0f),gateSeconds=.25f).samples
        val circleB=Rime.render(RimeVoice.GLINT,base+mapOf("DETAIL" to 0f,"UNFOLD" to 1f),gateSeconds=.25f).samples
        assertContentEquals(circleA,circleB,"No unrelated motion is added at the circle endpoint")
        val coarse=RimeContour.prepare(261.626,0.0,1.0)
        assertEquals(1,coarse.bands.size)
        assertTrue(coarse.coefficients.filter{abs(it.n)!=1}.all{it.energy==0.0})
    }

    @Test fun `unfold reveals finer bands later and lets them recede before the root`() {
        for(unfold in listOf(.3,.7,1.0)) {
            val arrivals=(1..7).map { band ->
                (0..2500).first { ms->Rime.bandGain(ms/1000.0,band,8,unfold,3.0) >= .5 }
            }
            assertTrue(arrivals.zipWithNext().all{(a,b)->b>=a})
            assertTrue(Rime.bandGain(2.99,7,8,unfold,3.0)<.02)
            assertEquals(1.0,Rime.bandGain(2.99,0,8,unfold,3.0))
        }
    }

    @Test fun `modal body rings and loses energy with its input removed`() {
        val body=RimeBody(RimeVoice.VESSEL,130.8128,.85,44100)
        body.next(1.0,.3)
        body.next(0.0,0.0)
        val initial=body.energy()
        var last=initial
        repeat(44100*3) { i->
            body.next(0.0,0.0)
            if(i%1000==0) {val now=body.energy(); assertTrue(now<=last*1.000001);last=now}
        }
        assertTrue(body.energy()<initial*1e-8)
    }

    @Test fun `finite extremes preserve internal headroom DC and a decayed ending`() {
        for(voice in RimeVoice.entries) for(midi in listOf(24,60,96)) for(high in listOf(false,true)) {
            val macros=Rime.TIMBRAL.associateWith{if(high)1f else 0f}
            val probe=Rime.inspect(voice,macros,midi,1f,.35f)
            assertTrue(probe.samples.all{it.isFinite()})
            assertTrue(probe.samples.maxOf{abs(it)}<=.94001f)
            assertTrue(probe.internalPeak<8.0,"$voice/$midi internal peak ${probe.internalPeak}")
            assertTrue(abs(probe.samples.average())<1e-3,"$voice/$midi DC ${probe.samples.average()}")
            assertTrue(probe.tailDb < -45.0,"$voice/$midi tail ${probe.tailDb}")
            println("RIME $voice/$midi high=$high peak=${probe.internalPeak} tail=${probe.tailDb}")
        }
    }

    @Test fun `all five controls change the normal leveled sound and angle changes magnitudes`() {
        val base=Rime.defaults(RimeVoice.GLINT)
        for(name in Rime.TIMBRAL) {
            val a=Rime.inspect(RimeVoice.GLINT,base+(name to 0f),gateSeconds=.6f)
            val b=Rime.inspect(RimeVoice.GLINT,base+(name to if(name=="ANGLE").5f else 1f),gateSeconds=.6f)
            val n=min(a.samples.size,b.samples.size)
            val difference=sqrt((0 until n).sumOf{(a.samples[it]-b.samples[it].toDouble()).pow(2)}/a.samples.take(n).sumOf{it.toDouble().pow(2)})
            assertTrue(difference>.01,"$name relative difference $difference")
            if(name=="ANGLE") {
                val spectrumA=spectrum(a.samples); val spectrumB=spectrum(b.samples)
                val magDifference=sqrt(spectrumA.indices.sumOf{(spectrumA[it]-spectrumB[it]).pow(2)}/spectrumA.sumOf{it*it})
                println("RIME ANGLE leveled magnitude difference $magDifference")
                assertTrue(magDifference>.02)
            }
        }
    }

    private fun spectrum(samples:FloatArray):DoubleArray {
        val size=16384; val from=min(samples.size-size,4410).coerceAtLeast(0)
        val re=FloatArray(size){i->(samples.getOrElse(from+i){0f}*(.5-.5*cos(2*PI*i/size))).toFloat()}
        val im=FloatArray(size); Fft.forward(re,im)
        return DoubleArray(size/2){hypot(re[it].toDouble(),im[it].toDouble())}
    }

    @Test fun `pitched core survives bright settings and upper register bandwidth limiting`() {
        for(voice in RimeVoice.entries) for(midi in listOf(36,60,84,96)) {
            val p=Rime.inspect(voice,Rime.defaults(voice)+mapOf("DETAIL" to 1f,"EDGE" to 1f,"UNFOLD" to 0f),midi,1f,.6f)
            val hz=440.0*2.0.pow((midi-69)/12.0)
            val measured=FineTuning.measuredHz(com.snipsnap.audio.Snip(p.samples,1,Dsp.RATE),hz.toFloat(),fromSec=.12f,bodySeconds=.4f)
            assertNotNull(measured,"$voice/$midi root missing")
            val cents=FineTuning.cents(measured.toDouble(),hz)
            val share=TangleMeasure.toneShare(p.samples,hz)
            println("RIME $voice/$midi pitch cents=$cents rootShare=$share")
            assertTrue(abs(cents)<6,"$voice/$midi pitch $cents cents")
            assertTrue(share>.08,"$voice/$midi root share $share")
            assertTrue(p.safeMaximum*hz<=18000.001)
        }
    }

    @Test fun `high edge agrees with a higher rate reference without friction noise`() {
        for(midi in listOf(36,72,96)) {
            val macros=Rime.defaults(RimeVoice.GLINT)+mapOf("DETAIL" to 1f,"EDGE" to 1f,"UNFOLD" to .5f)
            val a=Rime.inspect(RimeVoice.GLINT,macros,midi,1f,.6f,options=Rime.Options(friction=false))
            val b=Rime.inspect(RimeVoice.GLINT,macros,midi,1f,.6f,options=Rime.Options(oversample=8,friction=false))
            val n=min(a.raw.size,b.raw.size)
            val error=(4096 until n-4096).sumOf{(a.raw[it]-b.raw[it].toDouble()).pow(2)}
            val energy=(4096 until n-4096).sumOf{b.raw[it].toDouble().pow(2)}
            val db=10*log10(error/energy)
            println("RIME 4x/8x MIDI $midi residual $db dB")
            assertTrue(db < -30,"4x/8x reference residual $db dB at $midi")
        }
    }
}
