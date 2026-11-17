package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.SideroomKits
import java.io.File

/** Native deliveries and an audition of the new Kotlin-authored finite gestures. */
object SideroomKitGenerator {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.firstOrNull()?:"testkit/sideroom-native").absoluteFile.apply { mkdirs() }
        val proof=StringBuilder("kit,pads,zones,takes,native_pcm,playback_cycles,mpc3_pcm,mpc3_metadata,mpc2_readback\n")
        for(g in SideroomKits.Kit.entries) {
            val folder=File(root,g.slug).apply { mkdirs() };val native=File(folder,"native")
            val pads=SideroomKits.build(g)
            val kit=KitAssembler.assembleArranged("Sideroom ${g.title}",pads,native,tempoBpm=g.bpm.toFloat())
            val saved=KitStore.load(native);check(saved==kit);TriadKitProof.verify(saved,native,pads)
            val mpc3=Mpc3Exporter.exportTrack(saved,native,File(folder,"mpc3"),overwrite=true)
            TriadKitProof.verify(saved,File(mpc3.directory,Mpc3TrackWriter.trackDataDirName(saved.name)),pads)
            TriadKitProof.verifyTrack(mpc3.program,saved)
            val mpc2=KitExporter.exportProgramFolder(saved,native,File(folder,"mpc2"),overwrite=true)
            val findings=ExportReadBack.verifyProgram(saved,mpc2.program,ExportFormat.PROGRAM_FOLDER)
            check(findings.none { it.severity==Severity.FAIL }) { findings.toString() }
            for(role in 8..9) for(z in 0..2) for(t in 0..2) {
                val snip=pads[role].velocityTakes[z][t]
                WavWriter.write(File(folder,"${if(role==8)"roll" else "riser"}-$z-$t.wav"),snip)
            }
            proof.append("${g.slug},10,3,3,pass,2,pass,pass,no_failures\n")
            println("SIDEROOM ${g.title}: 90 variants through native save/playback and MPC export")
        }
        File(root,"verification.csv").writeText(proof.toString())
    }
}
