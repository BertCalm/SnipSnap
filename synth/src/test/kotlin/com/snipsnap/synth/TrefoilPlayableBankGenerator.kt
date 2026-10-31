package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Actual model-two renders, actual exported velocity WAVs, and replayable musical scores. */
object TrefoilPlayableBankGenerator {
    private fun json(x: Any?): JsonValue = when(x) {
        null -> JsonValue.Null; is JsonValue -> x; is String -> JsonValue.Str(x); is Boolean -> JsonValue.Bool(x)
        is Number -> if(x.toDouble().isFinite()) JsonValue.Num(x.toDouble()) else JsonValue.Null
        is Map<*,*> -> JsonValue.Obj(x.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(x.map(::json)); else -> error("unsupported JSON ${x::class}")
    }
    private fun write(root: File,path: String,x: Any?) { File(root,path).also { it.parentFile.mkdirs() }.writeText(Json.write(json(x))+"\n") }
    private fun sha(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun git(repo: File,vararg args: String): String=ProcessBuilder(listOf("git")+args).directory(repo).redirectErrorStream(true).start().let { p ->
        val text=p.inputStream.bufferedReader().readText().trim();val exit=p.waitFor()
        check(exit==0) { "git ${args.joinToString(" ")} failed ($exit): $text" };text }
    /** Detached CI checkouts may have no remote HEAD or ancestor history. Their
     * recorded commit already contains the source; its diff captures local edits. */
    internal fun replayBase(repo: File): String = try {
        git(repo,"merge-base","HEAD","origin/HEAD")
    } catch (_: IllegalStateException) {
        git(repo,"rev-parse","HEAD")
    }
    data class Demo(val id: String,val title: String,val description: String,val bpm: Float,val clip: Mpc3Clip)
    fun demos(): List<Demo> {
        fun note(beat: Double,slot: Int,velocity: Float)=Mpc3Note(Mpc3Note.noteFor(slot),(beat*960).roundToLong(),velocity,960L)
        val sparse=listOf(Triple(0.0,13,.45f),Triple(0.0,5,.7f),Triple(1.5,6,.45f),Triple(3.0,7,.85f),
            Triple(4.0,14,.5f),Triple(4.0,8,.65f),Triple(6.0,2,.45f),Triple(7.5,3,.85f),
            Triple(8.0,15,.65f),Triple(8.0,5,.6f),Triple(10.0,6,.75f),Triple(12.0,16,.75f),Triple(12.0,7,.6f),Triple(14.0,8,.8f))
        val dense=mutableListOf<Mpc3Note>()
        for(bar in 0..3) {
            dense+=note(bar*4.0,13+bar,.8f)
            for((n,slot) in listOf(5,6,8).withIndex()) dense+=note(bar*4.0,slot,listOf(.55f,.72f,.9f)[(bar+n)%3])
        }
        for(i in 0..23) dense+=note(i*.5,1+i%4,listOf(.22f,.55f,.9f)[i%3])
        dense+=note(2.0,9,.6f);dense+=note(10.0,9,.6f);dense+=note(8.0,11,.35f)
        return listOf(
            Demo("weave-study","Weave Study","Sparse keys, short contacts and lower notes, with space for the endings.",96f,
                Mpc3Clip("Weave Study",4,sparse.map { note(it.first,it.second,it.third) })),
            Demo("crossings","Crossings","Overlapping minor chords, a changing bass and a three-velocity pluck pattern.",104f,
                Mpc3Clip("Crossings",4,dense.sortedBy { it.timePulses })))
    }
    /** Unlike KitPreview's main-only path, this sampler reads each actual native velocity zone. */
    fun performance(kit: Kit,dir: File,demo: Demo): Snip {
        data class Event(val start: Int,val snip: Snip,val gain: Float)
        val cache=HashMap<String,Snip>()
        val events=demo.clip.notes.map { note ->
            val pad=requireNotNull(kit.pad(Mpc3Note.slotFor(note.note)))
            val midi=(note.velocity*127).roundToInt().coerceIn(1,127)
            val file=pad.velocityLayers.firstOrNull { midi in it.velStart..it.velEnd }?.sampleFile ?: pad.sampleFile
            Event((note.timePulses/960.0*60/demo.bpm*Dsp.RATE).roundToInt(),
                cache.getOrPut(file) { WavReader.read(File(dir,file)) },.45f*sqrt(.5f)*pad.level*(.35f+.65f*note.velocity))
        }
        val out=FloatArray(events.maxOf { it.start+it.snip.frameCount })
        for(e in events) for(i in e.snip.samples.indices) out[e.start+i]+=e.snip.samples[i]*e.gain
        check(out.all(Float::isFinite) && out.maxOf { abs(it) }<.95f) { "demo exceeds fixed headroom" }
        return Snip(out,1,Dsp.RATE)
    }
    @JvmStatic fun main(args: Array<String>) { generate(File(args.firstOrNull() ?: "../testkit/trefoil-playable-bank")) }
    fun generate(root: File): File {
        root.mkdirs();val repo=generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }.first { File(it,".git").exists() }
        val revision=git(repo,"rev-parse","HEAD");val dirty=git(repo,"status","--porcelain").isNotBlank()
        val cache=HashMap<String,Snip>()
        fun render(p: TrefoilPatch)=cache.getOrPut(p.copy(name="Render").toJsonText()) { p.render().also { s ->
            check(s.samples.all(Float::isFinite) && s.peak() in .000001f..1f) } }
        val clips=mutableListOf<Map<String,Any?>>()
        for((i,entry) in TrefoilPresets.entries.withIndex()) {
            val main=render(entry.patch)
            val layers=TrefoilKits.velocities.mapIndexed { zone,v ->
                val p=entry.patch.copy(velocity=v);val native=render(p);val sample=if(zone==2) native else Velocity.peakMatch(main,native)
                val path="audio/${entry.id}-${zone+1}.wav";WavWriter.write(File(root,path),sample)
                val recipe="recipes/${entry.id}-${zone+1}.json";write(root,recipe,mapOf("recipe" to PadRecipe(p).toJsonValue(),
                    "peakMatchReference" to entry.patch.toJsonValue(),"normalization" to if(zone==2) "native" else "peak-match-main"))
                val onset=Classifier.classify(Snip(sample.samples.copyOf(min(sample.samples.size,(1.2*Dsp.RATE).toInt())),1,Dsp.RATE))
                val pitch=Pitch.detect(sample,.05f,min(.3f,entry.patch.seconds-.05f))
                mapOf("nativeVelocity" to v,"triggerMidi" to listOf(32,64,114)[zone],"path" to path,"sha256" to sha(File(root,path)),
                    "recipe" to recipe,"peak" to sample.peak(),"pitchHz" to pitch?.hz,"pitchConfidence" to pitch?.confidence,"prefixClassifier" to onset.drumClass.name)
            }
            clips+=mapOf("id" to entry.id,"title" to entry.patch.name,"role" to entry.role,"description" to entry.description,
                "voice" to entry.patch.voice.name,"midi" to entry.patch.midi,"seconds" to entry.patch.seconds,"activeSeconds" to entry.patch.activeSeconds,
                "patch" to entry.patch.toJsonValue(),"layers" to layers)
            println("TREFOIL BANK ${i+1}/12 ${entry.patch.name}")
        }
        val work=File(root,".kit-work");check(!work.exists() || work.deleteRecursively())
        val kit=KitAssembler.assembleArranged(TrefoilKits.NAME,TrefoilKits.build(::render),work,KeySpec.parse("Cminpent"),96f)
        val performances=demos();GrooveStore.save(work,performances.map { it.clip })
        val destination=File(root,"kit/${TrefoilKits.NAME}");destination.parentFile.mkdirs()
        val exported=Mpc3Exporter.exportTrack(kit,work,destination,overwrite=true,mpc2Twin=true,clips=performances.map { it.clip })
        val data=exported.samples.first().parentFile;KitStore.save(kit,data);GrooveStore.save(data,performances.map { it.clip })
        check(KitStore.load(data)==kit && !Preflight.check(kit,data).blocked())
        val padRows=kit.pads.map { pad ->
            val p=PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as TrefoilPatch
            mapOf("slot" to pad.slot,"name" to pad.displayName,"midi" to p.midi,"level" to pad.level,"presetId" to pad.source.getValue("presetId"),
                "recipe" to pad.recipe,"sample" to File(data,pad.sampleFile).relativeTo(root).invariantSeparatorsPath,
                "zones" to pad.velocityLayers.mapIndexed { index,z ->
                    val f=File(data,z.sampleFile);val native=render(p.copy(velocity=TrefoilKits.velocities[index]))
                    val expected=if(index==2) native else Velocity.peakMatch(render(p),native)
                    val decoded=WavReader.read(f)
                    check(decoded.frameCount==expected.frameCount && decoded.samples.indices.all { abs(decoded.samples[it]-expected.samples[it])<2.4e-7f })
                    mapOf("low" to z.velStart,"high" to z.velEnd,"nativeVelocity" to TrefoilKits.velocities[index],
                        "sample" to f.relativeTo(root).invariantSeparatorsPath,"sha256" to sha(f))
                })
        }
        write(root,"pad-map.json",mapOf("kit" to kit.name,"model" to 2,"pads" to padRows))
        write(destination,"pad-map.json",mapOf("pads" to kit.pads.map { pad ->
            mapOf("slot" to pad.slot,"name" to pad.displayName,"recipe" to pad.recipe,
                "sample" to File(data,pad.sampleFile).relativeTo(destination).invariantSeparatorsPath,
                "zones" to pad.velocityLayers.mapIndexed { i,z -> mapOf("low" to z.velStart,"high" to z.velEnd,
                    "nativeVelocity" to TrefoilKits.velocities[i],"sample" to File(data,z.sampleFile).relativeTo(destination).invariantSeparatorsPath) })
        }))
        val demoRows=performances.map { demo ->
            val raw=performance(kit,data,demo);val loud=Loudness.of(raw).coerceAtLeast(1e-12f)
            val gain=min(.03f/loud,.95f/raw.peak());val matched=raw.copy(samples=FloatArray(raw.samples.size) { raw.samples[it]*gain })
            WavWriter.write(File(root,"audio/${demo.id}-raw.wav"),raw);WavWriter.write(File(root,"audio/${demo.id}.wav"),matched)
            val score=mapOf("tempoBpm" to demo.bpm,"bars" to 4,"ppq" to 960,"monitorGain" to .45,
                "velocityLaw" to ".35 + .65 * MIDI velocity / 127; choose the saved velocity zone first","matchedGain" to gain,
                "events" to demo.clip.notes.map { mapOf("slot" to Mpc3Note.slotFor(it.note),"timePulses" to it.timePulses,"velocity" to it.velocity,"lengthPulses" to it.lengthPulses) })
            write(root,"scores/${demo.id}.json",score)
            mapOf("id" to demo.id,"title" to demo.title,"description" to demo.description,"bpm" to demo.bpm,"path" to "audio/${demo.id}.wav",
                "raw" to "audio/${demo.id}-raw.wav","seconds" to raw.durationSeconds,"score" to "scores/${demo.id}.json","matchedGain" to gain)
        }
        File(destination,"README.txt").writeText("""
            ${TrefoilKits.NAME}
            Finite dry model-two one-shots, mono 44.1 kHz PCM24. No rack effects or sample loops.
            Keep the .xtd with its adjacent _[TrackData] folder. MPC 2: open the .xpm inside that folder.
            The data folder also contains kit.json with complete editable SnipSnap recipes.
            A01-A12: twelve colors on repeating C4/Eb4/F4/G4. A13-A16: lower First Weave on C3/Eb3/F3/G3.
            Three native strikes: .3 at MIDI 1-41, .6 at 42-84, .8 at 85-127.
            Softer samples are peak-matched to the main sample; hardware velocity controls playback level.
            All notes play their complete finite tails. New notes do not choke previous notes.
            Weave Study and Crossings are embedded as two patterns and retained in groove.json.
            Weave Study is authored for 96 BPM; Crossings for 104 BPM. Set project tempo for each score.
            Browser demos choose these actual exported zones with SnipSnap's preview velocity law;
            hardware audition and on-device performance remain separate verification steps.
        """.trimIndent()+"\n")
        val archive=File(root,"SnipSnap-Trefoil-Kit.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip -> destination.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            zip.putNextEntry(ZipEntry(destination.name+"/"+f.relativeTo(destination).invariantSeparatorsPath).also { it.time=0L });f.inputStream().use { it.copyTo(zip) };zip.closeEntry() } }
        check(exported.samples.size==48)
        for(name in listOf("index.html","styles.css","app.js")) {
            val stream=TrefoilPlayableBankGenerator::class.java.getResourceAsStream("/trefoil-bank/$name") ?: error("missing bank page $name")
            stream.use { input -> File(root,name).outputStream().use { input.copyTo(it) } }
        }
        val base=replayBase(repo)
        val patch=ProcessBuilder("git","diff",base,"--","synth","shell","app","README.md","docs/TREFOIL.md","docs/SYNTH_ROADMAP.md","scripts/trefoil_bank_browser_check.py",".gitignore")
            .directory(repo).start().let { process -> val value=process.inputStream.bufferedReader().readText();check(process.waitFor()==0);value }
        File(root,"source").mkdirs();File(root,"source/trefoil-playable.patch").writeText(patch)
        File(root,"REPLAY.txt").writeText("Replay base: $base\nSource revision: $revision\nCheck out the recorded replay base, then apply source/trefoil-playable.patch with git apply --allow-empty.\nAn empty patch means the base already contains the committed source and there are no local edits.\nRun ./gradlew --no-daemon :synth:generateTrefoilPlayableBank\nThe engine remains acoustic model 2. Full recipes include note, duration, contact time, seed and velocity.\n")
        val files=root.walkTopDown().filter { it.isFile && !it.relativeTo(root).path.startsWith(".kit-work") && it.name !in setOf("manifest.json","source-provenance.json") }.associate { it.relativeTo(root).invariantSeparatorsPath to sha(it) }
        val sourcePaths=listOf("synth/src/main/kotlin/com/snipsnap/synth/Trefoil.kt","synth/src/main/kotlin/com/snipsnap/synth/TrefoilModelTwo.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/TrefoilPatch.kt","synth/src/main/kotlin/com/snipsnap/synth/TrefoilTrajectory.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/TrefoilPresets.kt","synth/src/main/kotlin/com/snipsnap/synth/TrefoilKits.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/TrefoilPlayableBankGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/TrefoilBankTest.kt","shell/src/test/kotlin/com/snipsnap/shell/TrefoilIntegrationTest.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Presets.kt","synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt","shell/src/main/kotlin/com/snipsnap/shell/UserPresets.kt",
            "app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt","scripts/trefoil_bank_browser_check.py")+
            listOf("index.html","styles.css","app.js").map { "synth/src/test/resources/trefoil-bank/$it" }
        write(root,"source-provenance.json",mapOf("revision" to revision,"dirty" to dirty,"model" to 2,"replayBase" to base,
            "replayMode" to if(base==revision) "commit-plus-working-tree-delta" else "public-base-patch",
            "sourceSha256" to sourcePaths.associateWith { sha(File(repo,it)) }))
        write(root,"manifest.json",mapOf("model" to 2,"revision" to revision,"dirty" to dirty,"presets" to clips,"pads" to padRows,"demos" to demoRows,
            "kitZip" to archive.name,"assets" to files,"nativeVelocities" to TrefoilKits.velocities,"hardwareAudition" to "pending"))
        work.deleteRecursively();println("TREFOIL BANK complete: 12 presets, 16 pads, 48 native kit layers, 2 performances")
        return root
    }
}
