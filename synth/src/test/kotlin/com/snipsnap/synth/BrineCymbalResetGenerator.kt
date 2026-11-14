package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.*
import java.io.File
import java.security.MessageDigest

/** Focused listening gate with recorded benchmarks and the current selected-sound MPC kit. */
object BrineCymbalResetGenerator {
    private fun json(v: Any?): JsonValue=when(v) {
        null -> JsonValue.Null
        is JsonValue -> v
        is String -> JsonValue.Str(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Map<*,*> -> JsonValue.Obj(v.entries.associate {it.key.toString() to json(it.value)})
        is Iterable<*> -> JsonValue.Arr(v.map(::json))
        else -> error("Unsupported value")
    }
    private fun hash(f: File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args[0]);val previous=File(args[1]);root.mkdirs()
        for(d in listOf("audio","recipes","diagnostics")) File(root,d).mkdirs()
        val clips=mutableListOf<Map<String,Any?>>()
        val settings=Brine.Settings(room=0f,grit=0f)
        fun export(id: String,group: String,label: String,input: Snip,variant: BrineCymbalReset.Variant?,gain: Float?=null) {
            val x=BrineExportTail.trim(input)
            val trimmed=x.frameCount<input.frameCount
            val matched=if(gain==null) AuditionLevel.level(x) else Snip(FloatArray(x.frameCount){x.samples[it]*gain},1,Dsp.RATE)
            val rf=WavWriter.write(File(root,"audio/$id-raw.wav"),x,WavWriter.BitDepth.PCM_24)
            val mf=WavWriter.write(File(root,"audio/$id.wav"),matched,WavWriter.BitDepth.PCM_24)
            val recipe=mapOf("revision" to if(variant==BrineCymbalReset.Variant.RING) 10 else 9,"variant" to variant?.name,"board" to settings.values(),
                "source" to "R7 measured spectral envelopes; newly synthesized phases",
                "bodyCutHz" to 540,"bodyCutDb" to if(variant==BrineCymbalReset.Variant.CURRENT) 0 else -14,
                "bodyQ" to .8,"highPassHz" to if(variant==BrineCymbalReset.Variant.CURRENT) 0 else 240,
                "highPassPoles" to 2,"tail" to if(variant==BrineCymbalReset.Variant.RING) "35 ms attack preserved; added T60 ride 3.2 s / crash 3 s / choke unchanged" else if(variant==BrineCymbalReset.Variant.FOCUSED) "35 ms attack preserved; added T60 ride 1.8 s / crash 3 s / choke unchanged" else "R7 envelope",
                "exportTail" to if(trimmed) "Last sample above peak -90 dB, plus 120 ms; final 10 ms fade; no leading trim" else "Original render length",
                "normalization" to "No source makeup gain; matched solo playback or common gain for groove comparisons")
            File(root,"recipes/$id.json").writeText(Json.write(json(recipe)))
            clips+=mapOf("id" to id,"group" to group,"label" to label,"raw" to "audio/$id-raw.wav",
                "audio" to "audio/$id.wav","recipe" to "recipes/$id.json","peak" to x.peak(),
                "loudness" to Loudness.of(x),"frames" to x.frameCount,"sha256" to hash(mf),"rawSha256" to hash(rf))
            println("$id peak=${x.peak()} loudness=${Loudness.of(x)}")
        }
        fun variants(ride: Boolean) = if(ride) listOf(BrineCymbalReset.Variant.RING,
            BrineCymbalReset.Variant.FOCUSED,BrineCymbalReset.Variant.LEAN,BrineCymbalReset.Variant.CURRENT)
            else BrineCymbalReset.Variant.entries.filter {it!=BrineCymbalReset.Variant.RING}
        fun prefix(v: BrineCymbalReset.Variant)=if(v==BrineCymbalReset.Variant.RING) "r10" else "r9"
        for(ride in listOf(true,false)) {
            val renders=variants(ride).map {Brine.process(BrineCymbalReset.groove(ride,it),settings)}
            // Lock all groove gains to the loudest member, so quieter metal stays quieter.
            val gain=.03f/renders.maxOf {Loudness.of(it)}.coerceAtLeast(1e-9f)
            for((i,v) in variants(ride).withIndex()) export("${prefix(v)}-${if(ride) "ride" else "crash"}-groove-${v.name.lowercase()}",
                if(ride) "Start here · ride in the kit" else "Crash accents in the kit",v.label,renders[i],v,gain)
        }
        for(v in BrineCymbalReset.voices) for(variant in variants(v==BrineCymbals.Voice.RIDE)) {
            val id="${prefix(variant)}-${v.id}-${variant.name.lowercase()}"
            val source=Snip(BrineCymbalReset.source(v,variant),1,Dsp.RATE)
            val recorded=BrineCymbalReset.hit(v,variant)
            WavWriter.write(File(root,"diagnostics/$id-source.wav"),source,WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root,"diagnostics/$id-recorded.wav"),recorded,WavWriter.BitDepth.PCM_24)
            export(id,"Isolated · ${v.label} · full chain",variant.label,Brine.process(recorded,settings),variant)
        }
        // Copy exact recorded-reference audio from the earlier benchmark, with original credits.
        val old=(Json.parse(File(previous,"manifest.json").readText()) as JsonValue.Obj).entries
        val oldClips=(old.getValue("clips") as JsonValue.Arr).items.map {(it as JsonValue.Obj).entries}
        for(v in BrineCymbalReset.voices) {
            val c=oldClips.first {(it["id"] as JsonValue.Str).value=="r7-${v.id}-reference"}
            fun str(k: String)=(c.getValue(k) as JsonValue.Str).value
            for(k in listOf("audio","raw","recipe")) File(previous,str(k)).copyTo(File(root,str(k)),overwrite=true)
            // Earlier benchmark exports appended exactly one second of zeros. Remove that
            // generated padding only; retain every frame of the original recorded reference.
            for(k in listOf("audio","raw")) {
                val file=File(root,str(k));val x=WavReader.read(file)
                require(x.samples.takeLast(Dsp.RATE).all {it==0f})
                WavWriter.write(file,Snip(x.samples.copyOf(x.frameCount-Dsp.RATE),1,Dsp.RATE),WavWriter.BitDepth.PCM_24)
            }
            clips+=c+mapOf("frames" to ((c.getValue("frames") as JsonValue.Num).value-Dsp.RATE),
                "sha256" to hash(File(root,str("audio"))),"rawSha256" to hash(File(root,str("raw"))),"group" to "Recorded references · no effects","label" to "${v.label} · real recording")
        }
        for(name in listOf("REFERENCE-CREDITS.txt","reference-sources.json","LICENSE-CC0.txt"))
            File(previous,name).copyTo(File(root,name),overwrite=true)
        val manifest=Json.write(json(mapOf("title" to "BRINE · selected MPC kit R13","version" to 13,
            "status" to "Listener-kept sounds retained; trailing near-silence removed from all synthesized previews; recorded references lose only appended zero padding",
            "levelPolicy" to "Grooves share one playback gain within each group; solo clips matched individually at .03 loudest-200ms RMS; Raw always unscaled",
            "mpcDownload" to "Current R13 kit: selected ring ride, focused crashes, post-board tail cleanup on every sample; four programs with snare/ride velocity layers",
            "clips" to clips)))
        File(root,"manifest.json").writeText(manifest)
        val template=checkNotNull(javaClass.getResourceAsStream("/audition/brine-cymbal-reset.html")).bufferedReader().readText()
        File(root,"index.html").writeText(template.replace("/*MANIFEST*/","const manifest = $manifest;"))
        File(root,"metrics.csv").writeText("id,peak,loudness,frames\n"+clips.joinToString("\n"){c->listOf("id","peak","loudness","frames").joinToString(","){c[it].toString()}}+"\n")
        BrineMpcGenerator.write(root)
        println("Wrote ${clips.size} focused comparisons and current MPC kit")
    }
}
