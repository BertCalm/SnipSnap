package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.*
import com.snipsnap.synth.*
import java.io.File
import kotlin.math.*

/** Full-cell evidence for FOOTWORK; compares decoded windows, not only metadata counts. */
object FootworkKitProof {
    private fun rms(x: FloatArray)=sqrt(x.sumOf { it.toDouble()*it }/x.size)
    private fun difference(a: FloatArray,b: FloatArray): Double {
        val ra=rms(a); val rb=rms(b)
        return sqrt(a.indices.sumOf { (a[it]/ra-b[it]/rb).pow(2) }/a.size)
    }
    fun sources(genre: FootworkKits.Kit,pads: List<ArrangedPad>) {
        check(pads.size==18)
        for((i,pad) in pads.withIndex()) {
            val grid=pad.velocityTakes; check(grid.size==3 && grid.all { it.size==4 })
            for((z,row) in grid.withIndex()) for((t,snip) in row.withIndex()) {
                val x=snip.samples
                check(x.all { it.isFinite() && abs(it)<.98f } && abs(x.average())<.005 && x.last()==0f)
                val rendered=Footwork.render(FootworkVoice.entries[i],FootworkKits.velocities[z],t,genre).samples
                check(x.contentEquals(FloatArray(rendered.size) { rendered[it]/FootworkKits.velocities[z] })) { "replay $genre $i $z $t" }
                if(t>0) check(difference(row[0].samples,x)>.01) { "repeat $genre $i $z $t" }
            }
            for(t in 0..3) {
                check(rms(grid[0][t].samples)<rms(grid[1][t].samples) && rms(grid[1][t].samples)<rms(grid[2][t].samples)) { "velocity level $genre $i $t" }
                check(difference(grid[0][t].samples,grid[2][t].samples)>.005) { "velocity shape $genre $i $t" }
            }
            check(Footwork.render(FootworkVoice.entries[i],0f,0,genre).samples.all { it==0f })
        }
    }
    fun roundTrip(pads: List<ArrangedPad>,kit: Kit,dir: File,folder: File) {
        check(KitStore.load(dir)==kit)
        TriadKitProof.verify(kit,dir,pads)
        val exported=Mpc3Exporter.exportTrack(kit,dir,File(folder,"mpc"))
        TriadKitProof.verifyTrack(exported.program,kit)
        for(pad in kit.pads) {
            val delivered=exported.samples.single { it.name==pad.sampleFile }
            check(delivered.readBytes().contentEquals(File(dir,pad.sampleFile).readBytes()))
        }
        // SFZ round-trip must preserve actual packed PCM, every zone boundary and RR cycle.
        val sfz=SfzWriter.write(kit,dir,File(folder,"sfz"))
        val imported=SfzImporter.import(sfz,File(folder,"sfz-import"))
        check(imported.skipped.isEmpty() && imported.kit.pads.size==18)
        for((index,pad) in kit.pads.withIndex()) {
            val back=imported.kit.pads[index]
            check(pad.chain==back.chain)
            check(File(dir,pad.sampleFile).readBytes().contentEquals(File(imported.directory,back.sampleFile).readBytes()))
            val wav=WavReader.read(File(dir,pad.sampleFile))
            // At each authored anchor, native playback must reproduce full source dynamics.
            for((z,velocity) in FootworkKits.velocities.withIndex()) for(take in 0..3) {
                val hit=requireNotNull(PadHit.resolve(pad,velocity,take) { wav.frameCount.toLong() })
                val source=Footwork.render(FootworkVoice.entries[index],velocity,take,FootworkKits.Kit.valueOf(pad.source.getValue("kit"))).samples
                for(frame in source.indices) check(abs(source[frame]-wav.samples[hit.startFrame.toInt()+frame]*hit.gainLeft)<1e-6f)
            }
            for(v in 1..127) for(hit in 0..8) {
                val before=requireNotNull(PadHit.resolve(pad,v/127f,hit) { wav.frameCount.toLong() })
                val after=requireNotNull(PadHit.resolve(back,v/127f,hit) { wav.frameCount.toLong() })
                val chain=requireNotNull(pad.chain); val zone=requireNotNull(chain.zoneFor(v))
                val window=chain.window(zone.baseSlice+hit%4,wav.frameCount.toLong())
                check(before.startFrame==window.first && before.endFrameExclusive==window.last+1)
                check(before.startFrame==after.startFrame && before.endFrameExclusive==after.endFrameExclusive)
                check(abs(before.gainLeft-after.gainLeft)<1e-6 && abs(before.gainRight-after.gainRight)<1e-6 && before.pitchRatio==after.pitchRatio)
            }
        }
    }
}
