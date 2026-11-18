package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.File
import kotlin.math.*

/** Complete native velocity/take grid and measurements for a separate listening candidate. */
object ParadeSambaGenerator {
    private fun likeness(a: FloatArray,b: FloatArray): Double {
        val n=minOf(a.size,b.size,22050);var best=0.0
        for(d in -132..132) {
            var ab=0.0;var aa=0.0;var bb=0.0
            for(i in max(0,-d) until min(n,n-d)) { val x=a[i].toDouble();val y=b[i+d].toDouble();ab+=x*y;aa+=x*x;bb+=y*y }
            best=max(best,ab/sqrt(aa*bb+1e-30))
        };return best
    }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.single()).apply { mkdirs() };val report=StringBuilder("voice,closest_take,soft_hard_distance,hard_render_ms\n")
        for(voice in ParadeSamba.Voice.entries) {
            val name=voice.name.lowercase();val grid=mutableListOf<FloatArray>();var ms=0.0
            for(v in listOf(.3f,.6f,1f))for(take in 0..2) {
                val recipe=ParadeSamba.Recipe(voice,velocity=v,take=take)
                val start=System.nanoTime();val x=recipe.render();val elapsed=(System.nanoTime()-start)/1e6
                if(v==1f && take==0)ms=elapsed
                check(x.samples.all { it.isFinite() && abs(it)<=1f } && abs(x.samples.average())<.005 && x.samples.last()==0f)
                WavWriter.write(File(root,"${name}_v${v}_t$take.wav"),x,WavWriter.BitDepth.PCM_16)
                if(v==1f && take==0) {
                    WavWriter.write(File(root,"$name.wav"),x,WavWriter.BitDepth.PCM_16)
                    File(root,"$name.json").writeText(Json.write(recipe.json()))
                    check(x.samples.contentEquals(ParadeSamba.Recipe.fromJson(recipe.json()).render().samples))
                }
                grid+=x.samples
            }
            var closest=0.0
            for(z in 0..2)for(i in 0..2)for(j in i+1..2)closest=max(closest,likeness(grid[z*3+i],grid[z*3+j]))
            val a=grid[0];val b=grid[6];val aa=sqrt(a.sumOf { it.toDouble()*it });val bb=sqrt(b.sumOf { it.toDouble()*it })
            val movement=sqrt(a.indices.sumOf { (a[it]/aa-b[it]/bb).pow(2) })
            check(closest<.99995) { "$voice copied takes: $closest" };check(movement>.01) { "$voice gain-only velocity" }
            report.append("$name,$closest,$movement,$ms\n");println("$voice PASS $closest $movement ${ms}ms")
        }
        File(root,"metrics.csv").writeText(report.toString())
    }
}
