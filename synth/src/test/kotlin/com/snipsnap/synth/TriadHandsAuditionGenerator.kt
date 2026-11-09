package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.Snip
import java.io.File
import kotlin.math.*

object TriadHandsAuditionGenerator {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull() ?: "testkit/triad-hands-r2").apply { mkdirs() }
        val old=Triad.source(Triad.Settings(handRevision=1))
        val revised=Triad.source(Triad.Settings(handRevision=2))
        check(old.events==revised.events)
        for(lane in old.stems.keys-setOf("conga","bongo")) check(old.stems.getValue(lane).contentEquals(revised.stems.getValue(lane)))
        val clips=linkedMapOf<String,Snip>()
        for((revision,source) in listOf("before" to old,"revised" to revised)) {
            for(rig in Triad.Rig.entries) clips["${rig.name.lowercase()}-$revision"]=Triad.finish(Triad.process(source.mix(),124,rig).output)
            for(lane in listOf("conga","bongo")) clips["$lane-$revision"]=Triad.finish(source.stems.getValue(lane))
        }
        val metrics=StringBuilder("clip,peak,mean,loudness,rms\n")
        val groups=listOf(clips.keys.filter { it.substringBefore('-') in listOf("dry","warehouse","submerged","fracture") }, listOf("conga-before","conga-revised"),listOf("bongo-before","bongo-revised"))
        for(group in groups) {
            val target=group.minOf { Loudness.of(clips.getValue(it)) }
            for(id in group) {
                val s=clips.getValue(id);val gain=target/Loudness.of(s)
                for(i in s.samples.indices) s.samples[i]*=gain
                check(s.samples.all { it.isFinite() && abs(it)<=1 })
                check(abs(s.samples.average())<.005)
                WavWriter.write(File(root,"$id.wav"),s,WavWriter.BitDepth.PCM_24)
                metrics.append("$id,${s.peak()},${s.samples.average()},${Loudness.of(s)},${sqrt(s.samples.sumOf{it.toDouble()*it}/s.samples.size)}\n")
            }
        }
        File(root,"metrics.csv").writeText(metrics.toString())
        File(root,"settings.json").writeText("""{"bpm":124,"bars":4,"handRevision":2,"sourceBaseline":"4964e24a2","unchangedStemsVerified":7,"sameEventsVerified":true,"rigDrive":0.55,"rigSpace":0.55,"status":"Owner preferred R1 except weak/flat conga and bongo. R2 awaiting listening."}""")
        println("Verified identical events and all seven other stems; wrote 12 matched native Kotlin comparisons.")
    }
}
