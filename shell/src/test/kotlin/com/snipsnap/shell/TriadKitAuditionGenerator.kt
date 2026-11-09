package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Triad
import com.snipsnap.synth.TriadKits
import java.io.File
import kotlin.math.*

/** The listening pack plays saved/exported samples through the application's pad resolver. */
object TriadKitAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "testkit/triad-kits").absoluteFile.apply { mkdirs() }
        val report=StringBuilder("genre,pad,velocity_zones,takes_per_zone,pcm_playback_export\n")
        for(g in TriadKits.Genre.entries) {
            println("Rendering ${g.title}")
            val folder=File(root,g.name.lowercase()).apply { mkdirs() }
            val staging=File(folder,"native")
            val pads=TriadKits.build(g)
            val kit=KitAssembler.assembleArranged("Triad ${g.title}",pads,staging,tempoBpm=g.bpm.toFloat())
            val exported=Mpc3Exporter.exportTrack(kit,staging,File(folder,"mpc"),overwrite=true)
            val data=File(folder,"mpc/${Mpc3TrackWriter.trackDataDirName(kit.name)}")
            val saved=KitStore.load(staging)
            TriadKitProof.verify(saved,staging,pads)
            TriadKitProof.verify(saved,data,pads)
            TriadKitProof.verifyTrack(exported.program,saved)
            val waves=saved.pads.associate { it.slot to WavReader.read(File(data,it.sampleFile)) }
            fun play(slot:Int,velocity:Float,index:Int):FloatArray {
                val pad=saved.pads.single { it.slot==slot }; val wav=waves.getValue(slot)
                // Fixed level for the audition, velocity still selects timbre and rendered dynamics.
                val h=requireNotNull(PadHit.resolve(pad,velocity,index,1f) { wav.frameCount.toLong() })
                return wav.samples.copyOfRange(h.startFrame.toInt(),h.endFrameExclusive.toInt())
            }
            for(pad in saved.pads) {
                val sample=play(pad.slot,1f,0)
                WavWriter.write(File(folder,"pad-${pad.slot}.wav"),Snip(sample,1,44100))
                report.append("${g.name},${pad.slot},3,4,verified\n")
                if(pad.slot in listOf(7,8)) {
                    val clips=TriadKits.velocities.flatMap { v -> List(4) { t -> play(pad.slot,v,t) } }
                    val stride=44100
                    val strip=FloatArray(stride*clips.size)
                    clips.forEachIndexed { i,x -> for(j in 0 until min(stride,x.size)) strip[i*stride+j]=x[j] }
                    WavWriter.write(File(folder,"touch-${pad.slot}.wav"),Snip(strip,1,44100))
                }
            }
            val source=Triad.source(Triad.Settings(bpm=g.bpm,bars=2,tune=g.tune))
            val lanes=listOf("thud","sub","clap","open-hat","closed-hat","shaker","conga","bongo","rim")
            val counts=IntArray(9)
            val groove=FloatArray(source.frames)
            for((idx,event) in source.events.withIndex()) {
                val lane=lanes.indexOf(event.lane); val x=play(lane+1,event.velocity,counts[lane]++)
                val hat=lane in 3..4
                val end=if(hat) source.events.drop(idx+1).firstOrNull { it.lane.endsWith("hat") }?.frame ?: groove.size else groove.size
                val n=min(x.size,min(end-event.frame,groove.size-event.frame))
                val level=when(lane) { 0 -> .7f; 1 -> .6f; 2 -> .58f; 6,7 -> .48f; 8 -> .2f; else -> .26f }
                for(j in 0 until n) groove[event.frame+j]+=x[j]*level*event.velocity*.5f*(if(hat) ((n-1-j)/132f).coerceIn(0f,1f) else 1f)
            }
            check(groove.all { it.isFinite() && abs(it)<1f })
            WavWriter.write(File(folder,"playback.wav"),Snip(groove,1,44100))
            File(folder,"kit-source.json").writeText(File(staging,KitStore.FILE_NAME).readText())
            println("Verified ${g.title}: 16 pads, 192 slices, saved PCM + playback + MPC 3 track")
        }
        File(root,"verification.csv").writeText(report.toString())
    }
}
