package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.*

/** Actual Kotlin output, matched as whole performances, with native PCM retained. */
object TriadAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/triad-audition").apply { mkdirs() }
        val source = Triad.source()
        val dry = source.mix()
        val matched = linkedMapOf<String, Snip>()
        val rows = StringBuilder("id,renderMs,peak,mean,rms,tailRms,conditionedPeak\n")
        fun write(id: String, x: FloatArray, ms: Double = 0.0) {
            require(x.all { it.isFinite() })
            require(x.maxOf { abs(it) } < 2f) { "Native headroom exceeded: $id" }
            val s = Triad.finish(x)
            matched[id] = s
            WavWriter.write(File(root,"$id.wav"),s,WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root,"$id-native.wav"),Snip(FloatArray(x.size) { x[it] * .5f },1,Dsp.RATE),WavWriter.BitDepth.PCM_24)
            fun rms(a: FloatArray) = sqrt(a.sumOf { it.toDouble()*it }/a.size)
            rows.append("$id,$ms,${x.maxOf { abs(it) }},${x.average()},${rms(x)},${rms(x.takeLast(4410).toFloatArray())},${s.peak()}\n")
        }
        write("dry",dry)
        File(root,"events.csv").writeText("frame,lane,velocity\n"+source.events.joinToString("\n") { "${it.frame},${it.lane},${it.velocity}" })
        for ((name,stem) in source.stems) write("stem-$name",stem.copyOf(dry.size))
        for (rig in Triad.Rig.entries.filter { it != Triad.Rig.DRY }) {
            for ((label,drive,space) in listOf(Triple("light",.25f,.25f),Triple("main",.55f,.55f),Triple("heavy",.9f,.9f))) {
                val start = System.nanoTime()
                val stages = Triad.process(dry,124,rig,drive,space)
                val ms = (System.nanoTime()-start)/1e6
                val id = "${rig.name.lowercase()}-$label"
                write(id,stages.output,ms)
                if(label=="main") write("${rig.name.lowercase()}-first",stages.first)
            }
        }
        // Lower the entire comparison group to the quietest achievable house level.
        // This avoids calling peak-limited clips equally loud when they are not.
        val target = matched.filterKeys { !it.startsWith("stem-") }.values.minOf { Loudness.of(it) }
        val matching = StringBuilder("id,gainAfterConditioning,finalLoudness,finalPeak,nativeTransportGain\n")
        for ((id,s) in matched) {
            val gain = if(id.startsWith("stem-")) 1f else target / Loudness.of(s)
            for(i in s.samples.indices) s.samples[i] *= gain
            WavWriter.write(File(root,"$id.wav"),s,WavWriter.BitDepth.PCM_24)
            matching.append("$id,$gain,${Loudness.of(s)},${s.peak()},0.5\n")
        }
        File(root,"matching.csv").writeText(matching.toString())
        File(root,"metrics.csv").writeText(rows.toString())
        File(root,"settings.json").writeText("""{"model":1,"handRevision":${source.settings.handRevision},"bpm":124,"bars":4,"tune":0.5,"weight":0.65,"swing":0.38,"density":0.7,"tailSeconds":3,"loopClaim":false,"nativeTransportGain":0.5,"settings":{"light":[0.25,0.25],"main":[0.55,0.55],"heavy":[0.9,0.9]},"status":"Kotlin listening candidates, not approved presets"}""")
        println("TRIAD audition: ${root.absolutePath}")
    }
}
