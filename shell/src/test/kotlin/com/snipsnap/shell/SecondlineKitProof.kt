package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import com.snipsnap.synth.*
import java.io.File
import kotlin.math.abs

/** End-to-end software evidence on the persisted and exported PCM, not only metadata. */
object SecondlineKitProof {
    @JvmStatic fun main(args: Array<String>) { for(kit in SecondlineKits.Kit.entries)build(kit,File(args.single(),kit.id)) }
    fun groove(kit: SecondlineKits.Kit): Mpc3Clip {
        val notes=mutableListOf<Mpc3Note>()
        fun hit(beat: Double,slot: Int,v: Float) {
            val lilt=if(beat%1.0 in listOf(.25,.75)).035 else 0.0
            notes+=Mpc3Note(Mpc3Note.noteFor(slot),((beat+lilt)*960).toLong(),v)
        }
        for(bar in 0..7) {
            val b=bar*4.0
            for(q in listOf(0.0,1.5,2.0,3.5))hit(b+q,1,if(q in listOf(0.0,2.0)).8f else .55f)
            for(q in listOf(1.0,3.0))hit(b+q,3,1f)
            for(q in listOf(.75,1.75,2.5,3.75))hit(b+q,4,.35f)
            hit(b+.5,6,.5f);hit(b+2.75,5,.75f);hit(b,13,.6f)
            if(kit==SecondlineKits.Kit.TRIANGLE) { hit(b+1.5,14,.6f);hit(b+3.5,15,.45f) }
            if(bar%4==3)hit(b+3,7,.8f)
        }
        return Mpc3Clip("Second Line study",8,notes.sortedBy { it.timePulses })
    }
    fun build(which: SecondlineKits.Kit,root: File): Kit {
        root.mkdirs();val native=File(root,"native")
        val start=System.nanoTime();val pads=SecondlineKits.build(which)
        val kit=KitAssembler.assembleArranged(which.title,pads,native,tempoBpm=SecondlineKits.BPM)
        val saved=KitStore.load(native);check(kit==saved)
        TriadKitProof.verify(saved,native,pads)
        for((pad,patch) in saved.pads.zip(SecondlineKits.patches(which))) {
            check(Breed.recipeOf(pad)?.patch==patch)
            val wav=WavReader.read(File(native,pad.sampleFile))
            val zones=requireNotNull(pad.chain?.zones)
            // Inclusive MIDI boundaries must select the intended zone, not a neighboring capture.
            for(z in zones)for(velocity in listOf(z.velStart/127f,z.velEnd/127f)) {
                val hit=requireNotNull(PadHit.resolve(pad,velocity,0) { wav.frameCount.toLong() })
                check(hit.startFrame==pad.chain!!.boundaries[z.baseSlice])
            }
            // Replay a later soft strike through the persisted JSON, retaining its model.
            val replay=(Breed.recipeOf(pad)!!.patch as SecondlinePatch).render(.3f,2)
            val expected=pads[pad.slot-1].velocityTakes[0][2]
            check(replay.samples.contentEquals(expected.samples))
        }
        // Every MIDI velocity, two complete take cycles, using persisted native windows.
        for((index,pad) in saved.pads.withIndex()) {
            val wav=WavReader.read(File(native,pad.sampleFile));val zones=pad.chain!!.zones!!
            for(velocity in 1..127)for(take in 0..5) {
                val zi=zones.indexOfFirst { velocity in it.velStart..it.velEnd };check(zi>=0)
                val hit=requireNotNull(PadHit.resolve(pad,velocity/127f,take) { wav.frameCount.toLong() })
                val expected=pads[index].velocityTakes[zi][take%3].samples
                check(hit.endFrameExclusive-hit.startFrame==expected.size.toLong())
                check(expected.indices.all { abs(expected[it]-wav.samples[hit.startFrame.toInt()+it])<3f/8388608 })
            }
        }
        val measured=BreakerKitProof.measure(saved,native)
        for(g in measured) {
            check(g.closestTakes<BreakerKitProof.COPY_LIKENESS) { "secondline-native ${g.name}: copied strikes $g" }
            check(g.leastZoneMoveDb>BreakerKitProof.LEVEL_ONLY_DB) { "secondline-native ${g.name}: volume-only velocity $g" }
        }
        val exported=Mpc3Exporter.exportTrack(saved,native,File(root,"MPC3"),overwrite=true,mpc2Twin=true,clip=groove(which))
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
        val groove=KitPreview.render(saved,native,groove(which),SecondlineKits.BPM)
        check(groove.samples.all { it.isFinite() })
        WavWriter.write(File(root,"groove.wav"),groove,WavWriter.BitDepth.PCM_24)
        File(root,"verification.csv").writeText("pad,closest_takes,least_velocity_shape_db\n"+measured.joinToString("\n") { "${it.slot},${it.closestTakes},${it.leastZoneMoveDb}" })
        File(root,"README.txt").writeText("Second Line native candidate, model ${ParadeSecondLine.MODEL}, built in ${(System.nanoTime()-start)/1000000} ms.\n"+
            "16 pads, three velocity levels and three fresh takes per level. Saved PCM, JSON replay, boundary selection, cycling and MPC3 read-back verified in software. MPC2 preserves velocity but not combined velocity/RR (owner format exception). No phone or MPC hardware verification. Native candidate awaits listening and factory acceptance.\n")
        return saved
    }
}
