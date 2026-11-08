package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.synth.FiredBronzeDryKit.Pad as P
import com.snipsnap.synth.FiredBronzePush.Route as R
import java.io.File
import java.util.Locale

object FiredBronzePushAudition {
    @JvmStatic fun main(args:Array<String>) {
        val root=File("testkit/fired-bronze-push");root.mkdirs()
        val metrics=StringBuilder("id,seconds,peak,mean,treatment_warm_ms\n")
        fun emit(id:String,s:Snip,r:FiredBronzePush.Recipe?=null,ms:Double?=null) {
            for((folder,x) in listOf("native" to s,"listen" to AuditionLevel.level(s)))
                File(root,"$folder/$id.wav").apply{parentFile.mkdirs();WavWriter.write(this,x,WavWriter.BitDepth.PCM_24)}
            r?.let{File(root,"recipes/$id.json").apply{parentFile.mkdirs();writeText(it.toJsonText())}}
            metrics.append(String.format(Locale.ROOT,"%s,%.6f,%.7f,%.8f,%s%n",id,s.durationSeconds,s.peak(),s.samples.average(),ms?.let{String.format(Locale.ROOT,"%.3f",it)}?:""))
        }
        for(p in P.entries) {
            val source=FiredBronzeDryKit.Recipe(p);val d=source.render()
            FiredBronzePush.process(d,p,R.BOTH)
            val times=List(3){val t=System.nanoTime();FiredBronzePush.process(d,p,R.BOTH);(System.nanoTime()-t)/1e6}
            emit("${p.name.lowercase()}_both",FiredBronzePush.process(d,p,R.BOTH),FiredBronzePush.Recipe(source),times.sorted()[1])
            // Complete replay provenance for all exported timbre zones.
            for(v in listOf(.3f,.65f))File(root,"recipes/${p.name.lowercase()}_v${(v*100).toInt()}.json").writeText(FiredBronzePush.Recipe(source.copy(velocity=v)).toJsonText())
            if(p in listOf(P.SNARE,P.TIN,P.OPEN_HAT,P.GONG))for(route in listOf(R.REFERENCE,R.PROCESSOR_PUSH,R.AMP_PUSH,R.EDGE))
                emit("${p.name.lowercase()}_${route.name.lowercase()}",FiredBronzePush.process(d,p,route),FiredBronzePush.Recipe(source,route))
        }
        for(full in listOf(false,true)) {
            val d=FiredBronzeDryKitAudition.groove(full);val name=if(full)"full" else "foundation"
            for(route in R.entries)emit("${name}_${route.name.lowercase()}",FiredBronzePush.process(d,null,route))
        }
        emit("full_pads",FiredBronzeDryKitAudition.groove(true){s,p->FiredBronzePush.process(s,p,R.BOTH)})
        File(root,"metrics.csv").writeText(metrics.toString())
        File(root,"BUS_SETTINGS.txt").writeText("96 BPM accepted dry schedule. Reference: Color v1. Processor push: new processor, old amp. Amp push: old processor, new amp. Both: PRESS=.65 HEAT=.72 SPACE=.45 AMP=.70. Edge: PRESS=.85 HEAT=.90 SPACE=.45 AMP=.88. MPC kit uses BOTH on individual pads.\n")
        val work=File(root,".kit-work")
        try {
            val kit=KitAssembler.assembleArranged("SnipSnap Fired Bronze Push",FiredBronzePush.study(),work,tempoBpm=96f)
            KitExporter.exportProgramFolder(kit,work,root,overwrite=true)
        }finally{work.deleteRecursively()}
        println("45 clips, separate processor and amp comparisons, pushed three-layer MPC kit.")
    }
}
