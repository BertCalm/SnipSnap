package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.EastAfricaKits
import java.io.File

/** Deliver native banks and audition WAVs read back from the exported chain, not a parallel renderer. */
object EastAfricaKitGenerator {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull()?:"testkit/east-africa-kits").absoluteFile.apply { mkdirs() }
        val report=StringBuilder("genre,variants,render_ms,save_playback_mpc3\n")
        for(g in EastAfricaKits.Genre.entries) {
            val folder=File(root,g.slug).apply { mkdirs() };val dir=File(folder,"native")
            val start=System.nanoTime();val pads=EastAfricaKits.build(g);val ms=(System.nanoTime()-start)/1e6
            val kit=KitAssembler.assembleArranged(g.title,pads,dir,tempoBpm=g.bpm.toFloat())
            val saved=KitStore.load(dir);check(saved==kit);TriadKitProof.verify(saved,dir,pads)
            val mpc3=Mpc3Exporter.exportTrack(saved,dir,File(folder,"mpc3"),overwrite=true)
            val data=File(mpc3.directory,Mpc3TrackWriter.trackDataDirName(saved.name))
            TriadKitProof.verify(saved,data,pads);TriadKitProof.verifyTrack(mpc3.program,saved)
            KitExporter.exportProgramFolder(saved,dir,File(folder,"mpc2"),overwrite=true)
            val audio=File(folder,"audio").apply { mkdirs() }
            for((r,pad) in saved.pads.withIndex()) {
                val wav=WavReader.read(File(data,pad.sampleFile)); val chain=requireNotNull(pad.chain)
                for(z in 0..2) for(t in 0..2) {
                    val zone=chain.zones!![z];val velocity=(zone.velStart+zone.velEnd)/254f
                    val hit=requireNotNull(PadHit.resolve(pad,velocity,t){wav.frameCount.toLong()})
                    val x=wav.samples.copyOfRange(hit.startFrame.toInt(),hit.endFrameExclusive.toInt())
                    val name="${(r+1).toString().padStart(2,'0')}-${EastAfricaKits.voices[r]}-v${z+1}-r${t+1}.wav"
                    WavWriter.write(File(audio,name),Snip(x,1,44100),WavWriter.BitDepth.PCM_24)
                }
            }
            report.append("${g.slug},198,$ms,pass\n")
            println("${g.title}: 198 variants, saved PCM/playback/MPC3 verified; bank render $ms ms")
        }
        File(root,"verification.csv").writeText(report.toString())
    }
}
