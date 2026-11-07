package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.*
import kotlin.test.*

/** A bounded SPACE correction, with the original renderer still the default. */
internal object MorphSpaceRevision {
    const val ID = "morph-space-r2"
    val out = File(System.getProperty("morph.workspace"), "space-r2")
    fun snip(x: FloatArray) = Snip(x, 1, Dsp.RATE)
    fun bytes(x: FloatArray) = ByteArrayOutputStream().also { WavWriter.write(it, snip(x)) }.toByteArray()
    fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
    fun raw(r: MorphProof.Recipe, mode: MorphProof.Readout) = MorphProof.render(r, readout = mode)

    // Compare local timbre, removing each window's gain. A constant gain change
    // alone cannot pass this motion check. All windows are within the first 90 ms.
    fun colorDeltaDb(a: FloatArray, b: FloatArray, start: Double): Double {
        fun color(x: FloatArray): Double {
            val n = 1024
            val offset = (start * Dsp.RATE).roundToInt()
            val window = FloatArray(n) { i ->
                (x[offset + i] * (.5 - .5 * cos(2 * PI * i / (n - 1)))).toFloat()
            }
            val spectrum = Fft.magnitudeSpectrum(window, n)
            var low = 0.0; var high = 0.0
            for (k in 2 until spectrum.size) {
                val hz = k * Dsp.RATE.toDouble() / n
                val e = spectrum[k].toDouble().pow(2)
                if (hz < 1100) low += e else if (hz < 10000) high += e
            }
            return 10 * log10((high + 1e-24) / (low + 1e-24))
        }
        return color(a) - color(b)
    }

    fun decode(text: String): Triple<MorphProof.Recipe, MorphProof.Readout, Float> {
        val q = Json.parse(text).obj()
        require(q.keys == setOf("renderer", "version", "variant", "midi", "model", "space", "stiff", "bright", "velocity", "hold", "raw_gain"))
        require(q.getValue("renderer").str() == ID)
        require(q.getValue("version").int() == 2)
        require(!q.getValue("hold").bool())
        val mode = when(q.getValue("variant").str()) {
            "original" -> MorphProof.Readout.ORIGINAL
            "revised" -> MorphProof.Readout.EARLY_MOTION
            else -> error("Unknown variant")
        }
        val midi = q.getValue("midi").int().also { require(it in 48..72) }
        fun v(k: String) = q.getValue(k).num().also { require(it.isFinite() && it in 0.0..1.0) }.toFloat()
        val gain = q.getValue("raw_gain").num().also { require(it.isFinite() && it > 0 && it <= 1000) }.toFloat()
        return Triple(MorphProof.Recipe(midi,v("model"),v("space"),v("stiff"),v("bright"),v("velocity")),mode,gain)
    }

    fun replay(text: String): FloatArray {
        val (r,mode,gain) = decode(text)
        return raw(r,mode).raw.map { it * gain }.toFloatArray()
    }
}

class MorphSpaceRevisionTest {
    private val base = MorphProof.Recipe()
    private val r2 = MorphProof.Readout.EARLY_MOTION

    @Test fun `original recipes still reproduce every published WAV`() {
        val original = File(System.getProperty("morph.workspace"), "audition")
        for (f in File(original,"recipes").listFiles()!!.sortedBy { it.name }) {
            val q=Json.parse(f.readText()).obj()
            val r=MorphProof.Recipe(q.getValue("midi").int(), q.getValue("model").num().toFloat(),
                q.getValue("space").num().toFloat(),q.getValue("stiff").num().toFloat(),
                q.getValue("bright").num().toFloat(),q.getValue("velocity").num().toFloat())
            val result=MorphProof.render(r)
            for ((kind,x) in listOf("raw" to result.raw,"matched" to result.matched))
                assertContentEquals(File(original,"$kind/${f.nameWithoutExtension}.wav").readBytes(),MorphSpaceRevision.bytes(x),f.name)
        }
    }

