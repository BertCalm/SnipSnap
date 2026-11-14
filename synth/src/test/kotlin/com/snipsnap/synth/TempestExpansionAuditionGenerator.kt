package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.util.Locale
import kotlin.math.*

/** Actual renderer evidence for four unapproved voices and their proposed full-kit arrangement. */
object TempestExpansionAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1)
        val root = File(args.firstOrNull() ?: "testkit/tempest-expansion").canonicalFile.apply { mkdirs() }
        val cases = mutableListOf<JsonValue>()
        val rows = mutableListOf<String>()
        val timings = mutableListOf<String>()
        fun emit(id: String, patch: TempestPatch, category: String, control: String = "") {
            val before=System.nanoTime()
            val raw=Tempest.synthesize(patch.voice,patch.macros,Dsp.RATE*4)
            val snip=Snip(Tempest.finish(raw),1,Dsp.RATE)
            val ms=(System.nanoTime()-before)/1e6
            val native="$id.wav";val matched="$id-matched.wav"
            WavWriter.write(File(root,native),snip,WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root,matched),AuditionLevel.level(snip),WavWriter.BitDepth.PCM_24)
            File(root,"$id.json").writeText(patch.toJsonText())
            val capture=Classifier.classify(snip).drumClass
            val late=raw.takeLast(Dsp.RATE*4/100).sumOf { it.toDouble().pow(2) }/(Dsp.RATE*4/100)
            rows += listOf(id,ms,raw.maxOf { abs(it) },snip.peak(),snip.samples.average(),Loudness.of(snip),sqrt(late),capture).joinToString(",")
            cases += JsonValue.Obj(mapOf("id" to JsonValue.Str(id),"category" to JsonValue.Str(category),"control" to JsonValue.Str(control),
                "patch" to patch.toJsonValue(),"native" to JsonValue.Str(native),"matched" to JsonValue.Str(matched),"capture_class" to JsonValue.Str(capture.name)))
        }
        for (v in Tempest.EXPANSION_VOICES) {
            fun patch(id: String, m: Map<String,Float>)=TempestPatch(id,v,Tempest.settled(v,m),model=3)
            emit("${v.name}-default",patch("${v.name} start",emptyMap()),"default")
            for (t in listOf(.25f,.5f,.75f)) emit("${v.name}-TUNE-$t",patch("${v.name} note",mapOf("TUNE" to t)),"notes","TUNE")
            for (key in listOf("SOURCE","FILTER","DRIVE","SPACE","DECAY")) for (x in listOf(0f,.25f,.5f,.75f,1f)) {
                val id="${v.name}-$key-$x"
                emit(id,patch(id,mapOf(key to x)),"sweep",key)
            }
            for ((a,b) in listOf("SOURCE" to "DRIVE","FILTER" to "DRIVE","DECAY" to "SPACE"))
                for (x in listOf(0f,1f)) for (y in listOf(0f,1f)) {
                    val id="${v.name}-$a-$x-$b-$y"
                    emit(id,patch(id,mapOf(a to x,b to y)),"pair","$a $b")
                }
            val times=mutableListOf<Double>()
            repeat(10) { Tempest.render(v) }
            repeat(100) {
                val before=System.nanoTime();Tempest.render(v)
                times += (System.nanoTime()-before)/1e6
            }
            times.sort()
            timings += String.format(Locale.ROOT,"%s,%.3f,%.3f",v.name,times[50],times[94])
        }
        for ((i,p) in TempestExpansion.fullKitPatches().withIndex()) emit("A%02d".format(i+1),p,"kit")
        // The clocked noise is continuous across sample rates. These remain composite residuals,
        // including filter discretization and burst timing, rather than isolated alias power.
        val references=File(root,"rate-reference").apply { mkdirs() }
        for (v in Tempest.EXPANSION_VOICES) for ((n,source) in listOf(0f,1f).withIndex()) {
            val m=mapOf("TUNE" to 1f,"SOURCE" to source,"FILTER" to 1f,"DRIVE" to 1f,"SPACE" to 0f,"DECAY" to .5f)
            for (factor in listOf(4,16)) {
                val raw=Tempest.synthesize(v,m,Dsp.RATE*factor)
                WavWriter.write(File(references,"alias-${v.name}-$n-${factor}x.wav"),
                    Snip(FloatArray(raw.size) { raw[it]*.2f },1,Dsp.RATE*factor),WavWriter.BitDepth.PCM_24,allowNonMpcRate=true)
            }
        }
        // A two-bar, 100 BPM sketch uses the accepted pad renders. It is an audition,
        // not a factory performance. All voices are summed before one shared peak ceiling.
        val patches=TempestExpansion.fullKitPatches()
        val sounds=patches.map { it.render() }
        val hits=mutableListOf<Triple<Int,Int,Float>>() // slot, sixteenth, playback level
        for (step in listOf(0,6,8,16,22,24)) hits += Triple(1,step,.8f)
        for (step in listOf(14,30)) hits += Triple(2,step,.6f)
        for (step in listOf(4,12,20,28)) hits += Triple(3,step,.75f)
        hits += Triple(4,27,.4f)
        for (step in listOf(12,28)) hits += Triple(5,step,.5f)
        hits += Triple(6,29,.35f)
        for (step in 0 until 32 step 2) hits += Triple(if (step in listOf(14,30)) 11 else 10,step,.4f)
        hits += Triple(12,7,.25f);hits += Triple(12,23,.25f)
        hits += Triple(7,25,.55f);hits += Triple(8,26,.55f);hits += Triple(9,31,.55f)
        hits += Triple(13,0,.45f);hits += Triple(15,16,.35f);hits += Triple(16,31,.3f)
        val mix=FloatArray((6.0*Dsp.RATE).toInt())
        val sorted=hits.sortedBy { it.second }
        for ((slot,step,gain) in sorted) {
            val start=(step*.15*Dsp.RATE).roundToInt()
            val x=sounds[slot-1].samples
            val nextHat=if (slot in 10..12) sorted.filter { it.first in 10..12 && it.second>step }.minOfOrNull { (it.second*.15*Dsp.RATE).roundToInt() } else null
            val length=min(x.size,(nextHat?:mix.size)-start)
            for (j in 0 until min(length,mix.size-start)) {
                val choke=if (nextHat!=null) min(1f,(length-j)/(Dsp.RATE*.0015f)) else 1f
                mix[start+j] += x[j]*gain*choke
            }
        }
        Dsp.limitPeak(mix,.95f)
        WavWriter.write(File(root,"full-kit-groove.wav"),Snip(mix,1,Dsp.RATE),WavWriter.BitDepth.PCM_24)
        val manifest=JsonValue.Obj(mapOf("engine" to JsonValue.Str("TEMPEST"),"expansion_model" to JsonValue.Num(3.0),
            "status" to JsonValue.Str("All six voices and sixteen kit positions are owner-accepted; factory promotion preserves published audio"),
            "groove" to JsonValue.Str("full-kit-groove.wav"),"cases" to JsonValue.Arr(cases)))
        File(root,"manifest.json").writeText(Json.write(manifest))
        File(root,"metrics.csv").writeText("id,render_ms,raw_peak,peak,dc,loudness,prefade_tail_rms,capture_class\n"+rows.joinToString("\n")+"\n")
        File(root,"timing.csv").writeText("voice,median_ms,p95_ms\n"+timings.joinToString("\n")+"\n")
        File(root,"timing-context.txt").writeText("Kotlin JVM ${System.getProperty("java.version")} ${System.getProperty("os.arch")}; 10 warmups and 100 production renders per new voice. Desktop only, not phone timing.\n")
        File(root,"index.html").writeText(buildString {
            append("<!doctype html><meta charset=utf-8><title>TEMPEST full-kit audition</title><h1>TEMPEST: six voices</h1><p>All six voices are owner-accepted. The factory kit preserves the auditioned sound. HOLD reserved.</p><audio controls src='full-kit-groove.wav'></audio>")
            for (row in cases) {
                val id=row.obj().getValue("id").str()
                append("<p>$id <audio controls preload=none src='$id-matched.wav'></audio> <a href='$id.wav'>native WAV</a> <a href='$id.json'>patch</a></p>")
            }
        })
        println("Wrote ${cases.size} exact native/matched cases, clocked rate references and six-second groove to $root")
    }
}
