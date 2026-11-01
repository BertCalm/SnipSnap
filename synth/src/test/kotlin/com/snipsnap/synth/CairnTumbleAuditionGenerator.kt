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

/** The three-voice listening gate. Every clip is an actual production Kotlin render. */
object CairnTumbleAuditionGenerator {
    private val middle=mapOf("TUNE" to .5f,"WEIGHT" to .5f,"SLOPE" to .4f,"GROOVE" to .35f,
        "FRICTION" to .3f,"BODY" to .4f,"HOLD" to 0f)
    private data class Case(val id:String,val section:String,val patch:CairnPatch,val isolated:Boolean=false)
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull() ?: "../testkit/cairn-tumble").canonicalFile
        root.mkdirs()
        val repo=generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it,".git").exists() }
        val revision=ProcessBuilder("git","rev-parse","HEAD").directory(repo).start().let {
            val text=it.inputStream.bufferedReader().readText().trim();check(it.waitFor()==0);text
        }
        val hashes=listOf("Cairn.kt","CairnPatch.kt","CairnTumble.kt","Modes.kt").associateWith {
            sha(File(repo,"synth/src/main/kotlin/com/snipsnap/synth/$it").readBytes())
        }
        val coldStart=System.nanoTime();Cairn.renderLoop(CairnVoice.TUMBLE)
        val coldMs=(System.nanoTime()-coldStart)/1e6
        val cases=mutableListOf<Case>()
        fun add(id:String,section:String,v:CairnVoice,m:Map<String,Float>,note:Int=60,velocity:Float=1f,isolated:Boolean=false) {
            cases+=Case(id,section,CairnPatch(id,v,m,note,velocity),isolated)
        }
        for(v in CairnVoice.entries) for(held in listOf(false,true)) {
            val mode=if(held)"hold" else "shot";val flag=if(held)1f else 0f
            for(note in listOf(48,60,72)) add("${v.name.lowercase()}-$mode-$note","Voices",v,Cairn.defaults(v)+("HOLD" to flag),note)
            add("${v.name.lowercase()}-common-$mode","Common controls",v,middle+("HOLD" to flag))
        }
        val v=CairnVoice.TUMBLE
        for(held in listOf(false,true)) {
            val mode=if(held)"hold" else "shot";val base=Cairn.defaults(v)+("HOLD" to if(held)1f else 0f)
            for(control in listOf("WEIGHT","SLOPE","GROOVE","FRICTION","BODY")) for(value in listOf(0f,.5f,1f))
                add("tumble-${control.lowercase()}-${(value*100).roundToInt()}-$mode","TUMBLE controls",v,base+(control to value))
            for(body in listOf(.25f,.75f)) for(friction in listOf(.25f,.75f))
                add("tumble-pair-${(body*100).roundToInt()}-${(friction*100).roundToInt()}-$mode","TUMBLE corners",v,
                    base+mapOf("BODY" to body,"FRICTION" to friction))
            for(velocity in listOf(.35f,.65f)) add("tumble-soft-${(velocity*100).roundToInt()}-$mode","TUMBLE velocity",v,base,velocity=velocity)
            add("tumble-isolated-$mode","Cause and effect",v,base,isolated=true)
            for(edge in listOf(0f,1f)) add("tumble-edge-${edge.toInt()}-$mode","TUMBLE extremes",v,
                base.mapValues { if(it.key in listOf("TUNE","HOLD"))it.value else edge })
        }
        val rows=cases.mapIndexed { index,c ->
            val p=c.patch;val start=System.nanoTime()
            val r=Cairn.inspect(p.voice,p.macros,p.midi,p.velocity,p.startAtContact,Cairn.Options(disableCoupling=c.isolated),p.model)
            val ms=(System.nanoTime()-start)/1e6;val d=r.diagnostics;val motion=d.motion
            val wav=File(root,"audio/${c.id}.wav");WavWriter.write(wav,Snip(r.samples,1,Cairn.RATE))
            file(root,"recipes/${c.id}.json").writeText(p.toJsonText())
            file(root,"events/${c.id}.csv").writeText("seconds,stone,groove,work,speedBefore,speedAfter\n"+
                motion.events.joinToString("\n") { "${it.seconds},${it.stone},${it.groove},${it.work},${it.speedBefore},${it.speedAfter}" }+"\n")
            file(root,"traces/${c.id}.csv").writeText("seconds,energy,withdrawnWork,primary,second,third,fourth,mount\n"+
                motion.states.joinToString("\n") { "${it.seconds},${it.energy},${it.withdrawnWork},${it.positions.joinToString(",")}" }+"\n")
            val held=Cairn.isLoop(p.macros)
            file(root,"preceding-events/${c.id}.csv").writeText("seconds,stone,work\n"+
                motion.precedingEvents.joinToString("\n") { "${it.seconds},${it.stone},${it.work}" }+"\n")
            val parity=if(held) {
                WavWriter.write(file(root,"continuation/${c.id}.wav"),Snip(r.full,1,Cairn.RATE))
                val reference=Cairn.inspect(p.voice,p.macros,p.midi,p.velocity,p.startAtContact,
                    Cairn.Options(disableCoupling=c.isolated,fullLoopPreroll=true),p.model)
                relative(r.samples,reference.samples).also { check(it<1e-5) }
            } else null
            val pitchStart=if(p.voice==CairnVoice.TUMBLE)
                (motion.events.last().seconds-(if(held)0.0 else d.outputStartFrame.toDouble()/Cairn.RATE)+.012).toFloat()
                else if(held).45f else .2f
            val pitch=Pitch.detect(Snip(r.samples,1,Cairn.RATE),fromSec=pitchStart,
                windowSec=if(p.voice==CairnVoice.TUMBLE).12f else .25f)
            val budget=motion.initialEnergy+motion.suppliedWork-motion.finalEnergy-motion.withdrawnWork-motion.dissipatedWork
            check(abs(budget)<1e-8 && d.rawPeak<2 && r.samples.all { it.isFinite() })
            val gaps=motion.events.zipWithNext { a,b -> b.seconds-a.seconds }
            println("CAIRN THREE ${index+1}/${cases.size} ${c.id}: ${motion.events.size} contacts, seam=${d.seamError}")
            mapOf("id" to c.id,"section" to c.section,"voice" to p.voice.name,"midi" to p.midi,
                "held" to held,"model" to p.model,"velocity" to p.velocity,"isolated" to c.isolated,"macros" to p.macros,
                "audio" to "audio/${c.id}.wav","sha256" to sha(wav.readBytes()),"seconds" to r.samples.size.toDouble()/Cairn.RATE,
                "cueSecondsRemoved" to if(held)0.0 else d.outputStartFrame.toDouble()/Cairn.RATE,
                "primaryContacts" to motion.events.count { it.stone==0 },"higherContacts" to motion.events.count { it.stone>0 },
                "contactEvents" to motion.events.map { mapOf("seconds" to it.seconds,"stone" to it.stone,"work" to it.work) },
                "firstGapSeconds" to gaps.firstOrNull(),"lastGapSeconds" to gaps.lastOrNull(),
                "initialEnergy" to motion.initialEnergy,"suppliedWork" to motion.suppliedWork,"finalEnergy" to motion.finalEnergy,
                "withdrawnWork" to motion.withdrawnWork,"dissipatedWork" to motion.dissipatedWork,"capturedWork" to motion.terminalBrakeWork,"budgetResidual" to budget,
                "largestEnergyIncrease" to motion.largestEnergyIncrease,"cycleResidual" to motion.cycleResidual,
                "settledCycles" to motion.settledCycles,"loopStartFrame" to d.loopStartFrame,"seamError" to d.seamError,
                "fullPrerollReferenceRelativeRms" to parity,"renderMs" to ms,"mechanicsMs" to d.mechanicsMs,"audioMs" to d.audioMs,
                "peak" to r.samples.maxOf { abs(it) },"dc" to r.samples.average(),"rawPeak" to d.rawPeak,
                "classified" to Classifier.classify(Snip(r.samples,1,Cairn.RATE)).drumClass.name,
                "filed" to Cairn.drumClassFor(p.voice,p.macros,p.velocity,p.startAtContact,p.model).name,
                "pitchHz" to pitch?.hz,"pitchConfidence" to pitch?.confidence,
                "pitchCents" to pitch?.let { 1200*log2(it.hz/d.requestedHz) })
        }
        // The first phrase gives each role four seconds in isolation. The
        // second is a dry 120 BPM arrangement, with TUMBLE entering as a fill.
        fun saveExample(id:String,description:String,seconds:Int,arrange:(FloatArray)->Unit):Map<String,Any> {
            val out=FloatArray(seconds*Cairn.RATE);arrange(out)
            Dsp.levelTo(out,Cairn.RATE,Dsp.MELODIC_LOUDNESS_TARGET);Dsp.fadeTail(out,rate=Cairn.RATE)
            val wav=file(root,"audio/$id.wav");WavWriter.write(wav,Snip(out,1,Cairn.RATE))
            return mapOf("id" to id,"audio" to "audio/$id.wav","sha256" to sha(wav.readBytes()),
                "description" to description,"seconds" to seconds)
        }
        fun mix(out:FloatArray,samples:FloatArray,seconds:Double,gain:Float) {
            val start=(seconds*Cairn.RATE).roundToInt()
            for(i in samples.indices) if(start+i in out.indices) out[start+i]+=samples[i]*gain
        }
        val examples=listOf(
            saveExample("three-roles","12 seconds, dry: KNOCK alone, ANSWER alone, then TUMBLE alone. Four seconds each.",12) { out ->
                for((i,note) in listOf(48,55,58).withIndex()) mix(out,Cairn.render(CairnVoice.KNOCK,midi=note).samples,i.toDouble(),.85f)
                mix(out,Cairn.renderLoop(CairnVoice.ANSWER).samples,4.0,.8f)
                for(i in 0..1)mix(out,Cairn.renderLoop(CairnVoice.TUMBLE,midi=if(i==0)60 else 67).samples,8.0+2*i,.8f)
            },
            saveExample("dry-sketch","16 seconds, 120 BPM, dry: KNOCK bass punctuation, ANSWER above, TUMBLE fills in bars four and eight.",16) { out ->
                val answer=Cairn.renderLoop(CairnVoice.ANSWER).samples
                for(i in 0..3)mix(out,answer,4.0*i,.55f)
                for((i,note) in listOf(48,48,55,58,48,51,55,53).withIndex())
                    mix(out,Cairn.render(CairnVoice.KNOCK,midi=note,velocity=if(i%2==0).85f else .6f).samples,2.0*i,.55f)
                for(i in listOf(6,14))mix(out,Cairn.render(CairnVoice.TUMBLE,midi=72,velocity=.8f).samples,i.toDouble(),.65f)
            })
        repeat(4) { Cairn.renderLoop(CairnVoice.TUMBLE) }
        val bench=mutableListOf<Map<String,Any>>()
        for(held in listOf(false,true)) repeat(12) { n ->
            val m=Cairn.defaults(v)+mapOf("SLOPE" to (.341f+n*.0093f),"HOLD" to if(held)1f else 0f)
            Cairn.clearMotionCache();var start=System.nanoTime();val fresh=Cairn.inspect(v,m)
            val ms=(System.nanoTime()-start)/1e6;start=System.nanoTime();val tuned=Cairn.inspect(v,m,midi=62)
            bench+=mapOf("voice" to "TUMBLE","held" to held,"uncachedMs" to ms,
                "mechanicsMs" to fresh.diagnostics.mechanicsMs,"audioMs" to fresh.diagnostics.audioMs,
                "cachedMotionPitchMs" to (System.nanoTime()-start)/1e6,"cacheHit" to tuned.diagnostics.mechanicsCacheHit)
        }
        val rateChecks=listOf(false,true).map { held ->
            val m=Cairn.defaults(v)+("HOLD" to if(held)1f else 0f)
            val a=Cairn.inspect(v,m,72);val b=Cairn.inspect(v,m,72,options=Cairn.Options(oversample=8))
            mapOf("voice" to "TUMBLE","held" to held,"midi" to 72,"relativeRms4x8x" to relative(a.samples,b.samples).also { check(it<.08) })
        }
        val legacy=if("--legacy-hold" in args) {
            val source=File(args[args.indexOf("--legacy-hold")+1]);val old=Json.parse(File(source,"manifest.json").readText()).obj()
            val clips=old.getValue("clips").arr()
            for(clip in clips) {
                val c=clip.obj();val id=c.getValue("id").str()
                val saved=CairnPatch.fromJsonText(File(source,"recipes/$id.json").readText())
                val p=if(saved.model==1)saved else saved.copy(model=3)
                val r=Cairn.inspect(p.voice,p.macros,p.midi,p.velocity,p.startAtContact,
                    Cairn.Options(disableCoupling=c.getValue("uncoupled").bool()),p.model)
                val wav=file(root,"legacy-replay/$id.wav");WavWriter.write(wav,Snip(r.samples,1,Cairn.RATE))
                val expected=sha(wav.readBytes())
                check(expected==c.getValue("sha256").str()) { "Preserved HOLD clip changed: $id" }
            }
            mapOf("replayed" to clips.size,"changed" to 0)
        } else null
        file(root,"manifest.json").writeText(Json.write(json(mapOf(
            "title" to "Cairn / Punctuation, conversation, cascade","generatedAt" to Instant.now().toString(),
            "sourceRevision" to revision,"sourceSha256" to hashes,"model" to 3,"coldFirstTumbleHoldMs" to coldMs,
            "benchmark" to bench,"rateChecks" to rateChecks,"clips" to rows,"examples" to examples,"legacyHold" to legacy,
            "scope" to "Three Kotlin voices, C3-C5; TUMBLE is a new unilateral rebound model. Free-running HOLD at reference 120 BPM. Server timings, no phone/MPC acceptance."
        ))))
        println("CAIRN wrote ${cases.size} comparisons and ${examples.size} dry phrases; cold TUMBLE HOLD $coldMs ms")
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