    @Test fun `zero space and guide state are unchanged while revised output is deterministic`() {
        for (note in listOf(48,60,72)) {
            val zero=base.copy(midi=note,space=0f)
            assertContentEquals(MorphProof.render(zero).raw,MorphProof.render(zero,readout=r2).raw)
        }
        val a=MorphProof.render(base,readout=r2)
        val b=MorphProof.render(base,readout=r2)
        assertContentEquals(a.raw,b.raw)
        val old=MorphProof.render(base)
        assertEquals(old.loopPeak,a.loopPeak)
        assertEquals(old.releaseRatio,a.releaseRatio)
        assertContentEquals(a.raw,MorphProof.render(base.copy(space=Float.NaN),readout=r2).raw)
        assertTrue(MorphProof.render(base.copy(velocity=0f),readout=r2).raw.all { it==0f })
    }

    @Test fun `early motion changes color beyond frozen readout and gain alone`() {
        val rows=StringBuilder("space,window_start_s,moving_minus_frozen_color_db\n")
        for(space in listOf(.25f,.5f,1f)) {
            val r=base.copy(space=space)
            val moving=MorphProof.render(r,readout=r2).raw
            val frozen=MorphProof.render(r,readout=MorphProof.Readout.FROZEN_EARLY).raw
            val shifts=listOf(.005,.030,.060).map { t ->
                val d=MorphSpaceRevision.colorDeltaDb(moving,frozen,t)
                rows.append("$space,$t,$d\n")
                d
            }
            assertTrue(shifts.max()-shifts.min()>3.0,"SPACE $space needs a time-varying color change: $shifts")
            val doubled=frozen.map { it*2f }.toFloatArray()
            val control=listOf(.005,.030,.060).map { MorphSpaceRevision.colorDeltaDb(doubled,frozen,it) }
            assertTrue(control.max()-control.min()<.001,"Gain-only counterexample must not pass")
        }
        MorphSpaceRevision.out.mkdirs()
        File(MorphSpaceRevision.out,"motion-checks.csv").writeText(rows.toString())
    }

    @Test fun `revised corners retain bounded pitch output and release`() {
        val csv=StringBuilder("midi,model,stiff,bright,space,velocity,peak,guide_peak,dc,cents,confidence,release_ratio\n")
        for(note in listOf(48,60,72)) for(model in listOf(0f,1f))
            for(stiff in listOf(0f,1f)) for(bright in listOf(0f,1f)) for(space in listOf(0f,1f)) {
                val r=base.copy(midi=note,model=model,stiff=stiff,bright=bright,space=space)
                checkHealth(r,csv)
            }
        for(v in listOf(.2f,1f)) checkHealth(base.copy(space=1f,velocity=v),csv)
        MorphSpaceRevision.out.mkdirs()
        File(MorphSpaceRevision.out,"corner-metrics.csv").writeText(csv.toString())
    }

    private fun checkHealth(r: MorphProof.Recipe,csv:StringBuilder) {
        val x=MorphProof.render(r,readout=r2)
        assertEquals(61740,x.raw.size)
        assertTrue(x.raw.all { it.isFinite() } && x.matched.all { it.isFinite() })
        val peak=x.matched.maxOf { abs(it) }
        val dc=abs(x.matched.average())
        assertTrue(peak<=1f && peak>=.01f)
        assertTrue(x.loopPeak<2f && dc<.005 && x.releaseRatio<.02)
        val pitch=assertNotNull(Pitch.detect(MorphSpaceRevision.snip(x.matched),.08f,.25f))
        val cents=1200*log2(pitch.hz/(440*2.0.pow((r.midi-69)/12.0)))
        assertTrue(abs(cents)<35,"$r cents=$cents")
        assertTrue(pitch.confidence>=.3f,"$r confidence=${pitch.confidence}")
        csv.append("${r.midi},${r.model},${r.stiff},${r.bright},${r.space},${r.velocity},$peak,${x.loopPeak},$dc,$cents,${pitch.confidence},${x.releaseRatio}\n")
    }

