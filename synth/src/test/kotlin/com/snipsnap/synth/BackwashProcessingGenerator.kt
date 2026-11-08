package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.*
import java.io.File
import kotlin.math.*

/** Matched listening comparisons and the unchanged model-3 reference. */
object BackwashProcessingGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull()?:"testkit/backwash-processing").apply{mkdirs()}
        val raw=linkedMapOf<String,MutableMap<BackwashTreatment,Snip>>()
        val report=StringBuilder("case,treatment,milliseconds,seconds,peak,mean,loudness,lateEnergyFraction\n")
        for(treatment in BackwashTreatment.entries){
            val dir=File(root,treatment.name.lowercase()).apply{mkdirs()}
            BackwashGroove.write(dir,renderWet={v,m,vel->BackwashProcessingStudy.render(v,m,vel,treatment)})
            raw.getOrPut("groove"){linkedMapOf()}[treatment]=WavReader.read(File(dir,"groove-processed.wav"))
            for((i,patch) in BackwashPresets.kitOrder().withIndex()){
                val start=System.nanoTime();val s=BackwashProcessingStudy.render(patch.voice,patch.macros,1f,treatment)
                val ms=(System.nanoTime()-start)/1e6
                check(s.samples.all{it.isFinite()&&abs(it)<=1f}&&s.peak()>.01f)
                check(abs(s.samples.average())<.005 && s.samples.last()==0f)
                val again=BackwashProcessingStudy.render(patch.voice,patch.macros,1f,treatment)
                check(s.samples.contentEquals(again.samples))
                val id="A%02d".format(i+1)
                raw.getOrPut(id){linkedMapOf()}[treatment]=s
                WavWriter.write(File(dir,"$id-raw.wav"),s,WavWriter.BitDepth.PCM_24)
                File(dir,"$id.study.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
                    "format" to JsonValue.Str("snipsnap-backwash-processing-study"),"studyVersion" to JsonValue.Num(1.0),
                    "sourceModel" to JsonValue.Num(3.0),"treatment" to JsonValue.Str(treatment.name),
                    "sourceVelocity" to JsonValue.Num(1.0),"baseRecipe" to PadRecipe(patch).toJsonValue()))))
                val energy=s.samples.sumOf{it.toDouble()*it};val late=s.samples.drop(4410).sumOf{it.toDouble()*it}/energy
                report.append("$id,$treatment,$ms,${s.frameCount.toDouble()/44100},${s.peak()},${s.samples.average()},${Loudness.of(s)},$late\n")
            }
        }
        val levels=StringBuilder("case,treatment,targetLoudness,measuredLoudness,gain,matchedPeak\n")
        for((id,group)in raw){
            val loud=group.mapValues{Loudness.of(it.value)}
            // A shared peak-safe target preserves exact matching without a second limiter.
            val target=minOf(Dsp.MELODIC_LOUDNESS_TARGET,group.minOf{(t,s)->.9f*loud.getValue(t)/s.peak()})
            for((t,s)in group){
                val gain=target/loud.getValue(t)
                val matched=Snip(FloatArray(s.samples.size){s.samples[it]*gain},1,Dsp.RATE)
                val measured=Loudness.of(matched)
                check(abs(measured-target)<1e-5&&matched.peak()<=.90001f)
                WavWriter.write(File(root,"${t.name.lowercase()}/$id-matched.wav"),matched,WavWriter.BitDepth.PCM_24)
                levels.append("$id,$t,$target,$measured,$gain,${matched.peak()}\n")
            }
        }
        File(root,"metrics.csv").writeText(report.toString());File(root,"matching.csv").writeText(levels.toString())
        println("80 deterministic processing renders; 85 peak-safe matched comparisons. ${root.absolutePath}")
    }
}
