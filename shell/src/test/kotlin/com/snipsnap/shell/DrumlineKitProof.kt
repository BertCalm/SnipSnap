package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import com.snipsnap.synth.*
import java.io.File
import kotlin.math.abs

/** End-to-end software evidence on the persisted and exported PCM, not only metadata. */
object DrumlineKitProof {
    fun groove(): Mpc3Clip {
        val notes=mutableListOf<Mpc3Note>()
        fun hit(slot:Int,step:Int,v:Float) { notes+=Mpc3Note(Mpc3Note.noteFor(slot),step*240L,v) }
        for(i in 0..31) {
            if(i%8 in listOf(0,3,4,6,7))hit(if(i%8==4)2 else 1,i,if(i%4==0).95f else .3f)
            if(i%8 in listOf(1,2,5))hit(5+i%4,i,.6f)
            if(i%2==0)hit(9+(i/2)%5,i,.9f)
        }
        hit(15,0,.6f);hit(16,16,.6f);hit(4,24,.9f)
        return Mpc3Clip("Battery Cadence",3,notes.sortedBy { it.timePulses })
    }
    fun build(which: DrumlineKits.Kit, root: File): Kit {
        root.mkdirs();val native=File(root,"native")
        val start=System.nanoTime();val pads=DrumlineKits.build(which)
        val kit=KitAssembler.assembleArranged(if(which==DrumlineKits.Kit.FIELD)"Drumline Field" else "Drumline Street",pads,native,tempoBpm=which.bpm)
        val saved=KitStore.load(native);check(kit==saved)
        TriadKitProof.verify(saved,native,pads)
        for((pad,patch) in saved.pads.zip(DrumlineKits.patches(which))) {
            check(Breed.recipeOf(pad)?.patch==patch)
            val wav=WavReader.read(File(native,pad.sampleFile))
            val zones=requireNotNull(pad.chain?.zones)
            // Inclusive MIDI boundaries must select the intended zone, not a neighboring capture.
            for(z in zones)for(velocity in listOf(z.velStart/127f,z.velEnd/127f)) {
                val hit=requireNotNull(PadHit.resolve(pad,velocity,0) { wav.frameCount.toLong() })
                check(hit.startFrame==pad.chain!!.boundaries[z.baseSlice])
            }
            // Replay a later soft strike through the persisted JSON, retaining its model.
            val replay=(Breed.recipeOf(pad)!!.patch as DrumlinePatch).render(.3f,2)
            val expected=pads[pad.slot-1].velocityTakes[0][2]
            check(replay.samples.contentEquals(expected.samples))
        }
        val measured=BreakerKitProof.measure(saved,native)
        for(g in measured) {
            check(g.closestTakes<BreakerKitProof.COPY_LIKENESS) { "${which.id} ${g.name}: copied strikes $g" }
            check(g.leastZoneMoveDb>BreakerKitProof.LEVEL_ONLY_DB) { "${which.id} ${g.name}: volume-only velocity $g" }
        }
        val exported=Mpc3Exporter.exportTrack(saved,native,File(root,"MPC3"),overwrite=true,mpc2Twin=true,clip=groove())
        TriadKitProof.verifyTrack(exported.program,saved)
        val data=exported.samples.first().parentFile
        check(!ExportReadBack.verify(saved,ExportOutcome(ExportFormat.MPC3_TRACK,exported.program,data,exported.findings)).blocked())
        check(!ExportReadBack.verifyProgram(saved,File(data,"${saved.name}.xpm"),ExportFormat.PROGRAM_FOLDER).blocked())
        for(pad in saved.pads) {
            val mine=WavReader.read(File(native,pad.sampleFile));val copy=WavReader.read(File(data,pad.sampleFile))
            check(mine.frameCount==copy.frameCount && mine.samples.indices.all { abs(mine.samples[it]-copy.samples[it])<3f/8388608 })
            val chain=pad.chain!!;val zones=chain.zones!!
            for(z in zones)for(take in 0 until z.cycle*2) {
                val h=requireNotNull(PadHit.resolve(pad,(z.velStart+z.velEnd)/254f,take) { copy.frameCount.toLong() })
                check(copy.samples.copyOfRange(h.startFrame.toInt(),h.endFrameExclusive.toInt()).contentEquals(BreakerKitProof.slice(copy,pad,z.baseSlice+take%z.cycle)))
            }
            val name="pad-%02d".format(pad.slot)
            WavWriter.write(File(root,"$name.wav"),Snip(BreakerKitProof.slice(copy,pad,zones.last().baseSlice),1,44100),WavWriter.BitDepth.PCM_24)
            val strip=zones.flatMap { z -> (0 until z.cycle).map { BreakerKitProof.slice(copy,pad,z.baseSlice+it) } }
            val out=FloatArray(strip.sumOf { it.size+5512 });var at=0
            for(x in strip) { x.copyInto(out,at);at+=x.size+5512 }
            WavWriter.write(File(root,"$name-touch.wav"),Snip(out,1,44100),WavWriter.BitDepth.PCM_24)
        }
        val groove=KitPreview.render(saved,native,groove(),which.bpm)
        check(groove.samples.all { it.isFinite() })
        WavWriter.write(File(root,"groove.wav"),groove,WavWriter.BitDepth.PCM_24)
        File(root,"verification.csv").writeText("pad,closest_takes,least_velocity_shape_db\n"+measured.joinToString("\n") { "${it.slot},${it.closestTakes},${it.leastZoneMoveDb}" })
        File(root,"README.txt").writeText("${which.title}, model ${Drumline.MODEL_VERSION}, built in ${(System.nanoTime()-start)/1000000} ms.\n"+
            "16 pads, three velocity levels and three fresh takes per level. Saved PCM, JSON replay, boundary selection, cycling and MPC3 read-back verified in software. MPC2 preserves velocity but not combined velocity/RR (owner format exception). No phone or MPC hardware verification. Kotlin sound awaits listening.\n")
        return saved
    }
}
