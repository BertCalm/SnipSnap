package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavReader
import java.io.File
import java.util.Locale

object FiredBronzeR2Audition {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull() ?: "testkit/fired-bronze-r2")
        for ((name,id) in mapOf("tin" to "171911", "tun" to "171909", "na" to "171905", "te" to "171913", "ga" to "171902")) {
            val reference = File(root,"references/$id.wav")
            if (reference.isFile) {
                val out=File(root,"listen/reference_$name.wav");out.parentFile.mkdirs()
                WavWriter.write(out,AuditionLevel.level(WavReader.read(reference)),WavWriter.BitDepth.PCM_24)
            }
        }
        if (args.contains("--references-only")) return
        val metrics=StringBuilder("id,seconds,peak,mean,warm_ms\n")
        for(v in FiredBronzeR2.Voice.entries) {
            val base=FiredBronzeR2.Recipe(v)
            val candidates=linkedMapOf("default" to base,"soft" to base.copy(velocity=.3f),"damped" to base.copy(damp=.6f))
            if(v in listOf(FiredBronzeR2.Voice.PRESSURE,FiredBronzeR2.Voice.CLAY_OPEN,FiredBronzeR2.Voice.GONG)) {
                candidates["still"]=base.copy(motion=0f);candidates["motion"]=base.copy(motion=1f)
            }
            for((suffix,r) in candidates) {
                val id="${v.name.lowercase()}_$suffix"
                r.render();val times=List(3){val start=System.nanoTime();r.render();(System.nanoTime()-start)/1e6}
                val s=r.render()
                for((folder,clip) in listOf("native" to s,"listen" to AuditionLevel.level(s))) {
                    val file=File(root,"$folder/$id.wav");file.parentFile.mkdirs();WavWriter.write(file,clip,WavWriter.BitDepth.PCM_24)
                }
                File(root,"recipes/$id.json").apply{parentFile.mkdirs();writeText(r.toJsonText())}
                metrics.append(String.format(Locale.ROOT,"%s,%.6f,%.7f,%.8f,%.3f%n",id,s.durationSeconds,s.peak(),s.samples.average(),times.sorted()[1]))
            }
        }
        File(root,"metrics.csv").writeText(metrics.toString())
        println("Fired Bronze R2: 36 dry comparisons and replay recipes in ${root.absolutePath}")
    }
}
