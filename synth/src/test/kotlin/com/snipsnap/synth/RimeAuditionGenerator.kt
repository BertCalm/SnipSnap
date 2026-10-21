package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Actual JVM engine renders, explicit causal recipes, common-gain raw and matched audio. */
object RimeAuditionGenerator {
    private const val RAW_GAIN = .25f
    private const val MATCHED_TARGET = .10f
    private val steps = listOf(0f,.25f,.5f,.75f,1f)
    private data class Case(val id:String,val section:String,val title:String,val description:String,
        val patch:RimePatch,val options:Rime.Options=Rime.Options(),val macro:String?=null,val value:Float?=null,val group:String?=null)

    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull()?:"../testkit/rime-audition").absoluteFile
        root.mkdirs(); File(root,"manifest.json").delete()
        if("--replay" in args) { replay(File(args[args.indexOf("--replay")+1]),root); return }
        for(asset in listOf("index.html","style.css","app.js","favicon.svg")) {
            val input=RimeAuditionGenerator::class.java.getResourceAsStream("/rime-audition/$asset")?:error("Missing audition asset $asset")
            input.use{File(root,asset).outputStream().use{out->it.copyTo(out)}}
        }
        val quick="--quick" in args
        val entries=mutableListOf<Map<String,Any?>>()
        val cases=cases(quick)
        check(cases.map{it.id}.distinct().size==cases.size)
        val started=System.nanoTime()
        for((index,case) in cases.withIndex()) {
            val p=case.patch
            val began=System.nanoTime()
            val probe=Rime.inspect(p.voice,p.macros,p.midi,p.velocity,p.gateSeconds,p.seed,case.options)
            val elapsed=(System.nanoTime()-began)/1e6
            val native=Snip(probe.samples,1,Dsp.RATE)
            val metrics=LinkedHashMap(TangleMeasure.metrics(probe.raw,native,p.midi,elapsed))
            metrics["internalPeak"]=probe.internalPeak; metrics["maxBodyEnergy"]=probe.maxBodyEnergy
            metrics["resolvedCutoff"]=probe.cutoff; metrics["safeMaximum"]=probe.safeMaximum; metrics["unfadedTailDb"]=probe.tailDb
            val raw=FloatArray(probe.raw.size){probe.raw[it]*RAW_GAIN}
            check(raw.all{it.isFinite()}&&raw.maxOf{abs(it)}<1f) { "RIME ${case.id} common-gain raw PCM overflow" }
            check(probe.samples.all{it.isFinite()})
            val matched=probe.samples.copyOf()
            Dsp.levelTo(matched,Dsp.RATE,MATCHED_TARGET,ceiling=.9f)
            val files=writeAudio(root,case.id,raw,matched)
            metrics["matchedLoudness"]=Loudness.of(matched,1,Dsp.RATE)
            metrics["rawExportGain"]=RAW_GAIN
            val recipe=recipe(p,case.options)
            val recipeFile="recipes/${case.id}.json"
            File(root,recipeFile).apply{parentFile.mkdirs();writeText(Json.write(recipe))}
            if(case.section=="start") {
                File(root,"recipes/${p.voice.name.lowercase()}-patch.json").writeText(p.toJsonText())
                File(root,"traces/${case.id}.json").apply{parentFile.mkdirs();writeText(Json.write(json(probe.trace.map{
                    mapOf("seconds" to it.seconds,"bandGains" to it.bandGains,"bodyEnergy" to it.bodyEnergy)
                })))}
            }
            entries+=linkedMapOf("id" to case.id,"section" to case.section,"title" to case.title,"description" to case.description,
                "voice" to p.voiceName,"midi" to p.midi,"velocity" to p.velocity,"gateSeconds" to p.gateSeconds,
                "macros" to (Rime.defaults(p.voice)+p.macros),"macro" to case.macro,"value" to case.value,"group" to case.group,
                "files" to (files+mapOf("recipe" to recipeFile)),"metrics" to metrics,"waveform" to waveform(matched))
            println("RIME ${index+1}/${cases.size}: ${case.id}; ${"%.0f".format(elapsed)} ms; peak=${probe.internalPeak}; tail=${probe.tailDb}")
        }
        if(!quick) for(patch in RimePresets.all()) entries+=phrase(root,patch)
        geometry(root)
        val manifest=linkedMapOf("schema" to 1,"engine" to "RIME","model" to Rime.MODEL_VERSION,
            "generatedAt" to Instant.now().toString(),"sourceRevision" to sourceRevision(),"sampleRate" to Dsp.RATE,
            "oversample" to 4,"rawExportGain" to RAW_GAIN,"matchedTarget" to MATCHED_TARGET,
            "status" to "Finite first-listen prototype. Owner listening pending. HOLD and phone integration are deferred.",
            "renderSeconds" to (System.nanoTime()-started)/1e9,"clips" to entries)
        File(root,"manifest.json").writeText(Json.write(json(manifest)))
        File(root,"README.txt").writeText("RIME finite listening prototype, model 1\n\n44.1 kHz mono, synthesized in the SnipSnap Kotlin engine at 4x.\nRaw: one fixed gain of $RAW_GAIN for all prelevel outputs.\nMatched: shared Dsp.levelTo, loudest-200-ms RMS target $MATCHED_TARGET, peak ceiling .90.\nDiagnostic recipes include an ordinary RIME patch and explicit contact/reference/body switches.\nReplay a recipe: ./gradlew :synth:generateRimeAudition -PrimeReplay=/absolute/recipe.json -PrimeOutput=/absolute/output\nOrdinary patches replay through: synth RIME VOICE --patch /path/voice-patch.json --out /path/output\nHOLD is refused in model 1. No external samples or rack effects.\n")
        File(root,"downloads").mkdirs()
        zip(File(root,"downloads/rime-recipes.zip"),root,listOf(File(root,"recipes"),File(root,"README.txt"),File(root,"manifest.json"),File(root,"geometry.json")))
        zip(File(root,"downloads/rime-first-listen.zip"),root,entries.filter{it["section"]=="start"||it["section"]=="phrases"}.flatMap {
            @Suppress("UNCHECKED_CAST") val files=it["files"] as Map<String,String>
            files.values.map{File(root,it)}
        }+File(root,"README.txt"))
        check(entries.isNotEmpty())
        println("RIME audition complete: ${entries.size} clips in $root")
    }

    private fun cases(quick:Boolean):List<Case> = buildList {
        for(p in RimePresets.all()) {
            val voice=p.voice.name.lowercase()
            add(Case("$voice-start","start",p.name,when(p.voice){
                RimeVoice.LOOP->"A rounded bass silhouette with a small, dry body."
                RimeVoice.GLINT->"A bright key whose fine contour pressure adds a serrated edge."
                RimeVoice.VESSEL->"A slower reveal through a larger, longer-ringing body."
            },p))
            for(macro in Rime.TIMBRAL) for(value in if(quick)listOf(0f,1f)else steps) {
                val id="$voice-${macro.lowercase()}-${(value*100).roundToInt()}"
                add(Case(id,"controls","$macro ${(value*100).roundToInt()}%","One control changes; note, velocity, seed and all other controls stay fixed.",
                    p.copy(macros=p.macros+(macro to value)),macro=macro,value=value,group="$voice-$macro"))
            }
            for((name,options) in listOf("Full" to Rime.Options(),"No contact" to Rime.Options(contact=false),
                "One pickup" to Rime.Options(reference=false),"No body" to Rime.Options(body=false))) {
                add(Case("$voice-causal-${name.lowercase().replace(' ','-')}","causal",name,
                    "Same saved patch and performance. Only the named internal path is removed.",p,options,group=voice))
            }
            if(!quick) {
                for(midi in listOf(36,60,84)) for(velocity in listOf(.35f,1f)) add(Case("$voice-note-$midi-velocity-${(velocity*100).toInt()}",
                    "register","MIDI $midi · velocity ${(velocity*100).toInt()}%","Root and physical excitation across the register.",p.copy(midi=midi,velocity=velocity)))
                add(Case("$voice-all-high","stress","All controls high · C7","Safe bandwidth at the upper end of the audition register.",
                    p.copy(midi=96,velocity=1f,macros=Rime.TIMBRAL.associateWith{1f})))
            }
        }
        if(!quick) {
            val p=RimePresets.forVoice(RimeVoice.GLINT).single().copy(midi=48,gateSeconds=2f)
            for((a,b) in listOf("DETAIL" to "EDGE","DETAIL" to "UNFOLD","ANGLE" to "BODY","EDGE" to "BODY")) {
                for(x in listOf(.2f,.9f)) for(y in listOf(.2f,.9f)) {
                    add(Case("interaction-${a.lowercase()}-${b.lowercase()}-${(x*100).toInt()}-${(y*100).toInt()}","interactions",
                        "$a ${(x*100).toInt()} · $b ${(y*100).toInt()}","A two-by-two comparison at C3; remaining controls share one realization.",
                        p.copy(macros=p.macros+mapOf(a to x,b to y)),group="$a × $b"))
                }
            }
        }
    }

    private fun phrase(root:File,p:RimePatch):Map<String,Any?> {
        val offsets=listOf(0,3,7,10,7,3)
        val spacing=if(p.voice==RimeVoice.VESSEL).8 else .36
        val gate=if(p.voice==RimeVoice.VESSEL)1.05f else .27f
        val velocities=listOf(.55f,.75f,.85f,.65f,.9f,.6f)
        val notes=offsets.mapIndexed{i,offset->p.copy(midi=p.midi+offset,velocity=velocities[i],gateSeconds=gate)}
        val renders=notes.map{it.render().samples}
        val size=renders.indices.maxOf{(it*spacing*Dsp.RATE).roundToInt()+renders[it].size}
        val mix=FloatArray(size)
        for(i in renders.indices) {val at=(i*spacing*Dsp.RATE).roundToInt();for(j in renders[i].indices)mix[at+j]+=renders[i][j]*.55f}
        val matched=mix.copyOf();Dsp.levelTo(matched,Dsp.RATE,MATCHED_TARGET,ceiling=.9f)
        val raw=FloatArray(size){mix[it]*RAW_GAIN}
        val id="${p.voice.name.lowercase()}-phrase"
        val files=writeAudio(root,id,raw,matched)
        val score=mapOf("kind" to "RIME_SCORE","model" to 1,"mixGain" to .55,"events" to notes.mapIndexed{i,n->
            mapOf("seconds" to i*spacing,"patch" to n.toJsonValue())})
        val file="recipes/$id.json";File(root,file).writeText(Json.write(json(score)))
        return mapOf("id" to id,"section" to "phrases","title" to "${p.name} · six notes","description" to "An original C-minor phrase, with physical velocity and overlapping dry tails.",
            "voice" to p.voiceName,"midi" to p.midi,"velocity" to p.velocity,"macros" to p.macros,"files" to (files+("recipe" to file)),
            "metrics" to mapOf("durationSeconds" to size.toDouble()/Dsp.RATE,"matchedLoudness" to Loudness.of(matched,1,Dsp.RATE)),"waveform" to waveform(matched))
    }

    private fun writeAudio(root:File,id:String,raw:FloatArray,matched:FloatArray):Map<String,String> {
        val r="audio/$id-raw.wav";val m="audio/$id-matched.wav"
        WavWriter.write(File(root,r),Snip(raw,1,Dsp.RATE));WavWriter.write(File(root,m),Snip(matched,1,Dsp.RATE))
        return mapOf("raw" to r,"matched" to m)
    }
    private fun waveform(samples:FloatArray):List<Double> = (0 until 96).map { j->
        val a=j*samples.size/96;val b=((j+1)*samples.size/96).coerceAtLeast(a+1)
        (a until min(b,samples.size)).maxOfOrNull{abs(samples[it]).toDouble()}?:0.0
    }
    private fun geometry(root:File) {
        val path=RimeContour.generate();val coefficients=RimeContour.reference
        val results=listOf(1,8,32,128,512).map {cutoff->
            val selected=coefficients.filter{abs(it.n)<=cutoff}
            val points=(0 until 1024).map{i->RimeContour.evaluate(selected,i/1024.0).toList()}
            val errors=(0 until 1024).map{i->val k=i*path.x.size/1024;val z=points[i];hypot(z[0]-path.x[k],z[1]-path.y[k])}
            mapOf("cutoff" to cutoff,"points" to points,"rmsRadiusError" to sqrt(errors.sumOf{it*it}/errors.size),"maxRadiusError" to errors.max())
        }
        val result=mapOf("iteration" to 5,"edges" to path.edges,"samples" to path.x.size,"radius" to path.radius,
            "convention" to "Top vertex, counterclockwise, equal polygon arc length, perimeter-centered, unit maximum sampled radius.",
            "target" to (path.x.indices step 4).map{listOf(path.x[it],path.y[it])},"reconstructions" to results,
            "coefficients" to coefficients.filter{it.n!=0}.map{mapOf("n" to it.n,"re" to it.re,"im" to it.im)})
        File(root,"geometry.json").writeText(Json.write(json(result)))
    }
    private fun recipe(p:RimePatch,o:Rime.Options)=json(mapOf("kind" to "RIME_DIAGNOSTIC","version" to 1,"patch" to p.toJsonValue(),
        "options" to mapOf("contact" to o.contact,"reference" to o.reference,"body" to o.body,"oversample" to o.oversample,"friction" to o.friction)))

    private fun replay(file:File,root:File) {
        val obj=Json.parse(file.readText()).obj()
        val kind=obj.getValue("kind").str()
        if(kind=="RIME_SCORE") {
            require(obj.getValue("model").int()==1)
            val events=obj.getValue("events").arr().map{it.obj()}.map{
                it.getValue("seconds").num() to RimePatch.fromJsonValue(it.getValue("patch"))}
            val renders=events.map{it.second.render().samples}
            val size=events.indices.maxOf{(events[it].first*Dsp.RATE).roundToInt()+renders[it].size}
            val mix=FloatArray(size);val gain=obj.getValue("mixGain").num().toFloat()
            for(i in events.indices){val at=(events[i].first*Dsp.RATE).roundToInt();for(j in renders[i].indices)mix[at+j]+=renders[i][j]*gain}
            val matched=mix.copyOf();Dsp.levelTo(matched,Dsp.RATE,MATCHED_TARGET,ceiling=.9f)
            writeAudio(root,"replay",FloatArray(size){mix[it]*RAW_GAIN},matched)
        } else {
            require(kind=="RIME_DIAGNOSTIC"&&obj.getValue("version").int()==1)
            val p=RimePatch.fromJsonValue(obj.getValue("patch"));val o=obj.getValue("options").obj()
            val options=Rime.Options(o.getValue("contact").bool(),o.getValue("reference").bool(),o.getValue("body").bool(),o.getValue("oversample").int(),o.getValue("friction").bool())
            val probe=Rime.inspect(p.voice,p.macros,p.midi,p.velocity,p.gateSeconds,p.seed,options)
            val matched=probe.samples.copyOf();Dsp.levelTo(matched,Dsp.RATE,MATCHED_TARGET,ceiling=.9f)
            writeAudio(root,"replay",FloatArray(probe.raw.size){probe.raw[it]*RAW_GAIN},matched)
        }
        println("Replayed $file into $root")
    }

    private fun zip(file:File,root:File,inputs:List<File>) {
        ZipOutputStream(file.outputStream().buffered()).use{zip->
            inputs.flatMap{if(it.isDirectory)it.walkTopDown().filter{it.isFile}.toList()else listOf(it)}.distinct().sortedBy{it.path}.forEach{
                val entry=ZipEntry(it.relativeTo(root).invariantSeparatorsPath);entry.time=0;zip.putNextEntry(entry);it.inputStream().use{input->input.copyTo(zip)};zip.closeEntry()
            }
        }
    }
    private fun sourceRevision():String=try{ProcessBuilder("git","rev-parse","HEAD").start().inputStream.bufferedReader().readText().trim()}catch(_:Exception){"unknown"}
    private fun json(value:Any?):JsonValue=when(value){
        null->JsonValue.Null
        is JsonValue->value
        is String->JsonValue.Str(value)
        is Boolean->JsonValue.Bool(value)
        is Number->if(value.toDouble().isFinite())JsonValue.Num(value.toDouble())else JsonValue.Null
        is Map<*,*>->JsonValue.Obj(value.entries.associate{it.key.toString() to json(it.value)})
        is Iterable<*>->JsonValue.Arr(value.map{json(it)})
        else->error("unsupported manifest value $value")
    }
}
