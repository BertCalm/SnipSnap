package com.snipsnap.synth

import com.snipsnap.synth.FiredBronzeDryKit.Pad as P
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

object FiredBronzeDryKitAudition {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull()?:"testkit/fired-bronze-dry-kit");root.mkdirs()
        val metrics=StringBuilder("id,seconds,peak,mean,warm_ms\n")
        fun emit(id:String,s:Snip,r:FiredBronzeDryKit.Recipe?=null,ms:Double=0.0) {
            for((folder,clip) in listOf("native" to s,"listen" to AuditionLevel.level(s))) {
                File(root,"$folder/$id.wav").apply { parentFile.mkdirs();WavWriter.write(this,clip,WavWriter.BitDepth.PCM_24) }
            }
            r?.let{File(root,"recipes/$id.json").apply{parentFile.mkdirs();writeText(it.toJsonText())}}
            metrics.append(String.format(Locale.ROOT,"%s,%.6f,%.7f,%.8f,%.3f%n",id,s.durationSeconds,s.peak(),s.samples.average(),ms))
        }
        for(p in FiredBronzeDryKit.Pad.entries) {
            val r=FiredBronzeDryKit.Recipe(p);r.render()
            val times=List(3){val t=System.nanoTime();r.render();(System.nanoTime()-t)/1e6}
            emit(p.name.lowercase(),r.render(),r,times.sorted()[1])
            if(p.source==null) for(v in listOf(.3f,.65f)) {
                val soft=r.copy(velocity=v);emit("${p.name.lowercase()}_${(v*100).roundToInt()}",soft.render(),soft)
            }
        }
        emit("groove_foundation",groove(false));emit("groove_full",groove(true))
        File(root,"metrics.csv").writeText(metrics.toString())
        val work=File(root,".kit-work")
        try {
            val kit=KitAssembler.assembleArranged("SnipSnap Fired Bronze Dry",FiredBronzeDryKit.study(),work,tempoBpm=96f)
            KitExporter.exportProgramFolder(kit,work,root,overwrite=true)
        } finally {work.deleteRecursively()}
        println("Dry kit rendered: 16 pads, velocity comparisons, two grooves, MPC program at ${root.absolutePath}")
    }
    internal fun groove(full:Boolean, treatment: ((Snip, P) -> Snip)? = null):Snip {
        data class Hit(val p:FiredBronzeDryKit.Pad,val beat:Double,val v:Float)
        val hits=buildList {
            for(bar in 0..3) {
                val b=bar*4.0
                add(Hit(P.KICK,b,1f));add(Hit(P.KICK,b+2.5,.8f))
                add(Hit(P.SNARE,b+1,.85f));add(Hit(P.SNARE,b+3,1f))
                for(i in 0..7) add(Hit(if(i==7)P.OPEN_HAT else P.CLOSED_HAT,b+i*.5,if(i%2==0).8f else .45f))
                if(full) {
                    add(Hit(P.TIN,b+.75,.7f));add(Hit(P.TE,b+1.75,.5f));add(Hit(P.GA,b+2,.8f))
                    add(Hit(if(bar%2==0)P.TUN else P.NA,b+3.25,.7f))
                    add(Hit(if(bar%2==0)P.CLAY_OPEN else P.CLAY_CLOSED,b+3.75,.65f))
                }
            }
            if(full){add(Hit(P.KETTLE_LOW,.25,.65f));add(Hit(P.KETTLE_HIGH,8.25,.65f));add(Hit(P.GONG,16.0,.7f))}
        }.sortedBy{it.beat}
        // The ending gong gets its full eight seconds, independent of the phrase grid.
        val out=FloatArray((((if(full)18.1 else 11.3)+(if(treatment!=null)1.0 else 0.0))*Dsp.RATE).toInt())
        val cache=mutableMapOf<Pair<FiredBronzeDryKit.Pad,Float>,Snip>()
        for((index,h) in hits.withIndex()) {
            val s=cache.getOrPut(h.p to h.v){
                val dry=FiredBronzeDryKit.Recipe(h.p,velocity=h.v).render()
                treatment?.invoke(dry,h.p) ?: dry
            }
            val start=(h.beat*60/96*Dsp.RATE).roundToInt()
            val next=if(h.p.choke!=0) hits.drop(index+1).firstOrNull{it.p.choke==h.p.choke} else null
            val stop=next?.let{((it.beat-h.beat)*60/96*Dsp.RATE).roundToInt()}?:s.frameCount
            val count=minOf(s.frameCount,stop,out.size-start)
            for(i in 0 until count) {
                val choke=if(stop<s.frameCount)((count-1-i)/(Dsp.RATE*.003f)).coerceIn(0f,1f) else 1f
                out[start+i]+=s.samples[i]*h.p.gain*.35f*choke
            }
        }
        Dsp.fadeTail(out);return Snip(out,1,Dsp.RATE)
    }
}
