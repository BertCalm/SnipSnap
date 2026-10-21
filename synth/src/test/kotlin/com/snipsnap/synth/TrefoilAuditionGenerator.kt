package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Only real Kotlin renders enter this pack. Each diagnostic records its own replay options. */
object TrefoilAuditionGenerator {
    private data class Case(val id: String,val title: String,val section: String,val description: String,
        val question: String,val patch: TrefoilPatch,val options: Trefoil.Options=Trefoil.Options(),
        val stem: Int=-1,val reference: String?=null,val group: String?=null,val sweep: String?=null,val step: Float?=null)
    private fun cases(): List<Case> {
        val out=mutableListOf<Case>()
        fun p(v: TrefoilVoice=TrefoilVoice.WEAVE,m: Map<String,Float> = emptyMap(),seed: Long=43)=
            TrefoilPatch("Study",v,Trefoil.defaults(v)+m,seed=seed)
        val ordered=p(); val tangled=p(TrefoilVoice.TANGLE,seed=27)
        val main=listOf(
            Triple(ordered,"ordered","Ordered weave"),Triple(p(TrefoilVoice.HOLLOW),"hollow","Hollow exchange"),
            Triple(p(TrefoilVoice.WIRE),"wire","Wire contact"),Triple(tangled,"unsettled","Unsettled threads"),
            Triple(p(TrefoilVoice.TRACE),"trace","After the encounter"))
        val descriptions=listOf("An unperturbed choreography shared by three pitched bodies.","Rounded contact and lower modes, with room for the exchanges to ring.",
            "Brighter friction and upper modes in the same resonator network.","One initial disturbance changes the meetings. This finite clip makes no escape claim.",
            "Sparse contact leaves a longer sympathetic trace.")
        for((i,t) in main.withIndex()) out+=Case(t.second,t.third,"First listen",descriptions[i],
            "Can you hear three changing contributions within one stable note?",t.first.copy(name=t.third))
        for((label,patch) in listOf("ordered" to ordered,"unsettled" to tangled)) {
            val title=if(label=="ordered") "Ordered" else "Perturbed"
            val base="$label-live"
            out+=Case(base,"$title · moving, exchanging","Comparisons","Geometry controls contact and actual resonator-state exchange.",
                "Does the live relationship change more than the overall volume?",patch,group=label)
            out+=Case("$label-frozen","$title · frozen geometry","Comparisons","The starting descriptor snapshot stays fixed; tuning, excitation laws and seed are unchanged.",
                "What movement disappears when geometry stops?",patch,Trefoil.Options(frozen=true),reference=base,group=label)
            out+=Case("$label-independent","$title · exchange off","Comparisons","Trajectory-driven excitation remains, while inter-participant state transfer is exactly zero.",
                "What does exchange add beyond motion in the exciters?",patch.copy(macros=patch.macros+("EXCHANGE" to 0f)),reference=base,group=label)
            out+=Case("$label-no-memory","$title · memory off","Comparisons","Only the three core bodies remain; the sympathetic send is disabled.",
                "Can you hear previous contacts lingering in the full version?",patch.copy(macros=patch.macros+("MEMORY" to 0f)),reference=base,group=label)
        }
        out+=Case("stem-mix","All three + memory","Stems","The complete ordered mix, followed by its additive contributions.","Does any participant permanently dominate?",ordered,Trefoil.Options(stems=true),group="stems")
        for(b in 0..3) out+=Case("stem-$b",listOf("A · rounded body","B · wiry body","C · fine body","Shared sympathetic trace")[b],"Stems",
            "Isolated observer contribution from the same live network; it keeps the full mix's gain.","How does this thread change as the contact pattern turns?",
            ordered,Trefoil.Options(stems=true),stem=b,reference="stem-mix",group="stems")
        val impulse=p(m=mapOf("MEMORY" to 0f,"EXCHANGE" to .9f)).copy(seconds=3f,activeSeconds=.2f)
        for(on in listOf(true,false)) {
            val prefix=if(on) "transfer-on" else "transfer-off"
            val ip=impulse.copy(macros=impulse.macros+("EXCHANGE" to if(on) .9f else 0f))
            val opts=Trefoil.Options(impulseOnly=true,excitedParticipant=0,memoryEnabled=false,stems=true)
            out+=Case(prefix,if(on) "Strike A · exchange on" else "Strike A · exchange off","Transfer proof",
                "Only A receives a finite strike. B and C have no exciters; memory is disabled.","Does energy arrive in the unstruck bodies?",ip,opts,
                reference=if(on) null else "transfer-on",group="transfer")
            for(b in 0..2) out+=Case("$prefix-$b","${if(on) "On" else "Off"} · ${'A'+b} alone","Transfer proof",
                if(b==0) "The struck body, at the full diagnostic mix's gain." else "An unstruck receiver. With exchange off this file is exactly silent.",
                "Listen for transferred ringing, or the exact silence of the disconnected receiver.",ip,opts,b,"transfer-on",group="transfer")
        }
        for(k in listOf("WEAVE","NUDGE","EXCHANGE","THREAD","MEMORY")) for(s in listOf(0f,.25f,.5f,.75f,1f)) {
            val patch=ordered.copy(name="$k ${s*100}%",macros=ordered.macros+(k to s))
            out+=Case("${k.lowercase()}-${(s*100).roundToInt()}","$k · ${(s*100).roundToInt()}%","Control sweeps",
                "Only $k changes; note, velocity, remaining controls and seed stay fixed.","Is each step musically different across the whole gesture?",patch,sweep=k,step=s)
        }
        for(note in listOf(36,60,84)) for(velocity in listOf(.3f,.8f,1f)) out+=Case("note-$note-${(velocity*100).toInt()}",
            "${Scales.nameOf(note)} · velocity ${(velocity*100).toInt()}","Notes & touch","The same ordered trajectory at another note and playing strength.",
            "Does the note stay clear while touch changes the contact?",ordered.copy(midi=note,velocity=velocity))
        out+=Case("fast","Fast weave","Extremes","At eight reference cycles per second, contacts merge toward timbre.",
            "Does rapid exchange retain a pitched body?",ordered.copy(macros=ordered.macros+("WEAVE" to 1f)))
        out+=Case("high","All controls high","Extremes","A bounded stress example. A close encounter may end excitation early; the diagnostic records it.",
            "Does the extreme remain useful and pitched?",ordered.copy(macros=ordered.macros.mapValues { if(it.key=="HOLD") 0f else 1f },seed=27))
        return out
    }
    private fun json(value: Any?): JsonValue = when(value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> if(value.toDouble().isFinite()) JsonValue.Num(value.toDouble()) else JsonValue.Null
        is Map<*,*> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        is DoubleArray -> JsonValue.Arr(value.map(::json))
        is FloatArray -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported JSON ${value::class}")
    }
    private fun write(f: File,value: Any?) { f.parentFile.mkdirs(); f.writeText(Json.write(json(value))) }
    private fun hash(f: File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun git(repo: File,vararg args: String)=ProcessBuilder(listOf("git")+args).directory(repo).start().let {
        val s=it.inputStream.bufferedReader().readText().trim(); check(it.waitFor()==0); s }
    private fun options(o: Trefoil.Options,stem: Int)=mapOf("frozen" to o.frozen,"impulseOnly" to o.impulseOnly,
        "excitedParticipant" to o.excitedParticipant,"memoryEnabled" to o.memoryEnabled,"stem" to stem)
    private fun select(p: Trefoil.Probe,stem: Int)=when(stem) { -1 -> p.raw; 3 -> p.memory; else -> p.stems[stem] }
    private fun waveform(s: FloatArray)=List(100) { b ->
        val lo=b*s.size/100; val hi=(b+1)*s.size/100
        (lo until hi).maxOfOrNull { abs(s[it]) } ?: 0f }
    @JvmStatic fun main(args: Array<String>) {
        if(args.firstOrNull()=="--replay") { replay(args.drop(1)); return }
        val root=File(args.firstOrNull() ?: "../testkit/trefoil-audition").canonicalFile; root.mkdirs()
        val repo=generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }.first { File(it,".git").exists() }
        val quick=System.getProperty("trefoil.quick")=="true"
        val cases=cases().let { if(quick) it.filter { c -> c.section in setOf("First listen","Transfer proof") } else it }
        val revision=git(repo,"rev-parse","HEAD"); val dirty=git(repo,"status","--porcelain").isNotBlank()
        val clips=mutableListOf<Map<String,Any?>>()
        val gains=mutableMapOf<String,Float>(); val records=mutableMapOf<String,FloatArray>()
        var lastKey=""; var lastProbe: Trefoil.Probe?=null
        for((index,c) in cases.withIndex()) {
            val opts=c.options.copy(stems=c.options.stems || c.stem>=0)
            val key=c.patch.toJsonText()+opts.toString()
            val p=if(key==lastKey) lastProbe!! else Trefoil.probe(c.patch,opts).also { lastKey=key; lastProbe=it }
            val selected=select(p,c.stem); val rawSnip=Snip(selected,1,Dsp.RATE)
            val loud=Loudness.of(rawSnip); val peak=selected.maxOf { abs(it) }
            val gain=c.reference?.let { gains.getValue(it) } ?: min(.03f/loud.coerceAtLeast(1e-12f),.95f/peak.coerceAtLeast(1e-12f))
            gains[c.id]=gain
            val matched=Snip(FloatArray(selected.size) { selected[it]*gain },1,Dsp.RATE)
            check(p.diagnostics.finite && selected.all(Float::isFinite) && matched.peak()<.991f) { "invalid audio ${c.id}" }
            check(p.diagnostics.unforcedRise<1e-10 && p.diagnostics.coreMaxEnergy<=p.diagnostics.positiveWork+1e-9) { "passivity ${c.id}" }
            check(p.diagnostics.positiveWork<=p.diagnostics.workBudget+1e-9) { "excitation budget ${c.id}" }
            check(p.diagnostics.trajectory.energyDrift<1e-6) { "trajectory conservation ${c.id}: ${p.diagnostics.trajectory.energyDrift}" }
            val rawFile=File(root,"audio/${c.id}-raw.wav"); val matchedFile=File(root,"audio/${c.id}-matched.wav")
            WavWriter.write(rawFile,rawSnip,WavWriter.BitDepth.PCM_16); WavWriter.write(matchedFile,matched,WavWriter.BitDepth.PCM_16)
            val d=p.diagnostics; val t=d.trajectory
            val measures=LinkedHashMap(TangleMeasure.metrics(selected,matched,c.patch.midi,d.synthesisMs))
            val onset=Classifier.classify(Snip(matched.samples.copyOf(min(matched.samples.size,(1.2*Dsp.RATE).toInt())),1,Dsp.RATE))
            measures["onsetClassifier"]=onset.drumClass.name
            measures["lengthNeutralClassifier"]=Classifier.classify(FeatureExtractor.extract(matched).copy(durationSeconds=1.2f)).drumClass.name
            measures["lengthNeutralClassifierPolicy"]="Full spectral/envelope evidence, only duration capped to bypass the generic LOOP shortcut; prefix result remains separately reported"
            measures["coreMaxEnergy"]=d.coreMaxEnergy; measures["coreFinalEnergy"]=d.coreFinalEnergy
            measures["positiveWork"]=d.positiveWork; measures["workBudget"]=d.workBudget; measures["unforcedRise"]=d.unforcedRise
            measures["participantPeakEnergy"]=d.participantEnergy; measures["scaledEnergyDrift"]=t.energyDrift
            measures["momentumDrift"]=t.momentumDrift; measures["comDrift"]=t.comDrift; measures["angularDrift"]=t.angularDrift
            measures["solverEvaluations"]=t.evaluations; measures["acceptedSteps"]=t.acceptedSteps; measures["minSeparation"]=t.minSeparation
            measures["trajectoryOutcome"]=t.outcome; measures["observedSimulatedTime"]=t.reached; measures["matchingGain"]=gain
            measures["gainPolicy"]=if(c.reference==null) "Loudest 200ms RMS 0.03, peak guard" else "Same fixed gain as ${c.reference}; no independent stem normalization"
            c.reference?.let { ref -> records[ref]?.let { measures["referenceShapeDistance"]=TangleMeasure.distance(it,selected) } }
            if(c.id in cases.mapNotNull { it.reference }.toSet()) records[c.id]=selected
            val note=Scales.nameOf(c.patch.midi)
            val recipe=mapOf("patch" to c.patch.toJsonValue(),"options" to options(c.options,c.stem),"matchedGain" to gain,
                "modelDescription" to "Finite TREFOIL model 1; no HOLD or escape classification")
            write(File(root,"recipes/${c.id}.json"),recipe)
            val trajectoryName="traces/${c.id}.json"
            val traceFrames=p.trace.states.indices.filter { it%10==0 || it==p.trace.states.lastIndex }.map { i ->
                val state=p.trace.states[i]; listOf(i.toDouble()/(p.trace.states.size-1)*c.patch.seconds)+state.take(6) }
            write(File(root,trajectoryName),mapOf("frames" to traceFrames,"frozen" to c.options.frozen,
                "energy" to d.energy.map { listOf(it.seconds,it.core,it.memory,it.positiveWork) }))
            write(File(root,"diagnostics/${c.id}.json"),measures)
            clips+=linkedMapOf("id" to c.id,"title" to c.title,"section" to c.section,"description" to c.description,
                "question" to c.question,"voice" to c.patch.voice.name,"note" to note,"midi" to c.patch.midi,
                "velocity" to c.patch.velocity,"seconds" to c.patch.seconds,"activeSeconds" to c.patch.activeSeconds,
                "seed" to c.patch.seed.toString(),"macros" to c.patch.macros,"group" to c.group,"reference" to c.reference,
                "sweep" to c.sweep,"step" to c.step,"raw" to "audio/${c.id}-raw.wav","matched" to "audio/${c.id}-matched.wav",
                "rawSha256" to hash(rawFile),"matchedSha256" to hash(matchedFile),"recipe" to "recipes/${c.id}.json",
                "trajectory" to trajectoryName,"diagnostics" to "diagnostics/${c.id}.json","metrics" to measures,"waveform" to waveform(selected))
            println("TREFOIL ${index+1}/${cases.size} ${c.id} peak=${matched.peak()} Edrift=${t.energyDrift} pitch=${measures["pitchHz"]}")
        }
        for(name in listOf("index.html","app.js","styles.css","favicon.svg")) {
            val input=TrefoilAuditionGenerator::class.java.getResourceAsStream("/trefoil-audition/$name") ?: error("missing page resource $name")
            input.use { stream -> File(root,name).outputStream().use { stream.copyTo(it) } }
        }
        val sources=listOf("synth/src/main/kotlin/com/snipsnap/synth/Trefoil.kt","synth/src/main/kotlin/com/snipsnap/synth/TrefoilPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/TrefoilTrajectory.kt","synth/src/test/kotlin/com/snipsnap/synth/TrefoilTest.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/TrefoilAuditionGenerator.kt","synth/build.gradle.kts","docs/TREFOIL.md",
            "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt","synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt","synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "scripts/trefoil_refine_reference.py","scripts/trefoil_browser_check.py")+
            listOf("index.html","app.js","styles.css","favicon.svg").map { "synth/src/test/resources/trefoil-audition/$it" }
        val sourceHashes=sources.filter { File(repo,it).exists() }.associateWith { path ->
            val f=File(repo,path); val to=File(root,"source/$path"); to.parentFile.mkdirs(); f.copyTo(to,true); hash(f) }
        val baseRevision=git(repo,"merge-base","HEAD","origin/HEAD")
        val patchFile=File(root,"source/trefoil-engine.patch")
        patchFile.writeText(git(repo,"diff",baseRevision,"--","synth","scripts/trefoil_refine_reference.py",
            "scripts/trefoil_browser_check.py","docs/TREFOIL.md","docs/README.md","docs/SYNTH_ROADMAP.md",".gitignore")+"\n")
        val provenance=mapOf("revision" to revision,"baseRevision" to baseRevision,"dirtyAtRender" to dirty,"sourceSha256" to sourceHashes,
            "generatedAt" to Instant.now().toString(),"renderer" to "SnipSnap Kotlin/JVM, mono 44100Hz, 4x internal",
            "dependency" to "org.apache.commons:commons-math3:3.6.1 (Apache-2.0)")
        write(File(root,"source-provenance.json"),provenance)
        write(File(root,"manifest.json"),mapOf("title" to "Trefoil","revision" to 1,"status" to "Finite first-listen prototype · owner listening pending",
            "generatedAt" to Instant.now().toString(),"provenance" to provenance,"clips" to clips))
        File(root,"REPLAY.txt").writeText("TREFOIL finite first-listen prototype\n\nPublic SnipSnap base: $baseRevision\nRendering source commit: $revision\nThe source/trefoil-engine.patch applies to that public base with git apply.\n\nFrom the matching source:\n./gradlew --no-daemon :synth:generateTrefoilAudition\n\nReplay one complete diagnostic recipe:\n./gradlew --no-daemon :synth:generateTrefoilAudition -PtrefoilReplay=/absolute/path/to/recipes/ordered.json -PtrefoilReplayWav=/tmp/ordered.wav\n\nWAVs are 44.1kHz mono PCM16. Comparison branches and stems retain their reference's matching gain. Off receiver stems are intentionally silent. HOLD, provisional escape classification, phone integration and listening acceptance remain later work.\n")
        val zip=File(root,"trefoil-listening-starter.zip")
        val selectedIds=cases.filter { it.section in setOf("First listen","Comparisons","Stems","Transfer proof") }.map { it.id }.toSet()
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            root.walkTopDown().filter { f -> f.isFile && f!=zip && (
                f.relativeTo(root).path.startsWith("source/") || f.name in setOf("manifest.json","source-provenance.json","REPLAY.txt") ||
                (f.parentFile.name=="audio" && selectedIds.any { f.name=="$it-matched.wav" }) ||
                (f.parentFile.name in setOf("recipes","diagnostics") && f.nameWithoutExtension in selectedIds)) }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        println("TREFOIL done: ${clips.size} clips, ${clips.size*2} WAVs, starter ZIP ${zip.length()} bytes")
    }
    private fun replay(args: List<String>) {
        require(args.size in 2..3) { "--replay <recipe.json> <output.wav> [raw|matched]" }
        val obj=Json.parse(File(args[0]).readText()).obj(); val p=TrefoilPatch.fromJsonValue(obj.getValue("patch"))
        val o=obj.getValue("options").obj()
        fun flag(k: String)=(o[k] as JsonValue.Bool).value
        val participant=o["excitedParticipant"]?.takeUnless { it is JsonValue.Null }?.int()
        val stem=o.getValue("stem").int()
        val probe=Trefoil.probe(p,Trefoil.Options(flag("frozen"),flag("impulseOnly"),participant,flag("memoryEnabled"),stem>=0))
        val x=select(probe,stem).copyOf()
        val gain=if(args.getOrNull(2)=="raw") 1f else obj.getValue("matchedGain").num().toFloat()
        for(i in x.indices) x[i]*=gain
        WavWriter.write(File(args[1]),Snip(x,1,Dsp.RATE),WavWriter.BitDepth.PCM_16)
    }
}
