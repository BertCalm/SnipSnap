package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import com.snipsnap.synth.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Delivered-kit evidence: native grids, saved source recipes, cycling preview and all export doors. */
object BackwashAcousticKitGenerator {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull()?:"testkit/backwash-acoustic-integration").apply{mkdirs()}
        for(id in listOf("backwash-soul","backwash-psych")) {
            val dir=File(root,id).apply{mkdirs()};val work=File(dir,"native")
            val kit=StarterKits.byId(id)!!.render(if(id.endsWith("soul"))"Backwash Soul" else "Backwash Psych",work)
            check(kit.pads.size==16 && kit.pads.all { it.chain?.zones!=null })
            val loaded=KitStore.load(work);check(kit==loaded)
            check(loaded.pads.all { Breed.recipeOf(it)!=null })
            check(StarterShelf.display().flatMap { it.second }.any { it.id==id })
            for(pad in loaded.pads.filter { it.source["backwashModel"]=="5" }) {
                val patch=PadRecipe.fromJsonValue(pad.recipe!!).patch as BackwashPatch
                check(patch.model==5)
                val whole=WavReader.read(File(work,pad.sampleFile));val chain=pad.chain!!
                for((z,zone) in chain.zones!!.withIndex())for(t in 0 until zone.cycle) {
                    val expected=patch.copy(take=patch.take+t).render((z+.5f)/chain.zones!!.size)
                    val w=chain.window(zone.baseSlice+t,whole.frameCount.toLong())
                    val actual=whole.samples.copyOfRange(w.first.toInt(),(w.last+1).toInt())
                    check(actual.size==expected.samples.size)
                    check(actual.indices.all { kotlin.math.abs(actual[it]-expected.samples[it])<2e-7f })
                }
            }
            val notes=buildList {
                for(bar in 0..3) {
                    val b=bar*4.0
                    fun hit(slot:Int,beat:Double,v:Float){add(Mpc3Note(Mpc3Note.noteFor(slot),((b+beat)*960).toLong(),v))}
                    for(i in 0..7)hit(3,i*.5,if(i%2==0).8f else .45f)
                    hit(1,0.0,.95f);hit(1,2.0,.9f);hit(1,2.75,.6f)
                    hit(if(id.endsWith("soul")&&bar%2==0)5 else 2,1.01,.85f)
                    hit(if(id.endsWith("soul")&&bar%2==0)5 else 2,3.01,.9f)
                    hit(12,2.5,.32f)
                    if(id.endsWith("soul")){hit(6,.5,.6f);hit(6,2.5,.7f)}
                    else {hit(7,.5,.8f);hit(7,1.75,.8f);hit(8,0.0,.9f)}
                }
                add(Mpc3Note(Mpc3Note.noteFor(13),12*960L,.6f))
            }
            val clip=Mpc3Clip("Backwash Groove",4,notes.sortedBy{it.timePulses})
            val bpm=if(id.endsWith("soul"))94f else 108f
            val groove=KitPreview.render(loaded,work,clip,bpm)
            check(groove.samples.all { it.isFinite() })
            WavWriter.write(File(dir,"groove.wav"),groove,WavWriter.BitDepth.PCM_24)
            val probe=Mpc3Clip("Recorded alternates",4,(0..7).map { Mpc3Note(Mpc3Note.noteFor(7),it*960L,.6f) })
            val cycling=KitPreview.render(loaded,work,probe,bpm)
            val fixed=loaded.copy(pads=loaded.pads.map { p -> p.copy(chain=p.chain?.let { c->c.copy(zones=c.zones?.map { it.copy(cycle=1) }) }) })
            check(!cycling.samples.contentEquals(KitPreview.render(fixed,work,probe,bpm).samples))
            check(cycling.samples.contentEquals(KitPreview.render(KitStore.load(work),work,probe,bpm).samples))
            val native=Mpc3Exporter.exportTrack(loaded,work,File(dir,"MPC3"),overwrite=true,mpc2Twin=true,clip=clip)
            val data=native.samples.first().parentFile
            val outcomes=listOf(ExportOutcome(ExportFormat.MPC3_TRACK,native.program,data,native.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER,File(data,"${loaded.name}.xpm"),null,native.findings))
            for(o in outcomes)check(!ExportReadBack.verify(loaded,o).blocked())
            val project=Mpc3Project.read(native.program)
            val instruments=project.drumPrograms().single().getValue("instruments").arr()
            for(p in loaded.pads) {
                val layers=instruments[p.slot-1].obj().getValue("layersv").arr().map{it.obj()}.filter{it.getValue("sampleFile").str().isNotBlank()}
                for((i,z)in p.chain!!.zones!!.asReversed().withIndex()) {
                    check(layers[i].getValue("sliceCycleLength").int()==z.cycle)
                    check(layers[i].getValue("sliceIncrement").int()==1)
                    check(layers[i].getValue("sliceIndex").int()==z.baseSlice)
                }
            }
            SfzWriter.write(loaded,work,File(dir,"SFZ"),overwrite=true)
            DecentSamplerWriter.write(loaded,work,File(dir,"DecentSampler"),overwrite=true)
            File(dir,"README.txt").writeText("BACKWASH acoustic model 5 with legacy model-4 core drums. Recorded takes and native velocity groups on acoustic pads; generated robin variation on procedural drums.\nMPC 3, SFZ and DecentSampler cycle takes. MPC 2 uses each zone's first take and does not pass the round-robin export gate.\nFull tails, editable source recipes, tambourine roll/riser. Hardware execution is not tested here.\nAcoustic recordings: CC0 Virtuosity Drums / Versilian Studios, Austin McMahon; VSCO 2 CE, Sam Gossner, Simon Dalzell, Elan Hickler. Pinned provenance: https://github.com/BertCalm/SnipSnap/tree/claude/backwash-drum-engine/reference/backwash-acoustic\n")
            ZipOutputStream(File(root,"$id.zip").outputStream().buffered()).use { zip ->
                for(f in dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).path.startsWith("native") && it.name!="groove.wav" && (it.relativeTo(dir).path.startsWith("MPC3") || it.name=="README.txt") }) {
                    zip.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath).also{it.time=0});f.inputStream().use{it.copyTo(zip)};zip.closeEntry()
                }
            }
        }
        for(v in BackwashVoice.entries.filter{!it.legacy}) {
            val p=BackwashPatch(v.name,v,Backwash.defaults(v))
            WavWriter.write(File(root,"${v.name.lowercase()}-dry.wav"),BackwashAcoustic.source(v,p.macros),WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root,"${v.name.lowercase()}-processed.wav"),p.render(),WavWriter.BitDepth.PCM_24)
        }
        println("PASS: two genre kits, 32 saved grids, acoustic PCM recipe replay, cycling preview, MPC3 cycle fields and MPC2 read-back. SFZ/DS exported. MPC2 RR unsupported; hardware untested.")
    }
}
