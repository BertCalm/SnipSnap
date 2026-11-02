package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.*

/** Actual JVM renders for the second listening milestone; old audio lives at /reconstruction/. */
object CairnHoldAuditionGenerator {
    private val middle = mapOf("TUNE" to .5f,"WEIGHT" to .5f,"SLOPE" to .4f,"GROOVE" to .35f,
        "FRICTION" to .3f,"BODY" to .4f,"HOLD" to 0f)
    private data class Case(val id: String, val section: String, val patch: CairnPatch,
                            val uncoupled: Boolean = false)
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "../testkit/cairn-hold").canonicalFile
        root.mkdirs()
        val repo=generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it,".git").exists() }
        val revision=ProcessBuilder("git","rev-parse","HEAD").directory(repo).start().let {
            val s=it.inputStream.bufferedReader().readText().trim();check(it.waitFor()==0);s
        }
        val sources=listOf("Cairn.kt","CairnPatch.kt","Modes.kt").associateWith {
            sha(File(repo,"synth/src/main/kotlin/com/snipsnap/synth/$it").readBytes())
        }
        // First call includes initialization, cold simulation, analytic priming,
        // a real preceding segment, kept cycle and complete output stage.
        val coldStart=System.nanoTime()
        val cold=Cairn.renderLoop(CairnVoice.ANSWER)
        val coldMs=(System.nanoTime()-coldStart)/1e6
        if ("--cold-only" in args) {
            write(root,"cold.json",mapOf("firstAnswerHoldMs" to coldMs,
                "mechanicsMs" to cold.diagnostics.mechanicsMs,"audioMs" to cold.diagnostics.audioMs,
                "sourceRevision" to revision,"sourceSha256" to sources));return
        }
        val cases=mutableListOf<Case>()
        fun add(id:String,section:String,v:CairnVoice,m:Map<String,Float>,note:Int=60,
                velocity:Float=1f,model:Int=2,uncoupled:Boolean=false) {
            cases+=Case(id,section,CairnPatch(id,v,m,note,velocity,model=model),uncoupled)
        }
        for (v in listOf(CairnVoice.KNOCK, CairnVoice.ANSWER)) {
            val stem=v.name.lowercase();val d=Cairn.defaults(v)
            for (note in listOf(48,60,72)) for (hold in listOf(false,true))
                add("$stem-${if(hold)"hold" else "shot"}-$note","Voices",v,d+("HOLD" to if(hold)1f else 0f),note)
            for (hold in listOf(false,true)) add("$stem-middle-${if(hold)"hold" else "shot"}","Common controls",v,
                middle+("HOLD" to if(hold)1f else 0f))
            for (body in listOf(.25f,.75f)) for (friction in listOf(.25f,.75f))
                add("$stem-pair-${(body*100).roundToInt()}-${(friction*100).roundToInt()}","HOLD corners",v,
                    middle+mapOf("HOLD" to 1f,"BODY" to body,"FRICTION" to friction))
            for (velocity in listOf(.35f,.65f)) add("$stem-soft-${(velocity*100).roundToInt()}","HOLD velocity",v,
                d+("HOLD" to 1f),velocity=velocity)
            for (control in listOf("WEIGHT","SLOPE","GROOVE","FRICTION","BODY")) for (value in listOf(0f,1f))
                add("$stem-${control.lowercase()}-${value.toInt()}","HOLD edges",v,middle+mapOf("HOLD" to 1f,control to value))
        }
        for (note in listOf(48,60,72)) for (model in listOf(1,2))
            add("answer-light-$note-model$model","Light root",CairnVoice.ANSWER,middle+("WEIGHT" to 0f),note,model=model)
        add("answer-uncoupled","Cause and effect",CairnVoice.ANSWER,middle+("HOLD" to 1f),uncoupled=true)
        val rows=mutableListOf<Map<String,Any?>>()
        for ((i,c) in cases.withIndex()) {
            val p=c.patch;val start=System.nanoTime()
            val r=Cairn.inspect(p.voice,p.macros,p.midi,p.velocity,p.startAtContact,
                Cairn.Options(disableCoupling=c.uncoupled),p.model)
            val elapsed=(System.nanoTime()-start)/1e6;val d=r.diagnostics;val m=d.motion
            val wav=File(root,"audio/${c.id}.wav")
            WavWriter.write(wav,Snip(r.samples,1,Cairn.RATE))
            File(root,"recipes/${c.id}.json").apply { parentFile.mkdirs();writeText(p.toJsonText()) }
            File(root,"events/${c.id}.csv").apply { parentFile.mkdirs();writeText(
                "seconds,stone,groove,work,speedBefore,speedAfter\n"+m.events.joinToString("\n") {
                    "${it.seconds},${it.stone},${it.groove},${it.work},${it.speedBefore},${it.speedAfter}"
                }+"\n") }
            File(root,"preceding-events/${c.id}.csv").apply { parentFile.mkdirs();writeText(
                "seconds,stone,groove,work\n"+m.precedingEvents.joinToString("\n") {
                    "${it.seconds},${it.stone},${it.groove},${it.work}"
                }+"\n") }
            File(root,"traces/${c.id}.csv").apply { parentFile.mkdirs();writeText(
                "seconds,energy,withdrawnWork,primary,second,third,fourth,mount\n"+m.states.joinToString("\n") {
                    "${it.seconds},${it.energy},${it.withdrawnWork},${it.positions.joinToString(",")}"
                }+"\n") }
            val held=Cairn.isLoop(p.macros)
            if (held) WavWriter.write(File(root,"continuation/${c.id}.wav"),Snip(r.full,1,Cairn.RATE))
            val reference=if(held) Cairn.inspect(p.voice,p.macros,p.midi,p.velocity,p.startAtContact,
                Cairn.Options(disableCoupling=c.uncoupled,fullLoopPreroll=true),p.model) else null
            val continuationError=reference?.let { ref -> sqrt(r.samples.indices.sumOf {
                (r.samples[it]-ref.samples[it]).toDouble().pow(2)
            } / r.samples.sumOf { it.toDouble().pow(2) }) }
            if (held && p.velocity > 0f) check(continuationError!! < 1e-5)
            val cue=if(held)0.0 else d.outputStartFrame.toDouble()/Cairn.RATE
            val pitch=Pitch.detect(Snip(r.samples,1,Cairn.RATE),fromSec=(.2+if(held) .07 else 0.0).toFloat(),windowSec=.25f)
            val row=mapOf("id" to c.id,"voice" to p.voice.name,"section" to c.section,"held" to held,
                "midi" to p.midi,"model" to p.model,"velocity" to p.velocity,"macros" to Cairn.settled(p.voice,p.macros),
                "uncoupled" to c.uncoupled,"audio" to "audio/${c.id}.wav","sha256" to sha(wav.readBytes()),
                "seconds" to r.samples.size.toDouble()/Cairn.RATE,"cueSecondsRemoved" to cue,
                "primaryContacts" to m.events.count { it.stone==0 },"higherContacts" to m.events.count { it.stone>0 },
                "contactEvents" to m.events.map { mapOf("seconds" to it.seconds,"stone" to it.stone,"work" to it.work) },
                "suppliedWork" to m.suppliedWork,"initialEnergy" to m.initialEnergy,"finalEnergy" to m.finalEnergy,
                "withdrawnWork" to m.withdrawnWork,"dissipatedWork" to m.dissipatedWork,"brakeWork" to m.terminalBrakeWork,
                "largestEnergyIncrease" to m.largestEnergyIncrease,"cycleResidual" to m.cycleResidual,
                "settledCycles" to m.settledCycles,"seamError" to d.seamError,"loopStartFrame" to d.loopStartFrame,
                "fullPrerollReferenceRelativeRms" to continuationError,
                "precedingSegmentSeconds" to d.loopStartFrame.toDouble()/Cairn.RATE,
                "renderMs" to elapsed,"mechanicsMs" to d.mechanicsMs,"audioMs" to d.audioMs,
                "peak" to r.samples.maxOf { abs(it) },"dc" to r.samples.average(),
                "loudness" to Loudness.of(Snip(r.samples,1,Cairn.RATE)),"rawPeak" to d.rawPeak,
                "requestedHz" to d.requestedHz,"pitchHz" to pitch?.hz,"pitchConfidence" to pitch?.confidence,
                "pitchCents" to pitch?.let { 1200*log2(it.hz/d.requestedHz) })
            rows+=row
            println("CAIRN HOLD ${i+1}/${cases.size} ${c.id}: ${d.seamError ?: "one-shot"}")
        }
        // A dry 120 BPM example uses exported audio as a producer would: a
        // repeating ANSWER C4 cradle under independently triggered bass notes.
        val example=FloatArray(16*Cairn.RATE)
        val answer=Cairn.renderLoop(CairnVoice.ANSWER).samples
        for(i in example.indices) example[i]=answer[i%answer.size]*.70f
        for((i,note) in listOf(48,48,55,58,48,51,55,53).withIndex()) {
            val shot=Cairn.render(CairnVoice.KNOCK,midi=note,velocity=if(i%2==0).85f else .6f).samples
            val start=i*2*Cairn.RATE
            for(j in shot.indices) if(start+j<example.size)example[start+j]+=shot[j]*.55f
        }
        Dsp.levelTo(example,Cairn.RATE,Dsp.MELODIC_LOUDNESS_TARGET);Dsp.fadeTail(example,rate=Cairn.RATE)
        val exampleFile=File(root,"audio/dry-example.wav")
        WavWriter.write(exampleFile,Snip(example,1,Cairn.RATE))
        // Warm controls are genuinely new motion keys. Cache-on pitch turns
        // reuse only the motion; no finished sample or modal state is cached.
        repeat(4) { Cairn.renderLoop(CairnVoice.ANSWER);Cairn.renderLoop(CairnVoice.KNOCK) }
        val bench=mutableListOf<Map<String,Any?>>()
        for(v in listOf(CairnVoice.KNOCK, CairnVoice.ANSWER)) for(held in listOf(false,true)) repeat(12) { n ->
            val controls=middle+mapOf("SLOPE" to (.341f+n*.0093f),"HOLD" to if(held)1f else 0f)
            Cairn.clearMotionCache()
            var start=System.nanoTime();val fresh=Cairn.inspect(v,controls)
            val uncached=(System.nanoTime()-start)/1e6
            start=System.nanoTime();val tuned=Cairn.inspect(v,controls,midi=62)
            bench+=mapOf("voice" to v.name,"held" to held,"uncachedMs" to uncached,
                "cachedMotionPitchMs" to (System.nanoTime()-start)/1e6,"mechanicsMs" to fresh.diagnostics.mechanicsMs,
                "audioMs" to fresh.diagnostics.audioMs,"cacheHit" to tuned.diagnostics.mechanicsCacheHit)
        }
        val rateChecks=listOf(CairnVoice.KNOCK, CairnVoice.ANSWER).map { v ->
            val a=Cairn.inspect(v,middle,midi=72);val b=Cairn.inspect(v,middle,midi=72,options=Cairn.Options(oversample=8))
            val n=min(a.samples.size,b.samples.size)
            val relative=sqrt((0 until n).sumOf { (a.samples[it]-b.samples[it]).toDouble().pow(2) }/
                (0 until n).sumOf { a.samples[it].toDouble().pow(2) })
            check(relative<.08)
            mapOf("voice" to v.name,"relativeRms4x8x" to relative,"midi" to 72,"held" to true)
        }
        write(root,"manifest.json",mapOf("title" to "Cairn / Continuing gestures","generatedAt" to Instant.now().toString(),
            "sourceRevision" to revision,"sourceSha256" to sources,"model" to 2,
            "coldFirstAnswerHoldMs" to coldMs,"benchmark" to bench,"rateChecks" to rateChecks,"clips" to rows,
            "example" to mapOf("id" to "dry-example","audio" to "audio/dry-example.wav","seconds" to 16,
                "sha256" to sha(exampleFile.readBytes()),"description" to "120 BPM, dry. ANSWER C4 HOLD with KNOCK bass notes. No effects."),
            "scope" to "Two voices, C3-C5. Real mechanically powered HOLD. Server JVM measurements; phone and MPC checks open."))
        println("CAIRN wrote ${cases.size} comparison clips and one dry example; first ANSWER HOLD $coldMs ms")
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun write(root:File,name:String,value:Any?)=File(root,name).writeText(Json.write(json(value)))
    private fun json(value:Any?):JsonValue=when(value) {
        null->JsonValue.Null;is String->JsonValue.Str(value);is Boolean->JsonValue.Bool(value)
        is Number->JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*,*>->JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*>->JsonValue.Arr(value.map(::json));else->error("Unsupported manifest value")
    }
}
