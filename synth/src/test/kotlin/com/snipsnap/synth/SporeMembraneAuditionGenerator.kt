package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.json.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Only the reference branch reads recordings. SporeMembrane itself has no sample/resource dependency. */
object SporeMembraneAuditionGenerator {
    private fun json(v:Any?):JsonValue=when(v) {
        null->JsonValue.Null;is String->JsonValue.Str(v);is Boolean->JsonValue.Bool(v);is Number->JsonValue.Num(v.toDouble())
        is Map<*,*>->JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*>->JsonValue.Arr(v.map(::json));else->error("unsupported value $v")
    }
    private fun hash(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun scale(s:Snip,g:Float)=s.copy(samples=FloatArray(s.samples.size) { s.samples[it]*g })
    private fun sequence(sounds:List<Snip>,gains:List<Float>,spacing:Double):Snip {
        val step=(spacing*Dsp.RATE).roundToInt()
        val out=FloatArray(step*(sounds.size-1)+sounds.maxOf { it.frameCount })
        sounds.forEachIndexed { j,s -> for(i in s.samples.indices)out[j*step+i]+=s.samples[i]*gains[j] }
        return Snip(out,1,Dsp.RATE)
    }
    private fun phrase(grids:List<List<List<Snip>>>):Snip {
        val events=SporeStationAuditionGenerator.breakEvents().filter { it.pad in listOf(0,1,2,3,4) }
        val out=FloatArray((8*60.0/170*Dsp.RATE).toInt()+grids.flatten().flatten().maxOf { it.frameCount })
        val counts=Array(2) { IntArray(3) }
        for(e in events) {
            val v=if(e.pad in 0..1)0 else 1
            val force=if(e.pad==3)e.velocity*.65f else if(e.pad==1)e.velocity*.72f else e.velocity
            val midi=(force*127).roundToInt();val z=if(midi<42)0 else if(midi<85)1 else 2
            val sound=grids[v][z][counts[v][z]++%3]
            val at=(e.beat*60/170*Dsp.RATE).roundToInt()
            for(i in sound.samples.indices)out[at+i]+=sound.samples[i]*force*.65f
        }
        return Snip(out,1,Dsp.RATE)
    }
    private fun export(root:File,grids:List<List<List<Snip>>>):File {
        val folder=File(root,"prototype-kit").apply { mkdirs() };val native=File(folder,"Native")
        val pads=grids.mapIndexed { i,grid -> ArrangedPad(grid[2][0],if(i==0)DrumClass.KICK else DrumClass.SNARE,
            level=1f,oneShot=true,displayName=if(i==0)"Coupled Kick" else "Contact Snare",velocityTakes=grid,
            source=mapOf("prototype" to "SporeMembrane","model" to SporeMembrane.MODEL_VERSION.toString(),"controls" to "tune=.5,damp=.45,strike=.4,body=.55,wires=.6","gain" to "fixed voice radiation pickup; no per-hit normalization")) }
        val kit=KitAssembler.assembleArranged("Spore Synthesis Prototype",pads,native,tempoBpm=170f)
        check(KitStore.load(native)==kit)
        kit.pads.forEachIndexed { i,p ->
            val wav=WavReader.read(File(native,p.sampleFile))
            for(z in 0..2)for(t in 0..3) {
                val window=requireNotNull(p.chain).window(z*3+t%3,wav.frameCount.toLong())
                val expected=grids[i][z][t%3]
                check(window.last-window.first+1==expected.frameCount.toLong())
                for(f in expected.samples.indices)check(abs(wav.samples[window.first.toInt()+f]-expected.samples[f])<=3f/8388608)
            }
        }
        val sfz=SfzWriter.write(kit,native,folder,overwrite=true)
        check(sfz.readText().lineSequence().count { it.startsWith("<region>") }==18)
        val imported=SfzImporter.import(sfz,File(root,"verify-import"),overwrite=true)
        check(imported.skipped.isEmpty() && imported.kit.pads.size==2)
        kit.pads.zip(imported.kit.pads).forEach { (a,b) ->
            check(a.chain==b.chain)
            check(WavReader.read(File(native,a.sampleFile)).samples.contentEquals(WavReader.read(File(imported.directory,b.sampleFile)).samples))
        }
        File(root,"verify-import").deleteRecursively()
        File(folder,"README.txt").writeText("Sample-free two-voice prototype, not a complete genre kit. Native kit.json and SFZ retain 3 velocity zones x 3 physical restrikes. A01/MIDI36 kick, A02/MIDI37 snare. Finite hits. No MPC hardware claim, no shelf registration. Other Spore Station downloads on the parent page still contain recorded acoustic drums. See synthesis-spec.md and manifest.json for mechanics, parameters and verification.\n")
        val zip=File(root,"spore-synthesis-prototype.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z -> folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            z.putNextEntry(ZipEntry(f.relativeTo(folder).invariantSeparatorsPath).apply { time=0 });f.inputStream().use { it.copyTo(z) };z.closeEntry()
        } }
        return zip
    }
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.first()).apply { mkdirs() };File(root,"audio").mkdirs()
        val clips=mutableListOf<Map<String,Any?>>()
        val timings=mutableListOf<Double>();val traces=mutableListOf<Map<String,Any?>>()
        fun clip(id:String,title:String,group:String,s:Snip) {
            check(s.samples.all { it.isFinite() } && s.peak() in 1e-7f..1f)
            val raw=File(root,"audio/$id.wav");WavWriter.write(raw,s)
            WavWriter.write(File(root,"audio/$id-matched.wav"),AuditionLevel.level(s))
            clips+=mapOf("id" to id,"title" to title,"group" to group,"raw" to "audio/$id.wav","matched" to "audio/$id-matched.wav","sha256" to hash(raw),"peak" to s.peak(),"dc" to s.samples.average(),"frames" to s.frameCount)
        }
        val velocities=listOf(.3f,.6f,1f)
        val synth=SporeMembrane.Voice.entries.map { voice -> velocities.map { v -> (0..2).map { t ->
            val start=System.nanoTime();val probe=SporeMembrane.probe(voice,velocity=v,take=t);timings+=(System.nanoTime()-start)/1e6
            traces+=mapOf("voice" to voice.name,"velocity" to v,"take" to t,"attackEnergy" to probe.trace.attackEnergy,
                "maximumFreeEnergy" to probe.trace.maximumFreeEnergy,"finalEnergy" to probe.trace.finalEnergy,"contacts" to probe.trace.contacts,
                "contactEnergyAdded" to probe.trace.maximumContactEnergyAdded,"rawPeak" to probe.trace.rawPeak)
            probe.sound
        } } }
        val acoustic=listOf(0,2).map { i -> velocities.map { v -> (0..2).map { SporeAcoustic.render(i,v,it) } } }
        val old=listOf(0,2).map { i -> velocities.map { v -> (0..2).map { t ->
            val r=SporeStation.recipe(i,previousJungle=true);r.process(requireNotNull(Restrike.take(requireNotNull(r.patch),v,170+i,t)))
        } } }
        for((i,name)in listOf("Kick","Snare").withIndex()) {
            clip("reference-$i","$name · acoustic reference","$name comparison",acoustic[i][2][0])
            clip("old-$i","$name · earlier synthesis","$name comparison",old[i][2][0])
            clip("new-$i","$name · new coupled synthesis","$name comparison",synth[i][2][0])
            for(z in 0..2)clip("rr-$i-$z","$name · ${listOf("soft","medium","hard")[z]} · takes 1–2–3 twice","Touch and repeats",
                sequence((0..5).map { synth[i][z][it%3] },List(6) { velocities[z] },.25))
            clip("touch-$i","$name · soft → medium → hard","Touch and repeats",
                sequence((0..8).map { synth[i][it/3][it%3] },(0..8).map { velocities[it/3] },.25))
        }
        val phrases=listOf(phrase(acoustic),phrase(old),phrase(synth));val gain=min(1f,.92f/phrases.maxOf { it.peak() })
        phrases.forEachIndexed { i,s -> clip("phrase-$i",listOf("Acoustic reference · same phrase","Earlier synthesis · same phrase","New synthesis only · same phrase")[i],"Kick and snare phrase",scale(s,gain)) }
        clip("heads-only","Snare · wires disconnected","Inside the model",SporeMembrane.probe(SporeMembrane.Voice.SNARE,wireContact=false).sound)
        clip("uncoupled","Snare · heads uncoupled","Inside the model",SporeMembrane.probe(SporeMembrane.Voice.SNARE,coupling=false).sound)
        clip("damped","Snare · damped heads","Inside the model",SporeMembrane.render(SporeMembrane.Voice.SNARE,SporeMembrane.Controls(damp=.85f)))
        clip("rim","Snare · strike nearer rim","Inside the model",SporeMembrane.render(SporeMembrane.Voice.SNARE,SporeMembrane.Controls(strike=.85f)))
        val zip=export(root,synth)
        val manifest=mapOf("model" to SporeMembrane.MODEL_VERSION,"status" to "two-voice-prototype-awaiting-listen","clips" to clips,
            "controls" to mapOf("tune" to .5,"damp" to .45,"strike" to .4,"body" to .55,"wires" to .6),"traces" to traces,
            "renderMs" to timings,"sourceSha256" to listOf("synth/src/main/kotlin/com/snipsnap/synth/SporeMembrane.kt","synth/src/test/kotlin/com/snipsnap/synth/SporeMembraneAuditionGenerator.kt").associateWith { hash(File(it)) },
            "download" to zip.name,"downloadSha256" to hash(zip),"checks" to mapOf("nativeSavePlayback" to true,"sfzRoundTrip" to true,"browserAudio" to "unverified","mpcHardware" to "unverified"))
        File(root,"manifest.json").writeText(Json.write(json(manifest)))
        File("docs/superpowers/specs/2026-11-12-spore-membrane-prototype.md").copyTo(File(root,"synthesis-spec.md"),overwrite=true)
        File("synth/src/test/resources/audition/spore-synthesis.html").copyTo(File(root,"index.html"),overwrite=true)
        SporeAcoustic.bytes("NOTICE.txt").let { File(root,"REFERENCE-NOTICE.txt").writeBytes(it) }
        SporeAcoustic.bytes("LICENSE.txt").let { File(root,"REFERENCE-LICENSE.txt").writeBytes(it) }
        println("Spore synthesis: ${clips.size} clips; 18 native/SFZ takes verified. Render ms median=${timings.sorted()[timings.size/2]}, max=${timings.max()}")
        println(Json.write(json(traces.filter { it["velocity"]==1f && it["take"]==0 })))
    }
}
