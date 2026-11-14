package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import com.snipsnap.synth.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Shelf, saved source, actual playback and exported PCM evidence for every kit cell. */
object SporeSyntheticDelivery {
    private fun json(v:Any?):JsonValue=when(v) {
        null->JsonValue.Null;is String->JsonValue.Str(v);is Boolean->JsonValue.Bool(v);is Number->JsonValue.Num(v.toDouble())
        is Map<*,*>->JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*>->JsonValue.Arr(v.map(::json));else->error("json")
    }
    private fun hash(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it)}
    private fun archive(root:File,out:File) {
        ZipOutputStream(out.outputStream().buffered()).use { z->root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f->
            z.putNextEntry(ZipEntry(root.name+"/"+f.relativeTo(root).invariantSeparatorsPath).apply { time=0 });f.inputStream().use{it.copyTo(z)};z.closeEntry()
        } }
        check(out.length()<25L*1024*1024) { "Archive exceeds Site asset limit: $out ${out.length()}" }
    }
    private fun clip(g:SporeSyntheticKits.Genre):Mpc3Clip {
        val notes=mutableListOf<Mpc3Note>()
        fun hit(slot:Int,b:Double,v:Float){notes+=Mpc3Note(Mpc3Note.noteFor(slot),(b*960).roundToLong(),v)}
        for(bar in 0..3) {
            val b=bar*4.0
            when(g) {
                SporeSyntheticKits.Genre.JUNGLE->{
                    for((at,v)in listOf(0.0 to 1f,.75 to .65f,2.0 to .9f,2.5 to .6f))hit(1,b+at,v)
                    hit(3,b+1,1f);hit(3,b+3,.95f);hit(4,b+1.75,.35f);hit(4,b+2.75,.3f);hit(4,b+3.5,.4f)
                    for(i in 0..7)hit(7,b+i*.5,if(i%2==0).7f else .45f)
                    hit(8,b+3.75,.6f);hit(9,b+1.25,.5f);hit(9,b+2.25,.4f)
                }
                SporeSyntheticKits.Genre.DUB->{
                    hit(1,b,1f);hit(2,b+2.5,.65f);hit(3,b+2,1f);hit(4,b+3.5,.6f)
                    for(i in 0..3)hit(7,b+i+.5,.55f)
                    hit(12,b+1.5,.6f);hit(6,b+3,.5f);hit(13,b+3.75,.5f)
                }
                SporeSyntheticKits.Genre.BROKEN->{
                    for(at in listOf(0.0,1.5,2.75))hit(1,b+at,if(at==0.0)1f else .7f)
                    hit(3,b+1,.9f);hit(3,b+3,.95f);hit(4,b+2.5,.55f)
                    for(i in 0..7)hit(7,b+i*.5,if(i%3==0).8f else .4f)
                    hit(8,b+1.75,.6f);hit(12,b+3.5,.65f);hit(9,b+.75,.4f)
                }
            }
            if(bar==1)hit(15,b+3.5,.65f)
            if(bar==2)hit(14,b+3.75,.65f)
            if(bar==3){hit(11,b+2.5,.8f);hit(12,b+2.75,.6f);hit(16,b+3.5,.65f)}
        }
        return Mpc3Clip(g.title,4,notes.sortedBy { it.timePulses })
    }
    fun generate(root:File,genres:List<SporeSyntheticKits.Genre> = SporeSyntheticKits.Genre.entries) {
        root.mkdirs();val audio=File(root,"audio").apply{mkdirs()};val records=mutableListOf<Map<String,Any?>>()
        for(g in genres) {
            val started=System.nanoTime();val base=File(root,g.id);val native=File(base,"Native")
            val starter=checkNotNull(StarterKits.byId(g.id));check(StarterShelf.display().flatMap { it.second }.any { it.id==g.id })
            val kit=starter.render(g.title,native);val loaded=KitStore.load(native);check(kit==loaded && loaded.pads.size==18)
            val wavs=loaded.pads.associate { it.slot to WavReader.read(File(native,it.sampleFile)) }
            val expectedByPad=mutableMapOf<Int,List<List<Snip>>>()
            for(p in loaded.pads) {
                val recipe=SporeSyntheticKits.Recipe.fromJsonText(p.source.getValue("sourceRecipe"))
                check(recipe.genre==g && recipe.pad==p.slot-1)
                val wav=wavs.getValue(p.slot)
                val grid=SporeSyntheticKits.velocities.map { v->(0..2).map { t->recipe.copy(velocity=v,take=t).render() } }
                expectedByPad[p.slot]=grid
                for((z,v)in SporeSyntheticKits.velocities.withIndex())for(t in 0..3) {
                    val hit=checkNotNull(PadHit.resolve(p,v.toFloat(),t){wav.frameCount.toLong()})
                    val expected=grid[z][t%3];check(hit.endFrameExclusive-hit.startFrame==expected.frameCount.toLong())
                    check(abs(hit.gainLeft-p.level*v.toFloat())<1e-6)
                    for(i in expected.samples.indices)check(abs(expected.samples[i]-wav.samples[hit.startFrame.toInt()+i])<=3f/8388608)
                }
                check(p.chain!!.zones!!.all { it.cycle==3 })
                println("Verified ${g.name} pad ${p.slot} source and all 9 saved cells")
            }
            val sfz=SfzWriter.write(loaded,native,File(base,"SFZ"),overwrite=true)
            check(sfz.readText().lineSequence().count{it.startsWith("<region>")}==162)
            val imported=SfzImporter.import(sfz,File(base,"verify-sfz"),overwrite=true)
            check(imported.skipped.isEmpty() && imported.kit.pads.size==18)
            for((p,q)in loaded.pads.zip(imported.kit.pads)) {
                check(p.chain==q.chain);val a=wavs.getValue(p.slot);val b=WavReader.read(File(imported.directory,q.sampleFile))
                check(a.samples.contentEquals(b.samples))
                for(v in SporeSyntheticKits.velocities)for(t in 0..3) {
                    val x=PadHit.resolve(p,v.toFloat(),t){a.frameCount.toLong()}!!;val y=PadHit.resolve(q,v.toFloat(),t){b.frameCount.toLong()}!!
                    check(x.startFrame==y.startFrame && x.endFrameExclusive==y.endFrameExclusive)
                }
            }
            File(base,"verify-sfz").deleteRecursively()
            val groove=clip(g);val preview=KitPreview.render(loaded,native,groove,g.bpm.toFloat())
            check(preview.samples.contentEquals(KitPreview.render(KitStore.load(native),native,groove,g.bpm.toFloat()).samples))
            val fixed=loaded.copy(pads=loaded.pads.map { p->p.copy(chain=p.chain!!.copy(zones=p.chain!!.zones!!.map { it.copy(cycle=1) })) })
            check(!preview.samples.contentEquals(KitPreview.render(fixed,native,groove,g.bpm.toFloat()).samples))
            val choke=Mpc3Clip("Hat choke",1,listOf(Mpc3Note(Mpc3Note.noteFor(8),0,1f),Mpc3Note(Mpc3Note.noteFor(7),77,1f)))
            check(!KitPreview.render(loaded,native,choke,g.bpm.toFloat()).samples.contentEquals(
                KitPreview.render(loaded.copy(pads=loaded.pads.map { it.copy(muteGroup=0) }),native,choke,g.bpm.toFloat()).samples))
            val mpc=Mpc3Exporter.exportTrack(loaded,native,File(base,"MPC3"),overwrite=true,mpc2Twin=false,clip=groove)
            check(!ExportReadBack.verify(loaded,ExportOutcome(ExportFormat.MPC3_TRACK,mpc.program,mpc.samples.first().parentFile,mpc.findings)).blocked())
            val instruments=Mpc3Project.read(mpc.program).drumPrograms().single().getValue("instruments").arr()
            val exportSamples=mpc.samples.associateBy { it.name }
            for(p in loaded.pads) {
                val layers=instruments[p.slot-1].obj().getValue("layersv").arr().map{it.obj()}.filter{it.getValue("sampleFile").str().isNotBlank()}
                check(layers.size==3)
                for((i,z)in p.chain!!.zones!!.asReversed().withIndex()) {
                    val l=layers[i];check(l.getValue("sliceCycleLength").int()==3 && l.getValue("sliceIncrement").int()==1 && l.getValue("sliceIndex").int()==z.baseSlice)
                    val f=checkNotNull(exportSamples[l.getValue("sampleFile").str()])
                    check(WavReader.read(f).samples.contentEquals(wavs.getValue(p.slot).samples))
                }
            }
            // Preview-only common gain leaves the downloadable pad grid untouched.
            val gain=min(1f,.90f/preview.peak())
            WavWriter.write(File(audio,"${g.id}-groove.wav"),preview.copy(samples=FloatArray(preview.samples.size){preview.samples[it]*gain}),WavWriter.BitDepth.PCM_24)
            for(p in loaded.pads) {
                val hit=expectedByPad.getValue(p.slot)[2][0]
                WavWriter.write(File(audio,"${g.id}-pad-${p.slot}.wav"),hit,WavWriter.BitDepth.PCM_24)
            }
            // Velocity and repeats use actual saved-kit playback for both core pads.
            val probe=Mpc3Clip("Core touch",4,(0..17).map { n->Mpc3Note(Mpc3Note.noteFor(if(n<9)1 else 3),n*480L,SporeSyntheticKits.velocities[n%9/3].toFloat()) })
            val touch=KitPreview.render(loaded,native,probe,g.bpm.toFloat());check(touch.peak()<1)
            WavWriter.write(File(audio,"${g.id}-touch.wav"),touch,WavWriter.BitDepth.PCM_24)
            val downloads=mutableMapOf<String,String>()
            for(format in listOf("Native","SFZ","MPC3")) {
                val folder=File(base,format)
                File(folder,"README.txt").writeText("${g.title}: sample-free synthesis. 18 pads, three velocities x three takes on every pad, including authored roll/riser.\nJungle A01/A03 preserve the owner-approved kick/snare. New voices and genre arrangements await listening acceptance.\nNative sourceRecipe metadata supports Kotlin rerendering; not yet an app-editable synth Patch. Native/SFZ/MPC3 grids and PCM are software-verified. Physical MPC/Android playback unverified. No MPC2 fallback.\n")
                val zip=File(root,"${g.id}-${format.lowercase()}.zip");archive(folder,zip);downloads[format]=zip.name
            }
            records+=mapOf("id" to g.id,"title" to g.title,"bpm" to g.bpm,"pads" to SporeSyntheticKits.layout(g).mapIndexed{i,p->mapOf("slot" to i+1,"name" to p.name,"level" to p.level,"muteGroup" to p.muteGroup)},
                "downloads" to downloads,"gridCells" to 162,"nativeRecipeReplay" to true,"nativePlayback" to true,"hatChoke" to true,"sfzGridPcmAndPlayback" to true,"mpc3GridAndPcm" to true,"hardware" to "unverified","renderVerifySeconds" to (System.nanoTime()-started)/1e9)
            println("PASS ${g.id}: 162 cells, saved source/playback, hats, SFZ and MPC3. Exports archived.")
        }
        File(root,"manifest.json").writeText(Json.write(json(mapOf("status" to "approved core; new kit arrangements awaiting listen","kits" to records,
            "kickModelSha256" to SporeKick.MODEL_SHA256,"snareModelSha256" to SporeSnare.MODEL_SHA256,
            "audioSha256" to audio.listFiles()!!.filter{it.extension=="wav"}.associate { it.name to hash(it) }))))
    }
    @JvmStatic fun main(args:Array<String>)=generate(File(args.first()))
}
