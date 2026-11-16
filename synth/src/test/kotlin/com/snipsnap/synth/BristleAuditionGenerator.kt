package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*

/** R1 evidence: each key is a native note at a native touch, with saved-recipe replay. */
object BristleAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val dir=File(args.firstOrNull() ?: "testkit/bristle-kotlin").apply { mkdirs() }
        val manifest=mutableListOf<JsonValue>()
        val times=mutableListOf<Double>()
        fun save(name:String, recipe:Bristle.Recipe) {
            val start=System.nanoTime()
            val snip=recipe.render()
            times += (System.nanoTime()-start)/1e6
            val file=File(dir,"$name.wav")
            WavWriter.write(file,snip, WavWriter.BitDepth.PCM_16)
            File(dir,"$name.json").writeText(recipe.toJsonText())
            check(snip.samples.contentEquals(Bristle.Recipe.fromJsonText(File(dir,"$name.json").readText()).render().samples))
            val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            manifest+=JsonValue.Obj(mapOf("name" to JsonValue.Str(name),"sha256" to JsonValue.Str(hash),
                "recipe" to Json.parse(recipe.toJsonText())))
        }
        // Warm up before timing; this is a workstation result, not phone evidence.
        repeat(8) { Bristle.Recipe().render() }
        for(route in Bristle.Route.entries) {
            for(midi in 24..48) for((index,v) in listOf(.35f,.65f,1f).withIndex())
                save("${route}_${midi}_$index",Bristle.Recipe(route,midi,v))
            save("${route}_slide",Bristle.Recipe(route,macros=mapOf("SLIDE" to .65f)))
            // Full raw C2 buffer lets the comparison harness separate DSP from output-stage differences.
            val raw=Bristle.synthesize(Bristle.Recipe(route))
            val bytes=ByteBuffer.allocate(raw.size*4).order(ByteOrder.LITTLE_ENDIAN)
            raw.forEach { bytes.putFloat(it) }; File(dir,"${route}_raw.f32").writeBytes(bytes.array())
            val score=listOf(Triple(0.0,36,.32),Triple(.48,36,.18),Triple(.72,39,.24),Triple(1.12,36,.4),
                Triple(1.78,34,.25),Triple(2.18,36,.35),Triple(2.72,43,.22),Triple(3.12,36,.48))
            val phrase=FloatArray((4.4*Dsp.RATE).roundToInt())
            val recipes=mutableListOf<JsonValue>()
            for((index,event) in score.withIndex()) {
                val (at,midi,duration)=event
                val slide=when(index) { 3 -> .55f; 6 -> .4f; else -> 0f }
                val recipe=Bristle.Recipe(route,midi,macros=mapOf("HOLD" to ((duration-.12)/.6).toFloat(),"SLIDE" to slide))
                val x=recipe.render().samples
                val offset=(at*Dsp.RATE).roundToInt()
                for(i in x.indices) if(offset+i<phrase.size) phrase[offset+i]+=x[i]
                recipes+=JsonValue.Obj(mapOf("atSeconds" to JsonValue.Num(at),"recipe" to Json.parse(recipe.toJsonText())))
            }
            Dsp.limitPeak(phrase,.99f)
            WavWriter.write(File(dir,"${route}_phrase.wav"),Snip(phrase,1,Dsp.RATE),WavWriter.BitDepth.PCM_16)
            File(dir,"${route}_phrase.json").writeText(Json.write(JsonValue.Arr(recipes)))
        }
        val metrics=JsonValue.Obj(mapOf("renders" to JsonValue.Arr(manifest),
            "medianRenderMs" to JsonValue.Num(times.sorted()[times.size/2]),
            "maxRenderMs" to JsonValue.Num(times.max()),"savedRecipeReplay" to JsonValue.Bool(true)))
        File(dir,"manifest.json").writeText(Json.write(metrics))
        println("BRISTLE ${manifest.size} native renders and saved replays; median ${times.sorted()[times.size/2]} ms")
    }
}
