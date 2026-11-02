package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Actual JVM engine renders, explicit causal recipes, common-gain raw and matched audio. */
object RimeAuditionGenerator {
    private const val RAW_GAIN = .25f
    private const val MATCHED_TARGET = .10f
    private val steps = listOf(0f,.25f,.5f,.75f,1f)
    private val angleSteps = (0..8).map { it / 8f }
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
        val bankRoles=RimePresets.legacyEntries().associate { it.id to it.role }
        val cases=cases(quick)
        check(cases.map{it.id}.distinct().size==cases.size)
        val started=System.nanoTime()
        for((index,case) in cases.withIndex()) {
            val p=case.patch
            val began=System.nanoTime()
            val probe=Rime.inspect(p.voice,p.macros,p.midi,p.velocity,p.gateSeconds,p.seed,case.options,p.model)
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
            val files=writeAudio(root,case.id,raw,matched) + if(case.section=="bank")
                mapOf("patch" to "recipes/${case.id}-patch.json") else emptyMap()
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
                "voice" to p.voiceName,"midi" to p.midi,"velocity" to p.velocity,"gateSeconds" to p.gateSeconds,"role" to bankRoles[case.group],
                "model" to p.model,"macros" to (Rime.defaults(p.voice,p.model)+p.macros),"macro" to case.macro,"value" to case.value,"group" to case.group,
                "files" to (files+mapOf("recipe" to recipeFile)),"metrics" to metrics,"waveform" to waveform(matched))
            println("RIME ${index+1}/${cases.size}: ${case.id}; ${"%.0f".format(elapsed)} ms; peak=${probe.internalPeak}; tail=${probe.tailDb}")
        }
        if(!quick) for(patch in Rime.LEGACY_VOICES.map { RimePresets.starter(it) }) entries+=phrase(root,patch)
        if(!quick) {
            for (entry in RimePresets.legacyEntries()) entries += bankPerformances(root, entry)
            val temporary = Files.createTempDirectory("rime-musical-kit").toFile()
            try {
                val kit = RimeKitGenerator.generate(temporary)
                val patches = RimeKits.patches()
                val events = RimeKitGenerator.hits.map { it.beat * 60.0 / RimeKitGenerator.BPM to patches[it.slot - 1] }
                // Main-zone hits only. This is the house preview mix of the same
                // recipes, not a recording from MPC hardware.
                val gain = .45f * .707946f * sqrt(.5f) * (.35f + .65f * RimeKitGenerator.DEMO_VELOCITY)
                entries += scoreClip(root, "bank-kit-phrase", "The full kit · 104 BPM",
                    "An original four-bar study of the exported recipes, using main-zone hits and the house preview mix.",
                    "bank-mix", "kit", events, gain, "RIME")
                File(root, "downloads").mkdirs()
                kit.archive.copyTo(File(root,"downloads/rime-mpc-kit.zip"), overwrite = true)
                File(kit.directory,"pad-map.json").copyTo(File(root,"downloads/rime-pad-map.json"), overwrite = true)
                bankDownload(root, kit)
            } finally { temporary.deleteRecursively() }
        }
        geometry(root)
        val manifest=linkedMapOf("schema" to 1,"engine" to "RIME","model" to Rime.MODEL_VERSION,
            "generatedAt" to Instant.now().toString(),"sourceRevision" to sourceRevision(),"sampleRate" to Dsp.RATE,
            "oversample" to 4,"rawExportGain" to RAW_GAIN,"matchedTarget" to MATCHED_TARGET,
            "status" to "Musical bank 1: nine finite model-4 presets, musical phrases, native touch studies and a sixteen-pad MPC kit. Accepted starters and controls are unchanged. Owner bank and hardware listening pending. HOLD and phone picker integration are deferred.",
            "bank" to if(quick)null else mapOf("version" to 1,"presets" to 9,"pads" to 16,"velocityLayers" to 3,"tempoBpm" to RimeKitGenerator.BPM),
            "renderSeconds" to (System.nanoTime()-started)/1e9,"clips" to entries)
        File(root,"manifest.json").writeText(Json.write(json(manifest)))
        File(root,"README.txt").writeText("RIME finite listening prototype, model ${Rime.MODEL_VERSION}\n\n44.1 kHz mono, synthesized in the SnipSnap Kotlin engine at 4x.\nRaw: one fixed gain of $RAW_GAIN for all prelevel outputs.\nMatched: shared Dsp.levelTo, loudest-200-ms RMS target $MATCHED_TARGET, peak ceiling .90.\nANGLE retains the preferred rounded/open positions and spreads the return from 180 to 360 degrees. The full sweep has nine positions.\nDiagnostic recipes include an ordinary RIME patch and explicit contact/reference/body switches.\nReplay a recipe: ./gradlew :synth:generateRimeAudition -PrimeReplay=/absolute/recipe.json -PrimeOutput=/absolute/output\nOrdinary patches replay through: synth RIME VOICE --patch /path/voice-patch.json --out /path/output\nMusical bank: nine model-4 presets, native one-shot download, phrases, three-strike touch studies and a sixteen-pad MPC kit.\nTouch studies preserve native relative note dynamics before a single whole-clip level match.\nNative one-shot downloads keep the kit main-sample gain; raw single notes keep the stated common prelevel gain.\nHOLD is refused. Models 1 through 3 retain their original pickup behavior. No external samples or rack effects.\n")
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
        for(p in Rime.LEGACY_VOICES.map { RimePresets.starter(it) }) {
            val voice=p.voice.name.lowercase()
            add(Case("$voice-start","start",p.name,when(p.voice){
                RimeVoice.LOOP->"A rounded bass silhouette with a small, dry body."
                RimeVoice.GLINT->"A bright key whose fine contour pressure adds a serrated edge."
                RimeVoice.VESSEL->"A slower reveal through a larger, longer-ringing body."
                else -> error("Historical starters use the three accepted voices")
            },p))
            for(macro in Rime.TIMBRAL) for(value in if(quick)listOf(0f,1f)else if(macro=="ANGLE")angleSteps else steps) {
                val id="$voice-${macro.lowercase()}-${(value*100).roundToInt()}"
                val title = if (macro == "ANGLE") "ANGLE ${(value*360).roundToInt()}°" else "$macro ${(value*100).roundToInt()}%"
                add(Case(id,"controls",title,"One control changes; note, velocity, seed and all other controls stay fixed.",
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
            for (entry in RimePresets.legacyEntries()) add(Case("bank-${entry.id}","bank",entry.patch.name,
                "${entry.role}. ${entry.description}",entry.patch,group=entry.id))
            for (voice in Rime.LEGACY_VOICES) for (value in listOf(.625f, .75f, .875f)) {
                val p = RimePatch("Previous ${voice.name} return", voice,
                    Rime.defaults(voice, 3) + ("ANGLE" to value), model = 3)
                add(Case("model3-${voice.name.lowercase()}-angle-${(value*100).roundToInt()}","revision",
                    "Previous return ${(value*360).roundToInt()}°",
                    "The preferred model-3 pickup before its return travel was redistributed.",p))
            }
            for (voice in Rime.LEGACY_VOICES) for (value in listOf(0f, .5f)) {
                val p = RimePatch("Previous ${voice.name} ANGLE", voice,
                    Rime.defaults(voice, 2) + ("ANGLE" to value), model = 2)
                add(Case("previous-${voice.name.lowercase()}-angle-${(value*100).roundToInt()}","revision",
                    "Previous ANGLE ${(value*360).roundToInt()}°",
                    "The model-2 point pickup before finite contact spacing was added.",p))
            }
            for (voice in Rime.LEGACY_VOICES) {
                val p = RimePatch("Previous ${voice.name} BODY 100", voice,
                    Rime.defaults(voice, 1) + ("BODY" to 1f), model = 1)
                add(Case("legacy-${voice.name.lowercase()}-body-100","revision","Previous BODY 100%",
                    "The original model-1 audition recipe; preserved without the revised body excitation.",p))
            }
            add(Case("legacy-loop-unfold-100","revision","Previous LOOP UNFOLD 100%",
                "The original LOOP starting contour and body, before additional fine structure was available to reveal.",
                RimePatch("Previous LOOP unfold",RimeVoice.LOOP,Rime.defaults(RimeVoice.LOOP,1)+("UNFOLD" to 1f),model=1)))
            val p=RimePresets.starter(RimeVoice.GLINT).copy(midi=48,gateSeconds=2f)
            for((a,b) in listOf("DETAIL" to "EDGE","DETAIL" to "UNFOLD","ANGLE" to "BODY","EDGE" to "BODY")) {
                for(x in listOf(.2f,.9f)) for(y in listOf(.2f,.9f)) {
                    add(Case("interaction-${a.lowercase()}-${b.lowercase()}-${(x*100).toInt()}-${(y*100).toInt()}","interactions",
                        "$a ${(x*100).toInt()} · $b ${(y*100).toInt()}","A two-by-two comparison at C3; remaining controls share one realization.",
                        p.copy(macros=p.macros+mapOf(a to x,b to y)),group="$a × $b"))
                }
            }
        }
    }

    private fun bankPerformances(root: File, entry: RimePresets.Entry): List<Map<String,Any?>> {
        val p = entry.patch
        val (beats, offsets) = when(p.voice) {
            RimeVoice.LOOP -> listOf(0.0,2.0,4.0,6.0) to listOf(0,3,7,0)
            RimeVoice.GLINT -> listOf(0.0,1.0,1.5,3.0,4.0,5.5) to listOf(0,3,7,10,7,3)
            RimeVoice.VESSEL -> listOf(0.0,3.0,6.0) to listOf(0,7,3)
            else -> error("Historical score uses the three accepted voices")
        }
        val velocities = listOf(.55f,.8f,.65f,.75f,.5f,.8f)
        val phrase = beats.mapIndexed { i, beat -> beat * 60.0 / RimeKitGenerator.BPM to
            p.copy(midi = p.midi + offsets[i], velocity = velocities[i]) }
        val spacing = p.render().durationSeconds + .15
        val touch = listOf(.3f,.6f,.8f).mapIndexed { i, v -> i * spacing to p.copy(velocity = v) }
        return listOf(
            scoreClip(root,"bank-${entry.id}-phrase","${p.name} · phrase",
                "An original C-minor phrase at 104 BPM. The preset's complete note and body tail are retained.",
                "bank-phrases",entry.id,phrase),
            scoreClip(root,"bank-${entry.id}-touch","${p.name} · soft to hard",
                "Three native strikes at 30%, 60% and 80%, with complete tails. Relative dynamics survive matched playback.",
                "bank-touch",entry.id,touch))
    }

    private fun mixScore(events: List<Pair<Double,RimePatch>>, gain: Float): FloatArray {
        val renders = events.map { it.second.render().samples }
        val starts = events.map { (it.first * Dsp.RATE).roundToInt() }
        val mix = FloatArray(events.indices.maxOf { starts[it] + renders[it].size })
        for(i in renders.indices) for(j in renders[i].indices) mix[starts[i]+j] += renders[i][j] * gain
        return mix
    }

    private fun scoreClip(root: File, id: String, title: String, description: String, section: String,
        group: String, events: List<Pair<Double,RimePatch>>, gain: Float = .55f,
        voice: String = events.first().second.voiceName): Map<String,Any?> {
        val mix = mixScore(events,gain)
        val raw = FloatArray(mix.size) { mix[it] * RAW_GAIN }
        check(raw.all { it.isFinite() } && raw.maxOf { abs(it) } < 1f) { "$id score raw overflow" }
        val matched = mix.copyOf(); Dsp.levelTo(matched,Dsp.RATE,MATCHED_TARGET,ceiling=.9f)
        val file = "recipes/$id.json"
        val score = mapOf("kind" to "RIME_SCORE","version" to 1,"model" to RimePresets.BANK_MODEL,
            "mixGain" to gain,"tempoBpm" to RimeKitGenerator.BPM,"events" to events.map {
                mapOf("seconds" to it.first,"patch" to it.second.toJsonValue()) })
        File(root,file).writeText(Json.write(json(score)))
        val p = events.first().second
        return mapOf("id" to id,"section" to section,"group" to group,"title" to title,"description" to description,
            "model" to p.model,"voice" to voice,"midi" to p.midi,"velocity" to p.velocity,"macros" to p.macros,
            "files" to (writeAudio(root,id,raw,matched)+("recipe" to file)),"waveform" to waveform(matched),
            "metrics" to mapOf("durationSeconds" to mix.size.toDouble()/Dsp.RATE,"matchedLoudness" to Loudness.of(matched,1,Dsp.RATE)))
    }

    private fun bankDownload(root: File, kit: RimeKitGenerator.Bundle) {
        ZipOutputStream(File(root,"downloads/rime-musical-bank.zip").outputStream().buffered()).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry("Rime Musical Bank/$path").also { it.time=0 });zip.write(bytes);zip.closeEntry()
            }
            for (entry in RimePresets.legacyEntries()) {
                val pad = kit.kit.pads.single { it.displayName == entry.patch.name }
                put("${entry.id}.wav",File(kit.data,pad.sampleFile).readBytes())
                put("${entry.id}.json",entry.patch.toJsonText().toByteArray())
                File(root,"recipes/bank-${entry.id}-patch.json").writeText(entry.patch.toJsonText())
            }
            put("README.txt",("Nine native model-4 RIME one-shots and complete patches.\n"+
                "These are the kit's native .8 strikes, without audition gain matching.\n"+
                "Replay: snipsnap synth RIME VOICE --patch /path/preset.json --out /path/output\n"+
                "HOLD is refused. Musical-bank listening and MPC hardware acceptance remain pending.\n").toByteArray())
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
        val score=mapOf("kind" to "RIME_SCORE","version" to 1,"model" to Rime.MODEL_VERSION,"mixGain" to .55,"events" to notes.mapIndexed{i,n->
            mapOf("seconds" to i*spacing,"patch" to n.toJsonValue())})
        val file="recipes/$id.json";File(root,file).writeText(Json.write(json(score)))
        return mapOf("id" to id,"section" to "phrases","model" to p.model,"title" to "${p.name} · six notes","description" to "An original C-minor phrase, with physical velocity and overlapping dry tails.",
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
            require((obj["version"]?.int() ?: 1)==1 && obj.getValue("model").int() in 1..Rime.MODEL_VERSION)
            val events=obj.getValue("events").arr().map{it.obj()}.map{
                it.getValue("seconds").num() to RimePatch.fromJsonValue(it.getValue("patch"))}
            val mix=mixScore(events,obj.getValue("mixGain").num().toFloat())
            val size=mix.size
            val matched=mix.copyOf();Dsp.levelTo(matched,Dsp.RATE,MATCHED_TARGET,ceiling=.9f)
            writeAudio(root,"replay",FloatArray(size){mix[it]*RAW_GAIN},matched)
        } else {
            require(kind=="RIME_DIAGNOSTIC"&&obj.getValue("version").int()==1)
            val p=RimePatch.fromJsonValue(obj.getValue("patch"));val o=obj.getValue("options").obj()
            val options=Rime.Options(o.getValue("contact").bool(),o.getValue("reference").bool(),o.getValue("body").bool(),o.getValue("oversample").int(),o.getValue("friction").bool())
            val probe=Rime.inspect(p.voice,p.macros,p.midi,p.velocity,p.gateSeconds,p.seed,options,p.model)
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
