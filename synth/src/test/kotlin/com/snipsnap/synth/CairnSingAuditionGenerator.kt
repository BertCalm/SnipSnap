package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.*

/** Four voice roles and SING's new traction; every recording uses production Kotlin. */
object CairnSingAuditionGenerator {
    private val middle=mapOf("TUNE" to .5f,"WEIGHT" to .5f,"SLOPE" to .4f,"GROOVE" to .35f,
        "FRICTION" to .3f,"BODY" to .4f,"HOLD" to 0f)
    private data class Case(val id:String,val section:String,val patch:CairnPatch,val isolated:Boolean=false)
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull()?:"../testkit/cairn-sing").canonicalFile
        root.mkdirs()
        val repo=generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it,".git").exists() }
        val revision=ProcessBuilder("git","rev-parse","HEAD").directory(repo).start().let {
            val s=it.inputStream.bufferedReader().readText().trim();check(it.waitFor()==0);s
        }
        val hashes=listOf("Cairn.kt","CairnPatch.kt","CairnTumble.kt","CairnSing.kt","Modes.kt").associateWith {
            sha(File(repo,"synth/src/main/kotlin/com/snipsnap/synth/$it").readBytes())
        }
        var started=System.nanoTime();Cairn.renderLoop(CairnVoice.SING)
        val coldMs=(System.nanoTime()-started)/1e6
        val cases=mutableListOf<Case>()
        fun add(id:String,section:String,voice:CairnVoice,m:Map<String,Float>,note:Int=60,velocity:Float=1f,isolated:Boolean=false) {
            cases+=Case(id,section,CairnPatch(id,voice,m,note,velocity),isolated)
        }
        for(voice in CairnVoice.entries) for(held in listOf(false,true)) {
            val mode=if(held)"hold" else "shot";val flag=if(held)1f else 0f
            for(note in listOf(48,60,72)) add("${voice.name.lowercase()}-$mode-$note","Voices",voice,
                Cairn.defaults(voice)+("HOLD" to flag),note)
            add("${voice.name.lowercase()}-common-$mode","Common controls",voice,middle+("HOLD" to flag))
        }
        val voice=CairnVoice.SING
        for(held in listOf(false,true)) {
            val mode=if(held)"hold" else "shot";val base=Cairn.defaults(voice)+("HOLD" to if(held)1f else 0f)
            for(control in listOf("WEIGHT","SLOPE","GROOVE","FRICTION","BODY")) for(value in listOf(0f,.5f,1f))
                add("sing-${control.lowercase()}-${(value*100).roundToInt()}-$mode","SING controls",voice,base+(control to value))
            for(body in listOf(.25f,.75f)) for(friction in listOf(.25f,.75f))
                add("sing-pair-${(body*100).roundToInt()}-${(friction*100).roundToInt()}-$mode","SING corners",voice,
                    base+mapOf("BODY" to body,"FRICTION" to friction))
            for(velocity in listOf(.35f,.65f)) add("sing-soft-${(velocity*100).roundToInt()}-$mode","SING velocity",voice,base,velocity=velocity)
            for(edge in listOf(0f,1f)) add("sing-edge-${edge.toInt()}-$mode","SING extremes",voice,
                base.mapValues { if(it.key in listOf("TUNE","HOLD"))it.value else edge })
        }
        add("sing-rubbing-off","Cause and effect",voice,Cairn.defaults(voice),isolated=true)
        for(value in listOf(.5f,.95f)) add("sing-dwell-${(value*100).roundToInt()}","Finite dwell",voice,
            Cairn.defaults(voice)+("HOLD" to value))
        val rows=cases.mapIndexed { index,c ->
            val p=c.patch;started=System.nanoTime()
            val r=Cairn.inspect(p.voice,p.macros,p.midi,p.velocity,p.startAtContact,
                Cairn.Options(disableCoupling=c.isolated),p.model)
            val ms=(System.nanoTime()-started)/1e6;val d=r.diagnostics;val motion=d.motion
            val wav=file(root,"audio/${c.id}.wav");WavWriter.write(wav,Snip(r.samples,1,Cairn.RATE))
            file(root,"recipes/${c.id}.json").writeText(p.toJsonText())
            file(root,"events/${c.id}.csv").writeText("seconds,stone,work\n"+
                motion.events.joinToString("\n") { "${it.seconds},${it.stone},${it.work}" }+"\n")
            file(root,"traces/${c.id}.csv").writeText("seconds,energy,withdrawnWork,first,second,third,fourth,fifth\n"+
                motion.states.joinToString("\n") { "${it.seconds},${it.energy},${it.withdrawnWork},${it.positions.joinToString(",")}" }+"\n")
            motion.rubbing?.let { rub ->
                file(root,"rubbing/${c.id}.csv").writeText("seconds,energy,pressure,precedingEnergy\n"+
                    rub.energy.indices.joinToString("\n") { i ->
                        "${i.toDouble()/rub.rate},${rub.energy[i]},${rub.pressure[i]},${rub.precedingEnergy.getOrNull(i)?:""}"
                    }+"\n")
            }
            val held=Cairn.isLoop(p.macros)
            val parity=if(held) {
                WavWriter.write(file(root,"continuation/${c.id}.wav"),Snip(r.full,1,Cairn.RATE))
                val reference=Cairn.inspect(p.voice,p.macros,p.midi,p.velocity,p.startAtContact,
                    Cairn.Options(disableCoupling=c.isolated,fullLoopPreroll=true),p.model)
                relative(r.samples,reference.samples).also { check(it<1e-5) { "${c.id} warm reference $it" } }
            } else null
            val pitchFrom=if(p.voice==CairnVoice.SING).6f else if(p.voice==CairnVoice.TUMBLE)
                (motion.events.last().seconds-(if(held)0.0 else d.outputStartFrame.toDouble()/Cairn.RATE)+.012).toFloat()
                else if(held).45f else .2f
            val pitch=Pitch.detect(Snip(r.samples,1,Cairn.RATE),fromSec=pitchFrom,
                windowSec=if(p.voice==CairnVoice.TUMBLE).12f else .25f)
            val classification=Classifier.classify(Snip(r.samples,1,Cairn.RATE)).drumClass.name
            if(p.voice==CairnVoice.SING) check(classification=="LOOP") { "${c.id} classified $classification" }
            val budget=motion.initialEnergy+motion.suppliedWork-motion.finalEnergy-motion.withdrawnWork-motion.dissipatedWork
            check(abs(budget)<1e-8 && d.rawPeak<2 && r.samples.all { it.isFinite() })
            val levels=(r.samples.indices step 882).map { begin ->
                sqrt((begin until min(begin+882,r.samples.size)).sumOf { r.samples[it].toDouble().pow(2) }/
                    min(882,r.samples.size-begin))
            }
            println("${index+1}/${cases.size} ${c.id} contacts=${motion.events.size} seam=${d.seamError}")
            mapOf("id" to c.id,"section" to c.section,"voice" to p.voice.name,"midi" to p.midi,
                "held" to held,"model" to p.model,"velocity" to p.velocity,"isolated" to c.isolated,"macros" to p.macros,
                "audio" to "audio/${c.id}.wav","sha256" to sha(wav.readBytes()),"seconds" to r.samples.size.toDouble()/Cairn.RATE,
                "cueSecondsRemoved" to if(held)0.0 else d.outputStartFrame.toDouble()/Cairn.RATE,
                "contactEvents" to motion.events.map { mapOf("seconds" to it.seconds,"stone" to it.stone,"work" to it.work) },
                "primaryContacts" to motion.events.count { it.stone==0 },"higherContacts" to motion.events.count { it.stone>0 },
                "initialEnergy" to motion.initialEnergy,"suppliedWork" to motion.suppliedWork,"finalEnergy" to motion.finalEnergy,
                "withdrawnWork" to motion.withdrawnWork,"dissipatedWork" to motion.dissipatedWork,"budgetResidual" to budget,
                "cycleResidual" to motion.cycleResidual,"settledCycles" to motion.settledCycles,
                "loopStartFrame" to d.loopStartFrame,"seamError" to d.seamError,"warmReferenceRelativeRms" to parity,
                "energyBound" to motion.rubbing?.energyBound,"renderMs" to ms,"mechanicsMs" to d.mechanicsMs,"audioMs" to d.audioMs,
                "peak" to r.samples.maxOf { abs(it) },"dc" to r.samples.average(),"rawPeak" to d.rawPeak,
                "classified" to classification,"filed" to Cairn.drumClassFor(p.voice,p.macros,p.velocity,p.startAtContact,p.model).name,
                "pitchHz" to pitch?.hz,"pitchConfidence" to pitch?.confidence,
                "pitchCents" to pitch?.let { 1200*log2(it.hz/d.requestedHz) },"levelStepSeconds" to .02,"levels" to levels)
        }
        fun phrase(id:String,seconds:Int,description:String,build:(FloatArray)->Unit):Map<String,Any> {
            val out=FloatArray(seconds*Cairn.RATE);build(out)
            val peak=out.maxOf { abs(it) };if(peak>.9f) for(i in out.indices)out[i]*=.9f/peak
            Dsp.fadeTail(out,rate=Cairn.RATE)
            val wav=file(root,"audio/$id.wav");WavWriter.write(wav,Snip(out,1,Cairn.RATE))
            return mapOf("id" to id,"audio" to "audio/$id.wav","sha256" to sha(wav.readBytes()),"seconds" to seconds,"description" to description)
        }
        fun mix(out:FloatArray,sample:FloatArray,seconds:Double,gain:Float) {
            val first=(seconds*Cairn.RATE).roundToInt()
            for(i in sample.indices) if(first+i in out.indices)out[first+i]+=sample[i]*gain
        }
        val examples=listOf(
            phrase("four-roles",16,"Four seconds each: punctuation, conversation, cascade, song.") { out ->
                for(i in 0..2)mix(out,Cairn.render(CairnVoice.KNOCK,midi=listOf(48,55,58)[i]).samples,i.toDouble(),.8f)
                mix(out,Cairn.renderLoop(CairnVoice.ANSWER).samples,4.0,.8f)
                for(i in 0..1)mix(out,Cairn.renderLoop(CairnVoice.TUMBLE,midi=if(i==0)60 else 67).samples,8.0+i*2,.75f)
                mix(out,Cairn.render(CairnVoice.SING,midi=60).samples,12.0,.8f)
            },
            phrase("sung-sketch",16,"SING carries a short melody over KNOCK, ANSWER and TUMBLE. All dry.") { out ->
                for((i,n) in listOf(60,67,65,62).withIndex())mix(out,Cairn.render(CairnVoice.SING,midi=n,velocity=.8f).samples,i*4.0,.7f)
                for(i in 0..7)mix(out,Cairn.render(CairnVoice.KNOCK,midi=if(i%2==0)48 else 55,velocity=.7f).samples,i*2.0,.35f)
                for(i in listOf(2,10))mix(out,Cairn.render(CairnVoice.ANSWER,midi=60,velocity=.65f).samples,i.toDouble(),.35f)
                for(i in listOf(6,14))mix(out,Cairn.render(CairnVoice.TUMBLE,midi=72,velocity=.6f).samples,i.toDouble(),.35f)
            })
        repeat(6) { Cairn.renderLoop(voice) }
        val bench=mutableListOf<Map<String,Any>>()
        for(held in listOf(false,true)) for(i in 0..11) {
            val m=Cairn.defaults(voice)+mapOf("SLOPE" to (.311f+i*.0113f),"HOLD" to if(held)1f else 0f)
            Cairn.clearMotionCache();started=System.nanoTime();val a=Cairn.inspect(voice,m)
            val ms=(System.nanoTime()-started)/1e6;started=System.nanoTime();val b=Cairn.inspect(voice,m,midi=62)
            bench+=mapOf("held" to held,"uncachedMs" to ms,"mechanicsMs" to a.diagnostics.mechanicsMs,
                "audioMs" to a.diagnostics.audioMs,"cachedMotionPitchMs" to (System.nanoTime()-started)/1e6,"cacheHit" to b.diagnostics.mechanicsCacheHit)
        }
        val rates=listOf(false,true).map { held ->
            val m=Cairn.defaults(voice)+("HOLD" to if(held)1f else 0f)
            val a=Cairn.inspect(voice,m,72);val b=Cairn.inspect(voice,m,72,options=Cairn.Options(oversample=8))
            mapOf("held" to held,"relativeRms4x8x" to relative(a.samples,b.samples).also { check(it<.04) })
        }
        val legacy=mutableListOf<Map<String,Any>>()
        for(i in args.indices) if(args[i]=="--legacy-root") {
            val source=File(args[i+1]);val old=Json.parse(File(source,"manifest.json").readText()).obj()
            var wavs=0;var latest=0
            for(clip in old.getValue("clips").arr()) {
                val c=clip.obj();val id=c.getValue("id").str()
                val saved=CairnPatch.fromJsonText(File(source,"recipes/$id.json").readText())
                val isolated=(c["isolated"]?:c["uncoupled"])?.bool()?:false
                val r=Cairn.inspect(saved.voice,saved.macros,saved.midi,saved.velocity,saved.startAtContact,
                    Cairn.Options(disableCoupling=isolated),saved.model)
                for((folder,audio,rate) in listOf(Triple("audio",r.samples,Cairn.RATE),Triple("full",r.full,Cairn.RATE),
                    Triple("raw",r.raw,r.diagnostics.internalRate),Triple("continuation",r.full,Cairn.RATE))) {
                    val original=File(source,"$folder/$id.wav");if(!original.exists())continue
                    val replay=file(root,"legacy-replay/${source.name}/$folder/$id.wav")
                    WavWriter.write(replay,Snip(audio,1,rate),allowNonMpcRate=rate!=Cairn.RATE)
                    check(sha(replay.readBytes())==sha(original.readBytes())) { "Earlier audio changed: ${source.name}/$folder/$id" }
                    wavs++
                }
                if(saved.model>=2) {
                    val current=Cairn.inspect(saved.voice,saved.macros,saved.midi,saved.velocity,saved.startAtContact,
                        Cairn.Options(disableCoupling=isolated),4)
                    check(current.samples.contentEquals(r.samples)) { "Model 4 changed ${source.name}/$id" };latest++
                }
            }
            legacy+=mapOf("source" to source.name,"cases" to old.getValue("clips").arr().size,"wavs" to wavs,"changed" to 0,"throughModel4" to latest)
        }
        file(root,"manifest.json").writeText(Json.write(json(mapOf(
            "title" to "Cairn / From touch to song","generatedAt" to Instant.now().toString(),"sourceRevision" to revision,
            "sourceSha256" to hashes,"model" to 4,"coldFirstSingHoldMs" to coldMs,"benchmark" to bench,
            "rateChecks" to rates,"clips" to rows,"examples" to examples,"legacy" to legacy,
            "scope" to "Four production Kotlin voices, C3-C5. SING uses an averaged regenerative rubbing analogy. Server timings; phone/MPC and listening acceptance remain open."
        ))))
        println("CAIRN SING wrote ${cases.size} comparisons and ${examples.size} dry phrases; cold HOLD $coldMs ms")
    }
    private fun relative(a:FloatArray,b:FloatArray)=sqrt(a.indices.sumOf { (a[it]-b[it]).toDouble().pow(2) }/b.sumOf { it.toDouble().pow(2) })
    private fun file(root:File,name:String)=File(root,name).apply { parentFile.mkdirs() }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value:Any?):JsonValue=when(value) {
        null->JsonValue.Null;is String->JsonValue.Str(value);is Boolean->JsonValue.Bool(value)
        is Number->JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*,*>->JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*>->JsonValue.Arr(value.map(::json));else->error("Unsupported manifest value")
    }
}
