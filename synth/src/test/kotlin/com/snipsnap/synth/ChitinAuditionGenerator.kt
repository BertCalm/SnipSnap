package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.*

/** Actual Kotlin finite-note exports. Starting settings await the owner's ear. */
object ChitinAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "testkit/chitin-audition");root.mkdirs()
        val manifest=mutableListOf<String>()
        val rows=StringBuilder("id,rawPeak,filterPeak,energyExcess,finalStorage,renderMs\n")
        val html=StringBuilder("<!doctype html><meta charset=utf-8><title>CHITIN audition</title><style>body{font:17px system-ui;max-width:1000px;margin:40px auto;background:#191d21;color:#eee}article{padding:12px;border-bottom:1px solid #444}audio{width:100%}</style><h1>CHITIN finite-note audition</h1><p>Two voices under one shared morph. HOLD extends the note; all clips are finite. Settings remain provisional.</p>")
        for(v in ChitinVoice.entries) {
            val cases=linkedMapOf<String,Map<String,Float>>()
            for(t in listOf(0f,.5f,1f)) cases["tune-$t"]=mapOf("TUNE" to t)
            for(key in Chitin.defaults(v).keys) for(step in listOf(0f,.25f,.5f,.75f,1f)) cases["$key-$step"]=mapOf(key to step)
            for(pair in listOf("BITE" to "MORPH","COLOR" to "PHASE","PACE" to "HOLD","TUNE" to "PHASE"))
                for(a in listOf(.25f,.75f)) for(b in listOf(.25f,.75f)) cases["${pair.first}-$a-${pair.second}-$b"]=mapOf(pair.first to a,pair.second to b)
            for((id,m) in cases) {
                val name="${v.name.lowercase()}-$id"
                val r=Chitin.inspect(v,m); val d=r.diagnostics
                WavWriter.write(File(root,"$name.wav"),r.snip)
                File(root,"$name.json").writeText(ChitinPatch(name,v,Chitin.defaults(v)+m).toJsonText())
                val plan=Chitin.plan(v,m)
                val trace=(0..(r.snip.frameCount*200/44100)).joinToString(",") { j ->
                    val t=j/200.0
                    "{\"seconds\":$t,\"morph\":${plan.morph(t)},\"ratio\":${plan.ratio(t)}}"
                }
                File(root,"$name.trace.json").writeText("[$trace]")
                File(root,"$name.diagnostics.json").writeText("{\"rawPeak\":${d.rawPeak},\"filterPeak\":${d.filterPeak},\"energyExcess\":${d.energyExcess},\"finalStorage\":${d.finalStorage},\"renderMillis\":${d.renderMillis}}")
                manifest += "{\"id\":\"$name\",\"audio\":\"$name.wav\",\"recipe\":\"$name.json\",\"trace\":\"$name.trace.json\"}"
                rows.append("$name,${d.rawPeak},${d.filterPeak},${d.energyExcess},${d.finalStorage},${d.renderMillis}\n")
                html.append("<article><b>$name</b><audio controls preload=none src='$name.wav'></audio><a href='$name.json'>Recipe</a></article>")
            }
            for(mode in listOf("shared","source-only","phase-only","unprotected","soft")) {
                val r=Chitin.inspect(v,velocity=if(mode=="soft") .35f else 1f,protected=mode!="unprotected",sourceMorph=mode!="phase-only",phaseMorph=mode!="source-only")
                val name="${v.name.lowercase()}-$mode"
                WavWriter.write(File(root,"$name.wav"),r.snip)
                html.append("<article><b>$name (comparison)</b><audio controls preload=none src='$name.wav'></audio></article>")
            }
        }
        // Independent sample-rate references and naive ablations for source alias measurement.
        for(midi in listOf(24,48,72)) for(ratio in listOf(1.0,2.3,5.7)) for(os in listOf(4,16)) for(corrected in listOf(true,false)) {
            val rate=44100*os;val hz=440*2.0.pow((midi-69)/12.0)
            val raw=ChitinSource.render(rate/2,rate,hz,false,ratio={ratio},corrected=corrected)
            WavWriter.write(File(root,"source-$midi-$ratio-$os-$corrected.wav"),Snip(FloatArray(raw.size){raw[it]*.4f},1,rate),allowNonMpcRate=true)
        }
        File(root,"manifest.json").writeText(manifest.joinToString(",", "[", "]"))
        File(root,"metrics.csv").writeText(rows.toString());File(root,"index.html").writeText(html.toString())
        println("CHITIN audition: ${root.absolutePath}")
    }
}
