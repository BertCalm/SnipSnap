package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import com.snipsnap.synth.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Exercises persisted playback and actual exported files; used by the test and delivery task. */
object SporeSnareDelivery {
    fun generate(root:File) {
        root.mkdirs();val folder=File(root,"instrument");val native=File(folder,"Native")
        val pads=SporeSnareBank.arranged();val grid=requireNotNull(pads.single().velocityTakes)
        val kit=KitAssembler.assembleArranged("Spore Approved Snare",pads,native,tempoBpm=170f)
        val loaded=KitStore.load(native);check(kit==loaded)
        val pad=loaded.pads.single();val wav=WavReader.read(File(native,pad.sampleFile))
        val base=SporeSnare.Recipe.fromJsonText(pad.source.getValue("sourceRecipe"))
        val audio=File(root,"audio").apply { mkdirs() };val timings=mutableListOf<Double>()
        for((z,v)in SporeSnareBank.velocities.withIndex())for(t in 0..3) {
            val start=System.nanoTime();val expected=base.copy(velocity=v,take=t%3).render();timings+=(System.nanoTime()-start)/1e6
            check(expected.samples.contentEquals(grid[z][t%3].samples))
            val hit=checkNotNull(PadHit.resolve(pad,v.toFloat(),t) { wav.frameCount.toLong() })
            check(abs(hit.gainLeft-pad.level*v.toFloat())<1e-6)
            check(hit.endFrameExclusive-hit.startFrame==expected.frameCount.toLong())
            for(i in expected.samples.indices)check(abs(expected.samples[i]-wav.samples[hit.startFrame.toInt()+i])<=3f/8388608)
            if(t<3)WavWriter.write(File(audio,"snare-z$z-t$t.wav"),expected,WavWriter.BitDepth.PCM_24)
        }
        val sfz=SfzWriter.write(loaded,native,File(folder,"SFZ"),overwrite=true)
        check(sfz.readText().lineSequence().count { it.startsWith("<region>") }==9)
        val imported=SfzImporter.import(sfz,File(root,"verify-sfz"),overwrite=true)
        check(imported.skipped.isEmpty() && imported.kit.pads.size==1)
        val restored=imported.kit.pads.single();check(restored.chain==pad.chain)
        val sfzWav=WavReader.read(File(imported.directory,restored.sampleFile))
        check(sfzWav.samples.contentEquals(wav.samples))
        for(v in SporeSnareBank.velocities)for(t in 0..3) {
            val a=PadHit.resolve(pad,v.toFloat(),t) { wav.frameCount.toLong() }!!
            val b=PadHit.resolve(restored,v.toFloat(),t) { sfzWav.frameCount.toLong() }!!
            check(a.startFrame==b.startFrame && a.endFrameExclusive==b.endFrameExclusive)
        }
        File(root,"verify-sfz").deleteRecursively()
        val clip=Mpc3Clip("Snare touch and repeats",4,(0..8).map { i ->
            Mpc3Note(Mpc3Note.noteFor(pad.slot),i*960L,SporeSnareBank.velocities[i/3].toFloat()) })
        val preview=KitPreview.render(loaded,native,clip,170f)
        check(preview.samples.contentEquals(KitPreview.render(KitStore.load(native),native,clip,170f).samples))
        val fixed=loaded.copy(pads=loaded.pads.map { p -> p.copy(chain=p.chain!!.copy(zones=p.chain!!.zones!!.map { it.copy(cycle=1) })) })
        check(!preview.samples.contentEquals(KitPreview.render(fixed,native,clip,170f).samples))
        WavWriter.write(File(audio,"native-playback.wav"),preview,WavWriter.BitDepth.PCM_24)
        val mpc=Mpc3Exporter.exportTrack(loaded,native,File(folder,"MPC3"),overwrite=true,mpc2Twin=false,clip=clip)
        val findings=ExportReadBack.verify(loaded,ExportOutcome(ExportFormat.MPC3_TRACK,mpc.program,mpc.samples.first().parentFile,mpc.findings))
        check(!findings.blocked())
        val project=Mpc3Project.read(mpc.program)
        val instrument=project.drumPrograms().single().getValue("instruments").arr()[pad.slot-1].obj()
        val layers=instrument.getValue("layersv").arr().map { it.obj() }.filter { it.getValue("sampleFile").str().isNotBlank() }
        check(layers.size==3)
        for((i,z)in pad.chain!!.zones!!.asReversed().withIndex()) {
            check(layers[i].getValue("sliceCycleLength").int()==z.cycle && z.cycle==3)
            check(layers[i].getValue("sliceIncrement").int()==1)
            check(layers[i].getValue("sliceIndex").int()==z.baseSlice)
        }
        check(mpc.samples.any { WavReader.read(it).samples.contentEquals(wav.samples) })
        File(folder,"README.txt").writeText("Spore approved snare, model 1. One pad, three velocity levels and three deterministic takes per level.\nNative kit.json, SFZ and MPC 3 retain the complete grid. The sourceRecipe metadata can rerender via SporeSnare.Recipe, but is not an app-editable PadRecipe.\nNo MPC 2 fallback included: that format does not preserve this round-robin grid. MPC hardware and Android playback remain untested.\nOwner approved the Python sound on 2026-11-14; Kotlin PCM parity is checked separately. This is not a complete genre drumkit.\n")
        ZipOutputStream(File(root,"spore-approved-snare.zip").outputStream().buffered()).use { zip ->
            folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(folder).invariantSeparatorsPath).apply { time=0 })
                f.inputStream().use { it.copyTo(zip) };zip.closeEntry()
            }
        }
        File(root,"delivery.json").writeText(Json.write(JsonValue.Obj(mapOf(
            "model" to JsonValue.Num(1.0),"modelSha256" to JsonValue.Str(SporeSnare.MODEL_SHA256),
            "nativeSaveReplayAndPlayback" to JsonValue.Bool(true),"sfzGridAndPcm" to JsonValue.Bool(true),
            "mpc3GridAndPcm" to JsonValue.Bool(true),"mpcHardware" to JsonValue.Str("unverified"),
            "androidPlayback" to JsonValue.Str("unverified"),
            "renderMedianMs" to JsonValue.Num(timings.sorted()[timings.size/2]),"renderMaxMs" to JsonValue.Num(timings.max())))))
        println("PASS Spore snare delivery: saved recipes, 3x3 PCM windows, PadHit/preview cycling, SFZ and MPC3 grid/PCM. JVM median ms=${timings.sorted()[timings.size/2]}")
    }
    @JvmStatic fun main(args:Array<String>)=generate(File(args.first()))
}