    @Test fun `render focused RMS matched comparisons with strict recipe replay`() {
        val out=MorphSpaceRevision.out
        out.mkdirs()
        data class Take(val id:String,val variant:String,val r:MorphProof.Recipe,val x:MorphProof.Result)
        val takes=mutableListOf<Take>()
        for(note in listOf(48,60,72)) for(space in listOf(0f,.25f,.5f,.75f,1f))
            for((variant,mode) in listOf("original" to MorphProof.Readout.ORIGINAL,"revised" to r2)) {
                val r=base.copy(midi=note,space=space)
                takes+=Take("$variant-$note-$space",variant,r,MorphProof.render(r,readout=mode))
            }
        // One common RMS target, reduced if any take would breach the common
        // ceiling. No compression or per-window normalization changes the gesture.
        val target=min(.04,takes.minOf { .9*MorphSpaceRevision.rms(it.x.raw)/it.x.raw.maxOf { v->abs(v) } })
        val csv=StringBuilder("id,variant,midi,space,raw_gain,rms,peak,cents,confidence,render_ms\n")
        for(t in takes) {
            val gain=(target/MorphSpaceRevision.rms(t.x.raw)).toFloat()
            val matched=t.x.raw.map { it*gain }.toFloatArray()
            val q=t.r
            val recipe="""{"renderer":"morph-space-r2","version":2,"variant":"${t.variant}","midi":${q.midi},"model":${q.model},"space":${q.space},"stiff":${q.stiff},"bright":${q.bright},"velocity":${q.velocity},"hold":false,"raw_gain":$gain}"""
            WavWriter.write(File(out,"raw/${t.id}.wav"),MorphSpaceRevision.snip(t.x.raw))
            WavWriter.write(File(out,"matched/${t.id}.wav"),MorphSpaceRevision.snip(matched))
            File(out,"recipes/${t.id}.json").apply { parentFile.mkdirs(); writeText(recipe) }
            assertContentEquals(MorphSpaceRevision.bytes(matched),MorphSpaceRevision.bytes(MorphSpaceRevision.replay(recipe)),t.id)
            val pitch=assertNotNull(Pitch.detect(MorphSpaceRevision.snip(matched),.08f,.25f))
            val cents=1200*log2(pitch.hz/(440*2.0.pow((q.midi-69)/12.0)))
            assertTrue(abs(cents)<35 && pitch.confidence>=.3f,t.id)
            csv.append("${t.id},${t.variant},${q.midi},${q.space},$gain,${MorphSpaceRevision.rms(matched)},${matched.maxOf { abs(it) }},$cents,${pitch.confidence},${t.x.millis}\n")
        }
        File(out,"metrics.csv").writeText(csv.toString())
    }

    @Test fun `new recipe format refuses unsupported or ambiguous inputs`() {
        val valid="""{"renderer":"morph-space-r2","version":2,"variant":"revised","midi":60,"model":0.4,"space":0.5,"stiff":0.6,"bright":0.5,"velocity":0.7,"hold":false,"raw_gain":1}"""
        for(bad in listOf(valid.replace("\"version\":2","\"version\":1"),
            valid.replace("\"hold\":false","\"hold\":true"), valid.replace("\"midi\":60","\"midi\":60.5"),
            valid.replace("\"space\":0.5","\"space\":2"),valid.replace("\"raw_gain\":1","\"raw_gain\":0"),
            valid.replace("\"revised\"","\"unknown\""),valid.dropLast(1)+",\"extra\":1}"))
            assertFails { MorphSpaceRevision.decode(bad) }
    }
}

object MorphSpaceReplay {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2) { "Usage: recipe.json new-output.wav" }
        val output=File(args[1]); require(!output.exists()) { "Output already exists" }
        WavWriter.write(output,MorphSpaceRevision.snip(MorphSpaceRevision.replay(File(args[0]).readText())))
    }
}
