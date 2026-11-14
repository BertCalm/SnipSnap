package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.synth.FiredBronzeDryKit.Pad as P
import com.snipsnap.synth.FiredBronzeColor.Stage as S
import java.io.File
import java.util.Locale

object FiredBronzeColorAudition {
    @JvmStatic fun main(args:Array<String>) {
        val root=File("testkit/fired-bronze-color");root.mkdirs()
        val metrics=StringBuilder("id,seconds,peak,mean,treatment_warm_ms\n")
        fun emit(id:String,x:Snip,r:FiredBronzeColor.Recipe?=null,ms:Double?=null) {
            for((folder,s) in listOf("native" to x,"listen" to AuditionLevel.level(x)))
                File(root,"$folder/$id.wav").apply{parentFile.mkdirs();WavWriter.write(this,s,WavWriter.BitDepth.PCM_24)}
            r?.let{File(root,"recipes/$id.json").apply{parentFile.mkdirs();writeText(it.toJsonText())}}
            metrics.append(String.format(Locale.ROOT,"%s,%.6f,%.7f,%.8f,%s%n",id,x.durationSeconds,x.peak(),x.samples.average(),ms?.let{String.format(Locale.ROOT,"%.3f",it)}?:""))
        }
        for(p in P.entries) {
            val source=FiredBronzeDryKit.Recipe(p);val dry=source.render()
            for(stage in S.entries) {
                val r=FiredBronzeColor.Recipe(source,stage)
                FiredBronzeColor.process(dry,p,stage)
                val times=List(3){val t=System.nanoTime();FiredBronzeColor.process(dry,p,stage);(System.nanoTime()-t)/1e6}
                emit("${p.name.lowercase()}_${stage.name.lowercase()}",FiredBronzeColor.process(dry,p,stage),r,times.sorted()[1])
            }
            for(v in listOf(.3f,.65f)) {
                val r=FiredBronzeColor.Recipe(source.copy(velocity=v))
                emit("${p.name.lowercase()}_soft_${(v*100).toInt()}",r.render(),r)
            }
        }
        for(full in listOf(false,true)) {
            val name=if(full)"full" else "foundation"
            val dry=FiredBronzeDryKitAudition.groove(full)
            for(stage in S.entries)emit("${name}_bus_${stage.name.lowercase()}",FiredBronzeColor.process(dry,null,stage))
            emit("${name}_pads_amp",FiredBronzeDryKitAudition.groove(full){s,p->FiredBronzeColor.process(s,p,S.AMP)})
        }
        val hot=FiredBronzeColor.Settings(press=.55f,heat=.8f,space=.5f)
        emit("full_bus_hot",FiredBronzeColor.process(FiredBronzeDryKitAudition.groove(true),null,S.AMP,hot))
        for(p in listOf(P.SNARE,P.TIN,P.OPEN_HAT,P.GONG)) {
            val r=FiredBronzeColor.Recipe(FiredBronzeDryKit.Recipe(p),settings=hot)
            emit("${p.name.lowercase()}_hot",r.render(),r)
        }
        File(root,"metrics.csv").writeText(metrics.toString())
        val work=File(root,".kit-work")
        try {
            val kit=KitAssembler.assembleArranged("SnipSnap Fired Bronze Color",FiredBronzeColor.study(),work,tempoBpm=96f)
            KitExporter.exportProgramFolder(kit,work,root,overwrite=true)
        } finally{work.deleteRecursively()}
        File(root,"BUS_SETTINGS.txt").writeText("Shared bus: accepted four-bar dry schedule at 96 BPM, then processor/amp.\nDefault PRESS=.28 HEAT=.35 SPACE=.30; hot PRESS=.55 HEAT=.80 SPACE=.50.\nMPC program is separately processed per pad; it does not reproduce shared bus dynamics.\n")
        println("Rendered 93 comparisons, recipes, shared bus and per-pad grooves, and 16-pad three-layer program.")
    }
}
