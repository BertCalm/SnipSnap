package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.synth.FiredBronzeDryKit.Pad as P
import java.io.File

object FiredBronzePerformanceAudition {
    @JvmStatic fun main(args:Array<String>) {
        val root=File("testkit/fired-bronze-performance");root.mkdirs()
        fun emit(id:String,s:Snip) {
            for((folder,x) in listOf("native" to s,"listen" to AuditionLevel.level(s)))
                File(root,"$folder/$id.wav").apply{parentFile.mkdirs();WavWriter.write(this,x,WavWriter.BitDepth.PCM_24)}
        }
        val metrics=StringBuilder("pad,velocity,seconds,peak,mean,energy\n")
        for(p in P.entries) {
            val clips=mutableListOf<Snip>()
            for(v in FiredBronzePerformance.layerVelocities) {
                val r=FiredBronzePerformance.Recipe(FiredBronzeDryKit.Recipe(p,velocity=v));val s=r.render()
                val id="${p.name.lowercase()}_${(v*100).toInt()}"
                File(root,"recipes/$id.json").apply{parentFile.mkdirs();writeText(r.toJsonText())}
                if(v==1f)emit(id,s)
                clips+=s
                metrics.append("${p.name},$v,${s.durationSeconds},${s.peak()},${s.samples.average()},${s.samples.sumOf{it.toDouble()*it}}\n")
            }
            if(p in listOf(P.KICK,P.SNARE,P.TIN,P.OPEN_HAT)) {
                // Native sequence shares one level scale across light-to-full hits.
                fun sequence(xs:List<Snip>):Snip {
                    val step=(2.4*Dsp.RATE).toInt();val out=FloatArray(step*4)
                    for((k,x) in xs.withIndex())for(i in x.samples.indices)if(k*step+i<out.size)out[k*step+i]+=x.samples[i]
                    return Snip(out,1,Dsp.RATE)
                }
                emit("${p.name.lowercase()}_response",sequence(clips))
                emit("${p.name.lowercase()}_fixed",sequence(FiredBronzePerformance.layerVelocities.map{FiredBronzePush.Recipe(FiredBronzeDryKit.Recipe(p,velocity=it)).render()}))
            }
        }
        emit("groove_fixed",FiredBronzeDryKitAudition.groove(true){s,p->FiredBronzePush.process(s,p,FiredBronzePush.Route.BOTH)})
        emit("groove_response",FiredBronzeDryKitAudition.grooveResponsive(true){s,p,v->FiredBronzePerformance.process(s,p,v)})
        File(root,"metrics.csv").writeText(metrics.toString())
        val work=File(root,".kit-work")
        try {
            val kit=KitAssembler.assembleArranged("SnipSnap Fired Bronze Performance",FiredBronzePerformance.study(),work,tempoBpm=96f)
            KitExporter.exportProgramFolder(kit,work,root,overwrite=true)
        }finally{work.deleteRecursively()}
        println("26 comparisons, 16 pads with four timbre layers, and 64 replay recipes.")
    }
}
